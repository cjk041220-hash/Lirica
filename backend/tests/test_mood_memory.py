"""情绪核心记忆回归（schema v18 情绪联动）。

「当前情绪」存成一条 core 记忆：只存不浮现（一切浮现/计数读面排除），
UPDATE 走 superseded 链 → 情绪可回放（history / 导出账本）。
"""
import pytest

from memory.manager import (
    MOOD_MEMORY_CATEGORY,
    MOOD_MEMORY_ID,
    MOOD_MEMORY_SOURCE,
)


async def test_upsert_creates_core_row(memory_mgr):
    """首次落情绪：core 行，固定 id，类别/来源/情绪坐标正确。"""
    row = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    assert row["id"] == MOOD_MEMORY_ID
    assert row["status"] == "core"
    assert row["category"] == MOOD_MEMORY_CATEGORY
    assert row["source"] == MOOD_MEMORY_SOURCE
    assert row["content"] == "2026-08-04 10:00 起，小汐的心情是「开心」"
    assert row["valence"] == pytest.approx(0.85)   # MOOD_VECTORS["开心"]
    assert row["arousal"] == pytest.approx(0.55)


async def test_upsert_update_grows_superseded_chain(memory_mgr, tmp_db):
    """第二次换心情：走 update_content——旧说法落 superseded 链，
    created_at 保留首次，行上情绪坐标刷新为新心情。"""
    first = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    await memory_mgr.upsert_mood_memory("担心", now="2026-08-04 16:00")

    cursor = await tmp_db.execute(
        "SELECT content, created_at, valence, arousal FROM memories WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    content, created, valence, arousal = await cursor.fetchone()
    assert content == "2026-08-04 16:00 起，小汐的心情是「担心」"
    assert created == first["created_at"]          # 首次时间保留（可回放锚点）
    assert valence == pytest.approx(-0.55)          # 坐标刷新为新心情
    assert arousal == pytest.approx(0.25)

    history = await memory_mgr.history(MOOD_MEMORY_ID)
    assert len(history) == 1
    assert "开心" in history[0]["content"]
    assert history[0]["status"] == "superseded"
    assert history[0]["superseded_by"] == MOOD_MEMORY_ID


async def test_upsert_same_content_is_noop(memory_mgr, tmp_db):
    """同分钟同心情重复调用 → no-op，不空落历史行。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    again = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    assert again is None
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE superseded_by = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_upsert_chain_capped_five_generations(memory_mgr, tmp_db):
    """换心情 8 次：superseded 链继承 O3 的 5 代上限（更旧压缩为 digest）。"""
    moods = ["开心", "好奇", "期待", "满足", "担心", "困倦", "兴奋", "活泼"]
    for i, m in enumerate(moods):
        await memory_mgr.upsert_mood_memory(m, now=f"2026-08-10 {i:02d}:00")

    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE superseded_by = ? AND status = 'superseded'",
        (MOOD_MEMORY_ID,),
    )
    assert (await cursor.fetchone())[0] <= 5

    cursor = await tmp_db.execute(
        "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert "活泼" in (await cursor.fetchone())[0]   # 当前行是最后一次心情


async def test_mood_row_excluded_from_all_surface_reads(memory_mgr, tmp_db):
    """只存不浮现：recall/recent/top_core/old_memories/recall_by_category
    /list_categories/count/all 全部排除情绪核心记忆。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    normal = await memory_mgr.add("冷冷的信息", "冷冷今天去了映海")

    assert [m["id"] for m in await memory_mgr.recall(passive=True)] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.recent()] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.top_core(limit=5)] == []  # core 席也不占
    assert [m["id"] for m in await memory_mgr.old_memories(min_age_days=0)] == [normal["id"]]
    assert await memory_mgr.recall_by_category(MOOD_MEMORY_CATEGORY) == []
    assert MOOD_MEMORY_CATEGORY not in await memory_mgr.list_categories()
    assert await memory_mgr.count() == 1
    assert [m["id"] for m in await memory_mgr.all()] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.all(include_archived=True)] == [normal["id"]]

    # hit/access 计数器天然不碰它（不被任何读面返回 → _count_hits 不触达）
    cursor = await tmp_db.execute(
        "SELECT hit_count, access_count FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert tuple(await cursor.fetchone()) == (0, 0)


async def test_check_memory_health_ignores_mood_row(memory_mgr):
    """健康检查只数关于冷冷的记忆：只有情绪行时仍是「零记忆」。

    回归：若情绪行计入，「0 记忆」告警恒假；且心情高频更新刷
    updated_at，「记忆停滞」告警也恒假——两条健康防线全废。
    """
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    health = await memory_mgr.check_memory_health()
    assert health["active_count"] == 0
    assert health["days_since_write"] is None


async def test_mood_row_kept_in_export_ledger(memory_mgr):
    """账本面保留：export_json 含情绪核心记忆与 superseded 链（回放源）。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    await memory_mgr.upsert_mood_memory("担心", now="2026-08-04 16:00")
    data = await memory_mgr.export_json(include_superseded=True)
    assert "2026-08-04 16:00 起，小汐的心情是「担心」" in data
    assert "2026-08-04 10:00 起，小汐的心情是「开心」" in data  # 历史链也在


async def test_mood_row_immune_to_status_decay(memory_mgr, tmp_db):
    """core 不降级：情绪记忆再老、零计数也不会被归档。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-01-01 10:00")
    await tmp_db.execute(
        "UPDATE memories SET created_at = '2026-01-01 10:00', "
        "last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    await tmp_db.commit()

    result = await memory_mgr.apply_status_decay()

    assert result["archived"] == 0
    cursor = await tmp_db.execute(
        "SELECT status FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == "core"
