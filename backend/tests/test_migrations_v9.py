"""迁移 v9 回归 — events dedupe 部分唯一索引谓词扩至 active+deleted（墓碑）。

v9 之前索引只覆盖 active：软删行的 dedupe_key 失去约束，LLM 重新提取 /
检测器再生成会把删掉的事原样建回来（「删了又偷偷长回来」）。v9 后：
- deleted 行（墓碑）继续占位挡重建；
- archived 行仍不在谓词内——生日/周年/台风的「明年再来」语义不变。

不借用 conftest 的 tmp_db（那里已套新 schema）——本文件自建裸库，
测的是迁移 SQL 与 DDL 常量本身。
"""
import sqlite3

import aiosqlite
import pytest

from database.migrations import EVENTS_SCHEMA_SQL, _migrate_v9

_INS = """INSERT INTO events
          (id, title, event_type, event_date, severity, source,
           status, created_at, dedupe_key)
          VALUES (?, '期中考试', 'exam', '2026-08-01', 'normal', 'user',
                  ?, '2026-07-29 10:00', 'llm:期中考试:2026-08-01')"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v9.db"))
    yield conn
    await conn.close()


async def test_new_db_schema_tombstone_blocks_recreate(bare_db):
    """新库 DDL 常量（EVENTS_SCHEMA_SQL）的谓词已含 deleted：墓碑挡重建。

    钉死常量与迁移终态一致——防止两处漂移（conftest/init_db 共享此常量）。
    """
    await bare_db.executescript(EVENTS_SCHEMA_SQL)
    await bare_db.commit()

    await bare_db.execute(_INS, ("e1", "deleted"))   # 墓碑
    await bare_db.commit()
    with pytest.raises(sqlite3.IntegrityError):
        await bare_db.execute(_INS, ("e2", "active"))  # 同 key 活跃插入被挡


async def test_v9_upgrade_old_db_tombstone_starts_blocking(bare_db):
    """老库升级路径：旧谓词（仅 active）→ v9 迁移 → 墓碑开始挡重建。"""
    # 先建表，再把索引还原成 v6 旧谓词，模拟升级前的老库
    await bare_db.executescript(EVENTS_SCHEMA_SQL)
    await bare_db.execute("DROP INDEX IF EXISTS idx_events_dedupe")
    await bare_db.execute(
        """CREATE UNIQUE INDEX idx_events_dedupe
           ON events(dedupe_key) WHERE dedupe_key IS NOT NULL AND status = 'active'"""
    )
    await bare_db.commit()

    # 旧语义下：墓碑不挡重建（这正是 v9 要修的行为）
    await bare_db.execute(_INS, ("e1", "deleted"))
    await bare_db.commit()
    await bare_db.execute(_INS, ("e2", "active"))
    await bare_db.commit()

    # 清掉升级前插入的行，跑迁移，验证新语义
    await bare_db.execute("DELETE FROM events")
    await bare_db.commit()
    await _migrate_v9(bare_db)

    await bare_db.execute(_INS, ("e3", "deleted"))
    await bare_db.commit()
    with pytest.raises(sqlite3.IntegrityError):
        await bare_db.execute(_INS, ("e4", "active"))


async def test_v9_migration_is_idempotent(bare_db):
    """新库上再跑 v9（drop+recreate）安全幂等——迁移重放不炸。"""
    await bare_db.executescript(EVENTS_SCHEMA_SQL)
    await bare_db.commit()
    await _migrate_v9(bare_db)
    await _migrate_v9(bare_db)  # 二次执行不报错


async def test_v9_archived_still_allows_recreate(bare_db):
    """归档行不在谓词内：明年/下次事件照常再生成（v9 不改此语义）。

    与 test_events.py 的 test_create_event_dedupe_key_allows_recreate_after_archive
    互为表里：那条钉 manager 行为，这条钉索引谓词本身。
    """
    await bare_db.executescript(EVENTS_SCHEMA_SQL)
    await bare_db.commit()
    await _migrate_v9(bare_db)

    await bare_db.execute(_INS, ("e1", "archived"))   # 翻篇 ≠ 否决
    await bare_db.commit()
    await bare_db.execute(_INS, ("e2", "active"))     # 同 key 新建成功
    await bare_db.commit()

    cursor = await bare_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 2
