"""迁移 v17 回归 — memories.last_mention_ts（O1 双时钟）+ importance（I1 重要性）。

自建 v16 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v17

_V16_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT,
    superseded_by TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v17.db"))
    await conn.executescript(_V16_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v17_adds_columns(bare_db):
    await _migrate_v17(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "last_mention_ts" in cols
    assert "importance" in cols


async def test_v17_backfills_last_mention_ts_from_latest_clock(bare_db):
    """存量回填：last_mention_ts = MAX(created_at, updated_at, last_used_at)。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, last_used_at, updated_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', '2026-06-01 10:00', '2026-07-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT last_mention_ts FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == "2026-07-01 10:00"  # 取最新（updated_at）
    # 无 updated_at / last_used_at 的行回退 created_at
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m2', '无辅助时间', '其他', '2026-02-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT last_mention_ts FROM memories WHERE id = 'm2'")
    assert (await cursor.fetchone())[0] == "2026-02-01 10:00"


async def test_v17_sets_default_importance(bare_db):
    """存量行 importance 默认 5（LLM 打分前的存量记忆按中性处理）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT importance FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == 5


async def test_v17_idempotent(bare_db):
    await _migrate_v17(bare_db)
    await _migrate_v17(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "last_mention_ts" in cols and "importance" in cols