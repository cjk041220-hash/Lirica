"""era 会话路由 — 主动消息的会话归属判定与「三写一推」。

背景（2026-08-09 用户需求）：主动消息此前无任何会话归属，前端把积压
一股脑注入当前打开的对话（「早安/午安/晚上好」问候轰炸）。新模型：

- 冷冷在线时，主动消息追加进她当前活跃会话（presence 上报的
  active_session_id），她实时看到；
- 冷冷离线时（presence 判定：pagehide 上报 leaving 或距上次在线超
  过 presence_timeout_minutes），第一条主动消息**新开 era 会话**
  （is_era=1 + kind='solo'，一条「小汐的碎碎念」时间线），后续离线
  期间的主动消息全部追加进它——直到冷冷回来在 era 里接话（第一条
  user 消息把 kind 提升为 'chat'）即视为关闭；她再次离线后，下一条
  主动消息再开新 era；
- 每条主动消息三写一推：档案行（chat_messages role='assistant' +
  msg_type='solo'）→ checkpoint 追加 AI turn（她回来后 LLM 上下文里
  有独处内容，能自然接话）→ proactive_messages 行（带 session_id）
  → 推送。每步独立降级（项目「失败放行」纪律）。

本模块不得 import heartbeat（防环）；heartbeat 在函数内延迟 import
本模块（同 chat_archive 的消费模式）。app.state.agent 在调用时现取
（server.py 的 _refresh_agent 共享同一 checkpointer，旧引用也安全）。
"""
import datetime
import itertools

from utils.logger import get_logger
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

# 时间格式与全项目统一（utils.timezone.now_str）
_TIME_FMT = "%Y-%m-%d %H:%M"

# 模块级序号：同一毫秒内生成多个 era id 时保证唯一（同前端 sessionSeq 模式）
_era_seq = itertools.count(1)

_BASE36_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"


def _base36(n: int) -> str:
    """正整数转 base36（对齐前端 Date.now().toString(36) 的 id 风格）。

    注意：Python 的 format spec 只支持 b/o/d/x 基底，`format(n, "36")`
    会被解析成「宽度 36 的十进制填充」而不是 base36（2026-08-09 实锤），
    必须手写。
    """
    if n == 0:
        return "0"
    out = []
    while n:
        n, r = divmod(n, 36)
        out.append(_BASE36_DIGITS[r])
    return "".join(reversed(out))


# ── 纯函数：判定 ──────────────────────────────────────────────

def is_online(now_dt: datetime.datetime, last_seen_at: str | None,
              timeout_minutes: int) -> bool:
    """presence 在线判定：距 last_seen 不超过 timeout 分钟即在线。

    纯函数（便于测试）。last_seen 为 None（旧库 / 旧前端从未上报）
    或无法解析 → 判定离线（安全默认：宁可新开窗口，不把消息塞进
    她可能已经离开的会话）。
    """
    if not last_seen_at:
        return False
    try:
        last = datetime.datetime.strptime(last_seen_at, _TIME_FMT).replace(tzinfo=_HKT)
    except ValueError:
        log.warning("presence：last_seen_at 无法解析，按离线处理: %s", last_seen_at)
        return False
    return (now_dt - last) <= datetime.timedelta(minutes=timeout_minutes)


def decide_proactive_target(online: bool = False,
                            active_session_id: str | None = None,
                            open_era_id: str | None = None,
                            *,
                            latest_session: dict | None = None,
                            today_date: str | None = None) -> tuple[str, str | None]:
    """主动消息的路由判定（纯函数）。

    业务规则（2026-09-06 用户裁定）：
    1. 主动消息永远放在最新的聊天之下，不要搞得乱七八糟的；
    2. 小汐每天的主动消息都放在新一天的同一个聊天下；
    3. 除非冷冷新开聊天（冷冷新开后，后续主动消息跟随最新聊天）。

    判定流程：
    若提供 latest_session 与 today_date：
    - 若最新会话存在且属于今天（updated_at 或 created_at 是 today_date）：
      * 若最新会话为 is_era 且 kind='solo' → ('era', latest_session['id'])
      * 否则 → ('session', latest_session['id'])
    - 若无会话或最新会话属于过去的日期（跨天新的一天）→ ('new_era', None)

    向下兼容（未提供 latest_session 时保留纯函数既有判定）：
    - 在线且报了活跃会话 → ('session', active_session_id)
    - 离线且已有开放 era → ('era', open_era_id)
    - 否则 → ('new_era', None)
    """
    if latest_session is not None and today_date:
        latest_date = (
            latest_session.get("updated_at")
            or latest_session.get("created_at")
            or ""
        )[:10]
        if latest_date == today_date:
            if latest_session.get("is_era") and latest_session.get("kind") == "solo":
                return ("era", latest_session["id"])
            return ("session", latest_session["id"])
        return ("new_era", None)

    if online and active_session_id:
        return ("session", active_session_id)
    if open_era_id:
        return ("era", open_era_id)
    return ("new_era", None)


def new_era_id() -> str:
    """生成新的 era 会话 id：e-{毫秒时间戳36进制}-{序号}。

    对齐前端 session_id 的 s-{ts36} 风格（chat.py _SESSION_ID_RE
    契约 ^[a-z0-9-]{1,64}$），e- 前缀便于日志与排查。
    """
    ts36 = _base36(int(datetime.datetime.now(_HKT).timestamp() * 1000))
    return f"e-{ts36}-{next(_era_seq)}"


# ── 读面：开放 era 与路由目标 ─────────────────────────────────

async def get_latest_session(conn) -> dict | None:
    """全库最新活跃的会话（按 updated_at DESC, rowid DESC）。

    保证主动消息永远追加在最新的聊天之下。
    """
    cursor = await conn.execute(
        """SELECT id, title, created_at, updated_at, is_era, kind
           FROM chat_sessions
           ORDER BY updated_at DESC, rowid DESC LIMIT 1"""
    )
    row = await cursor.fetchone()
    if not row:
        return None
    return {
        "id": row[0],
        "title": row[1],
        "created_at": row[2],
        "updated_at": row[3],
        "is_era": bool(row[4]),
        "kind": row[5] or "chat",
    }


async def get_open_era_id(conn, today_date: str | None = None) -> str | None:
    """最新「仍在开放」的 era 会话 id，无则 None。

    开放 = kind='solo'（小汐在独处时间线说话、冷冷还没接话）。她回来
    在 era 里说话（第一条 user 消息）→ chat_archive 把 kind 提升为
    'chat' → 本查询自然找不到，视为关闭；era 被用户在 SessionsPanel
    删除后行消失，同样找不到 → 下一条主动消息新开 era。
    传入 today_date 时限制为当天会话，防止跨天泄漏进过去的旧独处时间线。
    """
    if today_date:
        cursor = await conn.execute(
            """SELECT id FROM chat_sessions
               WHERE kind = 'solo'
                 AND (created_at LIKE ? OR updated_at LIKE ?)
               ORDER BY updated_at DESC, rowid DESC LIMIT 1""",
            (f"{today_date}%", f"{today_date}%"),
        )
    else:
        cursor = await conn.execute(
            """SELECT id FROM chat_sessions
               WHERE kind = 'solo'
               ORDER BY created_at DESC, rowid DESC LIMIT 1"""
        )
    row = await cursor.fetchone()
    return row[0] if row else None


async def get_proactive_target(conn, now_dt: datetime.datetime,
                               timeout_minutes: int) -> tuple[str, str | None, bool]:
    """判定本条主动消息的路由目标（读 presence + 最新会话 + 跨天判定）。

    业务规则（2026-09-06 用户裁定）：
    1. 主动消息永远放在最新的聊天之下，不要搞得乱七八糟的；
    2. 小汐每天的主动消息都放在新一天的同一个聊天下；
    3. 除非冷冷新开聊天（冷冷新开后，后续主动消息跟随最新聊天）。

    返回 (kind, session_id, opener)：opener = kind == 'new_era'，
    即「这条消息是独处时间线的开场」——只有新一天的开场允许按时段问候。
    """
    cursor = await conn.execute(
        "SELECT last_seen_at, active_session_id FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    last_seen = row[0] if row else None
    active_sid = row[1] if row else None

    today_date = now_dt.strftime("%Y-%m-%d")
    online = is_online(now_dt, last_seen, timeout_minutes)

    # 1. 若冷冷在线且活跃会话属于今天，优先追加进她正在活跃的对话（实时看到）
    if online and active_sid:
        cursor = await conn.execute(
            "SELECT created_at, updated_at FROM chat_sessions WHERE id = ?",
            (active_sid,),
        )
        s_row = await cursor.fetchone()
        if s_row:
            s_date = (s_row[1] or s_row[0] or "")[:10]
            if s_date == today_date:
                return ("session", active_sid, False)
        else:
            if last_seen and last_seen[:10] == today_date:
                return ("session", active_sid, False)

    # 2. 否则查全库最新活跃会话（最新优先 + 当天聚合）
    latest = await get_latest_session(conn)
    if latest is not None:
        latest_date = (
            latest.get("updated_at")
            or latest.get("created_at")
            or ""
        )[:10]
        if latest_date == today_date:
            if latest.get("is_era") and latest.get("kind") == "solo":
                return ("era", latest["id"], False)
            return ("session", latest["id"], False)

    # 3. 新的一天或全空库：新开一段独处时间线（开场允许时段问候）
    return ("new_era", None, True)


async def resolve_proactive_target(conn, now_dt: datetime.datetime,
                                   timeout_minutes: int) -> tuple[str, str, bool]:
    """路由判定 + 失败放行：heartbeat 的单一入口。

    返回 (kind, session_id, opener)，session_id 恒非空——kind ==
    'new_era' 时已在此处生成新 era id。任何读取异常都回退到
    「离线开新 era」的安全默认：宁可新开一段独处时间线，绝不把主动
    消息塞进一个未知会话（那会原样复现「积压注入当前对话」的 bug）。
    """
    try:
        kind, sid, opener = await get_proactive_target(conn, now_dt, timeout_minutes)
        if kind == "new_era":
            sid = new_era_id()
            # C-1（2026-08-14）：预插会话行，关闭双 era 竞态窗口。
            # 竞态场景：早安 cron 与启动对齐的心跳（或任何两条主动消息
            # 任务）同分钟并发 resolve，都看到「无开放 era」→ 各开一个
            # era 且都允许开场问候（双份碎碎念）。此处立即在写锁内预插
            # chat_sessions 行（is_era=1 + kind='solo'、无消息）：
            # get_open_era_id 按 kind='solo' 找，第二个竞态方 resolve 时
            # 命中它 → 降级为追加，双开窗口关闭。
            # 标题与 record_solo_message 同款（chat_archive.era_title），
            # 保证 SessionsPanel 呈现一致。预插失败（罕见）只记日志、
            # 仍返回 new_era——回到修复前行为（宁可可能双开，不吞消息）。
            try:
                from database.core import get_write_lock
                from memory.chat_archive import era_title

                now_str = now_dt.strftime(_TIME_FMT)
                async with get_write_lock():
                    await conn.execute(
                        "INSERT INTO chat_sessions "
                        "(id, title, created_at, updated_at, is_era, kind) "
                        "VALUES (?, ?, ?, ?, 1, 'solo')",
                        (sid, era_title(), now_str, now_str),
                    )
                    await conn.commit()
            except Exception as e:
                log.warning("era 预插会话行失败（sid=%s，继续按 new_era 处理）: %s",
                            sid, e)
        return kind, sid, opener
    except Exception as e:
        log.warning("era 路由判定失败，回退为离线开新 era: %s", e)
        return ("new_era", new_era_id(), True)


# ── 写面：三写一推 ────────────────────────────────────────────

async def append_proactive_turn(app, session_id: str, content: str, *,
                                kind: str, label: str = "主动消息",
                                now_dt: datetime.datetime | None = None) -> None:
    """主动消息的三写一推，顺序固定，每步独立降级。

    1. 档案行：record_solo_message（msg_type='solo'；era 类会话带
       is_era=1 + kind='solo' 标记；内部已吞异常只记日志）
    2. checkpoint 追加 AI turn：失败只记日志——她回来后上下文缺这段
       独处内容，但档案与推送不丢（失败放行）
    3. proactive_messages 行 + 推送：_store_and_push_proactive 内部
       已有「存库失败不推送 / 推送失败降级 stored」的完整语义

    kind 取值与 decide_proactive_target 一致：'session'（在线路由，
    is_era=0）/ 'era'、'new_era'（独处时间线，is_era=1）。
    """
    from memory import chat_archive

    is_era = 1 if kind in ("era", "new_era") else 0
    now_str = now_dt.strftime(_TIME_FMT) if now_dt else None
    await chat_archive.record_solo_message(session_id, content, is_era=is_era, now=now_str)

    # 2) checkpoint 追加 —— 图 state 的 messages 通道带 add_messages
    # reducer，aupdate_state 直接追加；目标线程不存在时从空 checkpoint
    # 起步（era 新会话首次写入天然成立）；recursion_limit 只作用于图
    # 执行，不参与本调用。pre_model_hook 对连续 AI 消息无副作用
    # （裁剪只在有 human 边界时执行，记忆检索降级为空 query）。
    # 若 app 为 None（外部脚本等场景），由 chat.py 的 ensure_checkpoint_hydrated 自动水合兜底。
    try:
        from langchain_core.messages import AIMessage

        agent = getattr(app.state, "agent", None) if app is not None and hasattr(app, "state") else None
        if agent is not None:
            nodes = {}
            try:
                nodes = agent.get_graph().nodes
            except Exception:
                pass
            attribution = next(
                (n for n in ("model", "agent") if n in nodes),
                next(
                    (
                        n
                        for n in nodes
                        if n not in ("__start__", "__end__", "start", "end", "input", "output")
                    ),
                    None,
                ),
            )
            if attribution is None:
                log.warning("%s checkpoint 追加跳过：图中无可用归属节点（%s）",
                            label, sorted(nodes))
            else:
                await agent.aupdate_state(
                    {"configurable": {"thread_id": session_id}},
                    {"messages": [AIMessage(content=content)]},
                    as_node=attribution,
                )
    except Exception as e:
        log.warning("%s checkpoint 追加失败（sid=%s，不影响档案与推送）: %s",
                    label, session_id, e)

    # 3) proactive 行 + 推送（含存储失败止步 / 推送失败降级 stored）
    from heartbeat import _store_and_push_proactive

    if now_dt is not None:
        try:
            await _store_and_push_proactive(content, session_id=session_id, label=label,
                                            now_dt=now_dt)
        except TypeError:
            await _store_and_push_proactive(content, session_id=session_id, label=label)
    else:
        await _store_and_push_proactive(content, session_id=session_id, label=label)
