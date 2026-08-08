"""迁移 v16 回归 — life_state.mood_changed_at（心情节奏：早晨锚定 + 最小停留）。

自建 v15 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v16

_V15_DDL = """
CREATE TABLE life_state (
    id INTEGER PRIMARY KEY DEFAULT 1,
    mood TEXT,
    daily_event TEXT,
    updated_at TEXT,
    first_met_on TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v16.db"))
    await conn.executescript(_V15_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v16_adds_mood_changed_at(bare_db):
    await _migrate_v16(bare_db)
    assert "mood_changed_at" in await _columns(bare_db, "life_state")


async def test_v16_preserves_existing_rows(bare_db):
    """加列不动存量行。"""
    await bare_db.execute(
        "INSERT INTO life_state (id, mood, daily_event, updated_at) "
        "VALUES (1, '开心', '刚刚醒来', '2026-07-01 10:00')"
    )
    await bare_db.commit()
    await _migrate_v16(bare_db)

    cursor = await bare_db.execute(
        "SELECT mood, daily_event, mood_changed_at FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    assert row[0] == "开心"
    assert row[1] == "刚刚醒来"
    assert row[2] is None  # 新列为空，首次心跳自然重置


async def test_v16_idempotent(bare_db):
    await _migrate_v16(bare_db)
    await _migrate_v16(bare_db)
    assert "mood_changed_at" in await _columns(bare_db, "life_state")


async def test_v16_creates_table_if_missing(tmp_path):
    """表不存在（极老库）→ 用完整 DDL 建出含新列的表。"""
    conn = await aiosqlite.connect(str(tmp_path / "v16-missing.db"))
    await _migrate_v16(conn)
    assert "mood_changed_at" in await _columns(conn, "life_state")
    await conn.close()
