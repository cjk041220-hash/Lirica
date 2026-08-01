"""迁移 v13 回归 — memories.hit_count + memory_extract_state.fail_count。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建 v12 时代
裸库，测的是迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v13

_V12_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT
);
CREATE TABLE memory_extract_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_message_id INTEGER NOT NULL DEFAULT 0,
    last_run_at TEXT,
    health_warned_at TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v13.db"))
    await conn.executescript(_V12_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v13_adds_both_columns(bare_db):
    """迁移为 v12 时代旧库补上 hit_count 与 fail_count。"""
    await _migrate_v13(bare_db)
    assert "hit_count" in await _columns(bare_db, "memories")
    assert "fail_count" in await _columns(bare_db, "memory_extract_state")


async def test_v13_existing_rows_read_zero(bare_db):
    """存量行新列读作 0（SQLite ADD COLUMN DEFAULT 语义）——衰减算术
    (access_count + hit_count) 依赖这一点，否则 NULL 让整行跌出谓词。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-07-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v13(bare_db)
    cursor = await bare_db.execute(
        "SELECT hit_count, access_count + hit_count FROM memories WHERE id = 'm1'"
    )
    row = await cursor.fetchone()
    assert row[0] == 0
    assert row[1] == 0


async def test_v13_preserves_state_row(bare_db):
    """加列不动水位线行：提取进度完整保留，fail_count 从 0 起。"""
    await bare_db.execute(
        "INSERT INTO memory_extract_state (id, last_message_id, last_run_at) "
        "VALUES (1, 42, '2026-07-31 10:00')"
    )
    await bare_db.commit()
    await _migrate_v13(bare_db)
    cursor = await bare_db.execute(
        "SELECT last_message_id, fail_count FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    assert row[0] == 42
    assert row[1] == 0


async def test_v13_idempotent(bare_db):
    """新库上再跑 v13 安全幂等。"""
    await _migrate_v13(bare_db)
    await _migrate_v13(bare_db)  # 二次执行不报错
    assert "hit_count" in await _columns(bare_db, "memories")
    assert "fail_count" in await _columns(bare_db, "memory_extract_state")
