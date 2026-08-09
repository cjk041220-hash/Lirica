"""era 会话路由 — 主动消息的会话归属判定与「三写一推」。

背景（2026-08-09 用户需求）：主动消息此前无任何会话归属，前端把积压
一股脑注入当前打开的对话（「早安/午安/晚上好」问候轰炸）。新模型：

- 冷冷在线时，主动消息追加进她当前活跃会话（presence 上报的
  active_session_id），她实时看到；
- 冷冷离线时（presence 判定：pagehide 上报 leaving 或距上次在线超
  过 presence_timeout_minutes），第一条主动消息**新开 era 会话**
  （chat_sessions.is_era=1，一条「小汐的独处时间线」），后续离线期间
  的主动消息全部追加进它——直到冷冷回来在 era 里接话（出现 user
  消息）即视为关闭；她再次离线后，下一条主动消息再开新 era；
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


def decide_proactive_target(online: bool, active_session_id: str | None,
                            open_era_id: str | None) -> tuple[str, str | None]:
    """主动消息的路由判定（纯函数）。

    返回 (kind, session_id)：
    - ('session', sid)：在线且报了活跃会话 → 追加进当前对话
    - ('era', era_id)：离线且已有开放的 era → 追加进独处时间线
    - ('new_era', None)：离线且无开放的 era → 新开一段独处时间线
    """
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

async def get_open_era_id(conn) -> str | None:
    """最新「仍在开放」的 era 会话 id，无则 None。

    开放 = is_era=1 且还没有任何用户消息（冷冷还没回来接话）。她回来
    在 era 里说话（出现 user 行）即视为关闭；era 被用户在 SessionsPanel
    删除后行消失，本查询自然找不到 → 下一条主动消息新开 era。
    """
    cursor = await conn.execute(
        """SELECT id FROM chat_sessions
           WHERE is_era = 1 AND NOT EXISTS (
               SELECT 1 FROM chat_messages m
               WHERE m.session_id = chat_sessions.id AND m.role = 'user'
           )
           ORDER BY created_at DESC, rowid DESC LIMIT 1"""
    )
    row = await cursor.fetchone()
    return row[0] if row else None


async def get_proactive_target(conn, now_dt: datetime.datetime,
                               timeout_minutes: int) -> tuple[str, str | None, bool]:
    """判定本条主动消息的路由目标（读 life_state + 开放 era）。

    返回 (kind, session_id, opener)：opener = kind == 'new_era'，
    即「这条消息是独处时间线的开场」——只有开场允许按时段问候。
    """
    cursor = await conn.execute(
        "SELECT last_seen_at, active_session_id FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    last_seen = row[0] if row else None
    active_sid = row[1] if row else None

    online = is_online(now_dt, last_seen, timeout_minutes)
    open_era = await get_open_era_id(conn)
    kind, sid = decide_proactive_target(online, active_sid, open_era)
    return kind, sid, (kind == "new_era")


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
        return kind, sid, opener
    except Exception as e:
        log.warning("era 路由判定失败，回退为离线开新 era: %s", e)
        return ("new_era", new_era_id(), True)


# ── 写面：三写一推 ────────────────────────────────────────────

async def append_proactive_turn(app, session_id: str, content: str, *,
                                kind: str, label: str = "主动消息") -> None:
    """主动消息的三写一推，顺序固定，每步独立降级。

    1. 档案行：record_solo_message（msg_type='solo'；era 类会话带
       is_era=1 标记；内部已吞异常只记日志）
    2. checkpoint 追加 AI turn：失败只记日志——她回来后上下文缺这段
       独处内容，但档案与推送不丢（失败放行）
    3. proactive_messages 行 + 推送：_store_and_push_proactive 内部
       已有「存库失败不推送 / 推送失败降级 stored」的完整语义

    kind 取值与 decide_proactive_target 一致：'session'（在线路由，
    is_era=0）/ 'era'、'new_era'（独处时间线，is_era=1）。
    """
    from memory import chat_archive

    is_era = 1 if kind in ("era", "new_era") else 0
    await chat_archive.record_solo_message(session_id, content, is_era=is_era)

    # 2) checkpoint 追加 —— 图 state 的 messages 通道带 add_messages
    # reducer，aupdate_state 直接追加；目标线程不存在时从空 checkpoint
    # 起步（era 新会话首次写入天然成立）；recursion_limit 只作用于图
    # 执行，不参与本调用。pre_model_hook 对连续 AI 消息无副作用
    # （裁剪只在有 human 边界时执行，记忆检索降级为空 query）。
    try:
        from langchain_core.messages import AIMessage

        agent = app.state.agent
        # as_node 必须显式传：langgraph 1.x 对外部 aupdate_state 强校验
        # ——不传时从 checkpoint 的 versions_seen 推断「最后更新状态的
        # 节点」，全新线程（era 首条消息天然如此）为空 → InvalidUpdateError:
        # Ambiguous update（2026-08-09 实锤）。create_agent 的节点名
        # （model/tools/middleware.*）随版本演进不能硬编码——运行时从图
        # 里挑一个真实节点做归属标注（优先 model/agent，回退任一非伪节点）
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

    await _store_and_push_proactive(content, session_id=session_id, label=label)
