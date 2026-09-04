"""迁移 v25 回归 — topic_bank（话题库系统）。

测试迁移 SQL 本身：
- 建表：topic_bank 存在且结构正确
- 索引：idx_topic_bank_status 存在
- 幂等：多次执行不报错
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v25


@pytest.fixture
async def bare_v24_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v25.db"))
    await conn.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY)")
    await conn.execute("INSERT INTO schema_version VALUES (24)")
    await conn.commit()
    yield conn
    await conn.close()


@pytest.mark.asyncio
async def test_migrate_v25_creates_table_and_index(bare_v24_db):
    """测试 _migrate_v25 创建 topic_bank 表与索引。"""
    # 迁移前表不存在
    cursor = await bare_v24_db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='topic_bank'"
    )
    assert await cursor.fetchone() is None

    # 执行迁移
    await _migrate_v25(bare_v24_db)

    # 迁移后表存在
    cursor = await bare_v24_db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='topic_bank'"
    )
    assert await cursor.fetchone() is not None

    # 验证字段
    cursor = await bare_v24_db.execute("PRAGMA table_info(topic_bank)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert {"id", "title", "category", "content", "guidance", "keywords", "status", "created_at", "consumed_at"} <= cols

    # 验证索引
    cursor = await bare_v24_db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_topic_bank_status'"
    )
    assert await cursor.fetchone() is not None

    # 幂等测试：重复运行不报错
    await _migrate_v25(bare_v24_db)
