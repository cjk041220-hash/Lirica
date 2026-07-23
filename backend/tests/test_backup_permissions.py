"""备份权限收紧测试（#21）。

备份含记忆、日记、生理期记录等高敏数据：
目录应为 700、文件应为 600（仅属主可读写）。
"""
import os
import sqlite3
import stat

import pytest

from database.core import backup_database, restrict_backup_permissions


def _make_source_db(path):
    """造一个最小 SQLite 源库。"""
    conn = sqlite3.connect(str(path))
    conn.execute("CREATE TABLE t (id INTEGER)")
    conn.commit()
    conn.close()


@pytest.mark.skipif(os.name != "posix", reason="Windows 下 chmod 语义有限，跳过权限断言")
async def test_backup_dir_and_file_permissions(tmp_path):
    """备份目录 700、备份文件 600。"""
    src = tmp_path / "userdata.db"
    _make_source_db(src)
    backup_dir = tmp_path / "backups"

    result = await backup_database(source_path=src, backup_dir=backup_dir)
    assert result is not None and result.exists()

    dir_mode = stat.S_IMODE(os.stat(backup_dir).st_mode)
    file_mode = stat.S_IMODE(os.stat(result).st_mode)
    assert dir_mode == 0o700
    assert file_mode == 0o600


async def test_backup_creates_valid_copy(tmp_path):
    """备份文件是有效的 SQLite 副本（权限收紧不破坏内容）。"""
    src = tmp_path / "userdata.db"
    conn = sqlite3.connect(str(src))
    conn.execute("CREATE TABLE memories (id TEXT, content TEXT)")
    conn.execute("INSERT INTO memories VALUES ('m1', '芒果')")
    conn.commit()
    conn.close()

    backup_dir = tmp_path / "backups"
    result = await backup_database(source_path=src, backup_dir=backup_dir)
    assert result is not None

    check = sqlite3.connect(str(result))
    rows = check.execute("SELECT content FROM memories").fetchall()
    check.close()
    assert rows == [("芒果",)]


async def test_backup_missing_source_returns_none(tmp_path):
    """源库不存在时返回 None，不抛异常。"""
    result = await backup_database(
        source_path=tmp_path / "nope.db", backup_dir=tmp_path / "backups"
    )
    assert result is None


def test_restrict_permissions_swallows_errors(tmp_path):
    """目标不存在时静默处理，不影响主流程。"""
    restrict_backup_permissions(tmp_path / "missing_file")
    restrict_backup_permissions(tmp_path / "missing_dir", is_dir=True)
