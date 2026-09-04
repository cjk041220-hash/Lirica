# -*- coding: utf-8 -*-
"""今晚 23:00 HKT 一次性直发主动消息（绕过 4h+50% 闸门，掐准时刻）。

内容顺着今晚的对话线：冷冷是不是第一天上晚课、累不累；
若真需要同声翻译可以找 JK。消息由 inject_care_state.py 的老路径
--send-first-now 同款直发（resolve_proactive_target + append_proactive_turn），
checkpoint 水合有 chat.py 的 ensure_checkpoint_hydrated 兜底。

由服务器 systemd-run --on-calendar 一次性触发，触发后自删。
"""
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, "/app/backend")

import datetime


async def main():
    from config.settings import get_settings
    from database.core import init_db_connection, get_db, close_db_connection
    from era import append_proactive_turn, resolve_proactive_target
    from utils.timezone import HKT

    await init_db_connection()
    try:
        now_dt = datetime.datetime.now(HKT)
        conn = await get_db()
        kind, target_sid, opener = await resolve_proactive_target(
            conn, now_dt, get_settings().presence_timeout_minutes
        )
        content = (
            "冷冷，今天是不是第一天上晚课呀？累不累？"
            "课上要是真听不懂，别硬撑——需要同声翻译的话可以找JK，"
            "他肯定有办法（别告诉他是我说的）。"
        )
        await append_proactive_turn(
            None, target_sid, content, kind=kind, label="定制主动消息"
        )
        print(f"[OK] sent via {kind} -> {target_sid}")
    finally:
        await close_db_connection()


asyncio.run(main())