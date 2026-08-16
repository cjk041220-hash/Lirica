"""会话脉络摘要 — session summary 层（P1-a，2026-08-13）。

补 ZifaMem L2：长对话只有硬裁剪 + recent 记忆，缺"这段聊了什么"的
语义压缩。本模块在心跳维护周期里，对"已结束且够长"的普通会话用 LLM
生成一句话脉络摘要，存 session_summaries 表，由 narrative.py 注入。

读面先例：直接 SQL 读 chat_messages（与 memory/extract.py 同款）。
隔离自检 test_archive_isolation_from_llm_context_paths 只禁
narrative.py 与 agent/* 引用 chat_archive；本模块是 memory/ 处理
模块，走 extract.py 先例，不 import chat_archive。输出是经 LLM 蒸馏
的脉络，注入时走叙事"数据非指令"包裹——与后台提取同类，不是新增
档案→上下文注入路径。

成本护栏：单轮 ≤3 会话；会话需 ≥15 条消息 + 空闲 ≥6h + 非当前活跃
会话 + 无既有摘要。失败（异常/空输出）跳过不写库，下轮重试。
"""
import datetime

from langchain_core.messages import HumanMessage, SystemMessage

from database.core import get_db, get_write_lock
from memory.extract import _coerce_content, _create_llm
from utils.logger import get_logger
from utils.timezone import _TIME_FMT
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

_MIN_MESSAGES = 15           # 会话至少这么多条消息才值得摘
_IDLE_HOURS = 6              # 空闲（最后消息距今）至少这么久才算"已结束"
_MAX_PER_RUN = 3             # 每轮最多摘几个（排旧账防一次性烧爆）
_SUMMARY_MESSAGE_CAP = 80    # 喂 LLM 的会话尾部消息条数上限（控 token）
_SUMMARY_CHARS = 4000        # 喂 LLM 的对话文本总字符上限
_SUMMARY_MAX_LEN = 120       # 摘要长度上限（超长截断，防 LLM 话痨）

_SUMMARIZE_SYSTEM = """你是小汐的记忆管家。读一段对话，用一句话总结这段对话的脉络与情绪走向。

规则：
1. 只总结对话里实际出现的内容；**禁止编造**对话里没提过的话题或事实；
2. 侧重冷冷的状态与情绪（如"从考试焦虑聊到家庭压力""她拿到了 offer 很高兴"），不要流水账罗列话题；
3. 一句话写完，不超过 80 字，用过去时；
4. 不写日期、时间（由系统补）。只输出摘要本身，不要任何解释、引号或前缀。"""


def _format_messages(rows) -> str:
    """[(role, content)] → 带说话人标记的文本（时间升序传入）。

    与 extract.py 同款说话人标记（冷冷/小汐）；总量超 _SUMMARY_CHARS
    时从最旧开始丢（保留最新段——摘要反映的是这场对话的走向）。
    """
    lines = []
    for role, content in rows:
        text = (content or "").strip()
        if not text:
            continue
        lines.append(f"冷冷：{text}" if role == "user" else f"小汐：{text}")
    total = sum(len(line) for line in lines)
    while total > _SUMMARY_CHARS and len(lines) > 1:
        dropped = lines.pop(0)
        total -= len(dropped)
    return "\n".join(lines)


async def _find_eligible(conn, now: datetime.datetime) -> list[tuple[str, str, int]]:
    """返回 [(session_id, updated_at, msg_count)]：合格待摘会话，最旧优先。

    合格：is_era=0 + 消息数 ≥ _MIN_MESSAGES + 无既有摘要 + 非当前活跃
    会话（life_state.active_session_id 离线即清空，:active 判参数自身）+
    空闲 ≥ _IDLE_HOURS（updated_at ≤ now − _IDLE_HOURS）。LIMIT 上限。

    消息数只算真实对话（msg_type != 'solo'）：solo 是小汐的自言自语，
    不该把会话顶上合格线，也不该进摘要（与 extract._fetch_pending
    同纪律，2026-08-17）。
    """
    active = None
    cursor = await conn.execute(
        "SELECT active_session_id FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    if row:
        active = row[0]
    cutoff = (now - datetime.timedelta(hours=_IDLE_HOURS)).strftime(_TIME_FMT)
    cursor = await conn.execute(
        """SELECT s.id, s.updated_at, COUNT(m.id) AS n
           FROM chat_sessions s
           JOIN chat_messages m
             ON m.session_id = s.id AND m.msg_type != 'solo'
           WHERE s.is_era = 0
             AND (:active IS NULL OR s.id != :active)
             AND NOT EXISTS (
                 SELECT 1 FROM session_summaries ss
                 WHERE ss.session_id = s.id
             )
           GROUP BY s.id
           HAVING COUNT(m.id) >= :min_msgs AND s.updated_at <= :cutoff
           ORDER BY s.updated_at ASC
           LIMIT :max_run""",
        {"active": active, "min_msgs": _MIN_MESSAGES,
         "cutoff": cutoff, "max_run": _MAX_PER_RUN},
    )
    return [(r[0], r[1], r[2]) for r in await cursor.fetchall()]


async def _fetch_session_messages(conn, session_id: str) -> list[tuple[str, str]]:
    """读会话尾部 ≤ _SUMMARY_MESSAGE_CAP 条消息（时间升序）。

    排除 solo 自言自语：摘要描的是「这段聊了什么、冷冷的状态」，小汐
    自己搭话/独白的句子不是冷冷的话，进了摘要就是自言自语污染叙事
    （与 extract._fetch_pending 同纪律，2026-08-17）。
    """
    cursor = await conn.execute(
        "SELECT role, content FROM chat_messages "
        "WHERE session_id = ? AND msg_type != 'solo' ORDER BY id DESC LIMIT ?",
        (session_id, _SUMMARY_MESSAGE_CAP),
    )
    return list(reversed(await cursor.fetchall()))


async def _summarize_one(conn, session_id: str) -> str | None:
    """单个会话摘一条摘要；失败返回 None（调用方不写库、下轮重试）。"""
    rows = await _fetch_session_messages(conn, session_id)
    if not rows:
        return None
    llm = _create_llm()
    try:
        response = await llm.ainvoke([
            SystemMessage(content=_SUMMARIZE_SYSTEM),
            HumanMessage(content=_format_messages(rows)),
        ])
    except Exception as e:
        log.warning("会话摘要：LLM 调用失败（sid=%s），跳过：%s", session_id, e)
        return None
    summary = _coerce_content(
        response.content if hasattr(response, "content") else str(response)
    ).strip()
    if not summary:
        return None
    return summary[:_SUMMARY_MAX_LEN]


async def summarize_closed_sessions(conn=None) -> dict:
    """主入口（心跳维护新段）。返回 {"summarized": n, "skipped": n}。"""
    if conn is None:
        conn = await get_db()
    now = datetime.datetime.now(_HKT)

    eligible = await _find_eligible(conn, now)
    summarized = 0
    skipped = 0
    for session_id, ended_at, msg_count in eligible:
        summary = await _summarize_one(conn, session_id)
        if summary is None:
            skipped += 1
            continue
        async with get_write_lock():
            await conn.execute(
                """INSERT INTO session_summaries
                   (session_id, summary, msg_count, ended_at, created_at)
                   VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(session_id) DO UPDATE SET
                       summary = excluded.summary,
                       msg_count = excluded.msg_count,
                       ended_at = excluded.ended_at,
                       created_at = excluded.created_at""",
                (session_id, summary, msg_count, ended_at,
                 now.strftime(_TIME_FMT)),
            )
            await conn.commit()
        summarized += 1
    if summarized or skipped:
        log.info("会话摘要：生成 %d 条（跳过 %d 个）", summarized, skipped)
    return {"summarized": summarized, "skipped": skipped}
