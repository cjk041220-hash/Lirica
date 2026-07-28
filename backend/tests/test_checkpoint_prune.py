"""Checkpoint 裁剪测试 — 新 schema 兼容 + 非活跃线程清理（P1-1 后遗症）。"""
import time
import uuid

import aiosqlite

from database.core import prune_checkpoints


# 与 langgraph/checkpoint/base/id.py 的 uuid6() 同一时间域
_GREGORIAN_OFFSET = 0x01B21DD213814000


def _uuid6_at(unix_ms: int, node: int = 1) -> str:
    """按 langgraph uuid6 的位布局构造指定时间的 checkpoint_id。

    官方 uuid6() 只读 time_ns()、不接受指定时间戳，测试夹具只能手拼：
    timestamp = unix 100ns 间隔数 + 格里高利 epoch 偏移；UUID 高 48 位 =
    timestamp >> 12，低 12 位 = timestamp & 0xFFF；最后 UUID(version=6)
    覆写版本/变体位——与官方实现逐位一致。
    """
    ts = unix_ms * 10_000 + _GREGORIAN_OFFSET
    uuid_int = ((ts >> 12) & 0xFFFFFFFFFFFF) << 80
    uuid_int |= (ts & 0x0FFF) << 64
    uuid_int |= node & 0xFFFFFFFFFFFF
    # 手工置 version=6 与 RFC 4122 variant 位——与 langgraph 打包的
    # uuid6-python UUID(version=6) 同布局（stdlib uuid.UUID 只收 v1-5）
    uuid_int &= ~(0xC000 << 48)
    uuid_int |= 0x8000 << 48
    uuid_int &= ~(0xF000 << 64)
    uuid_int |= 6 << 76
    return str(uuid.UUID(int=uuid_int))


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
                (_uuid6_at(now_ms + i, node=i + 1),),
            )
        # 僵尸线程：最后活动于 1970 年（前端一次性 session id 时代遗物）
        await conn.execute(
            "INSERT INTO checkpoints VALUES ('zombie', '', ?, NULL, NULL, NULL, NULL)",
            (_uuid6_at(old_ms),),
        )
        # 僵尸线程的孤儿 writes
        await conn.execute(
            "INSERT INTO writes VALUES ('zombie', '', ?, 't', 0, 'c', NULL, NULL)",
            (_uuid6_at(old_ms),),
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


# ── 漂移防线：与 langgraph 真实 uuid6 的互操作性 ────────────────

def test_uuid6_at_time_domain_matches_real():
    """手拼夹具与真实 uuid6() 的前缀必须在同一时间域。

    旧夹具伪造 unix 毫秒前缀（uuid7 假设），与 langgraph 实际生成的
    uuid6 相差约 16 个时间数量级，测试全绿但生产清理恒为假。
    此测试把手拼公式钉死在 langgraph 的真实编码上。
    """
    from langgraph.checkpoint.base.id import uuid6

    fake = int(_uuid6_at(int(time.time() * 1000)).replace("-", "")[:12], 16)
    real = int(str(uuid6()).replace("-", "")[:12], 16)
    # 同一瞬间的前缀只允许亚毫秒抖动（10000 个 100ns 单位 >> 12 ≈ 2.44）
    assert abs(fake - real) <= 3


async def test_prune_preserves_real_langgraph_uuid6(tmp_path):
    """漂移哨兵：langgraph 真实生成的 uuid6 id 绝不被误删。

    一旦 langgraph 更换 id 方案，或 core.py 的阈值公式与官方布局
    漂移，此测试当场红——而不是线上静默失效（或静默误删）重演。
    """
    from langgraph.checkpoint.base.id import uuid6

    db = tmp_path / "checkpoints.db"
    async with aiosqlite.connect(str(db)) as conn:
        await conn.execute("""
            CREATE TABLE checkpoints (
                thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                parent_checkpoint_id TEXT, type TEXT, checkpoint BLOB, metadata BLOB
            )
        """)
        await conn.execute("""
            CREATE TABLE writes (
                thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT,
                task_id TEXT, idx INTEGER, channel TEXT, value BLOB
            )
        """)
        await conn.execute(
            "INSERT INTO checkpoints VALUES ('real', '', ?, NULL, NULL, NULL, NULL)",
            (str(uuid6()),),
        )
        await conn.commit()

    await prune_checkpoints(db_path=db, drop_inactive_days=30)

    async with aiosqlite.connect(str(db)) as conn:
        cursor = await conn.execute(
            "SELECT COUNT(*) FROM checkpoints WHERE thread_id = 'real'"
        )
        (left,) = await cursor.fetchone()
        assert left == 1  # 刚生成的真实 id 必须被认定为活跃
