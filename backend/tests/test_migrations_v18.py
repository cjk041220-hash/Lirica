"""迁移 v18 回归 — memories.valence + arousal（情绪联动情绪标签）。

自建 v17 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v18

_V17_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT,
    superseded_by TEXT,
    last_mention_ts TEXT,
    importance INTEGER DEFAULT 5
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v18.db"))
    await conn.executescript(_V17_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v18_adds_columns(bare_db):
    await _migrate_v18(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "valence" in cols
    assert "arousal" in cols


async def test_v18_backfills_neutral_zero(bare_db):
    """存量行回填 0 = 中性（老记忆无情绪打分，按中性处理）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.0, 0.0)


async def test_v18_null_backfill_idempotent(bare_db):
    """NULL 兜底回填 + 幂等：手动置 NULL 后重跑迁移仍归 0。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    await bare_db.execute("UPDATE memories SET valence = NULL, arousal = NULL WHERE id = 'm1'")
    await bare_db.commit()
    await _migrate_v18(bare_db)  # 重跑：列已存在走守卫，NULL 回填生效
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.0, 0.0)


async def test_v18_does_not_touch_scored_rows(bare_db):
    """已有合法值（全新安装路径）重跑迁移不被覆盖。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    await bare_db.execute("UPDATE memories SET valence = 0.7, arousal = -0.3 WHERE id = 'm1'")
    await bare_db.commit()
    await _migrate_v18(bare_db)
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.7, -0.3)
