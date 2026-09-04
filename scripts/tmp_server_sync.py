# -*- coding: utf-8 -*-
"""服务器 userdata.db 同步（线上 ABC 等价清理）：
1) JK 事件 memo 压成一句（与本地一致）
2) life_state.daily_event 换中性句（不再拿 JK 当心跳种子）
3) 清空 custom_proactive_queue 残留（4 条 skipped）
注：线上 memories 不需要动——mem_jk_care 已被真对话更新为「冷冷说没事」，
    旧长文已是 superseded；mood_current 与 life_state 一致（期待 19:52）。
"""
import sqlite3

DB = "/app/backend/data/userdata.db"
conn = sqlite3.connect(DB)
conn.row_factory = sqlite3.Row
cur = conn.cursor()

cur.execute("SELECT memo FROM events WHERE id='evt_jk_care_20260831'")
row = cur.fetchone()
print("1) 原 JK memo 长度:", len(row["memo"]) if row else "无此行")
cur.execute(
    "UPDATE events SET memo=? WHERE id='evt_jk_care_20260831'",
    ("JK和冷冷似乎出了什么事...JK看起来很低落",),
)
print("   updated events:", cur.rowcount)

cur.execute("SELECT daily_event FROM life_state WHERE id=1")
print("2) 原 daily_event:", cur.fetchone()["daily_event"])
cur.execute(
    "UPDATE life_state SET daily_event=?, updated_at=? WHERE id=1",
    ("窝在窗边听了一下午雨，天很静，人也跟着闲下来了", "2026-09-01 22:00"),
)
print("   updated life_state:", cur.rowcount)

cur.execute("SELECT COUNT(*) n FROM custom_proactive_queue")
print("3) queue 原行数:", cur.fetchone()["n"])
cur.execute("DELETE FROM custom_proactive_queue")
print("   deleted:", cur.rowcount)

conn.commit()

# 校验
cur.execute("SELECT memo FROM events WHERE id='evt_jk_care_20260831'")
print("校验 memo:", cur.fetchone()["memo"])
cur.execute("SELECT mood, daily_event FROM life_state WHERE id=1")
r = cur.fetchone()
print("校验 life_state:", r["mood"], "|", r["daily_event"])
cur.execute("SELECT COUNT(*) n FROM custom_proactive_queue")
print("校验 queue:", cur.fetchone()["n"])
conn.close()
print("SYNC DONE")