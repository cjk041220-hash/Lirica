"""迁移 v20 回归 — events.completed_at（事件完成态「办完了，别再惦记」）。

自建 v19 时代裸库，测迁移 SQL 本身；语义（读侧过滤/勾选/复活清空）
由 test_events.py 钉死。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v20

_V19_DDL = """
CREATE TABLE events (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    event_type  TEXT NOT NULL,
    event_date  TEXT NOT NULL,
    end_date    TEXT,
    severity    TEXT NOT NULL DEFAULT 'normal',
    source      TEXT NOT NULL DEFAULT 'llm',
    memo        TEXT,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL,
    dedupe_key  TEXT
);
INSERT INTO events (id, title, event_type, event_date, status, created_at)
VALUES ('a1b2c3d4e5f60718', '期中考试', 'exam', '2026-08-05', 'active', '2026-07-25 10:00');
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v20.db"))
    await conn.executescript(_V19_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def test_v20_adds_completed_at_column(bare_db):
    await _migrate_v20(bare_db)

    cursor = await bare_db.execute("PRAGMA table_info(events)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert "completed_at" in cols

    # 存量行不回填：全部视为未完成
    cursor = await bare_db.execute("SELECT completed_at FROM events WHERE id = 'a1b2c3d4e5f60718'")
    assert (await cursor.fetchone())[0] is None


async def test_v20_idempotent(bare_db):
    """重跑迁移不报错（列已存在时跳过 ALTER）。"""
    await _migrate_v20(bare_db)
    await _migrate_v20(bare_db)

    cursor = await bare_db.execute("PRAGMA table_info(events)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert "completed_at" in cols
