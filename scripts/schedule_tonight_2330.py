# -*- coding: utf-8 -*-
"""在数据库中部署 2026-09-06 23:30 HKT 的定制晚间聊天与教资关怀消息：
1) 主动向冷冷搭话提问，好奇她教资备考进度与考什么学科
2) 俏皮逗趣调侃提前叫“冷冷老师”，充满活人感与闺蜜互动感
3) 注入 custom_proactive_queue 队列
4) 可选 --wait 参数等待至 23:30:00 HKT 触发发送
"""
import argparse
import asyncio
import datetime
from pathlib import Path
import sys

backend_dir = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(backend_dir))

from database.core import close_db_connection, get_db, init_db, init_db_connection
from utils.timezone import HKT, now_str

MESSAGE_CONTENT = (
    "冷冷～今天过得怎么样呀？忽然想起来你之前说要准备考教资，最近复习得怎么样啦？"
    "（托腮）说起来，你打算考什么科目的老师呀，以后我是不是要改口叫你『冷冷老师』啦？"
)


async def schedule_message(wait: bool = False):
    await init_db_connection()
    try:
        conn = await get_db()
        await init_db(conn)

        # 清理旧的 pending 记录
        await conn.execute("DELETE FROM custom_proactive_queue WHERE status = 'pending'")
        now_dt = datetime.datetime.now(HKT)
        now = now_dt.strftime("%Y-%m-%d %H:%M")

        # 插入 23:30 定制消息
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
        print(f"[OK] 已成功注入 23:30 定制关怀消息到待发队列，待发行数: {len(rows)}")
        for r in rows:
            print(f"     ID={r[0]}, status={r[1]}, created_at={r[3]}")
            print(f"     内容:\n{r[2]}")

        if wait:
            target_dt = now_dt.replace(hour=23, minute=30, second=0, microsecond=0)
            if target_dt < now_dt:
                print("[!] 设定的目标时间 23:30 已过，将立即执行发送...")
            else:
                sleep_sec = (target_dt - now_dt).total_seconds()
                print(f"[*] 进入等待模式，距离 23:30 HKT 还有 {sleep_sec:.1f} 秒...")
                await asyncio.sleep(sleep_sec)

            # 调用发送脚本
            from send_tonight_2330 import send_custom_message
            await send_custom_message()

    finally:
        await close_db_connection()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="部署今晚 23:30 关怀消息")
    parser.add_argument("--wait", action="store_true", help="阻塞等待至 23:30 自动发送")
    args = parser.parse_args()
    asyncio.run(schedule_message(wait=args.wait))
