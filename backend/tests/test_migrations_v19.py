"""迁移 v19 回归 — care_ledger（异常感知关心冷却台账）。

自建 v18 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v19

_V18_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    valence REAL DEFAULT 0, arousal REAL DEFAULT 0
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v19.db"))
    await conn.executescript(_V18_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def test_v19_creates_care_ledger(bare_db):
    await _migrate_v19(bare_db)

    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='care_ledger'"
    )
    assert await cursor.fetchone() is not None

    cursor = await bare_db.execute("PRAGMA table_info(care_ledger)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert {"type", "last_at"} <= cols


async def test_v19_idempotent_and_preserves_rows(bare_db):
    """重跑迁移不丢台账数据（CREATE IF NOT EXISTS 幂等）。"""
    await _migrate_v19(bare_db)
    await bare_db.execute(
        "INSERT INTO care_ledger (type, last_at) VALUES ('late_night', '2026-08-05 02:14')"
    )
    await bare_db.commit()

    await _migrate_v19(bare_db)  # 重跑

    cursor = await bare_db.execute(
        "SELECT last_at FROM care_ledger WHERE type = 'late_night'"
    )
    assert (await cursor.fetchone())[0] == "2026-08-05 02:14"
