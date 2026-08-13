"""迁移 v23 回归 — session_summaries 表。

自建 v22 时代裸库（镜像生产 schema），测迁移 SQL 本身：
- 表存在（sqlite_master）
- 幂等：重跑不报错，仍唯一一张表
- 列齐：session_id / summary / msg_count / ended_at / created_at
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v23


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v23.db"))
    await conn.executescript("""
        CREATE TABLE chat_sessions (
            id TEXT PRIMARY KEY, title TEXT, created_at TEXT,
            updated_at TEXT, is_era INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL, created_at TEXT NOT NULL,
            msg_type TEXT
        );
    """)
    await conn.commit()
    yield conn
    await conn.close()


async def test_v23_creates_table(bare_db):
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name='session_summaries'"
    )
    assert await cursor.fetchone() is not None


async def test_v23_idempotent(bare_db):
    await _migrate_v23(bare_db)
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name='session_summaries'"
    )
    assert len(await cursor.fetchall()) == 1


async def test_v23_schema_columns(bare_db):
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute("PRAGMA table_info(session_summaries)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert {"session_id", "summary", "msg_count", "ended_at", "created_at"} <= cols
