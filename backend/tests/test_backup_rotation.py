"""备份轮换回归（体检 D-1）：db 轮换同删 -wal/-shm 孤儿 + JSON 备份保留份数。

修复前：轮换 glob 只匹配 userdata_backup_*.db，碰不到 WAL 模式备份
非干净关闭时残留的 -wal/-shm sidecar（生产备份目录实测累积 14 个）；
memories_backup_*.json 完全无轮换。
"""
import os
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import aiosqlite

from database.core import backup_database
from memory.manager import MemoryManager


async def _make_tiny_source(path):
    """造一个可备份的最小源库（scrub 会 DELETE 的三张表都建出来）。"""
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
    await conn.commit()
    await conn.close()


async def test_db_rotation_removes_wal_shm_siblings(tmp_path):
    """淘汰旧 db 备份时同删其 -wal/-shm 兄弟（孤儿成因：备份继承 WAL
    模式位，非干净关闭残留 sidecar）。"""
    src = tmp_path / "src.db"
    await _make_tiny_source(src)
    bdir = tmp_path / "backups"
    bdir.mkdir()

    # 5 份「历史备份」各带 sidecar（mtime 设为很早，确保比新备份旧）
    for i in range(5):
        old = bdir / f"userdata_backup_20260701_01010{i}.db"
        old.write_bytes(b"old")
        Path(str(old) + "-wal").write_bytes(b"wal")
        Path(str(old) + "-shm").write_bytes(b"shm")
        os.utime(old, (1000 + i, 1000 + i))

    fake = SimpleNamespace(backup_scrub_sensitive=False)
    with patch("config.settings.get_settings", return_value=fake):
        await backup_database(source_path=src, backup_dir=bdir)

    # 5 旧 + 1 新 = 6 份 → 留 5 份：最旧的 0 号被淘汰
    dbs = list(bdir.glob("userdata_backup_*.db"))
    assert len(dbs) == 5
    assert not (bdir / "userdata_backup_20260701_010100.db").exists()
    # 核心回归：sidecar 同删（修复前永驻）
    assert not (bdir / "userdata_backup_20260701_010100.db-wal").exists()
    assert not (bdir / "userdata_backup_20260701_010100.db-shm").exists()
    # 较新的 sidecar 不受牵连（只删被淘汰那一份的兄弟）
    assert (bdir / "userdata_backup_20260701_010101.db-wal").exists()


async def test_db_rotation_missing_siblings_tolerated(tmp_path):
    """没有 sidecar 的旧备份淘汰时不报错（missing_ok 语义）。"""
    src = tmp_path / "src.db"
    await _make_tiny_source(src)
    bdir = tmp_path / "backups"
    bdir.mkdir()

    for i in range(5):
        old = bdir / f"userdata_backup_20260701_01010{i}.db"
        old.write_bytes(b"old")
        os.utime(old, (1000 + i, 1000 + i))

    fake = SimpleNamespace(backup_scrub_sensitive=False)
    with patch("config.settings.get_settings", return_value=fake):
        new_path = await backup_database(source_path=src, backup_dir=bdir)

    assert new_path is not None
    assert len(list(bdir.glob("userdata_backup_*.db"))) == 5


async def test_orphan_sidecars_swept_after_backup(tmp_path):
    """P0-05 回归：早已失去基础 .db 的历史孤儿 sidecar（修复前不会
    再进轮换列表、永久累积）在每次备份后被清扫；有主 sidecar 保留。"""
    src = tmp_path / "src.db"
    await _make_tiny_source(src)
    bdir = tmp_path / "backups"
    bdir.mkdir()

    # 孤儿：基础 .db 已不存在（模拟历史遗留，实测生产目录 14 个）
    (bdir / "userdata_backup_20260101_000000.db-wal").write_bytes(b"wal")
    (bdir / "userdata_backup_20260101_000000.db-shm").write_bytes(b"shm")
    # 有主：基础 .db 还在 → sidecar 必须保留
    owned = bdir / "userdata_backup_20260102_000000.db"
    owned.write_bytes(b"old")
    Path(str(owned) + "-wal").write_bytes(b"wal")

    fake = SimpleNamespace(backup_scrub_sensitive=False)
    with patch("config.settings.get_settings", return_value=fake):
        result = await backup_database(source_path=src, backup_dir=bdir)

    assert result is not None
    assert not (bdir / "userdata_backup_20260101_000000.db-wal").exists()
    assert not (bdir / "userdata_backup_20260101_000000.db-shm").exists()
    assert (bdir / "userdata_backup_20260102_000000.db-wal").exists()


async def test_json_backup_rotation_keeps_five(tmp_db, tmp_path):
    """memories_backup_*.json 保留最近 5 份（修复前无轮换无限累积）。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("日常", "记忆")

    # 6 份「历史 JSON 备份」（mtime 早于即将生成的新备份）
    for i in range(6):
        old = tmp_path / f"memories_backup_20260101_01010{i}.json"
        old.write_text("{}", encoding="utf-8")
        os.utime(old, (1000 + i, 1000 + i))

    await mgr.backup_before_clear(backup_dir=tmp_path)  # 第 7 份 → 淘汰最旧 2 份

    remain = list(tmp_path.glob("memories_backup_*.json"))
    assert len(remain) == 5
    assert not (tmp_path / "memories_backup_20260101_010100.json").exists()
    assert not (tmp_path / "memories_backup_20260101_010101.json").exists()
