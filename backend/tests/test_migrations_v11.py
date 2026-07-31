"""迁移 v11 回归 — memory_extract_state 状态表。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建裸库，
测的是迁移 SQL 与 MEMORY_EXTRACT_STATE_SQL 常量本身。
"""
import sqlite3

import aiosqlite
import pytest

from database.migrations import MEMORY_EXTRACT_STATE_SQL, _migrate_v11


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v11.db"))
    yield conn
    await conn.close()


async def test_v11_creates_state_table(bare_db):
    """迁移建立单行状态表（水位线 + 健康检查节流用）。"""
    await _migrate_v11(bare_db)
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='memory_extract_state'"
    )
    assert await cursor.fetchone() is not None


async def test_v11_single_row_check(bare_db):
    """CHECK (id=1)：单行表约束，防止多行状态分叉。"""
    await bare_db.executescript(MEMORY_EXTRACT_STATE_SQL)
    await bare_db.commit()

    await bare_db.execute(
        "INSERT INTO memory_extract_state (id, last_message_id) VALUES (1, 42)"
    )
    await bare_db.commit()

    with pytest.raises(sqlite3.IntegrityError):
        await bare_db.execute("INSERT INTO memory_extract_state (id) VALUES (2)")
        await bare_db.commit()


async def test_v11_idempotent(bare_db):
    """新库上再跑 v11（IF NOT EXISTS）安全幂等。"""
    await _migrate_v11(bare_db)
    await _migrate_v11(bare_db)  # 二次执行不报错
