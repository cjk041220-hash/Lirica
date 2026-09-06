"""对话档案 — 会话与消息的持久化存档，供历史列表与回看读取。

与 checkpoint（prune_checkpoints 每 2h 裁剪、30 天清线程）和 memories
（narrative.py 注入 LLM 上下文）解耦：重要信息由小汐的记忆系统
自己提取，历史主要是档案。写入面只有 api/routes/chat.py；读面两处：
api/routes/sessions.py（历史列表/回看）与 heartbeat 的主动消息/早安
（Spec A，2026-08-05：经下方 recent_messages 取「历史尾巴」注入生成
prompt——这是本表唯一进 LLM 上下文的通道，函数内延迟 import）。
agent/ 与 narrative.py 仍禁止 import 本模块（隔离有 grep 自检）。

写入函数（record_*）吞掉一切异常只记日志：归档失败不能拖垮对话；
recent_messages 读取异常正常上抛，由调用方降级（历史块可省，消息
生成不可断）；其余读取 / 删除函数正常上抛，由路由层处理。
"""
from database.core import get_db, get_write_lock
from database.migrations import ERA_SESSION_TITLE
from utils.logger import get_logger
from utils.timezone import now_str

log = get_logger(__name__)

# 会话标题长度上限（取首条用户消息前 N 字，超长加省略号）
_TITLE_MAX = 30
# 列表 preview 长度上限（末条消息）
_PREVIEW_MAX = 60


def _make_title(text: str) -> str:
    """从首条消息生成会话标题：前 30 字，超长加 …。"""
    t = (text or "").strip()
    return t[:_TITLE_MAX] + ("…" if len(t) > _TITLE_MAX else "")


def era_title() -> str:
    """era 会话（小汐的碎碎念）的标题：固定文案，不带日期。

    曾经是「小汐的日记 · 08月09日」——两个问题：日期后来由会话列表的
    时间分组（今天/昨天/8月）与会话内的时间分隔条表达，标题再写一遍
    是重复；且与 diaries 表每晚自动生成的真日记几乎同名，而「小汐的
    日记」实为互不相通的三种东西（真日记 / era 会话 / solo 消息），
    命名必须收口区分开。标题只在会话创建时写入，不随时间漂移。
    """
    return ERA_SESSION_TITLE


async def _upsert_session(conn, session_id: str, fallback_title: str, now: str,
                          is_era: int = 0, kind: str = "chat") -> None:
    """会话行不存在则以 fallback_title / kind 创建；已存在只刷新 updated_at。

    标题与 is_era / kind 都只在首次创建时写入（ON CONFLICT 只更新
    updated_at）——is_era 是来源标记、kind 是创建时的类型，两者都不
    能被后续消息改写。类型的**提升**（碎碎念被冷冷接话 → 变成对话）
    是唯一的可变路径，走 _record_message 里 role='user' 分支的显式
    UPDATE，不在本函数里。会话被删除后若同一 id 再次写入（删掉了
    正在进行的会话），行会自愈重建。
    """
    await conn.execute(
        """INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era, kind)
           VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at""",
        (session_id, fallback_title, now, now, is_era, kind),
    )


async def _record_message(session_id: str, text: str, role: str, msg_type: str,
                          is_era: int = 0, kind: str = "chat",
                          *, now: str | None = None) -> None:
    """归档一条消息的公共实现（用户 / 助手回复 / 主动消息独处话语共用）。

    text 应为不含 GPS 系统信息后缀的原始文本（后缀只拼进发给 LLM 的
    input，不落档案）。失败只记日志，不影响对话——归档是旁路。
    """
    try:
        now = now or now_str()
        conn = await get_db()
        # 逻辑写锁：upsert 会话行 + 插消息 + commit 是一个序列；
        # 持锁也防本 commit 截断记忆域进行中的多语句写
        async with get_write_lock():
            fallback = era_title() if msg_type == "solo" else _make_title(text)
            await _upsert_session(conn, session_id, fallback, now,
                                  is_era=is_era, kind=kind)
            await conn.execute(
                "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
                "VALUES (?, ?, ?, ?, ?)",
                (session_id, role, text, now, msg_type),
            )
            if role == "user":
                # A-1（2026-08-14）：用户新消息到达 = 上一个关闭窗口结束、新窗口
                # 开启 → 既有摘要作废。摘要只代表「上一次已关闭窗口」，重开就
                # 重摘；否则 summarize 的 NOT EXISTS 守卫会让续聊内容永不进摘要，
                # 叙事「最近的谈话」块越聊越旧。只对 role='user' 生效——era 主动
                # 消息追加（role='assistant'）不触发作废。
                await conn.execute(
                    "DELETE FROM session_summaries WHERE session_id = ?",
                    (session_id,),
                )
                # v24：冷冷接话 = 这段会话从「小汐的碎碎念」变成真正的对话。
                # is_era 是来源（不可变，留着记录它生于独处时间线），kind 是
                # 当前类型——列表标签读 kind，接话后「碎碎念」标签自然消失，
                # 不再像从前那样顶着来源标记永远不更新。带 AND kind <> 'chat'
                # 守卫：普通会话的每条消息不该都发一次无谓 UPDATE。
                await conn.execute(
                    "UPDATE chat_sessions SET kind = 'chat' "
                    "WHERE id = ? AND kind <> 'chat'",
                    (session_id,),
                )
                # 冷冷主动接话：自动作废尚未发出的定制预设主动消息（避免对话进行中被生硬插话）
                try:
                    await conn.execute(
                        "UPDATE custom_proactive_queue SET status = 'skipped' WHERE status = 'pending'"
                    )
                except Exception:
                    pass
            await conn.commit()
    except Exception:
        log.exception("对话档案写入失败（%s, sid=%s）", role, session_id)


async def record_user_message(session_id: str, text: str) -> None:
    """归档一条用户消息（首条消息同时创建会话行并定标题）。"""
    await _record_message(session_id, text, "user", "chat")


async def record_assistant_message(session_id: str, text: str) -> None:
    """归档一条助手回复（空文本跳过）。

    正常路径下会话行已由用户消息创建；_upsert_session 做防御性
    兜底（极端时序下缺失则用助手文本定标题补建）。失败只记日志。
    """
    text = (text or "").strip()
    if not text:
        return
    await _record_message(session_id, text, "assistant", "chat")


async def record_solo_message(session_id: str, text: str, *, is_era: int = 0,
                              now: str | None = None) -> None:
    """归档一条小汐的主动消息（独处话语 / 随机搭话）。

    msg_type='solo'：回放时前端渲染为日记风 + 时间戳，区别于普通回复；
    role 走 'assistant'（chat_messages 的 CHECK 约束只允许 user/assistant，
    solo 消息是小汐的话，就是 assistant）。is_era=1 / kind='solo' 标记
    era 会话（离线期间主动消息新开的独处时间线窗口）——两者都只在会话
    行首次创建时写入，追加消息不改。在线搭话走 is_era=0 / kind='chat'：
    那是在冷冷已有的对话里插一句话，会话本来就是对话，不改成碎碎念。
    失败只记日志，不影响推送。
    """
    text = (text or "").strip()
    if not text:
        return
    await _record_message(session_id, text, "assistant", "solo", is_era=is_era,
                          kind="solo" if is_era else "chat", now=now)


async def create_session(session_id: str, title: str = "",
                         kind: str = "chat") -> None:
    """显式建一条空会话行（前端「开一段新聊天」时立即调用）。

    修的是「惰性创建」的老问题：从前前端点了新聊天只改本地 sessionId，
    后端要等第一条消息才建行——于是新会话在列表里根本不存在，用户
    发完消息前切走／刷新，这段对话就凭空消失（只剩一个 localStorage
    里的 id）。现在点新聊天即落一行空会话，列表里立刻可见。

    幂等：id 已存在（如删除后立即重建同名）则静默跳过——ON CONFLICT
    DO NOTHING，不动既有标题与类型。失败只记日志：建行是旁路，建不
    成也不该打断用户开始一段新对话。
    """
    now = now_str()
    try:
        conn = await get_db()
        async with get_write_lock():
            await conn.execute(
                """INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era, kind)
                   VALUES (?, ?, ?, ?, 0, ?)
                   ON CONFLICT(id) DO NOTHING""",
                (session_id, title, now, now, kind),
            )
            await conn.commit()
    except Exception:
        log.exception("创建会话行失败（sid=%s）", session_id)


async def list_sessions(limit: int = 50, offset: int = 0) -> list[dict]:
    """按最近活跃倒序列出会话，附带末条消息预览与消息数。

    kind 与 is_era 一起返回，前端按职责分开用：
    - kind（'chat' / 'solo'）决定列表标签——「小汐的碎碎念」只在它真
      的还是独处时间线时出现，冷冷一接话标签就没了；
    - is_era 只作来源留档（这段会话生于冷冷离线的独处时间线），前端
      默认不展示。
    """
    conn = await get_db()
    cursor = await conn.execute(
        """
        SELECT s.id, s.title, s.created_at, s.updated_at, s.is_era, s.kind,
               (SELECT m.content FROM chat_messages m
                  WHERE m.session_id = s.id ORDER BY m.id DESC LIMIT 1) AS preview,
               (SELECT COUNT(*) FROM chat_messages m
                  WHERE m.session_id = s.id) AS message_count
        FROM chat_sessions s
        ORDER BY s.updated_at DESC, s.rowid DESC
        LIMIT ? OFFSET ?
        """,
        (limit, offset),
    )
    rows = await cursor.fetchall()
    return [
        {
            "id": r[0],
            "title": r[1],
            "created_at": r[2],
            "updated_at": r[3],
            "is_era": bool(r[4]),
            "kind": r[5] or "chat",
            "preview": (r[6] or "")[:_PREVIEW_MAX],
            "message_count": r[7],
        }
        for r in rows
    ]


async def get_messages(session_id: str, limit: int = 200) -> list[dict]:
    """返回会话最近 limit 条消息，按时间升序排列。

    档案永久留存，长会话可能有几千条消息——回看场景要的是
    「最近聊了什么」，故取尾部 N 条再反转为升序（子查询先
    ORDER BY id DESC LIMIT，外层 ORDER BY id ASC）。未知
    session_id 返回空列表。
    """
    conn = await get_db()
    cursor = await conn.execute(
        """
        SELECT role, content, created_at, msg_type FROM (
            SELECT role, content, created_at, msg_type, id FROM chat_messages
            WHERE session_id = ? ORDER BY id DESC LIMIT ?
        ) ORDER BY id ASC
        """,
        (session_id, limit),
    )
    rows = await cursor.fetchall()
    return [
        {"role": r[0], "content": r[1], "created_at": r[2], "msg_type": r[3]}
        for r in rows
    ]


async def delete_session(session_id: str) -> bool:
    """显式级联删除（先消息、后会话，无 FK CASCADE 可用）。

    返回是否删到了会话行。删除正在进行中的会话是安全的：下一轮
    record_* 的 upsert 会让会话行自愈重建。

    级联范围含 proactive_messages 与 session_summaries：主动消息行带
    session_id 归属，会话被删后这些行若不清理会永远停在 delivered/stored
    未读，占住 /api/push/unread 的 LIMIT 10 槽位（era 会话被删时尤其
    明显）；session_summaries 同理，残留会让叙事继续注入已删除会话的
    脉络（P1-a）。
    """
    conn = await get_db()
    async with get_write_lock():
        await conn.execute("DELETE FROM chat_messages WHERE session_id = ?", (session_id,))
        await conn.execute(
            "DELETE FROM proactive_messages WHERE session_id = ?", (session_id,)
        )
        await conn.execute(
            "DELETE FROM session_summaries WHERE session_id = ?", (session_id,)
        )
        cursor = await conn.execute("DELETE FROM chat_sessions WHERE id = ?", (session_id,))
        await conn.commit()
        return cursor.rowcount > 0


# ── Spec A：主动消息 / 早安的历史尾巴取面 ─────────────────────
# 本模块唯一的「档案 → LLM 上下文」通道：只有 heartbeat 的主动消息
# 与早安两处（函数内延迟 import）消费，隔离自检见
# tests/test_chat_archive.py::test_archive_isolation_from_llm_context_paths。

_RECENT_CONTENT_MAX = 100   # 单条截断字数
_RECENT_LINE_OVERHEAD = 20  # 「[MM-DD HH:MM] 说话人：」行标记的预算开销


async def recent_messages(limit: int = 20, max_chars: int = 1500) -> list[dict]:
    """跨会话取最近 limit 条消息（时间正序，最新在最后），供主动消息
    与早安生成时注入「历史尾巴」。

    刻意不带时间窗口：闹翻后冷冷三天不来，尾巴仍是那次争吵，正好
    该求和；一个月不来，尾巴恰好支撑「回来啦」。窗口反而漏掉这些。

    截断保护：单条 content 截 _RECENT_CONTENT_MAX 字；从最新往旧累加
    预算（内容长 + 行标记开销），超 max_chars 即停——注入块约几百
    token，不挤占生成空间。读取异常正常上抛，调用方降级。
    """
    conn = await get_db()
    cursor = await conn.execute(
        """
        SELECT role, content, created_at FROM (
            SELECT role, content, created_at, id FROM chat_messages
            ORDER BY id DESC LIMIT ?
        ) ORDER BY id ASC
        """,
        (limit,),
    )
    rows = await cursor.fetchall()
    messages = [
        {"role": r[0], "content": (r[1] or "")[:_RECENT_CONTENT_MAX], "created_at": r[2]}
        for r in rows
    ]

    # 总量封顶：从最新往旧收，超预算丢最旧
    kept: list[dict] = []
    total = 0
    for m in reversed(messages):
        cost = len(m["content"]) + _RECENT_LINE_OVERHEAD
        if kept and total + cost > max_chars:
            break
        kept.append(m)
        total += cost
    kept.reverse()
    return kept


def format_recent_messages(messages: list[dict]) -> str:
    """[{role, content, created_at}] → 带时间标记的交替行文本，最新在最下。

    时间标记是刻意设计：次日早安看到「昨晚 23:42 还僵着」、看到
    「02:13 还在聊天」，才能自然跟进求和或关心熬夜（Spec B 主动消息
    侧的承接全靠它）。
    """
    lines = []
    for m in messages:
        speaker = "冷冷" if m["role"] == "user" else "小汐"
        ts = (m.get("created_at") or "")[5:16]  # "YYYY-MM-DD HH:MM" → "MM-DD HH:MM"
        lines.append(f"[{ts}] {speaker}：{m['content']}")
    return "\n".join(lines)
