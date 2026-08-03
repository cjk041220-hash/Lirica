"""周期性记忆整合 — dreaming-lite（2026-08-01 全面复核 R1）。

业界共识（2025-2026）：ChatGPT Dreaming（off-peak 跨历史合成+修剪）、
Letta sleep-time compute（空闲期异步重写 core memory，arXiv:2504.13171）、
LangMem consolidation——周期性 LLM 整合 pass 已是事实标准，小汐此前
唯一空白。本模块零新基础设施：复用心跳维护窗口（2h 槽位）、提取
LLM 通道（memory.extract._create_llm）、解析容错（_parse_actions）与
superseded 历史链（v15：合并执行走 update_content，旧内容与被合并
行全部落 superseded 行可追溯）。

做什么：按类别审视活跃记忆，把近义重复/互相包含的条目合并成一句
更凝练的事实。narrative 注入预算就 5 条席位，记忆越积越多时近义
条目互相挤占——整合直接提升每一轮对话的上下文质量。

不做什么：不做语义检索（bigram 在千级中文短文本够用）、不做图谱、
不做提示词自优化（人设稳定性优先）。矛盾内容**绝不合并**（那是
update 覆盖的职责，提示词明令禁止）。

预算护栏（抄 extract.py 纪律）：
- 类别活跃+core 数 ≥ _MIN_CATEGORY_SIZE 才进入视野（不够多不值得
  花 LLM）；
- 单次运行最多 _MAX_MERGES_PER_RUN 个合并（防一次改写半个库）；
- 24h 节流（consolidate_ran_at 列，v15 迁移已建）——心跳 2h 一次，
  不节流一天 12 遍空耗账单。
"""
import datetime

from langchain_core.messages import SystemMessage, HumanMessage

from memory.extract import _create_llm, _parse_actions
from memory.manager import MemoryManager
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import HKT as _HKT, now_str as _now, _TIME_FMT

# ── 预算护栏 ──────────────────────────────────────────────────
_MIN_CATEGORY_SIZE = 8        # 类别至少这么多条才值得整合
_MAX_MERGES_PER_RUN = 3       # 单次最多合并数（LLM 也别太激进）
_CATEGORY_SCAN_LIMIT = 50     # 每类别最多送审条数（超大类别截断）
_CONSOLIDATE_THROTTLE_HOURS = 24

_CONSOLIDATE_SYSTEM = """你是小汐的记忆管家。审视下面同一类别的记忆，找出「近义重复 / 互相包含 / 换个说法的同一件事」的条目组，提出合并。只输出 JSON 数组，不要任何解释。

格式：[{"merge": [id1, id2], "content": "合并后的一句话"}]

规则：
1. 只合并语义重复或包含的条目；**矛盾的内容绝不合并**（「爱吃火锅」和「不爱吃火锅了」是变化，留给覆盖流程，不是合并）；
2. 合并后的 content 用一句话写完，具体、可独立理解（用「冷冷」而不是「她」），保留各条的具体信息不丢细节；
3. 单次最多 3 个合并提案；没有值得合并的就输出 []。"""


def _build_prompt(category: str, memories: list[dict]) -> str:
    """组装整合提示词：类别名 + [id] 内容清单。"""
    lines = [f"类别：{category}", ""]
    for m in memories:
        lines.append(f"[{m['id']}] {m.get('content', '')}")
    return "\n".join(lines)


async def _throttle_allowed(conn, now: datetime.datetime) -> bool:
    """24h 节流：距上次整合 ≥24h 才允许再次运行（同 extract 健康告警模式）。"""
    cursor = await conn.execute(
        "SELECT consolidate_ran_at FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    if not row or not row[0]:
        return True
    try:
        last = datetime.datetime.strptime(row[0], _TIME_FMT).replace(tzinfo=_HKT)
    except (ValueError, TypeError):
        return True
    return (now - last).total_seconds() >= _CONSOLIDATE_THROTTLE_HOURS * 3600


async def _mark_consolidated(conn, now: datetime.datetime) -> None:
    """记录本次整合时间（ON CONFLICT 只更新 consolidate_ran_at，保留水位线）。"""
    now_str = now.strftime(_TIME_FMT)
    await conn.execute(
        """INSERT INTO memory_extract_state (id, last_message_id, consolidate_ran_at)
           VALUES (1, 0, ?)
           ON CONFLICT(id) DO UPDATE SET consolidate_ran_at = excluded.consolidate_ran_at""",
        (now_str,),
    )
    await conn.commit()


async def _apply_merge(mgr: MemoryManager, conn, merge: dict) -> bool:
    """执行一个合并提案：保留最早一行写入合并句，其余落 superseded。

    - survivor 取 created_at 最早的行（合并后的事实延续它的生命起点，
      时近衰减不被重置）；update_content 自动把 survivor 的旧内容记入
      superseded 历史链；
    - 被合并的其他行 status='superseded'、superseded_by 指向 survivor——
      history(survivor) 因此同时包含 survivor 旧内容与被合并兄弟，
      完整账本（「这条合并了哪些」可溯）；
    - 校验失败（id 不存在/不同类别/<2 条）返回 False 静默跳过——LLM
      幻觉提案不该让整轮整合报错。
    """
    ids = [str(i).strip() for i in (merge.get("merge") or []) if str(i).strip()]
    content = str(merge.get("content", "") or "").strip()
    if len(ids) < 2 or not content:
        return False

    placeholders = ",".join("?" for _ in ids)
    cursor = await conn.execute(
        f"""SELECT id, category, created_at, rowid FROM memories
            WHERE id IN ({placeholders}) AND status IN ('active', 'core')""",
        ids,
    )
    rows = await cursor.fetchall()
    if len(rows) < 2:
        return False
    categories = {r[1] for r in rows}
    if len(categories) != 1:
        return False  # 跨类别合并不允许（类别是语义边界）

    # survivor = created_at 最早；同分钟打平时按 rowid（真实入库先后）
    # 决胜——时间戳是分钟精度，同批记忆必然打平，不打平到插入序的话
    # survivor 会随 uuid 十六进制字典序抽签（IN 查询走 id 索引返回）
    survivor = min(rows, key=lambda r: (r[2] or "", r[3]))
    others = [r for r in rows if r[0] != survivor[0]]

    if not await mgr.update_content(survivor[0], content):
        return False
    for other in others:
        await conn.execute(
            "UPDATE memories SET status = 'superseded', superseded_by = ? WHERE id = ?",
            (survivor[0], other[0]),
        )
    await mgr.compress_history(survivor[0])  # O3：合并兄弟行也纳入链上限
    await conn.commit()
    return True


async def consolidate_memories(conn=None) -> dict:
    """周期性整合主入口（心跳维护第六段，2h 一次、24h 节流）。

    返回值供日志与测试断言：
    {"ran": 是否实际运行（False=节流中/无合格类别）,
     "merged": 实际执行的合并数, "invalid": 校验失败跳过的提案数}
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()
    mgr = MemoryManager(conn)

    now = datetime.datetime.now(_HKT)
    if not await _throttle_allowed(conn, now):
        return {"ran": False, "merged": 0, "invalid": 0}

    cats = await mgr.list_categories()
    eligible = [c for c, n in cats.items() if n >= _MIN_CATEGORY_SIZE]
    if not eligible:
        # 无合格类别：不调 LLM、不计节流（下轮再看，纯读近零开销）
        return {"ran": False, "merged": 0, "invalid": 0}

    llm = _create_llm()
    merged = 0
    invalid = 0
    for category in eligible:
        if merged >= _MAX_MERGES_PER_RUN:
            break
        memories = await mgr.recall_by_category(category, limit=_CATEGORY_SCAN_LIMIT)
        if len(memories) < 2:
            continue
        prompt = _build_prompt(category, memories)
        try:
            response = await llm.ainvoke([
                SystemMessage(content=_CONSOLIDATE_SYSTEM),
                HumanMessage(content=prompt),
            ])
        except Exception as e:
            log.error("记忆整合：LLM 调用失败，本轮跳过：%s", e)
            break

        actions = _parse_actions(
            response.content if hasattr(response, "content") else str(response)
        )
        if actions is None:
            log.warning("记忆整合：LLM 输出不可解析，本类别跳过")
            continue

        for act in actions:
            if merged >= _MAX_MERGES_PER_RUN:
                break
            if await _apply_merge(mgr, conn, act):
                merged += 1
            else:
                invalid += 1

    await _mark_consolidated(conn, now)
    log.info("记忆整合：合并 %d 组（无效提案 %d 个）", merged, invalid)
    return {"ran": True, "merged": merged, "invalid": invalid}
