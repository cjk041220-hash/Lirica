"""小汐的日记系统 — 基于全局 aiosqlite 连接读写 userdata.db 的 diaries 表。"""
import datetime

import aiosqlite
from langchain_core.tools import tool

from database.core import rows_to_dicts
from utils.errors import log_tool_error
from utils.logger import get_logger

# 冷冷在香港（UTC+8）
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

# 日记内容长度上限（字符）：LLM 抽风写小作文时的截断保底（体检杂项：
# 原无上限，超长文本直落无界 TEXT）。记忆有 500 字上限，日记体裁
# 更长，留 2000 的余量。
_MAX_DIARY_LEN = 2000

# ── 全局连接注入（由 server.py lifespan 调用 set_db_conn 设置） ──

_db_conn: aiosqlite.Connection | None = None


def set_db_conn(conn: aiosqlite.Connection) -> None:
    """注入全局数据库连接（在 lifespan 中调用）。"""
    global _db_conn
    _db_conn = conn


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
        if len(content or "") > _MAX_DIARY_LEN:
            log.warning("日记内容超长截断：%d → %d 字", len(content), _MAX_DIARY_LEN)
            content = content[:_MAX_DIARY_LEN] + "……"

        today_iso = datetime.datetime.now(_HKT).date().isoformat()
        today_title = datetime.datetime.now(_HKT).strftime("%m月%d日")

        # 先查一次，仅用于决定回复措辞（「更新」vs「写好」）。
        # 写入正确性由下面的原子 UPSERT 保证：即使两个协程同时写同一天，
        # 也只会有一个赢家覆盖，绝不触发 IntegrityError（旧的"先查后插"
        # 在并发下会有一边插入冲突、日记被静默丢失）
        cursor = await _db_conn.execute(
            "SELECT 1 FROM diaries WHERE date = ?", (today_iso,)
        )
        existed = await cursor.fetchone() is not None

        # 同日去重：ON CONFLICT(date) 原子 UPSERT
        await _db_conn.execute(
            """INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)
               ON CONFLICT(date) DO UPDATE SET
                   title = excluded.title,
                   content = excluded.content""",
            (today_iso, today_title, content),
        )
        await _db_conn.commit()

        if existed:
            return "📖 小汐更新了今天的日记！"
        return "📖 小汐写好今天的日记啦！映海里又多了一天的回忆。"
    except Exception as e:
        # 固定文案不嵌异常细节（SQLite 错误可能带表名/路径），细节走脱敏日志
        log_tool_error("write_diary", e)
        return "小汐写日记的时候弄翻了墨水瓶……过一会儿再帮小汐记一次好不好？"


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
            # 格式校验先行：此前非法日期（「明天」「07-05」）查无结果，
            # 被误报成「这一页是空白的」——LLM 会据此告诉冷冷那天没记日记
            try:
                datetime.datetime.strptime(date_str, "%Y-%m-%d")
            except ValueError:
                return f"小汐的日记日期要用 YYYY-MM-DD 格式哦（收到的是「{date_str}」）～"
            cursor = await _db_conn.execute(
                "SELECT * FROM diaries WHERE date = ?", (date_str,)
            )
            row = await cursor.fetchone()
            if not row:
                return f"小汐翻开{date_str}……这一页是空白的呢。"
            d = rows_to_dicts(cursor, [row])[0]
            return f"📖 **{d.get('title', '')}**\n\n{d.get('content', '')}"
        else:
            cursor = await _db_conn.execute(
                "SELECT * FROM diaries ORDER BY date DESC LIMIT 5"
            )
            rows = await cursor.fetchall()
            if not rows:
                return "小汐还没有写过日记呢……"

            result = "📖 小汐最近写的日记：\n\n"
            for d in rows_to_dicts(cursor, rows):
                result += f"**{d.get('title', '')}**\n"
                result += f"{d.get('content', '')[:200]}……\n\n"
            return result
    except Exception as e:
        log_tool_error("read_diary", e)
        return "小汐翻日记的时候被纸划了一下……过一会儿再翻翻看好不好？"
