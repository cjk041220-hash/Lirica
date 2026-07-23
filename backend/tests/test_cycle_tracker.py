"""生理期追踪器测试。"""
import datetime
from datetime import timezone, timedelta
from unittest.mock import patch

import pytest

import database.core as db_core
from utils.cycle_tracker import (
    add_record,
    get_records,
    get_cycle_stats,
    get_care_context,
    seed_initial_records,
)

_HKT = timezone(timedelta(hours=8))


# ── 记录管理 ──────────────────────────────────────────────────


async def test_add_record_inserts(tmp_db):
    """add_record 插入新记录。"""
    db_core._db = tmp_db
    ok = await add_record("2026-04-22")
    assert ok is True

    records = await get_records()
    assert len(records) == 1
    assert records[0]["start_date"] == "2026-04-22"


async def test_add_record_dedup(tmp_db):
    """同一日期不重复插入，且返回值如实反映是否插入（回归：此前恒返回 True）。"""
    db_core._db = tmp_db
    first = await add_record("2026-04-22")
    second = await add_record("2026-04-22")  # 重复
    assert first is True
    assert second is False
    records = await get_records()
    assert len(records) == 1


async def test_seed_initial_counts_only_real_inserts(tmp_db):
    """回归：seed 计数不再把重复日期虚报为成功插入。"""
    db_core._db = tmp_db
    inserted = await seed_initial_records(
        ["2026-04-22", "2026-05-20", "2026-04-22", "2026-05-20"]
    )
    assert inserted == 2
    assert len(await get_records()) == 2


async def test_seed_initial_records(tmp_db):
    """批量写入初始记录。"""
    db_core._db = tmp_db
    dates = ["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"]
    inserted = await seed_initial_records(dates)
    assert inserted == 4

    records = await get_records()
    assert len(records) == 4


# ── 周期计算 ──────────────────────────────────────────────────


async def test_cycle_stats_insufficient_data(tmp_db):
    """记录不足 2 条时返回 None。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    stats = await get_cycle_stats()
    assert stats is None


async def test_cycle_stats_calculates_average(tmp_db):
    """正确计算平均周期。"""
    db_core._db = tmp_db
    # 间隔: 28, 26, 33 → 平均 ≈ 29
    await seed_initial_records(["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"])
    stats = await get_cycle_stats()

    assert stats is not None
    assert stats["avg_cycle"] == 29  # (28+26+33)/3 ≈ 29
    assert stats["last_start"] == "2026-07-18"
    assert stats["records_count"] == 4


async def test_cycle_stats_predicts_next(tmp_db):
    """正确预测下次日期。"""
    db_core._db = tmp_db
    await seed_initial_records(["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"])
    stats = await get_cycle_stats()

    # 上次 7.18 + 平均 29 天 = 8.16
    assert stats["next_predicted"] == "2026-08-16"


# ── 关心窗口 ──────────────────────────────────────────────────


async def test_care_context_in_window(tmp_db):
    """在关心窗口内返回关心文本。"""
    db_core._db = tmp_db
    # 让"今天"落在预测日期附近
    await seed_initial_records(["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"])

    # Mock 今天为 8 月 15 日（预测 8.16 前 1 天，在窗口内）
    fake_today = datetime.datetime(2026, 8, 15, 12, 0, tzinfo=_HKT)
    with patch("utils.cycle_tracker.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_today
        mock_dt.datetime.strptime = datetime.datetime.strptime
        mock_dt.timedelta = datetime.timedelta
        mock_dt.timezone = datetime.timezone
        context = await get_care_context()

    assert context is not None
    assert "痛经" in context
    assert "关心" in context


async def test_care_context_outside_window(tmp_db):
    """不在关心窗口内时返回 None。"""
    db_core._db = tmp_db
    await seed_initial_records(["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"])

    # Mock 今天为 7 月 25 日（预测 8.16 还很远，不在窗口内）
    fake_today = datetime.datetime(2026, 7, 25, 12, 0, tzinfo=_HKT)
    with patch("utils.cycle_tracker.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_today
        mock_dt.datetime.strptime = datetime.datetime.strptime
        mock_dt.timedelta = datetime.timedelta
        mock_dt.timezone = datetime.timezone
        context = await get_care_context()

    assert context is None


async def test_care_context_on_predicted_day(tmp_db):
    """预测当天也触发关心。"""
    db_core._db = tmp_db
    await seed_initial_records(["2026-04-22", "2026-05-20", "2026-06-15", "2026-07-18"])

    # Mock 今天为 8 月 16 日（预测日期当天）
    fake_today = datetime.datetime(2026, 8, 16, 12, 0, tzinfo=_HKT)
    with patch("utils.cycle_tracker.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_today
        mock_dt.datetime.strptime = datetime.datetime.strptime
        mock_dt.timedelta = datetime.timedelta
        mock_dt.timezone = datetime.timezone
        context = await get_care_context()

    assert context is not None
    assert "今天可能就是" in context
