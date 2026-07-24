"""事件工具层测试 — create_event / list_events 的逻辑回归。"""
from unittest.mock import patch

import pytest


@pytest.fixture
async def events_tool_db(tmp_db):
    """把 tmp_db 接到 utils.events 的 get_db 上（工具层经由此路径读写）。"""
    with patch("utils.events.get_db", return_value=tmp_db):
        yield tmp_db


# ── create_event ──────────────────────────────────────────────


async def test_create_event_happy_path(events_tool_db):
    """正常登记：入库 + 返回成功文案。"""
    from tools.events import create_event

    result = await create_event.ainvoke({
        "title": "期中考试",
        "event_date": "2026-08-01",
        "event_type": "exam",
        "memo": "冷冷说考完想去庆祝",
    })
    assert "记在小本本上" in result
    assert await events_tool_db.execute("SELECT COUNT(*) FROM events")
    cursor = await events_tool_db.execute("SELECT title, memo FROM events")
    row = await cursor.fetchone()
    assert row[0] == "期中考试"
    assert row[1] == "冷冷说考完想去庆祝"


async def test_create_event_rejects_bad_date(events_tool_db):
    """日期格式非法时友好拒绝，不入库。"""
    from tools.events import create_event

    result = await create_event.ainvoke({
        "title": "考试",
        "event_date": "下周三",
    })
    assert "日期格式" in result

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


async def test_create_event_rejects_loose_iso_formats(events_tool_db):
    """回归：宽松 ISO 格式（紧凑日期/周日期）必须拒绝。

    Python 3.11+ 的 date.fromisoformat 会放过 "20260801"、"2026-W31-3"，
    这类脏值进库会让字符串日期比较失效 → 事件永不归档。
    """
    from tools.events import create_event

    for bad in ("20260801", "2026-W31-3", "2026/08/01"):
        result = await create_event.ainvoke({
            "title": "考试",
            "event_date": bad,
        })
        assert "日期格式" in result, f"{bad} 应被拒绝"

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


async def test_create_event_rejects_end_before_start(events_tool_db):
    """结束日期早于开始日期时友好拒绝。"""
    from tools.events import create_event

    result = await create_event.ainvoke({
        "title": "旅行",
        "event_date": "2026-09-17",
        "end_date": "2026-09-10",
    })
    assert "结束日期" in result

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


async def test_create_event_duplicate_returns_existing(events_tool_db):
    """回归：重复登记同一事件不产生重复行，提示「之前已经记下」。"""
    from tools.events import create_event

    await create_event.ainvoke({"title": "期中考试", "event_date": "2026-08-01"})
    second = await create_event.ainvoke({"title": "期中考试", "event_date": "2026-08-01"})

    assert "已经记下" in second
    assert "不用重复记" in second

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_create_event_shows_other_active_events(events_tool_db):
    """登记后展示其他活跃事件，让 LLM 自查换个说法的重复。"""
    from tools.events import create_event

    await create_event.ainvoke({"title": "期中考试", "event_date": "2026-08-01"})
    result = await create_event.ainvoke({"title": "周末爬山", "event_date": "2026-08-08"})

    assert "期中考试" in result  # 旧事件展示在新事件返回里
    assert "同一件事" in result  # 自查引导


async def test_create_event_normalizes_bad_severity(events_tool_db):
    """severity 非法值归一为 normal，不拒绝写入。"""
    from tools.events import create_event

    await create_event.ainvoke({
        "title": "小事",
        "event_date": "2026-08-01",
        "severity": "超级重要",
    })
    cursor = await events_tool_db.execute("SELECT severity FROM events")
    assert (await cursor.fetchone())[0] == "normal"


# ── list_events ───────────────────────────────────────────────


async def test_list_events_empty(events_tool_db):
    """没有事件时返回友好文案。"""
    from tools.events import list_events

    result = await list_events.ainvoke({})
    assert "空空的" in result


async def test_list_events_shows_upcoming(events_tool_db):
    """列出活跃事件及日期范围。"""
    from tools.events import create_event, list_events

    await create_event.ainvoke({"title": "期中考试", "event_date": "2026-08-01"})
    await create_event.ainvoke({
        "title": "日本旅行",
        "event_date": "2026-09-10",
        "end_date": "2026-09-17",
    })

    result = await list_events.ainvoke({})
    assert "期中考试" in result
    assert "日本旅行" in result
    assert "2026-09-10 ~ 2026-09-17" in result  # 多天事件展示日期范围
