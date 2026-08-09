"""状态路由 — /api/status。

提供两个只读端点，让前端看见「小汐自己的世界」：
- GET /api/status — 小汐的今日状态（心情 + 日常事件，即页面上的「碎碎念」）
- GET /api/diaries — 小汐的日记列表（含每晚自动写的日记）

数据分别来自 life_state 表（心跳每 2 小时刷新）和 diaries 表
（write_diary 工具 + 每晚自动日记任务写入）。纯只读，无敏感操作。
"""
from fastapi import APIRouter, Query

from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()


@router.get("/status")
async def get_status():
    """小汐的今日状态：心情 + 今天经历的事（碎碎念）+ presence / era。

    v21 扩展（主动消息独立会话）：返回 last_seen_at / active_session_id
    （presence 上报回显，前端可感知「在不在」）与 unseen_era_id ——
    最新 is_era 会话且仍有未读主动消息的 id（非 null 即「小汐在你不
    在这段时间说了话，切过去看」的信号）。全部 try 降级，读失败回
    null 组不阻塞页面。
    """
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT mood, daily_event, updated_at, last_seen_at, active_session_id "
            "FROM life_state WHERE id = 1"
        )
        row = await cursor.fetchone()
        if not row:
            return {
                "mood": None, "daily_event": None, "updated_at": None,
                "last_seen_at": None, "active_session_id": None,
                "unseen_era_id": None,
            }
        # unseen_era_id：最新 era 会话且有未读主动消息（delivered/stored
        # 未读）才返回——没有新内容就不打断当前会话
        cursor = await conn.execute(
            """SELECT s.id FROM chat_sessions s
               WHERE s.is_era = 1 AND EXISTS (
                   SELECT 1 FROM proactive_messages p
                   WHERE p.session_id = s.id
                     AND p.status IN ('delivered', 'stored')
                     AND p.read_at IS NULL
               )
               ORDER BY s.created_at DESC, s.rowid DESC LIMIT 1"""
        )
        era_row = await cursor.fetchone()
        return {
            "mood": row[0],
            "daily_event": row[1],
            "updated_at": row[2],
            "last_seen_at": row[3],
            "active_session_id": row[4],
            "unseen_era_id": era_row[0] if era_row else None,
        }
    except Exception as e:
        log.warning("状态读取失败：%s", e)
        return {
            "mood": None, "daily_event": None, "updated_at": None,
            "last_seen_at": None, "active_session_id": None,
            "unseen_era_id": None,
        }


@router.get("/diaries")
async def get_diaries(limit: int = Query(default=30, ge=1, le=100)):
    """小汐的日记列表，按日期倒序（最新的在前）。"""
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT date, title, content FROM diaries ORDER BY date DESC LIMIT ?",
            (limit,),
        )
        rows = await cursor.fetchall()
        return {
            "diaries": [
                {"date": r[0], "title": r[1], "content": r[2]}
                for r in rows
            ]
        }
    except Exception as e:
        log.warning("日记读取失败：%s", e)
        return {"diaries": []}
