"""迁移 v12 回归 — memories 新增 updated_at / source / prev_content。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建裸库，
测的是迁移 SQL 本身（v10 时代 schema：无三列）。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v12

_MEMS_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active'
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v12.db"))
    await conn.executescript(_MEMS_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn) -> set[str]:
    cursor = await conn.execute("PRAGMA table_info(memories)")
    return {row[1] for row in await cursor.fetchall()}


async def test_v12_adds_three_columns(bare_db):
    """迁移为 v10 时代旧库补上 updated_at / source / prev_content。"""
    await _migrate_v12(bare_db)
    cols = await _columns(bare_db)
    assert {"updated_at", "source", "prev_content"} <= cols


async def test_v12_preserves_existing_data(bare_db):
    """加列不动存量行：旧数据完整保留，新列留空。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-07-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v12(bare_db)

    cursor = await bare_db.execute(
        "SELECT content, updated_at, source, prev_content FROM memories WHERE id = 'm1'"
    )
    row = await cursor.fetchone()
    assert row[0] == "旧记忆"
    assert row[1] is None and row[2] is None and row[3] is None


async def test_v12_idempotent(bare_db):
    """新库（已有三列）上再跑 v12 安全幂等。"""
    await _migrate_v12(bare_db)
    await _migrate_v12(bare_db)  # 二次执行不报错
    cols = await _columns(bare_db)
    assert {"updated_at", "source", "prev_content"} <= cols
