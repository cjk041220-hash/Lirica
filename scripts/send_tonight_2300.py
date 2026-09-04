# -*- coding: utf-8 -*-
"""2026-09-02 23:00 HKT 定制晚间问候执行脚本：
1. 检查 custom_proactive_queue 中是否有待发消息 (status='pending')
2. 若有待发消息，出队并通过 append_proactive_turn 发送给冷冷（三写一推：写档案 + 状态水合 + 存 proactive 行 + Web Push 推送）
3. 标记 custom_proactive_queue 中对应记录状态为 'sent'
4. 保证幂等防重复：若最近 3 分钟内已有主动消息发送，则跳过
"""
import asyncio
import datetime
import sys

sys.path.insert(0, "/app/backend")

from config.settings import get_settings
from database.core import close_db_connection, get_db, init_db_connection
from era import append_proactive_turn, resolve_proactive_target
from utils.timezone import HKT


async def main():
    await init_db_connection()
    try:
        conn = await get_db()
        now_dt = datetime.datetime.now(HKT)
        now_str = now_dt.strftime("%Y-%m-%d %H:%M")

        # 1. 检查定制队列
        cursor = await conn.execute(
            "SELECT id, content FROM custom_proactive_queue WHERE status = 'pending' ORDER BY id ASC LIMIT 1"
        )
        row = await cursor.fetchone()
        if not row:
            print("[INFO] 定制队列中无待发消息，可能已被调度器发送或已完成。")
            return

        qid, content = row
        print(f"[*] 找到待发定制消息 [ID={qid}]: {content[:40]}...")

        # 2. 检查幂等性：避免 3 分钟内重复发送
        cursor = await conn.execute(
            "SELECT created_at FROM proactive_messages ORDER BY created_at DESC LIMIT 1"
        )
        last_proactive = await cursor.fetchone()
        if last_proactive and last_proactive[0]:
            try:
                last_time = datetime.datetime.strptime(
                    last_proactive[0], "%Y-%m-%d %H:%M"
                ).replace(tzinfo=HKT)
                if (now_dt - last_time).total_seconds() < 180:  # 3分钟内
                    print(
                        f"[WARN] 3分钟内已有主动消息发送 ({last_proactive[0]})，标记队列为 sent 并跳过防重复。"
                    )
                    await conn.execute(
                        "UPDATE custom_proactive_queue SET status = 'sent' WHERE id = ?",
                        (qid,),
                    )
                    await conn.commit()
                    return
            except Exception as e:
                print(f"[WARN] 解析上次时间失败: {e}")

        # 3. 路由目标 (在线活跃会话 or 离线 era)
        kind, target_sid, opener = await resolve_proactive_target(
            conn, now_dt, get_settings().presence_timeout_minutes
        )
        print(f"[*] 目标路由: kind={kind}, target_sid={target_sid}")

        # 4. 三写一推
        await append_proactive_turn(
            None, target_sid, content, kind=kind, label="定制晚间问候"
        )

        # 5. 更新队列状态
        await conn.execute(
            "UPDATE custom_proactive_queue SET status = 'sent' WHERE id = ?",
            (qid,),
        )
        await conn.commit()
        print(f"[SUCCESS] 消息已成功发送至冷冷！(sid={target_sid})")

    finally:
        await close_db_connection()


if __name__ == "__main__":
    asyncio.run(main())
