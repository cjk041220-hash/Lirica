"""日记工具单元测试 — 写入、去重、读取。"""
import datetime
from datetime import timezone, timedelta

import pytest

from tools.diary import set_db_conn

from utils.timezone import HKT as _HKT


async def test_write_diary_inserts(tmp_db):
    """写入日记后可以读到。"""
    import tools.diary as diary_module
    set_db_conn(tmp_db)

    from tools.diary import write_diary, read_diary

    today_title = datetime.datetime.now(_HKT).strftime("%m月%d日")
    result = await write_diary.ainvoke({"content": "今天和冷冷聊了很久"})
    assert "日记" in result

    # 验证数据库中有记录
    today_iso = datetime.datetime.now(_HKT).date().isoformat()
    cursor = await tmp_db.execute("SELECT * FROM diaries WHERE date = ?", (today_iso,))
    row = await cursor.fetchone()
    assert row is not None
    assert "和冷冷聊了很久" in row[2]

    # 清理
    diary_module._db_conn = None


async def test_write_diary_same_day_updates(tmp_db):
    """同一天写两次日记只保留最新内容（UPDATE 去重）。"""
    import tools.diary as diary_module
    set_db_conn(tmp_db)

    from tools.diary import write_diary

    await write_diary.ainvoke({"content": "第一篇内容"})
    await write_diary.ainvoke({"content": "第二篇内容"})

    today_iso = datetime.datetime.now(_HKT).date().isoformat()
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM diaries WHERE date = ?", (today_iso,))
    count = (await cursor.fetchone())[0]
    assert count == 1  # 只有一条记录

    # 内容是第二次写入的
    cursor = await tmp_db.execute("SELECT content FROM diaries WHERE date = ?", (today_iso,))
    row = await cursor.fetchone()
    assert "第二篇内容" in row[0]

    diary_module._db_conn = None


async def test_read_diary_by_date(tmp_db):
    """按日期读取日记。"""
    import tools.diary as diary_module
    set_db_conn(tmp_db)

    today_iso = datetime.datetime.now(_HKT).date().isoformat()
    today_title = datetime.datetime.now(_HKT).strftime("%m月%d日")

    await tmp_db.execute(
        "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
        (today_iso, today_title, "测试日记内容"),
    )
    await tmp_db.commit()

    from tools.diary import read_diary

    result = await read_diary.ainvoke({"date_str": today_iso})
    assert "测试日记内容" in result

    diary_module._db_conn = None


async def test_read_diary_nonexistent_date(tmp_db):
    """读取不存在的日期返回空白提示。"""
    import tools.diary as diary_module
    set_db_conn(tmp_db)

    from tools.diary import read_diary

    result = await read_diary.ainvoke({"date_str": "2000-01-01"})
    assert "空白" in result

    diary_module._db_conn = None


async def test_read_diary_recent(tmp_db):
    """不传日期时返回最近几篇日记。"""
    import tools.diary as diary_module
    set_db_conn(tmp_db)

    # 插入多篇日记
    for i in range(3):
        date_str = f"2026-07-{18 + i:02d}"
        await tmp_db.execute(
            "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
            (date_str, f"07月{18+i:02d}日", f"日记内容{i}"),
        )
    await tmp_db.commit()

    from tools.diary import read_diary

    result = await read_diary.ainvoke({"date_str": ""})
    assert "日记" in result

    diary_module._db_conn = None


async def test_write_diary_concurrent_same_day_no_integrity_error(tmp_db):
    """回归：并发写同一天日记不触发 IntegrityError，最终只有一行。

    旧版「先查后插」在并发下有一边会插入冲突，日记被静默丢失。
    """
    import asyncio
    import tools.diary as diary_module

    set_db_conn(tmp_db)
    from tools.diary import write_diary

    results = await asyncio.gather(
        *(write_diary.ainvoke({"content": f"并发内容{i}"}) for i in range(3))
    )
    assert all("日记" in r for r in results)

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM diaries")
    assert (await cursor.fetchone())[0] == 1

    diary_module._db_conn = None


async def test_read_diary_rejects_malformed_date(tmp_db):
    """非法日期格式返回格式提示，而不是误报「那天没写日记」。"""
    import tools.diary as diary_module

    set_db_conn(tmp_db)
    from tools.diary import read_diary

    for bad in ("明天", "2026/07/05", "07-05", "2026-02-30"):
        result = await read_diary.ainvoke({"date_str": bad})
        assert "YYYY-MM-DD" in result, f"{bad} 应触发格式提示"
        assert "空白" not in result, f"{bad} 不得被误报为没写日记"

    diary_module._db_conn = None
