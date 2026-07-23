"""Checkpoint 裁剪测试 — 新 schema 兼容 + 非活跃线程清理（P1-1 后遗症）。"""
import time
import uuid

import aiosqlite

from database.core import prune_checkpoints


def _ck_id(ms: int) -> str:
    """构造 uuid7 形态的 checkpoint_id：前 12 位十六进制 = unix 毫秒。"""
    return f"{ms:012x}{uuid.uuid4().hex[:20]}"


async def _make_db(tmp_path):
    db = tmp_path / "checkpoints.db"
    async with aiosqlite.connect(str(db)) as conn:
        # langgraph-checkpoint-sqlite 3.x schema（表名为 writes 而非 checkpoint_writes）
        await conn.execute("""
            CREATE TABLE checkpoints (
                thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                parent_checkpoint_id TEXT, type TEXT, checkpoint BLOB, metadata BLOB
            )
        """)
        await conn.execute("""
            CREATE TABLE writes (
                thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                task_id TEXT, idx INTEGER, channel TEXT, type TEXT, value BLOB
            )
        """)

        now_ms = int(time.time() * 1000)
        old_ms = 1_000_000  # 1970 年——远超任何保留期

        # 活跃线程：当前时间
        for i in range(3):
            await conn.execute(
                "INSERT INTO checkpoints VALUES ('active', '', ?, NULL, NULL, NULL, NULL)",
                (_ck_id(now_ms + i),),
            )
        # 僵尸线程：最后活动于 1970 年（前端一次性 session id 时代遗物）
        await conn.execute(
            "INSERT INTO checkpoints VALUES ('zombie', '', ?, NULL, NULL, NULL, NULL)",
            (_ck_id(old_ms),),
        )
        # 僵尸线程的孤儿 writes
        await conn.execute(
            "INSERT INTO writes VALUES ('zombie', '', ?, 't', 0, 'c', NULL, NULL)",
            (_ck_id(old_ms),),
        )
        await conn.commit()
    return db


async def test_prune_drops_inactive_thread(tmp_path):
    """超过 drop_inactive_days 未活动的整个 thread 被删除。"""
    db = await _make_db(tmp_path)

    deleted = await prune_checkpoints(db_path=db, drop_inactive_days=30)
    assert deleted >= 1  # zombie 线程的 checkpoint

    async with aiosqlite.connect(str(db)) as conn:
        cursor = await conn.execute(
            "SELECT COUNT(*) FROM checkpoints WHERE thread_id = 'zombie'"
        )
        (zombie_left,) = await cursor.fetchone()
        assert zombie_left == 0

        cursor = await conn.execute(
            "SELECT COUNT(*) FROM checkpoints WHERE thread_id = 'active'"
        )
        (active_left,) = await cursor.fetchone()
        assert active_left == 3  # 活跃线程不受影响


async def test_prune_cleans_orphan_writes_new_schema(tmp_path):
    """新 schema 的 writes 表中孤儿行被清理，且不报 no such table。"""
    db = await _make_db(tmp_path)

    await prune_checkpoints(db_path=db, drop_inactive_days=30)

    async with aiosqlite.connect(str(db)) as conn:
        cursor = await conn.execute(
            "SELECT COUNT(*) FROM writes WHERE thread_id = 'zombie'"
        )
        (orphan_left,) = await cursor.fetchone()
        assert orphan_left == 0


async def test_prune_inactive_disabled(tmp_path):
    """drop_inactive_days=0 时保留全部线程（关闭策略）。"""
    db = await _make_db(tmp_path)

    await prune_checkpoints(db_path=db, drop_inactive_days=0)

    async with aiosqlite.connect(str(db)) as conn:
        cursor = await conn.execute(
            "SELECT COUNT(*) FROM checkpoints WHERE thread_id = 'zombie'"
        )
        (zombie_left,) = await cursor.fetchone()
        assert zombie_left == 1
