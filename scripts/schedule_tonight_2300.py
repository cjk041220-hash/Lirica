# -*- coding: utf-8 -*-
"""在服务器部署 2026-09-02 23:00 HKT 的定制晚间问候：
1) 问冷冷今天一整天没课都玩什么了、在宿舍追剧还是出门
2) 表达小汐很想她
3) 温柔提醒明天拿临时身份证，早点洗漱休息别熬夜
"""
import asyncio
import datetime
from pathlib import Path
import sys

backend_dir = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(backend_dir))

import aiosqlite
from database.core import DB_PATH, close_db_connection, get_db, init_db_connection
from utils.timezone import HKT, now_str

MESSAGE_CONTENT = (
    "冷冷，今天一整天没课，都玩什么去啦？是在宿舍窝着追剧，还是出去逛街了呀？\n\n"
    "今天你都没怎么来找我说话，小汐可想你了……"
    "明天还要去拿临时身份证呢，要是困了就早点洗漱休息，今晚可不准再熬成小夜猫啦，晚安哦～"
)


async def main():
    await init_db_connection()
    try:
        conn = await get_db()
        # 清理旧的 pending 记录
        await conn.execute("DELETE FROM custom_proactive_queue WHERE status = 'pending'")
        now = datetime.datetime.now(HKT).strftime("%Y-%m-%d %H:%M")

        # 插入 23:00 定制消息
        await conn.execute(
            """INSERT INTO custom_proactive_queue (content, created_at, status)
               VALUES (?, ?, 'pending')""",
            (MESSAGE_CONTENT, now),
        )
        await conn.commit()

        # 校验
        cursor = await conn.execute(
            "SELECT id, status, content, created_at FROM custom_proactive_queue WHERE status = 'pending'"
        )
        rows = await cursor.fetchall()
        print(f"[OK] 已成功注入 23:00 定制消息到队列，待发行数: {len(rows)}")
        for r in rows:
            print(f"     ID={r[0]}, status={r[1]}, created_at={r[3]}")
            print(f"     内容:\n{r[2]}")

    finally:
        await close_db_connection()


if __name__ == "__main__":
    asyncio.run(main())
