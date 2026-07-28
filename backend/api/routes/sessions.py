"""会话档案路由 — /api/sessions。

与 /api/chat/history 的分工：
- history 读 checkpoint（活会话刷新恢复用，会被 prune_checkpoints
  定期裁剪，不是档案）；
- sessions 读对话档案（chat_sessions / chat_messages，永久历史列表
  + 回看，可续聊——session_id 就是 LangGraph thread_id）。

档案永远不会回喂 LLM 上下文：本路由只读不写，写入只发生在
chat.py 的两条对话路径。
"""
from fastapi import APIRouter, HTTPException, Path, Query

from memory import chat_archive
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()

# 与 chat.py 的 _SESSION_ID_RE 同一契约
_SID_PATTERN = r"^[a-z0-9-]{1,64}$"


@router.get("/sessions")
async def list_sessions(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    """会话列表（按最近活跃倒序），含标题、末条预览、消息数。"""
    sessions = await chat_archive.list_sessions(limit, offset)
    return {"sessions": sessions}


@router.get("/sessions/{session_id}/messages")
async def get_session_messages(
    session_id: str = Path(pattern=_SID_PATTERN),
    limit: int = Query(200, ge=1, le=500),
):
    """会话消息（时间升序）。会话不存在返回空列表，
    与 /api/chat/history 对空线程的行为对齐。"""
    messages = await chat_archive.get_messages(session_id, limit)
    return {"session_id": session_id, "messages": messages}


@router.delete("/sessions/{session_id}")
async def delete_session(
    session_id: str = Path(pattern=_SID_PATTERN),
):
    """删除会话及其全部消息（显式级联）。"""
    ok = await chat_archive.delete_session(session_id)
    if not ok:
        raise HTTPException(404, "没有找到这个会话")
    # 删除是用户侧可见的破坏性操作，留痕（只记 sid 不记内容）
    log.info("会话已删除 (sid=%s)", session_id)
    return {"status": "ok"}
