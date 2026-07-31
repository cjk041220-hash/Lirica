"""迁移 v10 回归 — memories 去重部分唯一索引谓词扩至 active+core。

v10 之前索引只覆盖 active：core 行（分层衰减晋升的高频核心记忆）跌出
约束，add() 的 ON CONFLICT 推断不中 → 同 (category, content) 出现
active + core 双行。v10 后：
- core 行与 active 一样参与去重（ON CONFLICT 推断命中、读回拿到它）；
- archived 行仍不在谓词内——遗忘后重提同内容时 add() 插新行开始
  新生命周期（「忘了又想起」语义）。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建裸库，
测的是迁移 SQL 与 MEMORIES_INDEX_SQL 常量本身。
"""
import sqlite3

import aiosqlite
import pytest

from database.migrations import MEMORIES_INDEX_SQL, _migrate_v10

_MEMS_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active'
);
"""

_INS = """INSERT INTO memories (id, content, category, created_at, status)
          VALUES (?, '冷冷喜欢喝椰子水', '核心偏好', '2026-06-01 10:00', ?)"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v10.db"))
    yield conn
    await conn.close()


async def test_new_db_constant_core_dedupes(bare_db):
    """MEMORIES_INDEX_SQL 常量的谓词已含 core：同内容 core+active 互斥。

    钉死常量与迁移终态一致——conftest/init_db/v10 三处共享此常量。
    """
    await bare_db.executescript(_MEMS_DDL)
    await bare_db.executescript(MEMORIES_INDEX_SQL)
    await bare_db.commit()

    await bare_db.execute(_INS, ("m1", "core"))
    await bare_db.commit()
    with pytest.raises(sqlite3.IntegrityError):
        await bare_db.execute(_INS, ("m2", "active"))  # 同内容活跃插入被挡


async def test_v10_upgrade_old_db_core_starts_deduping(bare_db):
    """老库升级路径：旧谓词（仅 active）→ v10 迁移 → core 开始参与去重。"""
    await bare_db.executescript(_MEMS_DDL)
    await bare_db.execute(
        """CREATE UNIQUE INDEX idx_memories_cat_content
           ON memories(category, content) WHERE status = 'active'"""
    )
    await bare_db.commit()

    # 旧语义下：core 不在谓词内，同内容 active 可并存（v10 要修的行为）
    await bare_db.execute(_INS, ("m1", "core"))
    await bare_db.execute(_INS, ("m2", "active"))
    await bare_db.commit()

    # 清掉升级前的行，跑迁移，验证新语义
    await bare_db.execute("DELETE FROM memories")
    await bare_db.commit()
    await _migrate_v10(bare_db)

    cursor = await bare_db.execute(
        "SELECT sql FROM sqlite_master WHERE name = 'idx_memories_cat_content'"
    )
    assert "'active', 'core'" in (await cursor.fetchone())[0]

    await bare_db.execute(_INS, ("m3", "core"))
    await bare_db.commit()
    with pytest.raises(sqlite3.IntegrityError):
        await bare_db.execute(_INS, ("m4", "active"))


async def test_v10_migration_is_idempotent(bare_db):
    """新库上再跑 v10（drop+recreate）安全幂等。"""
    await bare_db.executescript(_MEMS_DDL)
    await bare_db.commit()
    await _migrate_v10(bare_db)
    await _migrate_v10(bare_db)  # 二次执行不报错


async def test_v10_archived_still_allows_remember(bare_db):
    """归档行不在谓词内：忘了的事重提时插新行开始新生命周期。"""
    await bare_db.executescript(_MEMS_DDL)
    await bare_db.commit()
    await _migrate_v10(bare_db)

    await bare_db.execute(_INS, ("m1", "archived"))   # 遗忘 ≠ 永久占位
    await bare_db.commit()
    await bare_db.execute(_INS, ("m2", "active"))     # 同内容重新记住成功
    await bare_db.commit()

    cursor = await bare_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 2
