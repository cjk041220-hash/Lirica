"""小汐的日记系统 — 基于全局 aiosqlite 连接读写 userdata.db 的 diaries 表。"""
import datetime
from datetime import timezone, timedelta

import aiosqlite
from langchain_core.tools import tool

# 冷冷在香港（UTC+8）
_HKT = timezone(timedelta(hours=8))

# ── 全局连接注入（由 server.py lifespan 调用 set_db_conn 设置） ──

_db_conn: aiosqlite.Connection | None = None


def set_db_conn(conn: aiosqlite.Connection) -> None:
    """注入全局数据库连接（在 lifespan 中调用）。"""
    global _db_conn
    _db_conn = conn


# ── 内部工具 ──────────────────────────────────────────────────

def _rows_to_dicts(cursor, rows: list) -> list[dict]:
    """用 cursor.description 手动构造 dict 列表，避免 row_factory 共享状态问题。"""
    cols = [desc[0] for desc in (cursor.description or [])]
    return [dict(zip(cols, row)) for row in rows]


# ── 工具函数 ──────────────────────────────────────────────────

@tool
async def write_diary(content: str) -> str:
    """让小汐写一篇日记。冷冷说「今天……」「帮小汐记一篇日记」时调用。

    Args:
        content: 日记内容。小汐会用温暖的方式记录今天发生了什么。
    """
    if _db_conn is None:
        return "小汐的日记本还没有打开……"
    try:
        today_iso = datetime.datetime.now(_HKT).date().isoformat()
        today_title = datetime.datetime.now(_HKT).strftime("%m月%d日")

        # 同日去重：已有今日日记则更新内容
        cursor = await _db_conn.execute(
            "SELECT date FROM diaries WHERE date = ?", (today_iso,)
        )
        existing = await cursor.fetchone()

        if existing:
            await _db_conn.execute(
                "UPDATE diaries SET title = ?, content = ? WHERE date = ?",
                (today_title, content, today_iso),
            )
            msg = "📖 小汐更新了今天的日记！"
        else:
            await _db_conn.execute(
                "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
                (today_iso, today_title, content),
            )
            msg = "📖 小汐写好今天的日记啦！映海里又多了一天的回忆。"

        await _db_conn.commit()
        return msg
    except Exception as e:
        return f"小汐写日记的时候弄翻了墨水瓶……（{str(e)[:100]}）"


@tool
async def read_diary(date_str: str = "") -> str:
    """读取小汐写的日记。冷冷想看日记时调用。

    Args:
        date_str: 日期（YYYY-MM-DD 格式），不传则返回最近的几篇
    """
    if _db_conn is None:
        return "小汐的日记本还没有打开……"
    try:
        if date_str:
            cursor = await _db_conn.execute(
                "SELECT * FROM diaries WHERE date = ?", (date_str,)
            )
            row = await cursor.fetchone()
            if not row:
                return f"小汐翻开{date_str}……这一页是空白的呢。"
            d = _rows_to_dicts(cursor, [row])[0]
            return f"📖 **{d.get('title', '')}**\n\n{d.get('content', '')}"
        else:
            cursor = await _db_conn.execute(
                "SELECT * FROM diaries ORDER BY date DESC LIMIT 5"
            )
            rows = await cursor.fetchall()
            if not rows:
                return "小汐还没有写过日记呢……"

            result = "📖 小汐最近写的日记：\n\n"
            for d in _rows_to_dicts(cursor, rows):
                result += f"**{d.get('title', '')}**\n"
                result += f"{d.get('content', '')[:200]}……\n\n"
            return result
    except Exception as e:
        return f"小汐翻日记的时候被纸划了一下……（{str(e)[:100]}）"
