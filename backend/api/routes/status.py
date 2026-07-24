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
    """小汐的今日状态：心情 + 今天经历的事（碎碎念）。"""
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT mood, daily_event, updated_at FROM life_state WHERE id = 1"
        )
        row = await cursor.fetchone()
        if not row:
            return {"mood": None, "daily_event": None, "updated_at": None}
        return {
            "mood": row[0],
            "daily_event": row[1],
            "updated_at": row[2],
        }
    except Exception as e:
        log.warning("状态读取失败：%s", e)
        return {"mood": None, "daily_event": None, "updated_at": None}


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
