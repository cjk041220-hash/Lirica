"""对话档案 — 会话与消息的持久化存档，供历史列表与回看读取。

与 checkpoint（prune_checkpoints 每 2h 裁剪、30 天清线程）和 memories
（narrative.py 注入 LLM 上下文）完全解耦：重要信息由小汐的记忆系统
自己提取，历史只是档案。本表永远不会被读进 LLM 上下文——只有
api/routes/chat.py 写入、api/routes/sessions.py 读取，agent/ 与
narrative.py 都不 import 本模块（隔离有 grep 自检）。

写入函数（record_*）吞掉一切异常只记日志：归档失败不能拖垮对话；
读取 / 删除函数正常上抛，由路由层处理。
"""
from database.core import get_db
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


async def _upsert_session(conn, session_id: str, fallback_title: str, now: str) -> None:
    """会话行不存在则以 fallback_title 为标题创建；已存在只刷新 updated_at。

    标题只在首次创建时写入，后续消息不覆盖（ON CONFLICT 只更新
    updated_at）。会话被删除后若同一 id 再次写入（删掉了正在进行
    的会话），行会自愈重建。
    """
    await conn.execute(
        """INSERT INTO chat_sessions (id, title, created_at, updated_at)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at""",
        (session_id, fallback_title, now, now),
    )


async def record_user_message(session_id: str, text: str) -> None:
    """归档一条用户消息（首条消息同时创建会话行并定标题）。

    text 应为不含 GPS 系统信息后缀的原始消息（后缀只拼进发给
    LLM 的 input，不落档案）。失败只记日志，不影响对话。
    """
    try:
        now = now_str()
        conn = await get_db()
        await _upsert_session(conn, session_id, _make_title(text), now)
        await conn.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at) "
            "VALUES (?, 'user', ?, ?)",
            (session_id, text, now),
        )
        await conn.commit()
    except Exception:
        log.exception("对话档案写入失败（user, sid=%s）", session_id)


async def record_assistant_message(session_id: str, text: str) -> None:
    """归档一条助手回复（空文本跳过）。

    正常路径下会话行已由用户消息创建；_upsert_session 做防御性
    兜底（极端时序下缺失则用助手文本定标题补建）。失败只记日志。
    """
    text = (text or "").strip()
    if not text:
        return
    try:
        now = now_str()
        conn = await get_db()
        await _upsert_session(conn, session_id, _make_title(text), now)
        await conn.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at) "
            "VALUES (?, 'assistant', ?, ?)",
            (session_id, text, now),
        )
        await conn.commit()
    except Exception:
        log.exception("对话档案写入失败（assistant, sid=%s）", session_id)


async def list_sessions(limit: int = 50, offset: int = 0) -> list[dict]:
    """按最近活跃倒序列出会话，附带末条消息预览与消息数。"""
    conn = await get_db()
    cursor = await conn.execute(
        """
        SELECT s.id, s.title, s.created_at, s.updated_at,
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
            "preview": (r[4] or "")[:_PREVIEW_MAX],
            "message_count": r[5],
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
        SELECT role, content, created_at FROM (
            SELECT role, content, created_at, id FROM chat_messages
            WHERE session_id = ? ORDER BY id DESC LIMIT ?
        ) ORDER BY id ASC
        """,
        (session_id, limit),
    )
    rows = await cursor.fetchall()
    return [{"role": r[0], "content": r[1], "created_at": r[2]} for r in rows]


async def delete_session(session_id: str) -> bool:
    """显式级联删除（先消息、后会话，无 FK CASCADE 可用）。

    返回是否删到了会话行。删除正在进行中的会话是安全的：下一轮
    record_* 的 upsert 会让会话行自愈重建。
    """
    conn = await get_db()
    await conn.execute("DELETE FROM chat_messages WHERE session_id = ?", (session_id,))
    cursor = await conn.execute("DELETE FROM chat_sessions WHERE id = ?", (session_id,))
    await conn.commit()
    return cursor.rowcount > 0
