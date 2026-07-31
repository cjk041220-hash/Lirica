"""生理期对话工具测试 — log_period / amend_period。

db_core._db = tmp_db 同时覆盖 utils.cycle_tracker 与 utils.events 的
get_db（两者都 from database.core import get_db），即时链的检测器
就在同一临时库上真跑，不用 mock。
"""
from datetime import timedelta
from unittest.mock import AsyncMock

import database.core as db_core
from utils.timezone import HKT as _HKT

from tools import get_all_tools
from tools.cycle import amend_period, log_period, set_prompt_refresh_callback


def _date(offset: int) -> str:
    import datetime
    return (datetime.datetime.now(_HKT).date() + timedelta(days=offset)).isoformat()


async def _records(db):
    cursor = await db.execute(
        "SELECT start_date, note FROM cycle_records ORDER BY start_date"
    )
    return await cursor.fetchall()


# ── 注册 ──────────────────────────────────────────────────────


def test_tools_registered():
    """两枚工具进了全局工具列表（get_all_tools 静态注册回归）。"""
    names = [t.name for t in get_all_tools()]
    assert "log_period" in names
    assert "amend_period" in names


# ── log_period ────────────────────────────────────────────────


async def test_log_period_inserts(tmp_db):
    db_core._db = tmp_db
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "记下啦" in result
    assert await _records(tmp_db) == [(_date(-1), "")]


async def test_log_period_with_note(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-1), "note": "这次痛得厉害"})
    assert await _records(tmp_db) == [(_date(-1), "这次痛得厉害")]


async def test_log_period_duplicate(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-1)})
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "已经记着啦" in result
    assert len(await _records(tmp_db)) == 1


async def test_log_period_rejects_malformed(tmp_db):
    db_core._db = tmp_db
    result = await log_period.ainvoke({"date": "明天"})
    assert "YYYY-MM-DD" in result
    assert await _records(tmp_db) == []


async def test_log_period_triggers_prompt_refresh(tmp_db):
    """即时链：写入后调刷新回调（不用等看门狗）。"""
    db_core._db = tmp_db
    cb = AsyncMock()
    set_prompt_refresh_callback(cb)
    await log_period.ainvoke({"date": _date(-1)})
    cb.assert_awaited_once()


async def test_log_period_refresh_failure_does_not_break_talk(tmp_db):
    """刷新回调炸了只降级，不影响入库结果话术。"""
    db_core._db = tmp_db
    set_prompt_refresh_callback(AsyncMock(side_effect=RuntimeError("boom")))
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "记下啦" in result
    assert len(await _records(tmp_db)) == 1


# ── amend_period ──────────────────────────────────────────────


async def test_amend_moves_record(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3), "note": "旧备注"})
    result = await amend_period.ainvoke({"old_date": _date(-3), "new_date": _date(-5)})
    assert "挪到" in result
    assert await _records(tmp_db) == [(_date(-5), "旧备注")]  # 备注跟着挪


async def test_amend_deletes_record(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3)})
    result = await amend_period.ainvoke({"old_date": _date(-3)})
    assert "划掉" in result
    assert await _records(tmp_db) == []


async def test_amend_updates_note_only(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3)})
    result = await amend_period.ainvoke({"old_date": _date(-3), "note": "那次很痛"})
    assert "备注" in result
    assert await _records(tmp_db) == [(_date(-3), "那次很痛")]


async def test_amend_move_with_new_note(tmp_db):
    """挪动 + 补备注可叠加，备注落在新日期上。"""
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3)})
    await amend_period.ainvoke(
        {"old_date": _date(-3), "new_date": _date(-5), "note": "记岔了两天"}
    )
    assert await _records(tmp_db) == [(_date(-5), "记岔了两天")]


async def test_amend_not_found(tmp_db):
    db_core._db = tmp_db
    result = await amend_period.ainvoke({"old_date": _date(-3)})
    assert "没找到" in result


async def test_amend_move_target_exists_keeps_old(tmp_db):
    """目标日期已有记录：不动旧的，话术引导 LLM 决定是否单独划掉。"""
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3)})
    await log_period.ainvoke({"date": _date(-5)})
    result = await amend_period.ainvoke({"old_date": _date(-3), "new_date": _date(-5)})
    assert "本来" in result
    assert len(await _records(tmp_db)) == 2  # 旧记录未受损


async def test_amend_rejects_malformed(tmp_db):
    db_core._db = tmp_db
    r1 = await amend_period.ainvoke({"old_date": "明天"})
    r2 = await amend_period.ainvoke({"old_date": _date(-3), "new_date": "后天"})
    assert "YYYY-MM-DD" in r1
    assert "YYYY-MM-DD" in r2


async def test_amend_triggers_prompt_refresh(tmp_db):
    db_core._db = tmp_db
    await log_period.ainvoke({"date": _date(-3)})
    cb = AsyncMock()
    set_prompt_refresh_callback(cb)
    await amend_period.ainvoke({"old_date": _date(-3)})
    cb.assert_awaited_once()
