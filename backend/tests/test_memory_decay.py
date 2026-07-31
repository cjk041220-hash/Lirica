"""记忆分层衰减测试（B2）：晋升 core / 归档 archived / 读面 / 保底席位 / 维护隔离。"""
import datetime
from unittest.mock import AsyncMock, patch

from utils.timezone import HKT, _TIME_FMT

from memory.narrative import NarrativeBuilder


def _days_ago(n: int) -> str:
    return (datetime.datetime.now(HKT) - datetime.timedelta(days=n)).strftime(_TIME_FMT)


async def _insert_raw(db, mid, content, category, created_at, access=0, status="active"):
    await db.execute(
        """INSERT INTO memories
           (id, content, category, created_at, last_used_at, access_count, status)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        (mid, content, category, created_at, created_at, access, status),
    )
    await db.commit()


# ── apply_status_decay 本体 ───────────────────────────────────


async def test_decay_promotes_core(memory_mgr, tmp_db):
    """access≥5 且 ≥30 天 → 晋升 core。"""
    await _insert_raw(tmp_db, "m1", "冷冷喜欢喝椰子水", "核心偏好", _days_ago(40), access=6)
    result = await memory_mgr.apply_status_decay()
    assert result == {"promoted": 1, "archived": 0}
    cursor = await tmp_db.execute("SELECT status FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == "core"


async def test_decay_archives_stale(memory_mgr, tmp_db):
    """access≤1 且 ≥90 天 → 归档冷藏。"""
    await _insert_raw(tmp_db, "m1", "某天随口的事", "日常", _days_ago(100), access=1)
    result = await memory_mgr.apply_status_decay()
    assert result == {"promoted": 0, "archived": 1}
    cursor = await tmp_db.execute("SELECT status FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == "archived"


async def test_decay_boundaries_no_action(memory_mgr, tmp_db):
    """阈值边界：年龄不够不晋升；次数居中（2~4）既不晋升也不归档。"""
    await _insert_raw(tmp_db, "young", "新晋高频", "喜好", _days_ago(20), access=10)
    await _insert_raw(tmp_db, "mid", "不高不低", "日常", _days_ago(100), access=3)
    result = await memory_mgr.apply_status_decay()
    assert result == {"promoted": 0, "archived": 0}


async def test_decay_core_not_demoted(memory_mgr, tmp_db):
    """core 不降级：已是 core 的行即使长期零访问也不动。"""
    await _insert_raw(tmp_db, "m1", "核心", "核心偏好", _days_ago(200), access=0, status="core")
    result = await memory_mgr.apply_status_decay()
    assert result == {"promoted": 0, "archived": 0}
    cursor = await tmp_db.execute("SELECT status FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == "core"


async def test_decay_promote_before_archive_exclusive(memory_mgr, tmp_db):
    """阈值互斥：同批里该升的升、该归的归，互不干扰。"""
    await _insert_raw(tmp_db, "up", "高频", "喜好", _days_ago(60), access=7)
    await _insert_raw(tmp_db, "down", "冷门", "日常", _days_ago(120), access=0)
    result = await memory_mgr.apply_status_decay()
    assert result == {"promoted": 1, "archived": 1}


# ── v10 三同步:晋升后 add 去重仍生效 ─────────────────────────


async def test_add_dedupes_against_core_row(memory_mgr, tmp_db):
    """钉死 v10 三同步：core 行参与 ON CONFLICT 去重，add 不产生双行、
    读回不 IndexError（谓词/推断子句/读回 SELECT 任一处漏改都会炸）。"""
    await _insert_raw(tmp_db, "m1", "冷冷喜欢喝椰子水", "核心偏好", _days_ago(40), access=6)
    await memory_mgr.apply_status_decay()  # m1 → core

    got = await memory_mgr.add("核心偏好", "冷冷喜欢喝椰子水")
    assert got["id"] == "m1"               # 刷的是 core 行本身，不是新行
    assert got["status"] == "core"

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 1


async def test_add_after_archive_starts_new_life(memory_mgr, tmp_db):
    """归档行释放唯一槽位：同内容重提插新行（忘了又想起）。"""
    await _insert_raw(tmp_db, "m1", "冷冷在学吉他", "喜好", _days_ago(100), access=0)
    await memory_mgr.apply_status_decay()  # m1 → archived

    got = await memory_mgr.add("喜好", "冷冷在学吉他")
    assert got["id"] != "m1"
    assert got["status"] == "active"

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 2


# ── 读面:archived 消失,core 在场 ─────────────────────────────


async def test_reads_include_core_exclude_archived(memory_mgr, tmp_db):
    """recall / recent / count / all / list_categories 统一谓词。"""
    await _insert_raw(tmp_db, "a", "活跃记忆", "日常", _days_ago(1), status="active")
    await _insert_raw(tmp_db, "c", "核心记忆", "喜好", _days_ago(50), access=9, status="core")
    await _insert_raw(tmp_db, "x", "冷藏记忆", "日常", _days_ago(120), status="archived")

    assert await memory_mgr.count() == 2
    assert {m["id"] for m in await memory_mgr.recall(passive=True)} == {"a", "c"}
    assert {m["id"] for m in await memory_mgr.recent()} == {"a", "c"}
    assert {m["id"] for m in await memory_mgr.all()} == {"a", "c"}
    cats = await memory_mgr.list_categories()
    assert cats == {"日常": 1, "喜好": 1}
    # 导出也不含 archived
    assert "冷藏记忆" not in await memory_mgr.export_json()


async def test_top_core_recent_first(memory_mgr, tmp_db):
    """top_core 按最近使用排序，纯只读不刷热度。"""
    await _insert_raw(tmp_db, "c1", "旧核心", "喜好", _days_ago(60), access=9, status="core")
    await _insert_raw(tmp_db, "c2", "新核心", "喜好", _days_ago(35), access=6, status="core")
    await tmp_db.execute(
        "UPDATE memories SET last_used_at = ? WHERE id = 'c2'", (_days_ago(2),)
    )
    await tmp_db.commit()

    top = await memory_mgr.top_core(limit=1)
    assert [m["id"] for m in top] == ["c2"]
    cursor = await tmp_db.execute("SELECT access_count FROM memories WHERE id = 'c2'")
    assert (await cursor.fetchone())[0] == 6  # 未被刷


# ── narrative 保底席位 ────────────────────────────────────────


async def test_narrative_reserves_core_seat(memory_mgr, tmp_db):
    """5 条新流水占满配额时，core 记忆保底在场（替换末位）。"""
    await _insert_raw(tmp_db, "core1", "冷冷对芒果过敏", "重要", _days_ago(80), access=12, status="core")
    for i in range(5):
        await _insert_raw(tmp_db, f"n{i}", f"流水账 {i}", "日常", _days_ago(0))

    builder = NarrativeBuilder(memory_mgr)
    text = await builder.build(max_items=5)
    assert "芒果过敏" in text
    assert text.count("\n- ") == 5          # 配额未被突破


async def test_narrative_no_core_no_reserve(memory_mgr, tmp_db):
    """没有 core 记忆时保底逻辑静默不触发。"""
    for i in range(3):
        await _insert_raw(tmp_db, f"n{i}", f"流水账 {i}", "日常", _days_ago(0))
    builder = NarrativeBuilder(memory_mgr)
    text = await builder.build(max_items=5)
    assert text.count("\n- ") == 3


# ── 心跳维护隔离 ──────────────────────────────────────────────


async def test_periodic_maintenance_decay_isolated():
    """衰减段抛异常不牵连 checkpoint 裁剪与备份（三段隔离回归）。"""
    import heartbeat

    with patch("database.core.prune_checkpoints", new=AsyncMock()) as prune, \
         patch("database.core.backup_database", new=AsyncMock()) as backup, \
         patch("memory.manager.run_status_decay", new=AsyncMock(side_effect=RuntimeError("boom"))), \
         patch("memory.extract.extract_new_memories", new=AsyncMock()) as extract_new, \
         patch("memory.extract.check_and_report_memory_health", new=AsyncMock()) as health:
        await heartbeat._periodic_maintenance()  # 不抛

    prune.assert_awaited_once()
    backup.assert_awaited_once()
    extract_new.assert_awaited_once()   # 提取段独立于衰减段，照常执行
    health.assert_awaited_once()


async def test_periodic_maintenance_runs_decay():
    """维护任务确实调用分层衰减。"""
    import heartbeat

    with patch("database.core.prune_checkpoints", new=AsyncMock()), \
         patch("database.core.backup_database", new=AsyncMock()), \
         patch("memory.manager.run_status_decay", new=AsyncMock(
             return_value={"promoted": 0, "archived": 0})) as decay, \
         patch("memory.extract.extract_new_memories", new=AsyncMock()), \
         patch("memory.extract.check_and_report_memory_health", new=AsyncMock()):
        await heartbeat._periodic_maintenance()

    decay.assert_awaited_once()
