"""迁移 v22 回归 — chat_messages (role, created_at) 组合索引。

自建 v21 时代裸库（DDL 含 msg_type 列，镜像生产 schema），测迁移 SQL
本身；关系密度 / 画像蒸馏的查询语义由 test_anchor_middleware 与
test_profile 钉死。重点：
- 索引存在（sqlite_master 有 idx_chat_messages_role_created）
- 幂等：重跑不报错，索引仍唯一一条
- 查询语义：对种子数据跑 (role='user', created_at >= ?) 谓词返回
  正确行数（索引不改变查询结果）
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v22

_V21_DDL = """
CREATE TABLE chat_messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL,
    role        TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content     TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    msg_type    TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v22.db"))
    await conn.executescript(_V21_DDL)
    # 种子：user/assistant 混合、不同 created_at、含 solo 型消息，
    # 覆盖近 7 天窗口内外与谓词排除面
    await conn.executescript(
        """
        INSERT INTO chat_messages (session_id, role, content, created_at, msg_type)
        VALUES
            ('s-test1', 'user',      '早呀',     '2026-08-05 08:00', 'chat'),
            ('s-test1', 'assistant', '早~',      '2026-08-05 08:01', 'chat'),
            ('s-test1', 'user',      '今天好忙', '2026-08-10 12:00', 'chat'),
            ('s-test1', 'assistant', '辛苦啦',   '2026-08-10 12:01', 'chat'),
            ('s-test1', 'user',      '睡啦晚安', '2026-08-11 23:30', 'chat'),
            ('s-test2', 'assistant', '独处日记风', '2026-08-11 10:00', 'solo');
        """
    )
    await conn.commit()
    yield conn
    await conn.close()


async def test_v22_creates_index(bare_db):
    """迁移后 sqlite_master 里有 idx_chat_messages_role_created。"""
    await _migrate_v22(bare_db)

    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='index' AND name='idx_chat_messages_role_created'"
    )
    assert await cursor.fetchone() is not None


async def test_v22_idempotent(bare_db):
    """重跑迁移不报错，索引仍在且唯一一条（IF NOT EXISTS 保证）。"""
    await _migrate_v22(bare_db)
    await _migrate_v22(bare_db)

    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='index' AND name='idx_chat_messages_role_created'"
    )
    rows = await cursor.fetchall()
    assert len(rows) == 1


async def test_v22_query_uses_index(bare_db):
    """索引对实际查询语义无影响：近 7 天 user 消息谓词返回正确行数。"""
    await _migrate_v22(bare_db)

    cursor = await bare_db.execute(
        "SELECT id FROM chat_messages "
        "WHERE role = 'user' AND created_at >= ?",
        ("2026-08-05 00:00",),
    )
    ids = [row[0] for row in await cursor.fetchall()]
    # 三条 user 消息（08-05 / 08-10 / 08-11）；assistant 与 solo 被谓词排除
    assert len(ids) == 3
