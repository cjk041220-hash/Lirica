"""事件工具层测试 — create_event / list_events 的逻辑回归。

日期防老化：事件日期一律用 _d(N)（今天 + N 天的动态偏移），禁止写死
具体日期——写死的「未来日期」一过零点就变成过去日期，全套变红
（2026-08-02 体检实锤：8 个用例因写死 2026-08-01 集体阵亡）。
"""
import datetime
from unittest.mock import patch

import pytest

from utils.timezone import HKT


def _d(days: int) -> str:
    """今天 + N 天的日期串（永不老化的事件日期）。"""
    return (
        datetime.datetime.now(HKT) + datetime.timedelta(days=days)
    ).strftime("%Y-%m-%d")


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
        "event_date": _d(3),
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
        "event_date": _d(47),
        "end_date": _d(40),
    })
    assert "结束日期" in result

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


async def test_create_event_duplicate_returns_existing(events_tool_db):
    """回归：重复登记同一事件不产生重复行，提示「之前已经记下」。"""
    from tools.events import create_event

    await create_event.ainvoke({"title": "期中考试", "event_date": _d(3)})
    second = await create_event.ainvoke({"title": "期中考试", "event_date": _d(3)})

    assert "已经记下" in second
    assert "不用重复记" in second

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_create_event_shows_other_active_events(events_tool_db):
    """登记后展示其他活跃事件，让 LLM 自查换个说法的重复。"""
    from tools.events import create_event

    await create_event.ainvoke({"title": "期中考试", "event_date": _d(3)})
    result = await create_event.ainvoke({"title": "周末爬山", "event_date": _d(10)})

    assert "期中考试" in result  # 旧事件展示在新事件返回里
    assert "同一件事" in result  # 自查引导


async def test_create_event_normalizes_bad_severity(events_tool_db):
    """severity 非法值归一为 normal，不拒绝写入。"""
    from tools.events import create_event

    await create_event.ainvoke({
        "title": "小事",
        "event_date": _d(3),
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

    await create_event.ainvoke({"title": "期中考试", "event_date": _d(3)})
    await create_event.ainvoke({
        "title": "日本旅行",
        "event_date": _d(40),
        "end_date": _d(47),
    })

    result = await list_events.ainvoke({})
    assert "期中考试" in result
    assert "日本旅行" in result
    assert f"{_d(40)} ~ {_d(47)}" in result  # 多天事件展示日期范围


async def test_create_event_rejects_unknown_event_type(events_tool_db):
    """event_type 白名单之外的自由文本被拒（防止垃圾值渲染进系统提示词）。"""
    from tools.events import create_event

    result = await create_event.ainvoke({
        "title": "生日派对",
        "event_date": _d(30),
        "event_type": "生日！！",
    })
    assert "exam" in result  # 提示合法枚举

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0  # 未入库


async def test_create_event_rejects_past_date(events_tool_db):
    """过去日期被拒：事件是「未来要惦记的事」，过期事件会污染 list_events。"""
    from tools.events import create_event

    result = await create_event.ainvoke({
        "title": "去年就去过的事",
        "event_date": "2020-01-01",
    })
    assert "过去" in result

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


# ── update_event / delete_event（日历联动）───────────────────


async def test_create_event_respects_tombstone(events_tool_db):
    """联动剧本 A 的 LLM 半边：冷冷删过的事件，小汐不再偷偷记回来。"""
    from utils import events as ev
    from tools.events import create_event

    saved, _ = await ev.create_event("期中考试", "exam", _d(3))
    await ev.soft_delete_event(saved["id"])

    result = await create_event.ainvoke({"title": "期中考试", "event_date": _d(3)})
    assert "删掉过" in result                 # 归因文案，不是「没找到」也不是空转
    assert "日历" in result                   # 指明恢复路径

    cursor = await events_tool_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1  # 没有重建


async def test_update_event_happy(events_tool_db):
    from utils import events as ev
    from tools.events import update_event

    saved, _ = await ev.create_event("爬山", "trip", _d(10))
    result = await update_event.ainvoke({
        "event_id": saved["id"], "event_date": _d(17),
    })
    assert "改好啦" in result and _d(17) in result


async def test_update_event_not_found_and_readonly(events_tool_db):
    from tools.events import update_event

    assert "没找到" in await update_event.ainvoke({
        "event_id": "ffffffffffffffff", "title": "x",
    })

    await events_tool_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('b1', '冷冷的生日', 'birthday', '2026-06-03', "
        "'high', 'profile', 'active', '2026-07-25 10:00')"
    )
    await events_tool_db.commit()
    assert "改不了" in await update_event.ainvoke({"event_id": "b1", "title": "x"})


async def test_delete_event_happy(events_tool_db):
    from utils import events as ev
    from tools.events import delete_event, list_events

    saved, _ = await ev.create_event("不去了的约", "appointment", _d(22))
    result = await delete_event.ainvoke({"event_id": saved["id"]})
    assert "划掉" in result

    # 划掉后 list_events 不再报给 LLM
    listing = await list_events.ainvoke({})
    assert "不去了的约" not in listing


async def test_delete_event_not_found_and_readonly(events_tool_db):
    from tools.events import delete_event

    assert "没找到" in await delete_event.ainvoke({"event_id": "ffffffffffffffff"})

    await events_tool_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('a1', '相识周年的日子', 'anniversary', "
        "'2026-08-03', 'high', 'profile', 'active', '2026-07-25 10:00')"
    )
    await events_tool_db.commit()
    assert "划不掉" in await delete_event.ainvoke({"event_id": "a1"})
