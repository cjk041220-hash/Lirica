"""删除会话时的 checkpoint 线程清理 — delete_checkpoint_thread。

删会话只清档案不清线程的老问题：thread 留在 checkpoints.db 里等 30 天
不活跃策略，期间冷冷再用同一个 session_id 说话，小汐会带着「这段对话
从没被删过」的完整上下文接话，而界面上历史是空的。

覆盖：
- 目标线程的 checkpoint 与其 writes 被清空，别的线程分毫不动
- 旧 schema（checkpoint_writes / checkpoint_blobs）同样适配
- 库不存在 / 无 checkpoints 表 → 静默返回 0，不抛
- 清理异常不冒泡（清理是旁路，档案已经删干净了）
"""
import aiosqlite
import pytest

from database.core import delete_checkpoint_thread


async def _make_db(tmp_path, *, writes_table="writes", with_blobs=False):
    db = tmp_path / "checkpoints.db"
    async with aiosqlite.connect(str(db)) as conn:
        await conn.execute("""
            CREATE TABLE checkpoints (
                thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                parent_checkpoint_id TEXT, type TEXT, checkpoint BLOB, metadata BLOB
            )
        """)
        if writes_table == "writes":
            await conn.execute("""
                CREATE TABLE writes (
                    thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                    task_id TEXT, idx INTEGER, channel TEXT, type TEXT, value BLOB
                )
            """)
        else:
            await conn.execute("""
                CREATE TABLE checkpoint_writes (
                    thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                    task_id TEXT, idx INTEGER, channel TEXT, type TEXT, value BLOB
                )
            """)
        if with_blobs:
            await conn.execute("""
                CREATE TABLE checkpoint_blobs (
                    thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                    channel TEXT, type TEXT, blob BLOB
                )
            """)

        for tid in ("s-target", "s-other"):
            for i in range(2):
                cid = f"c-{tid}-{i}"
                await conn.execute(
                    "INSERT INTO checkpoints VALUES (?, '', ?, NULL, NULL, NULL, NULL)",
                    (tid, cid),
                )
                await conn.execute(
                    f"INSERT INTO {writes_table} VALUES (?, '', ?, 't', 0, 'c', NULL, NULL)",
                    (tid, cid),
                )
                if with_blobs:
                    await conn.execute(
                        "INSERT INTO checkpoint_blobs VALUES (?, '', ?, 'c', NULL, NULL)",
                        (tid, cid),
                    )
        await conn.commit()
    return db


async def _count(db, table, thread_id):
    async with aiosqlite.connect(str(db)) as conn:
        cursor = await conn.execute(
            f"SELECT COUNT(*) FROM {table} WHERE thread_id = ?", (thread_id,)
        )
        (n,) = await cursor.fetchone()
        return n


async def test_delete_thread_clears_checkpoints_and_writes(tmp_path):
    """目标线程的 checkpoint + writes 清空，别的线程一个不动。"""
    db = await _make_db(tmp_path)

    deleted = await delete_checkpoint_thread("s-target", db_path=db)
    assert deleted == 2

    assert await _count(db, "checkpoints", "s-target") == 0
    assert await _count(db, "writes", "s-target") == 0
    assert await _count(db, "checkpoints", "s-other") == 2
    assert await _count(db, "writes", "s-other") == 2


async def test_delete_thread_supports_legacy_schema(tmp_path):
    """旧 schema（checkpoint_writes + checkpoint_blobs）同样清干净。"""
    db = await _make_db(tmp_path, writes_table="checkpoint_writes", with_blobs=True)

    assert await delete_checkpoint_thread("s-target", db_path=db) == 2
    assert await _count(db, "checkpoint_writes", "s-target") == 0
    assert await _count(db, "checkpoint_blobs", "s-target") == 0
    assert await _count(db, "checkpoints", "s-other") == 2


async def test_delete_thread_missing_db_is_noop(tmp_path):
    """库不存在 → 0，不抛（全新安装还没写过 checkpoint 是常态）。"""
    assert await delete_checkpoint_thread("s-x", db_path=tmp_path / "nope.db") == 0


async def test_delete_thread_without_checkpoints_table(tmp_path):
    """空库（表还没建）→ 0，不抛。"""
    db = tmp_path / "empty.db"
    async with aiosqlite.connect(str(db)) as conn:
        await conn.execute("CREATE TABLE unrelated (id INTEGER)")
        await conn.commit()
    assert await delete_checkpoint_thread("s-x", db_path=db) == 0


async def test_delete_thread_failure_is_swallowed(tmp_path, monkeypatch):
    """清理失败不冒泡：档案已删，不该让会话删除整体失败。"""
    db = await _make_db(tmp_path)

    # aiosqlite.connect 本身是同步函数（返回可作 async 上下文管理器的
    # Connection），补丁也得是同步的，否则留给调用方一个没人 await 的
    # 协程对象，除污染告警外还掩盖了真实异常
    def boom(*a, **kw):
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(aiosqlite, "connect", boom)
    assert await delete_checkpoint_thread("s-target", db_path=db) == 0


@pytest.mark.parametrize("thread_id", ["s-none", ""])
async def test_delete_unknown_thread_returns_zero(tmp_path, thread_id):
    """线程本就没有 checkpoint → 0（不是错误）。"""
    db = await _make_db(tmp_path)
    assert await delete_checkpoint_thread(thread_id, db_path=db) == 0
