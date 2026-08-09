"""迁移 v21 回归 — 主动消息独立会话（era 时间线）四表补列。

自建 v20 时代裸库，测迁移 SQL 本身；era 路由 / 三写一推的语义由
test_era.py 与 test_heartbeat.py 钉死。重点：
- 四表新列（proactive_messages.session_id / chat_sessions.is_era /
  chat_messages.msg_type / life_state.last_seen_at + active_session_id）
- 旧积压清账：v20 时代 delivered/stored 未读行 → 全部 read（防升级后
  把无归属积压再次注入当前对话）
- 幂等：重跑不报错、不清账已读行
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v21

_V20_DDL = """
CREATE TABLE proactive_messages (
    id              TEXT PRIMARY KEY,
    content         TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    delivered_at    TEXT,
    read_at         TEXT,
    status          TEXT DEFAULT 'pending'
);
CREATE TABLE chat_sessions (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL DEFAULT '',
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE TABLE chat_messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,
    role        TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content     TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
CREATE TABLE life_state (
    id              INTEGER PRIMARY KEY DEFAULT 1,
    mood            TEXT,
    daily_event     TEXT,
    updated_at      TEXT,
    first_met_on    TEXT,
    mood_changed_at TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v21.db"))
    await conn.executescript(_V20_DDL)
    # 旧积压种子：delivered 未读 + stored 未读 + 已读 + pending 各一条
    await conn.executescript(
        """
        INSERT INTO proactive_messages (id, content, created_at, status)
        VALUES
            ('old_delivered', '早安，冷冷。', '2026-08-08 08:00', 'delivered'),
            ('old_stored',    '午安呀，冷冷。', '2026-08-08 12:00', 'stored'),
            ('old_read',      '晚上好，冷冷。', '2026-08-08 20:00', 'read'),
            ('old_pending',   '想你啦。', '2026-08-09 06:00', 'pending');
        INSERT INTO chat_sessions (id, title, created_at, updated_at)
        VALUES ('s-test1', '旧会话', '2026-08-01 10:00', '2026-08-08 20:00');
        INSERT INTO chat_messages (session_id, role, content, created_at)
        VALUES ('s-test1', 'user', '你好呀', '2026-08-01 10:00');
        INSERT INTO life_state (id, mood, daily_event, updated_at)
        VALUES (1, '开心', '映海很安静', '2026-08-09 06:00');
        """
    )
    await conn.commit()
    yield conn
    await conn.close()


async def test_v21_adds_all_columns(bare_db):
    await _migrate_v21(bare_db)

    cursor = await bare_db.execute("PRAGMA table_info(proactive_messages)")
    assert "session_id" in {row[1] for row in await cursor.fetchall()}
    cursor = await bare_db.execute("PRAGMA table_info(chat_sessions)")
    assert "is_era" in {row[1] for row in await cursor.fetchall()}
    cursor = await bare_db.execute("PRAGMA table_info(chat_messages)")
    assert "msg_type" in {row[1] for row in await cursor.fetchall()}
    cursor = await bare_db.execute("PRAGMA table_info(life_state)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert "last_seen_at" in cols
    assert "active_session_id" in cols

    # 索引
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_proactive_session'"
    )
    assert await cursor.fetchone() is not None


async def test_v21_clears_legacy_unread_backlog(bare_db):
    """旧积压清账：升级前的 delivered/stored 未读全部标已读。"""
    await _migrate_v21(bare_db)

    cursor = await bare_db.execute(
        "SELECT id, status FROM proactive_messages ORDER BY id"
    )
    rows = {r[0]: r[1] for r in await cursor.fetchall()}
    assert rows["old_delivered"] == "read"  # 未读积压 → 清账
    assert rows["old_stored"] == "read"     # 未读积压 → 清账
    assert rows["old_read"] == "read"       # 已读行不受影响
    assert rows["old_pending"] == "pending"  # pending 不属「积压」，不动


async def test_v21_idempotent(bare_db):
    """重跑迁移不报错，且不清账已读行（read_at 不被二次改写）。"""
    await _migrate_v21(bare_db)
    await _migrate_v21(bare_db)

    cursor = await bare_db.execute(
        "SELECT status FROM proactive_messages WHERE id = 'old_delivered'"
    )
    assert (await cursor.fetchone())[0] == "read"
    cursor = await bare_db.execute("PRAGMA table_info(proactive_messages)")
    assert "session_id" in {row[1] for row in await cursor.fetchall()}
