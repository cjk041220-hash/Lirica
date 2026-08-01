"""迁移 v15 回归 — memories.superseded_by + memory_extract_state.consolidate_ran_at。

自建 v14 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v15

_V14_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT
);
CREATE TABLE memory_extract_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_message_id INTEGER NOT NULL DEFAULT 0,
    last_run_at TEXT,
    health_warned_at TEXT,
    fail_count INTEGER NOT NULL DEFAULT 0
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v15.db"))
    await conn.executescript(_V14_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v15_adds_both_columns(bare_db):
    await _migrate_v15(bare_db)
    assert "superseded_by" in await _columns(bare_db, "memories")
    assert "consolidate_ran_at" in await _columns(bare_db, "memory_extract_state")


async def test_v15_preserves_existing_rows(bare_db):
    """加列不动存量行。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-07-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v15(bare_db)

    cursor = await bare_db.execute(
        "SELECT content, superseded_by FROM memories WHERE id = 'm1'"
    )
    row = await cursor.fetchone()
    assert row[0] == "旧记忆"
    assert row[1] is None


async def test_v15_idempotent(bare_db):
    await _migrate_v15(bare_db)
    await _migrate_v15(bare_db)
    assert "superseded_by" in await _columns(bare_db, "memories")
