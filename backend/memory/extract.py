"""会话后台记忆提取 — 对话后异步提炼新事实，兜底纯 agentic 写路径。

背景（2026-08-01 架构评审 D1/D2）：
主对话的记忆写入完全依赖 LLM 自觉调用 remember 工具——实盘 31 条
真实消息（含明显该记的内容）零触发，记忆库空跑一周。业界
（mem0 / LangMem / Zep）全部标配「对话后异步提取」兜底通道：不打断
对话、不依赖模型在主对话里的工具调用纪律，由独立轻量 LLM 批量提炼。

本模块 = 提取流水线 + 水位线断点：
- 水位线存 memory_extract_state.last_message_id（chat_messages.id
  AUTOINCREMENT 单调，跨重启持久）；从未运行过时视为 0。**有界、
  不回看**（体检 T1-8 修 docstring 对齐实际行为）：首次运行只消费
  最新 50 条并把水位线直接推到批尾，更早的历史永久跳过——提取价值
  随对话变旧衰减，不追历史是有意设计，不是缺陷；
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
from memory.manager import MemoryManager, _HEALTH_MIN_CHATS
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

# 提取解析失败死信阈值（v13）：同一批连续解析失败达到此数 → 放弃该批
# 并推进水位线。修复前「输出不可解析」永远不推进水位线：输出确定性
# 损坏的批次（满载 8 动作 JSON 撞 max_tokens 截断就是典型）会让提取
# 通道在同一批上永久空转，且健康检查只能报「停滞」、无法自愈。连续
# 3 次（心跳 2h 一次 ≈ 6h）仍解析不了，判定为内容系统性损坏，死信丢弃
# （与预算超限从旧端丢弃同语义：被弃消息不再回看）。
# LLM 调用异常（网络/配额）不计次——那是瞬态故障，重试即自愈；长期
# 失灵由健康检查「记忆停滞」告警兜底。
_MAX_PARSE_FAILURES = 3

# 提取系统提示词：只提取确定事实、不抄档案、类别复用、输出纯 JSON。
# 与 remember 工具（tools/memory.py）及 soul.py 档案边界纪律保持一致。
_EXTRACT_SYSTEM = """你是小汐的记忆管家。从对话中提炼「值得长期记住的关于冷冷的新事实或变化」，只输出 JSON 数组，不要任何解释。

格式：[{"action": "add|update|noop", "category": "类别", "content": "内容", "memory_id": "可选", "importance": 1-10, "valence": -1~1, "arousal": -1~1}]

规则：
1. 只提取确定的事实与变化；猜测、闲聊、寒暄、情绪化的临时说法不要记（noop 最常见）。
2. content 用一句话写完，具体、可独立理解（用「冷冷」而不是「她」）。
3. 身份类事实（姓名、生日、学校、家庭住址、身体固定状况）如果已写在冷冷档案里，不要重复提取。
4. category 优先从现有记忆的类别里选；没有合适的才用：「冷冷喜欢的」「冷冷的信息」「去过的地方」「重要的事」「其他」。
5. action 语义：
   - add：新事实；
   - update：与某条现有记忆矛盾或需合并——填该记忆的 memory_id，content 写新内容（变化就合并成「以前……最近……」一句话）；
   - noop：没有值得长期记住的。
6. 最多 5 条 add、3 条 update；宁缺毋滥。
7. importance 只对 add 生效：1-10 打分（1=日常流水一句，5=一般，10=人生大事/重大变化）。update 与 noop 不填。
8. valence / arousal 只对 add 生效，给这条事实打一个情绪坐标：
   - valence 是这件事对冷冷而言的情绪效价：-1=明确的坏事/痛苦（生病、失去、吵架），0=中性事实（日程、信息、偏好陈述），1=明确的好事/快乐（实现愿望、好消息、被爱）；
   - arousal 是这件事的唤醒度：-1=平静低能量（习惯、常态、淡淡的），0=一般，1=激动高能量（惊喜、崩溃、急切）；
   - 中性事实两个都填 0；拿不准就往 0 靠。update 与 noop 不填。
9. content 里的日期一律写绝对日期（如「8月5日」），禁止写「今天/明天/后天/下周」这类相对时间词——日子会过去，下次读就过期弄混（对话原文里的相对说法，按对话当天的日子换算成绝对日期再写）。"""


def _create_llm():
    """创建提取用轻量 LLM（model 跟随运行时配置，temperature 低保证稳定）。

    测试 monkeypatch 本函数注入假 LLM，不走真实网络。
    """
    from api.routes.config import get_current_model
    from providers.deepseek import create_deepseek_llm

    # max_tokens 800（v13，原 300）：满载批允许 5 add + 3 update 共 8 个
    # JSON 动作，中文内容下 300 token 容易被截断 → 输出不可解析。放宽
    # 到 800 消除绝大多数截断；万一仍损坏，死信（_MAX_PARSE_FAILURES）
    # 保证水位线不会永久卡死。
    return create_deepseek_llm(model=get_current_model(), temperature=0.2, max_tokens=800)


# ── 水位线（memory_extract_state 单行表）──────────────────────


async def get_watermark(conn) -> int:
    """读取已处理到的 chat_messages.id；从未运行过（无行）返回 0。"""
    cursor = await conn.execute(
        "SELECT last_message_id FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    return row[0] if row else 0


async def _set_watermark(conn, last_message_id: int) -> None:
    """推进水位线（UPSERT 单行表）。

    同时归零 fail_count：批次只要被成功消费（含合法空批 []），连续
    解析失败计数就从头开始——死信只针对「同一批持续损坏」。
    """
    await conn.execute(
        """INSERT INTO memory_extract_state (id, last_message_id, last_run_at, fail_count)
           VALUES (1, ?, ?, 0)
           ON CONFLICT(id) DO UPDATE SET
               last_message_id = excluded.last_message_id,
               last_run_at = excluded.last_run_at,
               fail_count = 0""",
        (last_message_id, _now()),
    )
    await conn.commit()


async def get_fail_count(conn) -> int:
    """当前批次连续解析失败次数（无状态行返回 0）。"""
    cursor = await conn.execute(
        "SELECT fail_count FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    return (row[0] or 0) if row else 0


async def _set_fail_count(conn, n: int) -> None:
    """更新失败计数（UPSERT 保留水位线；尚无状态行时水位线为 0）。"""
    await conn.execute(
        """INSERT INTO memory_extract_state (id, last_message_id, fail_count)
           VALUES (1, 0, ?)
           ON CONFLICT(id) DO UPDATE SET fail_count = excluded.fail_count""",
        (n,),
    )
    await conn.commit()


async def _fetch_pending(
    conn, watermark: int
) -> tuple[list[str], int, str | None, str]:
    """取水位线之后的消息（最近一批）。

    返回 (渲染行文本, 批内最大 id, 末条会话 id, 冷冷发言拼接)。
    最后一项供冲突上下文做话题候选召回（体检 D1，见 extract_new_memories）。
    超长预算从旧消息端丢弃：提取价值随对话变旧衰减，批次尾部（最新）
    永远保留；无新消息返回 ([], watermark, None, "")。
    """
    cursor = await conn.execute(
        """SELECT id, session_id, role, content FROM chat_messages
           WHERE id > ? ORDER BY id ASC""",
        (watermark,),
    )
    rows = rows_to_dicts(cursor, await cursor.fetchall())
    if not rows:
        return [], watermark, None, ""

    batch = rows[-_MAX_EXTRACT_MESSAGES:]
    max_id = batch[-1]["id"]
    last_session_id = batch[-1].get("session_id")

    lines = []
    user_parts = []
    for r in batch:
        content = (r["content"] or "").strip()
        if r["role"] == "user":
            lines.append(f"冷冷：{content}")
            if content:
                user_parts.append(content)
        else:
            lines.append(f"小汐：{content}")

    total = sum(len(line) for line in lines)
    while total > _MAX_EXTRACT_CHARS and len(lines) > 1:
        dropped = lines.pop(0)
        total -= len(dropped)
    if total > _MAX_EXTRACT_CHARS:
        lines[0] = lines[0][-_MAX_EXTRACT_CHARS:]  # 单条超长保留最新段
    return lines, max_id, last_session_id, " ".join(user_parts)


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


def _coerce_content(text) -> str:
    """LLM 响应 content 归一化为纯文本（体检 T1-2：堵死信机制的后门）。

    OpenAI 兼容协议允许 content 是多段 list（[{"type": "text", "text": ...}, ...]，
    thinking 模型/特定 langchain 版本会产生）——修复前 `(text or "").strip()`
    对非空 list 直接抛 AttributeError，而 _parse_actions 的调用在 try 块
    之外：异常不计 fail_count、不推进水位线 → 每 2h 原样重放同一批，
    **恰好复现死信机制要消灭的永久卡死，只是换了个入口**。

    归一化优先于死信：list 里的 text 块拼回纯文本，其中可解析的 JSON
    照常提取（不该因为传输形态把有效输出送进死信永久丢失）；抽不出
    text 块 → 空串 → _parse_actions 返回 None → 走正常的失败计数/死信。
    """
    if isinstance(text, list):
        parts = []
        for part in text:
            if isinstance(part, dict) and isinstance(part.get("text"), str):
                parts.append(part["text"])
            elif isinstance(part, str):
                parts.append(part)
        return "".join(parts)
    return str(text or "")


def _parse_actions(text) -> list[dict] | None:
    """容错解析 LLM 输出的 JSON 数组（剥离 ```json 围栏，取首个 [ ] 块）。

    None 与 [] 语义不同：[] 是 LLM 判定无话可记（合法，推进水位线）；
    None 是输出格式坏了（不推进，计入 fail_count，达阈值死信弃批）。
    入参先经 _coerce_content 归一化——任何响应形态都不会在本函数内
    抛异常（防绕过死信的卡死后门，见 _coerce_content 说明）。
    """
    s = _coerce_content(text).strip()
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
                # I1：LLM 顺带打 1-10 分（mgr.add 内部 _coerce_importance
                # 兜底：None/非法 → 5，超界钳回 1-10）
                # v18 情绪联动：顺带打情绪坐标（_coerce_valence/_coerce_arousal
                # 兜底：None/非法/NaN → 0.0，超界钳回 [-1,1]）
                await mgr.add(category, content, source=source,
                              importance=act.get("importance"),
                              valence=act.get("valence"),
                              arousal=act.get("arousal"))
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
    {"processed": 成功消费时的消息条数（失败/死信路径为 0）,
     "added": 新增, "updated": 更新, "skipped": 跳过,
     "llm_error": 本批未产出（LLM 调用失败 / 输出不可解析 / 死信弃批
     均置真；注意死信时水位线**仍推进**——这是它与设计意图的区别：
     其余 llm_error=True 路径水位线保持待重试）,
     "dead_letter": 是否死信丢弃了本批（v13：连续解析失败 ≥3 次后
     放弃该批并推进水位线，防提取通道永久卡死）}
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()
    mgr = MemoryManager(conn)

    watermark = await get_watermark(conn)
    messages, max_id, last_session_id, user_text = await _fetch_pending(conn, watermark)
    if not messages:
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0,
                "llm_error": False, "dead_letter": False}

    # 冲突上下文：按批次话题召回候选（体检 D1）。修复前只喂最近 10 条
    # （mgr.recent）——与更旧记忆的矛盾（如三个月前的「爱吃火锅」遇上
    # 今天的「不爱吃了」）LLM 根本看不见，只能发 add → 矛盾记忆共存。
    # manager.recall 内置 bigram 二段话题排序（共享二字词整体优先 +
    # 层内四因子评分）与全表候选池，是现成的话题候选召回器；passive
    # 保持提取侧不刷排序热度的纪律（按日计 hit_count 作衰减燃料，
    # 提取上下文同样是一次「浮现」，该计就计）。
    existing = await mgr.recall(limit=10, passive=True, query=user_text)
    prompt = _build_prompt(messages, existing)

    try:
        llm = _create_llm()
        response = await llm.ainvoke([
            SystemMessage(content=_EXTRACT_SYSTEM),
            HumanMessage(content=prompt),
        ])
    except Exception as e:
        # 瞬态故障（网络/配额）：不计入 fail_count，重试即自愈；
        # 长期失灵由健康检查「记忆停滞」告警兜底
        log.error("记忆后台提取：LLM 调用失败，水位线保持 %d 待下次重试：%s", watermark, e)
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0,
                "llm_error": True, "dead_letter": False}

    actions = _parse_actions(
        response.content if hasattr(response, "content") else str(response)
    )
    if actions is None:
        fails = await get_fail_count(conn) + 1
        if fails >= _MAX_PARSE_FAILURES:
            # 死信：同一批连续 3 次不可解析 → 输出确定性损坏（如满载
            # JSON 撞 token 上限截断），放弃该批并推进水位线，让提取
            # 通道恢复流动。被弃消息不再回看（同预算超限旧端丢弃语义）。
            await _set_watermark(conn, max_id)  # 内部顺带归零 fail_count
            log.error(
                "记忆后台提取：批次（水位线 %d → %d）连续 %d 次不可解析，"
                "死信丢弃该批并推进水位线，防永久卡死",
                watermark, max_id, fails,
            )
            return {"processed": 0, "added": 0, "updated": 0, "skipped": 0,
                    "llm_error": True, "dead_letter": True}
        await _set_fail_count(conn, fails)
        log.warning(
            "记忆后台提取：LLM 输出不可解析（%d/%d），水位线保持 %d 待下次重试",
            fails, _MAX_PARSE_FAILURES, watermark,
        )
        return {"processed": 0, "added": 0, "updated": 0, "skipped": 0,
                "llm_error": True, "dead_letter": False}

    # 来源追踪（v12）：新记忆标注提取自哪个会话，供「为什么记得这个」追溯
    source = f"chat:{last_session_id}" if last_session_id else None
    added, updated, skipped = await _dispatch(mgr, actions, source=source)
    await _set_watermark(conn, max_id)  # 内部顺带归零 fail_count
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
        "dead_letter": False,
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
    WARNING（三态区分——从未写入 / 最近停摆 / 停滞且有对话，日志可
    直接区分排查方向），正常时 INFO 打点。INFO 行含全部多指标
    （提取率 / 归档占比）= 基线积累的时间序列数据源（2026-08-04
    MEMORY_GAPS 收尾：告警阈值待 2-4 周实盘数据校准）。
    返回 {指标..., "warned": bool}。
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
        rate = (
            f"{health['extract_rate_7d'] * 100:.0f}%"
            if health["extract_rate_7d"] is not None
            else "无对话"
        )
        log.info(
            "记忆健康：count=%d, 距上次写入=%s, 近7天 chats=%d/新增=%d"
            "（提取率 %s, 归档占比 %d%%）",
            health["active_count"], stale, health["chats_7d"],
            health["added_7d"], rate, health["archive_ratio"] * 100,
        )
        return {**health, "warned": False}

    now = datetime.datetime.now(_HKT)
    if not await _throttle_allowed(conn, now):
        return {**health, "warned": False}
    await _mark_health_warned(conn, now)

    if health["active_count"] == 0:
        log.warning(
            "记忆健康异常：近 7 天已有 %d 条对话消息但 0 条记忆 —— 写路径"
            "疑似失灵（从未写入），检查 remember 工具与后台提取",
            health["chats_7d"],
        )
    elif health["chats_7d"] >= _HEALTH_MIN_CHATS and health["added_7d"] == 0:
        log.warning(
            "记忆健康异常：近 7 天已有 %d 条对话消息但 0 条新增记忆 —— 写路径"
            "疑似停摆（库里有 %d 条旧记忆），检查后台提取水位线与 remember",
            health["chats_7d"], health["active_count"],
        )
    else:
        log.warning(
            "记忆健康异常：%d 条记忆但已 %.0f 天无新写入，且近 7 天仍有对话"
            " —— 记忆停滞",
            health["active_count"], health["days_since_write"],
        )
    return {**health, "warned": True}
