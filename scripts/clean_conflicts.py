# -*- coding: utf-8 -*-
"""清理服务器 userdata.db 中的冗余/冲突记忆与过时事件：
1) 删掉与档案重复的记忆（生日星座、LoveLive/凛凛蝶）
2) 将过时的 JK 关怀记忆标记为 superseded
3) 将 JK 心事日历事件标记为 archived（已翻篇）
"""
import sqlite3
import sys

DB_PATH = "/app/backend/data/userdata.db"

def main():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    # 1. 删掉档案中已有的重复记忆
    cur.execute(
        "UPDATE memories SET status='deleted' WHERE id IN ('8b191b5a0a6240a6', '257a05ac6c544d2e')"
    )
    print("1) 删除档案重复记忆行数:", cur.rowcount)

    # 2. 将过时的 mem_jk_care_20260831 改为 superseded
    cur.execute(
        "UPDATE memories SET status='superseded' WHERE id='mem_jk_care_20260831'"
    )
    print("2) 过时关怀记忆设为 superseded:", cur.rowcount)

    # 3. 将 evt_jk_care_20260831 日历事件归档（archived）
    cur.execute(
        "UPDATE events SET status='archived' WHERE id='evt_jk_care_20260831'"
    )
    print("3) JK心事事件归档:", cur.rowcount)

    conn.commit()

    # 校验
    print("\n=== 校验当前活跃记忆 (active / core) ===")
    for r in cur.execute(
        "SELECT id, category, content FROM memories WHERE status IN ('active', 'core')"
    ).fetchall():
        print(f"  [{r[0]}] ({r[1]}): {r[2]}")

    print("\n=== 校验当前活跃事件 (active) ===")
    for r in cur.execute(
        "SELECT id, title, memo FROM events WHERE status='active'"
    ).fetchall():
        print(f"  [{r[0]}] {r[1]}: {r[2]}")

    conn.close()
    print("\n[OK] CLEANUP COMPLETED SUCCESSFULLY")

if __name__ == "__main__":
    main()
