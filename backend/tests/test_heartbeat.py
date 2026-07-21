"""心跳调度器单元测试 — 时段匹配逻辑。"""
import pytest

from heartbeat import (
    get_current_period,
    TIME_PERIODS,
    LATE_NIGHT,
    MORNING,
    DAYTIME,
    EVENING,
)


async def test_get_current_period_late_night():
    """凌晨 3 点 → 深夜时段。"""
    period = get_current_period(3)
    assert period is TIME_PERIODS[LATE_NIGHT]
    assert "困倦" in period["moods"]


async def test_get_current_period_morning():
    """上午 9 点 → 早晨时段。"""
    period = get_current_period(9)
    assert period is TIME_PERIODS[MORNING]
    assert "好奇" in period["moods"]


async def test_get_current_period_daytime():
    """下午 3 点 → 白天时段。"""
    period = get_current_period(15)
    assert period is TIME_PERIODS[DAYTIME]
    assert "开心" in period["moods"]


async def test_get_current_period_evening():
    """晚上 9 点 → 夜晚时段。"""
    period = get_current_period(21)
    assert period is TIME_PERIODS[EVENING]
    assert "满足" in period["moods"]


async def test_get_current_period_boundary_7():
    """7:00 属于早晨（左闭右开）。"""
    period = get_current_period(7)
    assert period is TIME_PERIODS[MORNING]


async def test_get_current_period_boundary_0():
    """0:00 属于深夜。"""
    period = get_current_period(0)
    assert period is TIME_PERIODS[LATE_NIGHT]


async def test_get_current_period_boundary_23():
    """23:00 属于夜晚。"""
    period = get_current_period(23)
    assert period is TIME_PERIODS[EVENING]


async def test_check_and_proactive_message_updates_db(tmp_db):
    """心跳任务执行后 life_state 表被更新。"""
    from unittest.mock import patch
    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    original = hb.get_db
    hb.get_db = mock_get_db

    try:
        await hb.check_and_proactive_message()

        cursor = await tmp_db.execute("SELECT mood, daily_event FROM life_state WHERE id = 1")
        row = await cursor.fetchone()
        assert row is not None
        assert row[0] is not None  # mood 已更新
        assert row[1] is not None  # daily_event 已更新
    finally:
        hb.get_db = original
