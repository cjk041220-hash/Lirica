"""init_db 升级路径测试 — 旧版（v1）数据库必须能正常启动并迁移。

历史缺陷回归（v0.4.3 修复）：
init_db 在 ensure_schema（迁移）之前就执行
`CREATE INDEX ... ON memories(status)`，而 v1 旧库的 memories 表
没有 status 列 → 启动直接 OperationalError 崩溃，迁移永远没机会执行。
旧用户升级即砖，形成死锁。修复后顺序为：建表 → 迁移 → 建索引。
"""
import aiosqlite
import pytest


async def _create_legacy_v1_db(path) -> None:
    """构造一个 v1 时代的旧数据库（memories 为旧 schema，含遗留数据）。"""
    conn = await aiosqlite.connect(str(path))
    await conn.executescript("""
        CREATE TABLE memories (
            id          TEXT PRIMARY KEY,
            category    TEXT DEFAULT '其他',
            content     TEXT NOT NULL,
            source      TEXT,
            time        TEXT,
            last_used_at TEXT,
            weight      INTEGER,
            emotion_tag TEXT
        );
        INSERT INTO memories (id, category, content, source, time, weight, emotion_tag)
        VALUES ('legacy001', '冷冷喜欢的', '芒果', 'chat', '2026-07-01 10:00', 1, NULL);
        INSERT INTO memories (id, category, content, source, time, weight, emotion_tag)
        VALUES ('legacy002', '重要的事', '生日是3月5日', 'chat', '2026-07-02 12:00', 2, NULL);

        CREATE TABLE diaries (
            date TEXT PRIMARY KEY, title TEXT, content TEXT
        );
        CREATE TABLE life_state (
            id INTEGER PRIMARY KEY DEFAULT 1,
            mood TEXT, daily_event TEXT, updated_at TEXT
        );
    """)
    await conn.commit()
    await conn.close()


async def test_init_db_upgrades_legacy_v1_database(tmp_path):
    """回归：v1 旧库经 init_db 后完成迁移，数据保留，索引建成。"""
    from database.core import init_db

    db_path = tmp_path / "legacy.db"
    await _create_legacy_v1_db(db_path)

    conn = await aiosqlite.connect(str(db_path))
    try:
        # 修复前这里会抛 OperationalError: no such column: status
        await init_db(conn)

        # 1. memories 已是新 schema
        cursor = await conn.execute("PRAGMA table_info(memories)")
        columns = {row[1] for row in await cursor.fetchall()}
        assert "status" in columns
        assert "created_at" in columns
        assert "access_count" in columns
        assert "source" in columns  # v12 以新语义加回（来源追踪，与 v1 死字段无继承）
        assert "updated_at" in columns  # v12 覆盖留痕
        assert "prev_content" in columns  # v12 覆盖留痕
        assert "hit_count" in columns  # v13 衰减弱信号
        assert "last_hit_date" in columns  # v14 按日计数守卫
        assert "superseded_by" in columns  # v15 覆盖历史链
        assert "last_mention_ts" in columns  # v17 O1 统一提及时钟
        assert "importance" in columns       # v17 I1 重要性打分
        assert "valence" in columns          # v18 情绪联动：效价
        assert "arousal" in columns          # v18 情绪联动：唤醒度
        assert "time" not in columns

        # 2. 旧数据完整保留，time 已映射为 created_at
        cursor = await conn.execute(
            "SELECT content, created_at, status FROM memories ORDER BY id"
        )
        rows = await cursor.fetchall()
        assert len(rows) == 2
        assert rows[0][0] == "芒果"
        assert rows[0][1] == "2026-07-01 10:00"  # time → created_at
        assert rows[0][2] == "active"  # status 默认值生效

        # 3. 索引已建成
        cursor = await conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='memories'"
        )
        indexes = {row[0] for row in await cursor.fetchall()}
        assert "idx_memories_cat_content" in indexes
        assert "idx_memories_status" in indexes

        # 4. 迁移版本已登记，重复执行幂等
        cursor = await conn.execute("SELECT MAX(version) FROM schema_version")
        assert (await cursor.fetchone())[0] >= 12

        await init_db(conn)  # 第二次执行不应报错
    finally:
        await conn.close()


async def test_init_db_fresh_database_still_works(tmp_path):
    """全新空库路径不受顺序调整影响：表、索引、初始 life_state 全部就位。"""
    from database.core import init_db

    db_path = tmp_path / "fresh.db"
    conn = await aiosqlite.connect(str(db_path))
    try:
        await init_db(conn)

        cursor = await conn.execute("SELECT mood FROM life_state WHERE id = 1")
        row = await cursor.fetchone()
        assert row is not None and row[0]  # 初始生命状态行存在

        cursor = await conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_memories_status'"
        )
        assert await cursor.fetchone() is not None

        # events 表（迁移 v6）在全新库路径也就位
        cursor = await conn.execute("PRAGMA table_info(events)")
        assert len(await cursor.fetchall()) > 0
    finally:
        await conn.close()


async def test_migrate_v6_creates_events_table(tmp_path):
    """v6 迁移：建立 events 表与 dedupe 部分唯一索引。"""
    import sqlite3

    from database.migrations import SCHEMA_VERSION, ensure_schema

    db_path = tmp_path / "v5.db"
    conn = await aiosqlite.connect(str(db_path))
    # 真实 v5 库必有 memories 表（v1 建、v2 重构）——v10 迁移要重建其
    # 去重索引，合成库不能省掉这张表（否则报 no such table，那是对
    # 不真实输入的如实报错，不是迁移的缺陷）
    await conn.executescript("""
        CREATE TABLE memories (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
            last_used_at TEXT, access_count INTEGER DEFAULT 0,
            status TEXT DEFAULT 'active'
        );
        CREATE TABLE schema_version (
            version INTEGER PRIMARY KEY,
            applied_at TEXT DEFAULT (datetime('now'))
        );
        INSERT INTO schema_version (version) VALUES (1), (2), (3), (4), (5);
    """)
    await conn.commit()

    await ensure_schema(conn)

    # 1. events 表字段完整
    cursor = await conn.execute("PRAGMA table_info(events)")
    columns = {row[1] for row in await cursor.fetchall()}
    assert {
        "id", "title", "event_type", "event_date", "end_date",
        "severity", "source", "memo", "status", "created_at", "dedupe_key",
    } <= columns

    # 2. dedupe 部分唯一索引生效：活跃行同 dedupe_key 抛 IntegrityError
    await conn.execute(
        "INSERT INTO events (id, title, event_type, event_date, status, created_at, dedupe_key) "
        "VALUES ('e1', '大雨', 'rainstorm', '2026-08-01', 'active', '2026-07-25 10:00', 'k1')"
    )
    with pytest.raises(sqlite3.IntegrityError):
        await conn.execute(
            "INSERT INTO events (id, title, event_type, event_date, status, created_at, dedupe_key) "
            "VALUES ('e2', '又大雨', 'rainstorm', '2026-08-01', 'active', '2026-07-25 11:00', 'k1')"
        )

    # 3. 归档行不阻塞同 key 再生成（明年/下次事件）
    await conn.execute("UPDATE events SET status = 'archived' WHERE id = 'e1'")
    await conn.execute(
        "INSERT INTO events (id, title, event_type, event_date, status, created_at, dedupe_key) "
        "VALUES ('e3', '明年大雨', 'rainstorm', '2027-08-01', 'active', '2026-07-25 12:00', 'k1')"
    )

    # 4. 版本登记到最新（不钉死具体数字——随 SCHEMA_VERSION 演进），且迁移幂等
    cursor = await conn.execute("SELECT MAX(version) FROM schema_version")
    assert (await cursor.fetchone())[0] == SCHEMA_VERSION

    # 5. v7 顺带执行：此旧库本无 life_state 表，v7 应直接建出含
    #    first_met_on 的完整表（而不是在 ALTER 时炸 no such table）
    cursor = await conn.execute("PRAGMA table_info(life_state)")
    assert "first_met_on" in {row[1] for row in await cursor.fetchall()}

    await ensure_schema(conn)  # 第二次执行不报错
    await conn.close()


async def test_migrate_v5_dedupes_and_builds_unique_index(tmp_path):
    """v5 迁移：清理存量重复活跃行，并建立唯一部分索引。"""
    from database.migrations import ensure_schema

    db_path = tmp_path / "v4.db"
    conn = await aiosqlite.connect(str(db_path))
    await conn.executescript("""
        CREATE TABLE memories (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
            last_used_at TEXT, access_count INTEGER DEFAULT 0,
            status TEXT DEFAULT 'active'
        );
        CREATE INDEX idx_memories_cat_content ON memories(category, content);
        CREATE TABLE schema_version (
            version INTEGER PRIMARY KEY,
            applied_at TEXT DEFAULT (datetime('now'))
        );
        INSERT INTO schema_version (version) VALUES (1), (2), (3), (4);
        -- 两条 (cat, dup) 重复活跃行 + 一条普通行
        INSERT INTO memories VALUES ('a1', 'dup', 'cat', '2026-07-01 10:00', '2026-07-01 10:00', 0, 'active');
        INSERT INTO memories VALUES ('a2', 'dup', 'cat', '2026-07-02 10:00', '2026-07-05 10:00', 0, 'active');
        INSERT INTO memories VALUES ('a3', 'other', 'cat', '2026-07-03 10:00', '2026-07-03 10:00', 0, 'active');
    """)
    await conn.commit()

    await ensure_schema(conn)

    # 1. 重复行被清理：保留 last_used_at 最新的 a2
    cursor = await conn.execute("SELECT id FROM memories WHERE content = 'dup'")
    assert [r[0] for r in await cursor.fetchall()] == ["a2"]

    # 2. 索引升级为唯一（PRAGMA index_list: 第 3 列为 unique 标志）
    cursor = await conn.execute("PRAGMA index_list(memories)")
    unique_flags = {row[1]: row[2] for row in await cursor.fetchall()}
    assert unique_flags.get("idx_memories_cat_content") == 1

    # 3. 数据库层强制去重：再插重复活跃行抛 IntegrityError
    import sqlite3
    with pytest.raises(sqlite3.IntegrityError):
        await conn.execute(
            "INSERT INTO memories (id, content, category, created_at, status) "
            "VALUES ('x', 'other', 'cat', '2026-07-04 10:00', 'active')"
        )

    # 4. 版本已登记到最新（ensure_schema 会一路迁移到 SCHEMA_VERSION，
    #    v5 之后新增的版本也会顺序应用）
    from database.migrations import SCHEMA_VERSION
    cursor = await conn.execute("SELECT MAX(version) FROM schema_version")
    assert (await cursor.fetchone())[0] == SCHEMA_VERSION

    await conn.close()
