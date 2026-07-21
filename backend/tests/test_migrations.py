"""数据库迁移系统测试。"""
import pytest

from database.migrations import ensure_schema, SCHEMA_VERSION, _get_current_version


async def test_ensure_schema_creates_version_table(tmp_db):
    """ensure_schema 创建 schema_version 表。"""
    await ensure_schema(tmp_db)

    cursor = await tmp_db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'"
    )
    row = await cursor.fetchone()
    assert row is not None
    assert row[0] == "schema_version"


async def test_ensure_schema_idempotent(tmp_db):
    """ensure_schema 执行两次不报错，版本不变。"""
    await ensure_schema(tmp_db)
    v1 = await _get_current_version(tmp_db)

    await ensure_schema(tmp_db)
    v2 = await _get_current_version(tmp_db)

    assert v1 == v2 == SCHEMA_VERSION


async def test_current_version(tmp_db):
    """迁移后版本等于 SCHEMA_VERSION。"""
    await ensure_schema(tmp_db)
    version = await _get_current_version(tmp_db)
    assert version == SCHEMA_VERSION
