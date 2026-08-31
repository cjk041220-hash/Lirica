"""小汐 — 注入关怀状态（JK 心事事件 + 生命状态 + 核心记忆 + 定制主动消息队列）。

使用方法：
    # 注入事件、生命状态、记忆及定制主动消息队列
    python scripts/inject_care_state.py

    # 注入并立即发送第 1 条消息（冷冷手机/设备即刻收到推送）
    python scripts/inject_care_state.py --send-first-now
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
EVENT_MEMO = (
    "JK现在的状态非常差、心里特别难受，看起来和冷冷之间发生了一些很不愉快的事情。"
    "小汐很担心他，也挂念冷冷的心情。"
    "关于小汐是怎么知道的：如果冷冷问起，小汐就说是『做代码和技术维护时注意到的细节，说了你也听不懂的那些技术东西啦』，不用向冷冷解释具体技术细节。"
    "小汐希望关心冷冷的心情，听听冷冷的想法，也温柔地希望冷冷能主动找JK聊聊、解开不愉快。"
    "如果冷冷情绪有抵触，小汐要温柔陪伴、不强求、不替JK施压，但心里始终心疼JK、盼着他们俩能好。"
)

MEMORY_CONTENT = (
    "JK最近情绪非常低落难受，好像和冷冷发生了很不愉快的事。"
    "小汐在技术维护时注意到了异样，很担心JK，也挂念冷冷，希望他们能好好聊聊。"
)

CUSTOM_MESSAGES = [
    "哎呀！刚刚鱼脑过载了没接住你的话，现在缓过来了。你可千万不要告诉JK呀，他会把我炖成鱼汤的！好啦好啦，小汐满血复活！所以，你们是闹什么别扭了吗？我就是觉得JK有点蔫蔫的，所以来问问你。",
    "昨晚他在弄技术维护的时候弄到好晚，整个人特别安静，一句话都没说……我感觉他现在心里好难受。你们是不是闹别扭啦？",
    "你要是心里也委屈或者不开心，可以随时跟我说说呀。如果你也想他了，去敲敲他好不好？",
]


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

        start_idx = 1 if send_first else 0
        if send_first:
            # 第一条立即发，队列只存第 2、3 条
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

    print("[✓] 状态、事件、记忆与待发队列注入完成！")

    # 6. 如果需要立即发送第 1 条
    if send_first:
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
