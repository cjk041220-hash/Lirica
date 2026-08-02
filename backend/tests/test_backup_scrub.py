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


# ── VACUUM 字节级清页（体检 T1-4：DELETE 不 VACUUM = 脱敏名不副实）──

_MARKER = "SECRET_MARKER_XYZZY_123"  # 纯 ASCII，避免对 UTF-8 分页边界打赌


async def _make_db_with_marker(path):
    """造含唯一标记字符串的高敏数据源库（cycle 备注 + 对话档案双处植入）。"""
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
        "INSERT INTO cycle_records (start_date, note) VALUES ('2026-07-18', ?)",
        (_MARKER,),
    )
    await conn.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at) "
        "VALUES ('s1', ?, '2026-07-31 10:00', '2026-07-31 10:00')",
        (_MARKER,),
    )
    await conn.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s1', 'user', ?, '2026-07-31 10:00')",
        (_MARKER,),
    )
    await conn.commit()
    await conn.close()


async def test_scrub_vacuum_purges_marker_bytes_from_file(tmp_path):
    """T1-4 核心回归：脱敏后 VACUUM 重写文件，marker 字节不可捞回。

    修复前 DELETE 只标记页可复用，数据页原文仍在文件里——十六进制
    编辑器直接捞回「已脱敏」的生理期数据。
    """
    src = tmp_path / "src.db"
    await _make_db_with_marker(src)
    fake_settings = SimpleNamespace(backup_scrub_sensitive=True)

    with patch("config.settings.get_settings", return_value=fake_settings):
        backup_path = await backup_database(
            source_path=src, backup_dir=tmp_path / "backups"
        )

    assert backup_path is not None and backup_path.exists()
    # SQL 层确实删了
    assert _query(backup_path, "SELECT COUNT(*) FROM cycle_records")[0][0] == 0
    # 字节层也清干净了（VACUUM 的功劳）
    assert _MARKER.encode() not in backup_path.read_bytes()


async def test_no_scrub_marker_bytes_remain_in_file(tmp_path):
    """对照钉：不脱敏时 marker 字节存在于文件中——证明上一条测的是
    VACUUM 在起作用，而不是备份 API 碰巧没拷进去。"""
    src = tmp_path / "src.db"
    await _make_db_with_marker(src)
    fake_settings = SimpleNamespace(backup_scrub_sensitive=False)

    with patch("config.settings.get_settings", return_value=fake_settings):
        backup_path = await backup_database(
            source_path=src, backup_dir=tmp_path / "backups"
        )

    assert _MARKER.encode() in backup_path.read_bytes()


# ── P0-04 回归：脱敏失败必须 fail-closed ──

class _FailingScrubConn:
    """脱敏连接替身：任何 SQL 都抛异常（模拟 DELETE/VACUUM 阶段失败）。

    注：aiosqlite.connect 是同步函数返回带 __aenter__ 的连接（实测
    iscoroutinefunction=False），注入替身必须是同形态的同步对象——
    此前误写成 async 函数，第一个连接就坏，测试假阳性地没走到脱敏路径。
    """

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, *args, **kwargs):
        raise RuntimeError("simulated scrub failure")


async def test_scrub_failure_removes_unscrubbed_backup(tmp_path):
    """脱敏开启且删除/VACUUM 阶段失败：本次未脱敏备份必须删除、返回
    None——修复前只记 warning，完整库照常保留并宣称「备份已保存」，
    与开启脱敏的语义直接冲突（完整对话档案被同步去网盘/他人）。"""
    src = tmp_path / "src.db"
    await _make_source_db(src)
    bdir = tmp_path / "backups"
    fake_settings = SimpleNamespace(backup_scrub_sensitive=True)

    real_connect = aiosqlite.connect
    calls = {"n": 0}

    def _flaky_connect(path, *args, **kwargs):
        # 前两次调用是 Online Backup 的 src/dst 连接；第三次是脱敏连接
        calls["n"] += 1
        if calls["n"] == 3:
            return _FailingScrubConn()
        return real_connect(path, *args, **kwargs)

    with patch("config.settings.get_settings", return_value=fake_settings), \
         patch("aiosqlite.connect", side_effect=_flaky_connect):
        result = await backup_database(source_path=src, backup_dir=bdir)

    assert result is None
    # 未脱敏副本已成功拷出、随后必须被删除——不得留下任何文件
    # （含可能残留的 -wal/-shm sidecar）
    assert list(bdir.glob("userdata_backup_*")) == []
