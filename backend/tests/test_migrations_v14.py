"""迁移 v14 回归 — hit_count 按日计数语义修正 + 存量回填。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建 v13 时代
裸库，测的是迁移 SQL 本身（含一次性 clamp 回填）。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v14

_V13_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0,
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
    conn = await aiosqlite.connect(str(tmp_path / "v14.db"))
    await conn.executescript(_V13_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v14_adds_last_hit_date(bare_db):
    """迁移为 v13 时代旧库补上 last_hit_date。"""
    await _migrate_v14(bare_db)
    assert "last_hit_date" in await _columns(bare_db, "memories")


async def test_v14_clamps_inflated_hit_count(bare_db):
    """存量回填（T1-1 核心回归）：v13 通胀期 hit_count 被 clamp 到 19。

    不 clamp 的话，通胀垃圾值（活跃日 25-70/天）× 校准后阈值 20 →
    升级后首次衰减全体晋升 core，症状延迟复发。
    """
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, access_count, hit_count, status) "
        "VALUES ('m1', '通胀期记忆', '日常', '2026-07-01 10:00', 3, 70, 'active')"
    )
    await bare_db.commit()

    await _migrate_v14(bare_db)

    cursor = await bare_db.execute(
        "SELECT hit_count, last_hit_date, access_count FROM memories WHERE id = 'm1'"
    )
    hit, last_hit, access = await cursor.fetchone()
    assert hit == 19            # 70 → clamp 19
    assert last_hit is None     # 重置，从升级日起诚实累积
    assert access == 3          # access_count 绝不受回填影响


async def test_v14_clamp_floor_safe(bare_db):
    """clamp 下限安全：<19 的诚实值不动（尤其 ≤1 的冷记忆不被错改——
    清零会让它们撞上归档阈值被错杀，clamp 方案规避了这一点）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, hit_count, status) "
        "VALUES ('cold', '冷记忆', '日常', '2026-07-01 10:00', 0, 'active')"
    )
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, hit_count, status) "
        "VALUES ('warm', '温记忆', '日常', '2026-07-01 10:00', 7, 'active')"
    )
    await bare_db.commit()

    await _migrate_v14(bare_db)

    cursor = await bare_db.execute(
        "SELECT id, hit_count FROM memories ORDER BY id"
    )
    rows = dict(await cursor.fetchall())
    assert rows["cold"] == 0    # 不动
    assert rows["warm"] == 7    # 不动


async def test_v14_idempotent(bare_db):
    """二次执行安全幂等：已 ≤19 的行重跑不受影响（MIN 标量函数）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, hit_count, status) "
        "VALUES ('m1', '记忆', '日常', '2026-07-01 10:00', 50, 'active')"
    )
    await bare_db.commit()

    await _migrate_v14(bare_db)
    await _migrate_v14(bare_db)  # 二次执行不报错、不反复改写

    cursor = await bare_db.execute("SELECT hit_count FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == 19
