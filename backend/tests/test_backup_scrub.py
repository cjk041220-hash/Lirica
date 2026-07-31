"""备份脱敏（BACKUP_SCRUB_SENSITIVE）测试。

回归重点：生理期派生事件（events.source='cycle'）必须与 cycle_records
同进同出——它虽只存预测日，仍是生理期的衍生物；冷冷手记的其他事件
（source='user'）不受影响。
"""
import sqlite3
from types import SimpleNamespace
from unittest.mock import patch

import aiosqlite

from database.core import backup_database


async def _make_source_db(path):
    """构造含高敏数据与对照数据的源库。"""
    from database.migrations import CHAT_ARCHIVE_SCHEMA_SQL, EVENTS_SCHEMA_SQL

    conn = await aiosqlite.connect(str(path))
    await conn.executescript("""
        CREATE TABLE cycle_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_date TEXT NOT NULL UNIQUE, note TEXT
        );
    """)
    await conn.executescript(EVENTS_SCHEMA_SQL)
    await conn.executescript(CHAT_ARCHIVE_SCHEMA_SQL)
    await conn.execute(
        "INSERT INTO cycle_records (start_date, note) VALUES ('2026-07-18', '波动较大')"
    )
    await conn.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, severity,
            source, memo, status, created_at, dedupe_key)
           VALUES ('c1', '身体的日子', 'health', '2026-08-16', '2026-08-18',
                   'normal', 'cycle', '推算的关心', 'active',
                   '2026-07-31 10:00', 'cycle:2026-08-16')"""
    )
    await conn.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, severity,
            source, memo, status, created_at, dedupe_key)
           VALUES ('u1', '看牙医', 'health', '2026-08-20', NULL,
                   'normal', 'user', NULL, 'active',
                   '2026-07-31 10:00', 'llm:看牙医:2026-08-20')"""
    )
    await conn.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at) "
        "VALUES ('s1', '聊天', '2026-07-31 10:00', '2026-07-31 10:00')"
    )
    await conn.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s1', 'user', '你好', '2026-07-31 10:00')"
    )
    await conn.commit()
    await conn.close()


def _query(path, sql):
    conn = sqlite3.connect(str(path))
    try:
        return conn.execute(sql).fetchall()
    finally:
        conn.close()


async def test_scrub_removes_cycle_and_archive_keeps_user_events(tmp_path):
    """开启脱敏：cycle_records、cycle 派生事件、对话档案全剔；user 事件保留。"""
    src = tmp_path / "src.db"
    await _make_source_db(src)
    fake_settings = SimpleNamespace(backup_scrub_sensitive=True)

    with patch("config.settings.get_settings", return_value=fake_settings):
        backup_path = await backup_database(
            source_path=src, backup_dir=tmp_path / "backups"
        )

    assert backup_path is not None and backup_path.exists()
    assert _query(backup_path, "SELECT COUNT(*) FROM cycle_records")[0][0] == 0
    assert _query(backup_path, "SELECT COUNT(*) FROM events WHERE source = 'cycle'")[0][0] == 0
    assert _query(backup_path, "SELECT COUNT(*) FROM chat_messages")[0][0] == 0
    assert _query(backup_path, "SELECT COUNT(*) FROM chat_sessions")[0][0] == 0
    # 冷冷手记的事件不受脱敏影响
    rows = _query(backup_path, "SELECT id FROM events WHERE source = 'user'")
    assert [r[0] for r in rows] == ["u1"]


async def test_no_scrub_by_default_keeps_everything(tmp_path):
    """默认关闭：备份是完整快照。"""
    src = tmp_path / "src.db"
    await _make_source_db(src)
    fake_settings = SimpleNamespace(backup_scrub_sensitive=False)

    with patch("config.settings.get_settings", return_value=fake_settings):
        backup_path = await backup_database(
            source_path=src, backup_dir=tmp_path / "backups"
        )

    assert _query(backup_path, "SELECT COUNT(*) FROM cycle_records")[0][0] == 1
    assert _query(backup_path, "SELECT COUNT(*) FROM events WHERE source = 'cycle'")[0][0] == 1
    assert _query(backup_path, "SELECT COUNT(*) FROM chat_messages")[0][0] == 1
