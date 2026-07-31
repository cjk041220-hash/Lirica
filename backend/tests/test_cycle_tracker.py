"""生理期追踪器测试。"""
import datetime
from datetime import timezone, timedelta
from unittest.mock import patch

import pytest

import database.core as db_core
from utils.cycle_tracker import (
    add_record,
    delete_record,
    move_record,
    update_note,
    get_records,
    get_cycle_stats,
    seed_initial_records,
)

from utils.timezone import HKT as _HKT


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


# ── 对话修正面（delete / move / update_note）────────────────


async def test_delete_record_removes(tmp_db):
    """delete_record 删掉记录并返回 True。"""
    db_core._db = tmp_db
    await add_record("2026-07-18", "这次来得晚")
    assert await delete_record("2026-07-18") is True
    assert await get_records() == []


async def test_delete_record_not_found(tmp_db):
    """删不存在的日期返回 False。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await delete_record("2026-01-01") is False
    assert len(await get_records()) == 1


async def test_delete_record_rejects_malformed(tmp_db):
    """坏日期不删且不误伤既有记录。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await delete_record("2026-13-40") is False
    assert await delete_record("明天") is False
    assert await delete_record("") is False
    assert len(await get_records()) == 1


async def test_move_record_moves_and_keeps_note(tmp_db):
    """move_record 挪动记录并保留旧备注。"""
    db_core._db = tmp_db
    await add_record("2026-07-18", "近期周期波动较大")
    assert await move_record("2026-07-18", "2026-07-16") == "moved"

    records = await get_records()
    assert len(records) == 1
    assert records[0]["start_date"] == "2026-07-16"
    assert records[0]["note"] == "近期周期波动较大"


async def test_move_record_target_exists_keeps_old(tmp_db):
    """新日期已有记录时返回 exists，旧记录不受损（原子性回归）。"""
    db_core._db = tmp_db
    await add_record("2026-07-18", "旧备注")
    await add_record("2026-07-16", "已有备注")
    assert await move_record("2026-07-18", "2026-07-16") == "exists"

    records = {r["start_date"]: r["note"] for r in await get_records()}
    assert records == {"2026-07-18": "旧备注", "2026-07-16": "已有备注"}


async def test_move_record_not_found_creates_no_ghost(tmp_db):
    """旧日期不存在时返回 not_found，且不产生幽灵新记录。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await move_record("2026-01-01", "2026-02-02") == "not_found"
    records = await get_records()
    assert len(records) == 1
    assert records[0]["start_date"] == "2026-07-18"


async def test_move_record_same_date_is_exists(tmp_db):
    """old==new 归一为 exists 的幂等无操作。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await move_record("2026-07-18", "2026-07-18") == "exists"
    assert len(await get_records()) == 1


async def test_move_record_rejects_malformed(tmp_db):
    """任一端日期非法即拒绝，记录不动。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await move_record("2026-13-40", "2026-07-16") == "not_found"
    assert await move_record("2026-07-18", "明天") == "not_found"
    assert await move_record("", "") == "not_found"
    records = await get_records()
    assert len(records) == 1
    assert records[0]["start_date"] == "2026-07-18"


async def test_update_note_updates(tmp_db):
    """update_note 补/改备注。"""
    db_core._db = tmp_db
    await add_record("2026-07-18")
    assert await update_note("2026-07-18", "那次痛得很厉害") is True
    records = await get_records()
    assert records[0]["note"] == "那次痛得很厉害"


async def test_update_note_not_found(tmp_db):
    """改不存在记录的备注返回 False。"""
    db_core._db = tmp_db
    assert await update_note("2026-01-01", "备注") is False
    assert await update_note("2026-13-40", "备注") is False


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


# ── 坏日期入口防御 ────────────────────────────────────────────
# 关心窗口语义已迁移到事件系统（utils/events.detect_cycle_events），
# 原 get_care_context 及其三例窗口测试随 A4 收敛删除。


async def test_add_record_rejects_malformed_date(tmp_db):
    """非法日期格式在入口被拒（防止一条坏数据拖垮 get_cycle_stats 全表 strptime）。"""
    db_core._db = tmp_db
    assert await add_record("2026-13-40") is False   # 月日越界
    assert await add_record("明天") is False          # 非日期
    assert await add_record("") is False              # 空串
    assert await add_record("20260422") is False      # 宽松格式不收
    assert await get_records() == []                  # 全部未入库

    # 坏数据没能入库 → get_cycle_stats 不会被一条脏值禁用
    await add_record("2026-04-22")
    await add_record("2026-05-20")
    stats = await get_cycle_stats()
    assert stats is not None
