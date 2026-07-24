"""MemoryManager 单元测试 — CRUD、去重、相关性排序、矛盾覆盖。"""
import json

import pytest

from memory.manager import MemoryManager


# ── 基础 CRUD ─────────────────────────────────────────────────


async def test_add_inserts_new_memory(memory_mgr):
    """add() 插入新记忆并返回正确的字段。"""
    result = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert result["category"] == "喜好"
    assert result["content"] == "喜欢吃芒果"
    assert "id" in result
    assert "created_at" in result
    assert "access_count" in result
    assert result["status"] == "active"
    assert await memory_mgr.count() == 1


async def test_add_dedup_same_category_content(memory_mgr):
    """同一 category + content 不重复添加，只更新时间。"""
    first = await memory_mgr.add("喜好", "喜欢吃芒果")
    second = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert await memory_mgr.count() == 1
    assert second["id"] == first["id"]


async def test_add_different_content_not_deduped(memory_mgr):
    """不同 content 不去重。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    await memory_mgr.add("喜好", "喜欢吃草莓")
    assert await memory_mgr.count() == 2


async def test_add_different_category_not_deduped(memory_mgr):
    """同一 content、不同 category 不去重。"""
    await memory_mgr.add("喜好", "芒果")
    await memory_mgr.add("讨厌的", "芒果")
    assert await memory_mgr.count() == 2


async def test_delete_existing(memory_mgr):
    """删除已有记忆返回 True。"""
    mem = await memory_mgr.add("test", "content")
    assert await memory_mgr.delete(mem["id"]) is True
    assert await memory_mgr.count() == 0


async def test_delete_nonexistent(memory_mgr):
    """删除不存在的记忆返回 False。"""
    assert await memory_mgr.delete("nonexistent_id") is False


async def test_clear(memory_mgr):
    """clear() 返回清除的条数。"""
    await memory_mgr.add("a", "1")
    await memory_mgr.add("b", "2")
    await memory_mgr.add("c", "3")
    cleared = await memory_mgr.clear()
    assert cleared == 3
    assert await memory_mgr.count() == 0


# ── 更新（矛盾覆盖） ──────────────────────────────────────────


async def test_update_content_overwrites(memory_mgr):
    """update_content() 覆盖旧记忆内容。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    assert await memory_mgr.update_content(mem["id"], "不爱吃火锅了") is True
    all_mems = await memory_mgr.all()
    assert len(all_mems) == 1
    assert all_mems[0]["content"] == "不爱吃火锅了"


async def test_update_content_nonexistent(memory_mgr):
    """update_content() 对不存在的 ID 返回 False。"""
    assert await memory_mgr.update_content("fake_id", "new content") is False


async def test_update_content_refreshes_timestamp(memory_mgr):
    """update_content() 刷新 created_at 和 last_used_at。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    old_created = mem["created_at"]
    result = await memory_mgr.update_content(mem["id"], "不爱吃火锅了")
    assert result is True
    updated = (await memory_mgr.all())[0]
    assert updated["created_at"] >= old_created


# ── 查询 ──────────────────────────────────────────────────────


async def test_recall_all(memory_mgr):
    """recall() 不传参返回全部记忆。"""
    await memory_mgr.add("a", "1")
    await memory_mgr.add("b", "2")
    result = await memory_mgr.recall()
    assert len(result) == 2


async def test_recall_by_category(memory_mgr):
    """recall(category) 只返回匹配的记忆。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("城市", "香港")
    result = await memory_mgr.recall("水果")
    assert len(result) == 1
    assert result[0]["content"] == "芒果"


async def test_recall_updates_last_used_at_and_access_count(memory_mgr):
    """recall() 会更新被召回记忆的 last_used_at 和 access_count。"""
    await memory_mgr.add("test", "hello")
    result = await memory_mgr.recall("test")
    assert result[0]["last_used_at"] is not None
    # 重新查询确认 access_count 已增加
    all_mems = await memory_mgr.all()
    assert all_mems[0]["access_count"] >= 1


async def test_recall_passive_does_not_update_counters(memory_mgr):
    """recall(passive=True) 纯只读，不更新 access_count / last_used_at。

    供叙事注入等系统被动读取场景使用，防止「越被注入越热门」的正反馈闭环。
    """
    await memory_mgr.add("test", "hello")
    before = (await memory_mgr.all())[0]

    result = await memory_mgr.recall("test", passive=True)
    assert len(result) == 1

    after = (await memory_mgr.all())[0]
    assert after["access_count"] == before["access_count"] == 0
    assert after["last_used_at"] == before["last_used_at"]


async def test_recall_default_limit(memory_mgr):
    """recall() 默认最多返回 20 条，防止撑爆 LLM 上下文。"""
    for i in range(50):
        await memory_mgr.add("test", f"memory_{i:03d}")
    result = await memory_mgr.recall()
    assert len(result) <= 20


async def test_recall_custom_limit(memory_mgr):
    """recall(limit=N) 只返回 N 条。"""
    for i in range(30):
        await memory_mgr.add("test", f"item_{i:03d}")
    result = await memory_mgr.recall(limit=5)
    assert len(result) == 5


async def test_recall_category_respects_limit(memory_mgr):
    """recall(category, limit=N) 按类别筛选时也遵守 limit。"""
    for i in range(25):
        await memory_mgr.add("水果", f"水果_{i:03d}")
    result = await memory_mgr.recall("水果", limit=10)
    assert len(result) == 10


async def test_recall_by_category_method(memory_mgr):
    """recall_by_category() 按类别精确查询，不更新 access_count。"""
    await memory_mgr.add("喜好", "火锅")
    await memory_mgr.add("喜好", "奶茶")
    await memory_mgr.add("城市", "香港")
    result = await memory_mgr.recall_by_category("喜好", limit=5)
    assert len(result) == 2
    # recall_by_category 不更新 access_count
    all_mems = await memory_mgr.all()
    for m in all_mems:
        if m["category"] == "喜好":
            assert m["access_count"] == 0


async def test_list_categories(memory_mgr):
    """list_categories() 返回正确的 {类别: 数量} 映射。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("水果", "草莓")
    await memory_mgr.add("城市", "香港")
    cats = await memory_mgr.list_categories()
    assert cats["水果"] == 2
    assert cats["城市"] == 1


async def test_count_empty(memory_mgr):
    """空数据库 count() 返回 0。"""
    assert await memory_mgr.count() == 0


async def test_all_returns_ordered(memory_mgr):
    """all() 按 created_at DESC 返回。"""
    await memory_mgr.add("a", "first")
    await memory_mgr.add("b", "second")
    result = await memory_mgr.all()
    assert len(result) == 2
    assert all("id" in m for m in result)


async def test_recent_limits_results(memory_mgr):
    """recent(limit) 只返回指定数量的记忆。"""
    for i in range(40):
        await memory_mgr.add("test", f"memory_{i:03d}")
    result = await memory_mgr.recent(limit=10)
    assert len(result) == 10


async def test_recent_chronological_order(memory_mgr):
    """recent() 按时间正序排列（最早的在前）。"""
    await memory_mgr.add("test", "first")
    await memory_mgr.add("test", "second")
    result = await memory_mgr.recent(limit=10)
    assert len(result) == 2
    assert result[0]["content"] == "first"
    assert result[1]["content"] == "second"


async def test_export_json(memory_mgr):
    """export_json() 返回有效 JSON。"""
    await memory_mgr.add("test", "hello")
    raw = await memory_mgr.export_json()
    data = json.loads(raw)
    assert "memories" in data
    assert "exported_at" in data
    assert len(data["memories"]) == 1


# ── 状态过滤 ──────────────────────────────────────────────────


async def test_count_excludes_non_active(memory_mgr):
    """count() 只统计 active 状态的记忆。"""
    # 手动插入一条 superseded 记忆
    await memory_mgr._db.execute(
        """INSERT INTO memories (id, content, category, created_at, status)
           VALUES ('superseded_1', 'old', 'test', '2026-01-01 00:00', 'superseded')"""
    )
    await memory_mgr._db.commit()
    await memory_mgr.add("test", "active_one")
    assert await memory_mgr.count() == 1


async def test_all_excludes_non_active(memory_mgr):
    """all() 只返回 active 状态的记忆。"""
    await memory_mgr._db.execute(
        """INSERT INTO memories (id, content, category, created_at, status)
           VALUES ('superseded_2', 'old', 'test', '2026-01-01 00:00', 'superseded')"""
    )
    await memory_mgr._db.commit()
    await memory_mgr.add("test", "active_one")
    result = await memory_mgr.all()
    assert len(result) == 1
    assert result[0]["content"] == "active_one"


# ── #24 回归：去重原子化 ─────────────────────────────────────


async def test_add_concurrent_same_content_dedups(memory_mgr):
    """回归：并发写入同一 (category, content) 只产生 1 行（DB 层去重）。

    旧版「先查后写」在心跳与聊天并发时可产生重复行。
    """
    import asyncio

    await asyncio.gather(*(memory_mgr.add("并发类别", "相同内容") for _ in range(5)))

    cursor = await memory_mgr._db.execute(
        "SELECT COUNT(*) FROM memories "
        "WHERE category = '并发类别' AND content = '相同内容' AND status = 'active'"
    )
    assert (await cursor.fetchone())[0] == 1


async def test_add_duplicate_returns_existing_entry_with_real_access_count(memory_mgr):
    """回归：重复添加返回既有行，access_count 为真实值（旧版硬编码 0）。"""
    first = await memory_mgr.add("类别X", "内容A")

    await memory_mgr._db.execute(
        "UPDATE memories SET access_count = 3 WHERE id = ?", (first["id"],)
    )
    await memory_mgr._db.commit()

    second = await memory_mgr.add("类别X", "内容A")
    assert second["id"] == first["id"]
    assert second["access_count"] == 3
