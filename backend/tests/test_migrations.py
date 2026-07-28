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


# ── v7：相识纪念日 ────────────────────────────────────────────


async def _old_life_state_db(tmp_path):
    """构造一个 v6 时代（无 first_met_on 列）的 life_state 库。"""
    import aiosqlite

    conn = await aiosqlite.connect(str(tmp_path / "v7.db"))
    await conn.execute(
        "CREATE TABLE life_state (id INTEGER PRIMARY KEY DEFAULT 1, "
        "mood TEXT, daily_event TEXT, updated_at TEXT)"
    )
    await conn.execute(
        "INSERT INTO life_state (id, mood, daily_event, updated_at) "
        "VALUES (1, '开心', '刚刚醒来', '2026-01-01 00:00')"
    )
    await conn.commit()
    return conn


async def test_migrate_v7_adds_column_idempotent(tmp_path):
    """v7 给旧表补 first_met_on 列，跑两次不报错（幂等）。"""
    from database.migrations import _migrate_v7

    conn = await _old_life_state_db(tmp_path)
    await _migrate_v7(conn)
    await _migrate_v7(conn)

    cursor = await conn.execute("PRAGMA table_info(life_state)")
    cols = {r[1] for r in await cursor.fetchall()}
    assert "first_met_on" in cols
    # 既有数据不受影响
    cursor = await conn.execute("SELECT mood FROM life_state WHERE id = 1")
    assert (await cursor.fetchone())[0] == "开心"
    await conn.close()


async def test_migrate_v7_env_seed(tmp_path, monkeypatch):
    """env 提供合法日期 → 种入空列；既有值不被 env 覆盖（相识日只设定一次）。"""
    from database.migrations import _migrate_v7

    monkeypatch.setenv("XIAOXI_FIRST_MEET", "2025-10-01")
    conn = await _old_life_state_db(tmp_path)
    await _migrate_v7(conn)
    cursor = await conn.execute("SELECT first_met_on FROM life_state WHERE id = 1")
    assert (await cursor.fetchone())[0] == "2025-10-01"

    # 改 env 再跑：既有值不动
    monkeypatch.setenv("XIAOXI_FIRST_MEET", "2020-01-01")
    await _migrate_v7(conn)
    cursor = await conn.execute("SELECT first_met_on FROM life_state WHERE id = 1")
    assert (await cursor.fetchone())[0] == "2025-10-01"
    await conn.close()


async def test_migrate_v7_bad_env_ignored(tmp_path, monkeypatch):
    """非法日期（2026-02-30 / 非日期字符串）→ 静默忽略，列为 NULL。"""
    from database.migrations import _migrate_v7

    conn = await _old_life_state_db(tmp_path)
    for bad in ("2026-02-30", "not-a-date", "2026/01/01"):
        monkeypatch.setenv("XIAOXI_FIRST_MEET", bad)
        await _migrate_v7(conn)  # 不抛
    cursor = await conn.execute("SELECT first_met_on FROM life_state WHERE id = 1")
    assert (await cursor.fetchone())[0] is None
    await conn.close()


# ── v8：对话档案 ──────────────────────────────────────────────


async def test_migrate_v8_creates_tables_idempotent(tmp_path):
    """v8 建 chat_sessions / chat_messages 与两个索引，跑两次不报错（幂等）。"""
    import aiosqlite
    from database.migrations import _migrate_v8

    conn = await aiosqlite.connect(str(tmp_path / "v8.db"))
    await _migrate_v8(conn)
    await _migrate_v8(conn)

    cursor = await conn.execute(
        "SELECT name, type FROM sqlite_master WHERE name IN "
        "('chat_sessions', 'chat_messages', "
        "'idx_chat_sessions_updated', 'idx_chat_messages_session')"
    )
    found = {r[0]: r[1] for r in await cursor.fetchall()}
    assert found["chat_sessions"] == "table"
    assert found["chat_messages"] == "table"
    assert found["idx_chat_sessions_updated"] == "index"
    assert found["idx_chat_messages_session"] == "index"
    await conn.close()


async def test_init_db_fresh_install_seeds(tmp_path, monkeypatch):
    """全新安装 + env：初始行插入后相识日兜底种子生效（覆盖迁移跑在
    初始行之前的顺序坑）。"""
    import aiosqlite
    from database.core import init_db

    monkeypatch.setenv("XIAOXI_FIRST_MEET", "2025-10-01")
    conn = await aiosqlite.connect(str(tmp_path / "fresh.db"))
    await init_db(conn)

    cursor = await conn.execute("SELECT first_met_on FROM life_state WHERE id = 1")
    assert (await cursor.fetchone())[0] == "2025-10-01"
    assert await _get_current_version(conn) == SCHEMA_VERSION
    await conn.close()
