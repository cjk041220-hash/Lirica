"""会话档案路由 — /api/sessions。

对话档案（chat_sessions / chat_messages）是永久历史的**唯一**真相源：
- 列表与回看只读档案；
- 刷新恢复也读档案（每条用户/助手消息在写入时就已归档，见
  chat.py 的两条对话路径）——checkpoint 是 LLM 的实时上下文，会被
  prune_checkpoints 定期裁剪，不承担「历史」职责；
- 会话被删除时反过来要把 checkpoint 线程一并清掉，否则界面上历史
  已空、小汐的上下文里却还留着整段对话。

本路由只读不写（唯一的写是 POST 建空会话行与 DELETE 删会话），
档案的消息写入只发生在 chat.py 与 era/heartbeat 的主动消息路径。
"""
from fastapi import APIRouter, HTTPException, Path, Query

from database.core import delete_checkpoint_thread
from memory import chat_archive
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()

# 与 chat.py 的 _SESSION_ID_RE 同一契约
_SID_PATTERN = r"^[a-z0-9-]{1,64}$"

# 单用户陪伴场景：会话量级是「几百条」而非「几万条」，一次拉齐比分页
# 体验更好（前端要按时间连续分组，分页会在翻页处切断分组）。上限只
# 是防御性封顶，正常永远到不了。
_MAX_LIMIT = 500


@router.get("/sessions")
async def list_sessions(
    limit: int = Query(200, ge=1, le=_MAX_LIMIT),
    offset: int = Query(0, ge=0),
):
    """会话列表（按最近活跃倒序），含标题、类型、末条预览、消息数。"""
    sessions = await chat_archive.list_sessions(limit, offset)
    return {"sessions": sessions}


@router.post("/sessions")
async def create_session(session_id: str = Query(..., pattern=_SID_PATTERN)):
    """显式建一条空会话行（前端「开一段新聊天」时立即调用）。

    修的是惰性创建的老问题：从前点了新聊天只改前端的 sessionId，后端
    要等第一条消息才建行——新会话在列表里根本不存在，用户在发第一条
    消息前切走或刷新，这段对话就凭空消失。现在点新聊天即落一行空会话，
    列表里立刻可见。

    幂等（ON CONFLICT DO NOTHING）：删掉会话后同一 id 再次创建不会
    覆盖既有标题与类型。空会话不影响列表观感——前端按 updated_at 分组
    展示，它自然落在「今天」。
    """
    await chat_archive.create_session(session_id)
    return {"status": "ok", "session_id": session_id}


@router.get("/sessions/{session_id}/messages")
async def get_session_messages(
    session_id: str = Path(pattern=_SID_PATTERN),
    limit: int = Query(200, ge=1, le=500),
):
    """会话消息（时间升序）。会话不存在返回空列表。"""
    messages = await chat_archive.get_messages(session_id, limit)
    return {"session_id": session_id, "messages": messages}


@router.delete("/sessions/{session_id}")
async def delete_session(
    session_id: str = Path(pattern=_SID_PATTERN),
):
    """删除会话及其全部消息（显式级联 + 清理 LLM 上下文线程）。

    除了档案四张表（消息 / 主动消息 / 会话摘要 / 会话行），还要删掉
    checkpoints.db 里同名的 LangGraph thread：thread_id 就是
    session_id，留着它的话，冷冷再次用这个 id 说话时小汐会带着「这段
    对话从没被删过」的完整上下文接话，而界面上历史是空的——上下文与
    界面彻底脱节。清理失败不回滚档案删除（清理是旁路，只记日志）。
    """
    ok = await chat_archive.delete_session(session_id)
    if not ok:
        raise HTTPException(404, "没有找到这个会话")
    await delete_checkpoint_thread(session_id)
    # 删除是用户侧可见的破坏性操作，留痕（只记 sid 不记内容）
    log.info("会话已删除 (sid=%s)", session_id)
    return {"status": "ok"}
