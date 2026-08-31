"""生理期对话工具测试 — log_period / amend_period。

每个用例都用 `monkeypatch.setattr(db_core, "_db", tmp_db)` 注入临时库：
这一处赋值同时覆盖 utils.cycle_tracker 与 utils.events 的 get_db
（两者都 from database.core import get_db），即时链的检测器就在同一
临时库上真跑，不用 mock。

2026-08-30 起一律走 monkeypatch。此前写作 `db_core._db = tmp_db`
直接赋值且从不还原，跑完本文件后 database.core._db 就永久指向一个
失效连接，后续任何调用 get_db() 又不自己打补丁的用例都会拿到死连接
（ValueError: no active connection）。
"""
from datetime import timedelta
from unittest.mock import AsyncMock

import database.core as db_core
from tools import get_all_tools
from tools.cycle import (
    amend_period,
    get_period_status,
    log_period,
    set_prompt_refresh_callback,
)
from utils.timezone import HKT as _HKT


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
    """生理期三枚工具进了全局工具列表（get_all_tools 静态注册回归）。"""
    names = [t.name for t in get_all_tools()]
    assert "get_period_status" in names
    assert "log_period" in names
    assert "amend_period" in names


# ── get_period_status ─────────────────────────────────────────


async def test_get_period_status_empty_account(tmp_db, monkeypatch):
    """空账本：如实说还没有记录（别让小汐硬编「没记过」以外的说法）。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    result = await get_period_status.ainvoke({})
    assert "还没有生理期记录" in result


async def test_get_period_status_reports_records_and_prediction(tmp_db, monkeypatch):
    """账本 3 条：日期、备注、平均周期、预测日如实报出。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-90)})
    await log_period.ainvoke({"date": _date(-60)})
    await log_period.ainvoke({"date": _date(-30), "note": "这次很痛"})
    result = await get_period_status.ainvoke({})
    assert _date(-90) in result and _date(-60) in result and _date(-30) in result
    assert "这次很痛" in result
    assert "30 天一次" in result            # 平均周期（-90/-60/-30 间隔 30）
    assert _date(0) in result               # 预测日 = 最近一次 + 30 = 今天
    assert "下次预计" in result


async def test_get_period_status_too_few_records(tmp_db, monkeypatch):
    """不足两次：展示已有记录，同时如实说推算不了规律。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-30)})
    result = await get_period_status.ainvoke({})
    assert _date(-30) in result
    assert "推算不了" in result


async def test_get_period_status_readonly(tmp_db, monkeypatch):
    """只读不改：调用后账本原样，不触发任何写路径（无刷新回调调用）。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-30)})
    cb = AsyncMock()
    set_prompt_refresh_callback(cb)
    await get_period_status.ainvoke({})
    cb.assert_not_awaited()
    assert await _records(tmp_db) == [(_date(-30), "")]


# ── log_period ────────────────────────────────────────────────


async def test_log_period_inserts(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "记下啦" in result
    assert await _records(tmp_db) == [(_date(-1), "")]


async def test_log_period_with_note(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-1), "note": "这次痛得厉害"})
    assert await _records(tmp_db) == [(_date(-1), "这次痛得厉害")]


async def test_log_period_duplicate(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-1)})
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "已经记着啦" in result
    assert len(await _records(tmp_db)) == 1


async def test_log_period_rejects_malformed(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    result = await log_period.ainvoke({"date": "明天"})
    assert "YYYY-MM-DD" in result
    assert await _records(tmp_db) == []


async def test_log_period_triggers_prompt_refresh(tmp_db, monkeypatch):
    """即时链：写入后调刷新回调（不用等看门狗）。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    cb = AsyncMock()
    set_prompt_refresh_callback(cb)
    await log_period.ainvoke({"date": _date(-1)})
    cb.assert_awaited_once()


async def test_log_period_refresh_failure_does_not_break_talk(tmp_db, monkeypatch):
    """刷新回调炸了只降级，不影响入库结果话术。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    set_prompt_refresh_callback(AsyncMock(side_effect=RuntimeError("boom")))
    result = await log_period.ainvoke({"date": _date(-1)})
    assert "记下啦" in result
    assert len(await _records(tmp_db)) == 1


# ── amend_period ──────────────────────────────────────────────


async def test_amend_moves_record(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3), "note": "旧备注"})
    result = await amend_period.ainvoke({"old_date": _date(-3), "new_date": _date(-5)})
    assert "挪到" in result
    assert await _records(tmp_db) == [(_date(-5), "旧备注")]  # 备注跟着挪


async def test_amend_deletes_record(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3)})
    result = await amend_period.ainvoke({"old_date": _date(-3)})
    assert "划掉" in result
    assert await _records(tmp_db) == []


async def test_amend_updates_note_only(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3)})
    result = await amend_period.ainvoke({"old_date": _date(-3), "note": "那次很痛"})
    assert "备注" in result
    assert await _records(tmp_db) == [(_date(-3), "那次很痛")]


async def test_amend_move_with_new_note(tmp_db, monkeypatch):
    """挪动 + 补备注可叠加，备注落在新日期上。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3)})
    await amend_period.ainvoke(
        {"old_date": _date(-3), "new_date": _date(-5), "note": "记岔了两天"}
    )
    assert await _records(tmp_db) == [(_date(-5), "记岔了两天")]


async def test_amend_not_found(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    result = await amend_period.ainvoke({"old_date": _date(-3)})
    assert "没找到" in result


async def test_amend_move_target_exists_keeps_old(tmp_db, monkeypatch):
    """目标日期已有记录：不动旧的，话术引导 LLM 决定是否单独划掉。"""
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3)})
    await log_period.ainvoke({"date": _date(-5)})
    result = await amend_period.ainvoke({"old_date": _date(-3), "new_date": _date(-5)})
    assert "本来" in result
    assert len(await _records(tmp_db)) == 2  # 旧记录未受损


async def test_amend_rejects_malformed(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    r1 = await amend_period.ainvoke({"old_date": "明天"})
    r2 = await amend_period.ainvoke({"old_date": _date(-3), "new_date": "后天"})
    assert "YYYY-MM-DD" in r1
    assert "YYYY-MM-DD" in r2


async def test_amend_triggers_prompt_refresh(tmp_db, monkeypatch):
    monkeypatch.setattr(db_core, "_db", tmp_db)
    await log_period.ainvoke({"date": _date(-3)})
    cb = AsyncMock()
    set_prompt_refresh_callback(cb)
    await amend_period.ainvoke({"old_date": _date(-3)})
    cb.assert_awaited_once()
