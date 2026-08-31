"""迁移 v24 回归 — chat_sessions.kind（会话类型与来源分家）。

自建 v23 时代裸库（镜像生产 schema），测迁移 SQL 本身：
- 补列：kind 存在且带默认值 'chat'
- 回填：开放的 era（is_era=1 且无 user 消息）→ 'solo'；
        被接话的 era / 普通会话 → 'chat'
- 标题收口：era 会话标题统一为「小汐的碎碎念」，普通会话标题不动
- 幂等：重跑不报错、回填结果不变
"""
import aiosqlite
import pytest

from database.migrations import ERA_SESSION_TITLE, _migrate_v24


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v24.db"))
    await conn.executescript("""
        CREATE TABLE chat_sessions (
            id TEXT PRIMARY KEY, title TEXT, created_at TEXT,
            updated_at TEXT, is_era INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL, created_at TEXT NOT NULL,
            msg_type TEXT
        );
        -- 开放 era（只有小汐的独处消息）
        INSERT INTO chat_sessions VALUES
            ('e-open', '小汐的日记 · 08月09日', '2026-08-09 08:00',
             '2026-08-09 09:00', 1);
        INSERT INTO chat_messages (session_id, role, content, created_at, msg_type)
            VALUES ('e-open', 'assistant', '我在看云。', '2026-08-09 08:00', 'solo');
        -- 已被冷冷接话的 era（来源仍是 era，类型该是 chat）
        INSERT INTO chat_sessions VALUES
            ('e-answered', '小汐的日记 · 08月08日', '2026-08-08 08:00',
             '2026-08-08 20:00', 1);
        INSERT INTO chat_messages (session_id, role, content, created_at, msg_type)
            VALUES ('e-answered', 'assistant', '一个人待着。', '2026-08-08 08:00', 'solo'),
                   ('e-answered', 'user', '我回来啦', '2026-08-08 20:00', 'chat');
        -- 冷冷自己开的普通对话
        INSERT INTO chat_sessions VALUES
            ('s-normal', '今天过得怎么样', '2026-08-09 12:00',
             '2026-08-09 12:05', 0);
        INSERT INTO chat_messages (session_id, role, content, created_at, msg_type)
            VALUES ('s-normal', 'user', '今天过得怎么样', '2026-08-09 12:00', 'chat');
    """)
    await conn.commit()
    yield conn
    await conn.close()


async def _kinds(db):
    cursor = await db.execute("SELECT id, kind, title FROM chat_sessions")
    return {row[0]: (row[1], row[2]) for row in await cursor.fetchall()}


async def test_v24_adds_kind_column(bare_db):
    await _migrate_v24(bare_db)
    cursor = await bare_db.execute("PRAGMA table_info(chat_sessions)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert "kind" in cols


async def test_v24_backfills_open_era_as_solo(bare_db):
    """开放 era（还没人接话）→ kind='solo'。"""
    await _migrate_v24(bare_db)
    assert (await _kinds(bare_db))["e-open"][0] == "solo"


async def test_v24_backfills_answered_era_and_normal_as_chat(bare_db):
    """被接话的 era 与冷冷自己开的会话都保持 'chat'。"""
    await _migrate_v24(bare_db)
    kinds = await _kinds(bare_db)
    assert kinds["e-answered"][0] == "chat"
    assert kinds["s-normal"][0] == "chat"


async def test_v24_unifies_era_titles_only(bare_db):
    """era 标题收口为「小汐的碎碎念」，普通会话标题一个字都不动。"""
    await _migrate_v24(bare_db)
    kinds = await _kinds(bare_db)
    assert kinds["e-open"][1] == ERA_SESSION_TITLE
    assert kinds["e-answered"][1] == ERA_SESSION_TITLE
    assert kinds["s-normal"][1] == "今天过得怎么样"


async def test_v24_idempotent(bare_db):
    await _migrate_v24(bare_db)
    await _migrate_v24(bare_db)
    cursor = await bare_db.execute("PRAGMA table_info(chat_sessions)")
    assert len([r for r in await cursor.fetchall() if r[1] == "kind"]) == 1
    assert (await _kinds(bare_db))["e-open"][0] == "solo"
