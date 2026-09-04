"""小汐 — 注入关怀状态（JK 心事事件 + 生命状态 + 核心记忆）。

只给小汐留「印象」：日历事件一句、生命状态、核心记忆。不产生主动消息推送，
不再反复烦着冷冷。定制主动消息已清空（CUSTOM_MESSAGES = []）。

使用方法：
    # 注入事件、生命状态、记忆（无推送）
    python scripts/inject_care_state.py
"""
import argparse
import asyncio
import datetime
from pathlib import Path
import sys
import uuid

# 确保 backend 位于模块搜索路径
backend_dir = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(backend_dir))

import aiosqlite
from database.core import DB_PATH, get_db, init_db_connection, close_db_connection
from utils.timezone import HKT, now_str

EVENT_TITLE = "JK有些心事"
EVENT_MEMO = "JK和冷冷似乎出了什么事...JK看起来很低落"

MEMORY_CONTENT = (
    "JK最近情绪非常低落难受，好像和冷冷发生了很不愉快的事。"
    "小汐在技术维护时注意到了异样，很担心JK，也挂念冷冷，希望他们能好好聊聊。"
)

# 主动消息已清空：小汐只留「印象」（事件 + 核心记忆 + 生命状态），不再主动向冷冷推消息。
# 以后想恢复关怀推送，再往这列表加文案；--send-first-now 会立刻发第一条。
CUSTOM_MESSAGES: list[str] = []


async def inject_state(db_path: Path | None = None, send_first: bool = False):
    target_path = db_path or DB_PATH
    print(f"[*] 连接数据库: {target_path}")

    now_dt = datetime.datetime.now(HKT)
    now = now_dt.strftime("%Y-%m-%d %H:%M")
    today_str = now_dt.strftime("%Y-%m-%d")
    end_str = (now_dt + datetime.timedelta(days=3)).strftime("%Y-%m-%d")

    async with aiosqlite.connect(str(target_path)) as conn:
        await conn.execute("PRAGMA foreign_keys=OFF")
        await conn.execute("PRAGMA busy_timeout=5000")

        # 1. 确保表结构
        from database.migrations import (
            CUSTOM_PROACTIVE_QUEUE_SQL,
            EVENTS_SCHEMA_SQL,
            LIFE_STATE_SCHEMA_SQL,
        )
        await conn.executescript(CUSTOM_PROACTIVE_QUEUE_SQL)
        await conn.executescript(EVENTS_SCHEMA_SQL)
        await conn.executescript(LIFE_STATE_SCHEMA_SQL)
        await conn.commit()

        # 2. 更新 life_state
        print("[*] 更新生命状态: mood=有些心事...")
        cursor = await conn.execute("SELECT id FROM life_state WHERE id = 1")
        if await cursor.fetchone():
            await conn.execute(
                """UPDATE life_state
                   SET mood = '有些心事',
                       daily_event = '看JK这几天状态特别难受，很担心他……不知道他和冷冷怎么了',
                       mood_changed_at = ?,
                       updated_at = ?
                   WHERE id = 1""",
                (now, now),
            )
        else:
            await conn.execute(
                """INSERT INTO life_state (id, mood, daily_event, mood_changed_at, updated_at)
                   VALUES (1, '有些心事', '看JK这几天状态特别难受，很担心他……不知道他和冷冷怎么了', ?, ?)""",
                (now, now),
            )
        await conn.commit()

        # 3. 写入活跃事件
        print(f"[*] 写入活跃事件: 「{EVENT_TITLE}」 (有效期 {today_str} ~ {end_str})...")
        event_id = "evt_jk_care_" + now_dt.strftime("%Y%m%d")
        dedupe_key = f"system:care:{today_str}"
        await conn.execute(
            """INSERT INTO events
               (id, title, event_type, event_date, end_date, severity, source, memo, status, created_at, dedupe_key)
               VALUES (?, ?, 'general', ?, ?, 'high', 'system', ?, 'active', ?, ?)
               ON CONFLICT(dedupe_key) WHERE dedupe_key IS NOT NULL AND status IN ('active', 'deleted')
               DO UPDATE SET
                   title = excluded.title,
                   event_date = excluded.event_date,
                   end_date = excluded.end_date,
                   memo = excluded.memo,
                   status = 'active',
                   severity = excluded.severity""",
            (event_id, EVENT_TITLE, today_str, end_str, EVENT_MEMO, now, dedupe_key),
        )
        await conn.commit()

        # 4. 写入核心记忆
        print("[*] 写入核心记忆...")
        mem_id = "mem_jk_care_" + now_dt.strftime("%Y%m%d")
        await conn.execute(
            """INSERT INTO memories
               (id, content, category, importance, status, source, created_at, updated_at)
               VALUES (?, ?, '关怀', 8, 'active', 'system', ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                   content = excluded.content,
                   importance = 8,
                   status = 'active',
                   updated_at = excluded.updated_at""",
            (mem_id, MEMORY_CONTENT, now, now),
        )
        await conn.commit()

        # 5. 写入定制主动消息队列
        print("[*] 载入定制主动消息待发队列...")
        # 先清除先前的 pending 队列
        await conn.execute("UPDATE custom_proactive_queue SET status = 'replaced' WHERE status = 'pending'")
        if CUSTOM_MESSAGES:
            if send_first:
                # 第一条立即发，队列只存其余
                print(f"  -> 第 1 条立即发送: 「{CUSTOM_MESSAGES[0]}」")
                for msg in CUSTOM_MESSAGES[1:]:
                    await conn.execute(
                        "INSERT INTO custom_proactive_queue (content, created_at, status) VALUES (?, ?, 'pending')",
                        (msg, now),
                    )
                    print(f"  -> 队列中排队: 「{msg}」")
            else:
                for msg in CUSTOM_MESSAGES:
                    await conn.execute(
                        "INSERT INTO custom_proactive_queue (content, created_at, status) VALUES (?, ?, 'pending')",
                        (msg, now),
                    )
                    print(f"  -> 队列中排队: 「{msg}」")
            await conn.commit()
        else:
            print("  -> 定制消息为空：只刷新事件/记忆/生命状态，不产生推送。")
        await conn.commit()

    print("[✓] 状态、事件、记忆与待发队列注入完成！")

    # 6. 如果需要立即发送第 1 条
    if send_first and CUSTOM_MESSAGES:
        print("[*] 正在触发第 1 条即时主动消息推送...")
        await init_db_connection()
        try:
            from config.settings import get_settings
            from era import append_proactive_turn, resolve_proactive_target

            conn = await get_db()
            kind, target_sid, opener = await resolve_proactive_target(
                conn, now_dt, get_settings().presence_timeout_minutes
            )
            msg_content = CUSTOM_MESSAGES[0]
            await append_proactive_turn(None, target_sid, msg_content, kind=kind, label="定制主动消息")
            print(f"[✓] 已成功发送主动消息: 「{msg_content}」 (sid={target_sid}, kind={kind})")
        finally:
            await close_db_connection()


def main():
    parser = argparse.ArgumentParser(description="注入小汐的关怀状态与定制主动消息队列")
    parser.add_argument("--db-path", type=Path, default=None, help="目标数据库路径 (默认 backend/data/userdata.db)")
    parser.add_argument("--send-first-now", action="store_true", help="立即发送第 1 条主动消息并推送到设备")
    args = parser.parse_args()

    asyncio.run(inject_state(db_path=args.db_path, send_first=args.send_first_now))


if __name__ == "__main__":
    main()
