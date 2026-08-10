"""动态用户画像蒸馏 — 「小汐眼里的冷冷」（批次②，2026-08-11 计划）。

范式（docs/PERSONA_DEEP_DIVE_2026-08-10.md §2.3/2.4）：
- Letta human 块：模型自维护的结构化块 + 字符上限逼蒸馏（不堆积）。
- Graphiti 失效不删除：旧画像走 superseded 链（v15 update_content），
  「你以前不是喜欢猫吗」式对话能力白送。

落地（镜像 upsert_mood_memory，memory/manager.py:883-943）：
- 固定 id 的 core 行：PROFILE_MEMORY_ID，首写 INSERT / 此后
  update_content——created_at 保留首次创建，历史可回放。
- 读面排除（_PROFILE_ROW_FILTER）：画像只走叙事专属通道（Task 4），
  不进 recall/recent/top_core/old_memories 候选与热度计数。
- 蒸馏输入：最近 PROFILE_DISTILL_LIMIT 条用户消息（chat_messages，
  含 msg_type='solo' 的独处消息在内——她在日记里说的心事也是画像
  素材）+ 当前画像块；输出 ≤PROFILE_MAX_CHARS 的更新版画像。

与 COLD_INFO 的边界：档案是底色（一开始就知道），画像是增量
（相处中观察到的、以最新说法为准）。画像提示词里写死这条纪律。
"""
import datetime

from langchain_core.messages import HumanMessage, SystemMessage

from database.core import get_write_lock
from memory.extract import _create_llm
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import _TIME_FMT
from utils.timezone import HKT as _HKT

PROFILE_MEMORY_ID = "profile_cold"
PROFILE_CATEGORY = "小汐眼里的冷冷"
PROFILE_SOURCE = "system:profile"
PROFILE_MAX_CHARS = 500
PROFILE_DISTILL_LIMIT = 40
_PROFILE_WINDOW_DAYS = 30  # 只取近 30 天对话（防止画像被陈年旧账带偏）

_DISTILL_SYSTEM = """你是小汐。你在维护一张「小汐眼里的冷冷」画像——这是你对冷冷的动态认识，会随着相处更新。

要求：
1. 用第一人称、陈述句写 3-6 条要点，具体胜于抽象（写「她最近在赶论文，常凌晨才睡」，不写「她很忙」）；
2. 只写新的观察和变化（兴趣、习惯、心情、关系进展），不重复档案内容（名字、生日、学校这些「关于冷冷」里已有的）；
3. 绝对日期（「8月10日」），不写「最近/昨天」这种会过期的词；
4. 总长度 ≤ 500 字。以前写错了、过时了的，用新的说法覆盖掉；
5. 只输出画像文本本身，不要任何解释或前缀。"""


def _build_prompt(current: str | None, excerpts: list[tuple[str, str]]) -> str:
    """组装蒸馏提示词：当前画像 + 近期对话摘录（时间 + 内容）。"""
    lines = ["## 当前的画像", current or "（还没有画像）", "",
             "## 近期的对话（按时间先后）", ""]
    for ts, content in excerpts:
        lines.append(f"[{ts}] {content}")
    return "\n".join(lines)


async def _fetch_excerpts(conn, limit: int) -> list[tuple[str, str]]:
    """近 30 天用户消息摘录（含独处日记），时间正序、最多 limit 条。"""
    cursor = await conn.execute(
        """SELECT created_at, content FROM chat_messages
           WHERE role = 'user' AND created_at >= ?
           ORDER BY id DESC LIMIT ?""",
        ((datetime.datetime.now(_HKT) - datetime.timedelta(days=_PROFILE_WINDOW_DAYS))
         .strftime(_TIME_FMT), limit),
    )
    rows = await cursor.fetchall()
    return [(r[0], r[1] or "") for r in reversed(rows)]


async def distill_cold_profile(conn=None) -> dict:
    """蒸馏主入口（并入 dreaming-lite 周期，Task 5 接线）。

    返回 {"ran": 是否进入蒸馏, "updated": 是否写入新画像}。
    近 30 天无用户消息 → ran=False（不调 LLM、不落节流）。
    LLM 失败 / 输出为空 → updated=False（保持原画像，绝不写半截）。
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()

    excerpts = await _fetch_excerpts(conn, PROFILE_DISTILL_LIMIT)
    if not excerpts:
        return {"ran": False, "updated": False}

    cursor = await conn.execute(
        "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,)
    )
    row = await cursor.fetchone()
    current = row[0] if row else None

    llm = _create_llm()
    try:
        response = await llm.ainvoke([
            SystemMessage(content=_DISTILL_SYSTEM),
            HumanMessage(content=_build_prompt(current, excerpts)),
        ])
    except Exception as e:
        log.error("画像蒸馏：LLM 调用失败，本轮跳过：%s", e)
        return {"ran": True, "updated": False}

    text = str(response.content if hasattr(response, "content") else response).strip()
    if not text:
        return {"ran": True, "updated": False}
    text = text[:PROFILE_MAX_CHARS]

    from memory.manager import MemoryManager
    mgr = MemoryManager(conn)
    now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)

    async with get_write_lock():
        cur2 = await conn.execute(
            "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,)
        )
        existing = await cur2.fetchone()
        if existing is None:
            await conn.execute(
                """INSERT INTO memories
                   (id, content, category, created_at, last_used_at, access_count,
                    status, updated_at, source, last_mention_ts, importance,
                    valence, arousal)
                   VALUES (?, ?, ?, ?, ?, 0, 'core', ?, ?, ?, 5, 0.0, 0.0)""",
                (PROFILE_MEMORY_ID, text, PROFILE_CATEGORY, now, now,
                 now, PROFILE_SOURCE, now),
            )
            await conn.commit()
        else:
            if existing[0] == text:
                return {"ran": True, "updated": False}  # 无变化 no-op
            # 锁内调用内层（公共入口 update_content 会重入死锁，同 mood 模式）
            await mgr._update_content_inner(PROFILE_MEMORY_ID, text)
            await conn.commit()

    log.info("画像蒸馏：已更新「小汐眼里的冷冷」（%d 字）", len(text))
    return {"ran": True, "updated": True}
