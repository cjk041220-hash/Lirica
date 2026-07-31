"""会话后台记忆提取 — 对话后异步提炼新事实，兜底纯 agentic 写路径。

背景（2026-08-01 架构评审 D1/D2）：
主对话的记忆写入完全依赖 LLM 自觉调用 remember 工具——实盘 31 条
真实消息（含明显该记的内容）零触发，记忆库空跑一周。业界
（mem0 / LangMem / Zep）全部标配「对话后异步提取」兜底通道：不打断
对话、不依赖模型在主对话里的工具调用纪律，由独立轻量 LLM 批量提炼。

本模块 = 提取流水线 + 水位线断点：
- 水位线存 memory_extract_state.last_message_id（chat_messages.id
  AUTOINCREMENT 单调，跨重启持久）；从未运行过时视为 0，首次运行
  自动回填全部历史消息；
- 心跳每 2h 调 extract_new_memories()：无新消息 → 纯读检查直接返回
  （近零开销）；有新消息 → 取最近一批（限 50 条 / 4000 字，超长从
  旧消息端截断），连同现有记忆 top-10 作冲突上下文，轻量 LLM 决策
  add / update / noop；
- 整批 dispatch 成功后才推进水位线；LLM 调用失败或输出不可解析时
  不推进（下次重试同一批——manager.add 的内容去重保证重复重试无害）。

与 agentic 主通道的关系：remember 工具仍是首选（实时、带矛盾引导），
本模块是兜底，纪律一致（不抄档案、类别复用、只记确定的事实）。
"""
import datetime
import json

from langchain_core.messages import SystemMessage, HumanMessage

from database.core import rows_to_dicts
from memory.manager import MemoryManager
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import HKT as _HKT, now_str as _now, _TIME_FMT

# ── 提取预算护栏 ──────────────────────────────────────────────
# 单批最多 50 条消息 / 4000 字（超长从旧端丢弃——提取价值随对话变旧
# 衰减，水位线照常推进，旧消息不再回看）；单次最多 5 条新增 / 3 条
# 更新（防 LLM 一次性塞爆记忆库）。
_MAX_EXTRACT_MESSAGES = 50
_MAX_EXTRACT_CHARS = 4000
_MAX_ADD_PER_RUN = 5
_MAX_UPDATE_PER_RUN = 3

# 健康检查告警节流：同一异常 24h 内最多告警一次（心跳 2h 一次，
# 不节流会 12 条/天的刷屏告警）
_HEALTH_WARN_THROTTLE_HOURS = 24

# 提取系统提示词：只提取确定事实、不抄档案、类别复用、输出纯 JSON。
# 与 remember 工具（tools/memory.py）及 soul.py 档案边界纪律保持一致。
_EXTRACT_SYSTEM = """你是小汐的记忆管家。从对话中提炼「值得长期记住的关于冷冷的新事实或变化」，只输出 JSON 数组，不要任何解释。

格式：[{"action": "add|update|noop", "category": "类别", "content": "内容", "memory_id": "可选"}]

规则：
1. 只提取确定的事实与变化；猜测、闲聊、寒暄、情绪化的临时说法不要记（noop 最常见）。
2. content 用一句话写完，具体、可独立理解（用「冷冷」而不是「她」）。
3. 身份类事实（姓名、生日、学校、家庭住址、身体固定状况）如果已写在冷冷档案里，不要重复提取。
4. category 优先从现有记忆的类别里选；没有合适的才用：「冷冷喜欢的」「冷冷的信息」「去过的地方」「重要的事」「其他」。
5. action 语义：
   - add：新事实；
   - update：与某条现有记忆矛盾或需合并——填该记忆的 memory_id，content 写新内容（变化就合并成「以前……最近……」一句话）；
   - noop：没有值得长期记住的。
6. 最多 5 条 add、3 条 update；宁缺毋滥。"""


def _create_llm():
    """创建提取用轻量 LLM（model 跟随运行时配置，temperature 低保证稳定）。

    测试 monkeypatch 本函数注入假 LLM，不走真实网络。
    """
    from api.routes.config import get_current_model
    from providers.deepseek import create_deepseek_llm

    return create_deepseek_llm(model=get_current_model(), temperature=0.2, max_tokens=300)


# ── 水位线（memory_extract_state 单行表）──────────────────────


async def get_watermark(conn) -> int:
    """读取已处理到的 chat_messages.id；从未运行过（无行）返回 0。"""
    cursor = await conn.execute(
        "SELECT last_message_id FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    return row[0] if row else 0


async def _set_watermark(conn, last_message_id: int) -> None:
    """推进水位线（UPSERT 单行表）。"""
    await conn.execute(
        """INSERT INTO memory_extract_state (id, last_message_id, last_run_at)
           VALUES (1, ?, ?)
           ON CONFLICT(id) DO UPDATE SET
               last_message_id = excluded.last_message_id,
               last_run_at = excluded.last_run_at""",
        (last_message_id, _now()),
    )
    await conn.commit()


async def _fetch_pending(conn, watermark: int) -> tuple[list[str], int, str | None]:
    """取水位线之后的消息（最近一批），返回 (渲染行文本, 批内最大 id, 末条会话 id)。

    超长预算从旧消息端丢弃：提取价值随对话变旧衰减，批次尾部（最新）
    永远保留；无新消息返回 ([], watermark, None)。
    """
    cursor = await conn.execute(
        """SELECT id, session_id, role, content FROM chat_messages
           WHERE id > ? ORDER BY id ASC""",
        (watermark,),
    )
    rows = rows_to_dicts(cursor, await cursor.fetchall())
    if not rows:
        return [], watermark, None

    batch = rows[-_MAX_EXTRACT_MESSAGES:]
    max_id = batch[-1]["id"]
    last_session_id = batch[-1].get("session_id")

    lines = []
    for r in batch:
        label = "冷冷" if r["role"] == "user" else "小汐"
        lines.append(f"{label}：{(r['content'] or '').strip()}")

    total = sum(len(line) for line in lines)
    while total > _MAX_EXTRACT_CHARS and len(lines) > 1:
        dropped = lines.pop(0)
        total -= len(dropped)
    if total > _MAX_EXTRACT_CHARS:
        lines[0] = lines[0][-_MAX_EXTRACT_CHARS:]  # 单条超长保留最新段
    return lines, max_id, last_session_id


def _build_prompt(messages: list[str], existing: list[dict]) -> str:
    """组装提取提示词：现有记忆（冲突上下文）+ 待处理对话。"""
    parts = []
    if existing:
        parts.append("现有记忆（ID 格式为 [id] 类别：内容）：")
        parts.extend(
            f"[{m['id']}] {m.get('category', '其他')}：{m.get('content', '')}"
            for m in existing
        )
        parts.append("")
    parts.append("待处理对话（最新的在后面）：")
    parts.extend(messages)
    return "\n".join(parts)


def _parse_actions(text) -> list[dict] | None:
    """容错解析 LLM 输出的 JSON 数组（剥离 ```json 围栏，取首个 [ ] 块）。

    None 与 [] 语义不同：[] 是 LLM 判定无话可记（合法，推进水位线）；
    None 是输出格式坏了（不推进，下次重试同一批）。
    """
    s = (text or "").strip()
    start, end = s.find("["), s.rfind("]")
    if start == -1 or end <= start:
        return None
    try:
        data = json.loads(s[start:end + 1])
    except json.JSONDecodeError:
        return None
    if not isinstance(data, list):
        return None
    return [d for d in data if isinstance(d, dict)]


async def _dispatch(mgr: MemoryManager, actions: list[dict],
                    source: str | None = None) -> tuple[int, int, int]:
    """按 LLM 决策执行 add / update / noop，返回 (新增, 更新, 跳过)。

    add 走 manager.add（原子去重，重复内容自动刷新时间戳而非双行；
    source 为来源追踪：chat:{session_id}）；update 走
    manager.update_content（ID 不存在视为跳过——可能已删）。
    """
    added = updated = skipped = 0
    for act in actions:
        action = str(act.get("action", "") or "").strip().lower()
        content = str(act.get("content", "") or "").strip()

        if action == "add":
            if added >= _MAX_ADD_PER_RUN:
                skipped += 1
                continue
            category = str(act.get("category", "") or "").strip()
            if not category or not content:
                skipped += 1
                continue
            try:
                await mgr.add(category, content, source=source)
                added += 1
            except ValueError:
                skipped += 1

        elif action == "update":
            if updated >= _MAX_UPDATE_PER_RUN:
                skipped += 1
                continue
            memory_id = str(act.get("memory_id", "") or "").strip()
            if not memory_id or not content:
                skipped += 1
                continue
            if await mgr.update_content(memory_id, content):
                updated += 1
            else:
                skipped += 1

        else:  # 未知 action / noop
            skipped += 1
    return added, updated, skipped


# ── 主入口 ────────────────────────────────────────────────────


async def extract_new_memories(conn=None) -> dict:
    """后台提取主入口：处理水位线之后的新对话，提炼可长期记忆的事实。

    由心跳 _periodic_maintenance 每 2h 调用（无新消息时纯读，近零开销）。
    返回值供日志与测试断言：
    {"processed": 送入 LLM 的消息条数, "added": 新增, "updated": 更新,
     "skipped": 跳过, "llm_error": 是否失败（水位线未推进）}
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()
    mgr = MemoryManager(conn)

    watermark = await get_watermark(conn)
    messages, max_id, last_session_id = await _fetch_pending(conn, watermark)
    if not messages:
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0, "llm_error": False}

    # 冲突上下文：现有记忆 top-10（被动读取，不刷热度——叙事注入的
    # passive 纪律同样适用于提取侧）
    existing = await mgr.recent(limit=10)
    prompt = _build_prompt(messages, existing)

    try:
        llm = _create_llm()
        response = await llm.ainvoke([
            SystemMessage(content=_EXTRACT_SYSTEM),
            HumanMessage(content=prompt),
        ])
    except Exception as e:
        log.error("记忆后台提取：LLM 调用失败，水位线保持 %d 待下次重试：%s", watermark, e)
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0, "llm_error": True}

    actions = _parse_actions(
        response.content if hasattr(response, "content") else str(response)
    )
    if actions is None:
        log.warning("记忆后台提取：LLM 输出不可解析，水位线保持 %d 待下次重试", watermark)
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0, "llm_error": True}

    # 来源追踪（v12）：新记忆标注提取自哪个会话，供「为什么记得这个」追溯
    source = f"chat:{last_session_id}" if last_session_id else None
    added, updated, skipped = await _dispatch(mgr, actions, source=source)
    await _set_watermark(conn, max_id)
    log.info(
        "记忆后台提取：处理 %d 条消息 → 新增 %d / 更新 %d / 跳过 %d",
        len(messages), added, updated, skipped,
    )
    return {
        "processed": len(messages),
        "added": added,
        "updated": updated,
        "skipped": skipped,
        "llm_error": False,
    }


# ── 健康检查（节流告警，指标计算在 manager.check_memory_health）──


async def _throttle_allowed(conn, now: datetime.datetime) -> bool:
    """告警节流：距上次告警 ≥24h 才允许再次告警（防 12 条/天刷屏）。"""
    cursor = await conn.execute(
        "SELECT health_warned_at FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    if not row or not row[0]:
        return True
    try:
        last = datetime.datetime.strptime(row[0], _TIME_FMT).replace(tzinfo=_HKT)
    except (ValueError, TypeError):
        return True
    return (now - last).total_seconds() >= _HEALTH_WARN_THROTTLE_HOURS * 3600


async def _mark_health_warned(conn, now: datetime.datetime) -> None:
    """记录本次告警时间（ON CONFLICT 只更新 health_warned_at，保留水位线）。"""
    now_str = now.strftime(_TIME_FMT)
    await conn.execute(
        """INSERT INTO memory_extract_state (id, last_message_id, last_run_at, health_warned_at)
           VALUES (1, 0, ?, ?)
           ON CONFLICT(id) DO UPDATE SET health_warned_at = excluded.health_warned_at""",
        (now_str, now_str),
    )
    await conn.commit()


async def check_and_report_memory_health(conn=None) -> dict:
    """记忆健康检查 + 节流告警（心跳维护第 5 段）。

    实盘教训（2026-08-01 评审 D3）：0 记忆跑一周无告警。本函数把
    manager.check_memory_health 的纯指标转成日志：异常时 24h 节流
    WARNING（区分「有对话零记忆——写路径失灵」与「记忆停滞」），
    正常时 INFO 打点。返回 {指标..., "warned": bool}。
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()
    mgr = MemoryManager(conn)
    health = await mgr.check_memory_health()

    if not health["abnormal"]:
        stale = (
            f"{health['days_since_write']:.0f}天"
            if health["days_since_write"] is not None
            else "从未写入"
        )
        log.info("记忆健康：count=%d, 距上次写入=%s, chats=%d",
                 health["active_count"], stale, health["chat_count"])
        return {**health, "warned": False}

    now = datetime.datetime.now(_HKT)
    if not await _throttle_allowed(conn, now):
        return {**health, "warned": False}
    await _mark_health_warned(conn, now)

    if health["active_count"] == 0:
        log.warning(
            "记忆健康异常：已有 %d 条对话消息但 0 条记忆 —— 写路径疑似失灵，"
            "检查 remember 工具与后台提取",
            health["chat_count"],
        )
    else:
        log.warning(
            "记忆健康异常：%d 条记忆但已 %.0f 天无新写入 —— 记忆停滞",
            health["active_count"], health["days_since_write"],
        )
    return {**health, "warned": True}
