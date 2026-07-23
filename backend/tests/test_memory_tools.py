"""记忆工具层测试 — remember / recall 的逻辑回归。

历史缺陷回归：
- v2.0 的 remember 在同类别已有记忆时拒绝写入，并提示"再次调用确认"，
  而再次调用仍命中同一分支 → 同类别第二条起的记忆永远存不进去（死循环）。
- v2.0 的 recall 工具用 mems[-10:] 截取，而 manager.recall 按相关性降序
  排列 → 展示的恰是最不相关的 10 条，排序整体反转。
"""
import pytest


@pytest.fixture
def injected_mgr(memory_mgr):
    """把临时 MemoryManager 注入工具层全局，测试后还原。"""
    from tools import memory as mem_tools

    original = mem_tools._memory_manager
    mem_tools.set_memory_manager(memory_mgr)
    yield memory_mgr
    mem_tools._memory_manager = original


# ── remember ──────────────────────────────────────────────────


async def test_remember_first_write_stores(injected_mgr):
    """首次写入成功入库。"""
    from tools.memory import remember

    result = await remember.ainvoke({"category": "冷冷喜欢的", "content": "芒果"})
    assert "收好" in result
    assert await injected_mgr.count() == 1


async def test_remember_same_category_appends(injected_mgr):
    """回归：同类别第二条、第三条记忆必须能继续写入（v2.0 死循环缺陷）。"""
    from tools.memory import remember

    await remember.ainvoke({"category": "冷冷喜欢的", "content": "芒果"})
    second = await remember.ainvoke({"category": "冷冷喜欢的", "content": "奶茶"})
    third = await remember.ainvoke({"category": "冷冷喜欢的", "content": "看电影"})

    # 三条不同内容应全部入库
    assert await injected_mgr.count() == 3

    # 第二次写入应成功（含成功标记），而不是"拦截提示"
    assert "收好" in second

    # 且第二次写入的返回应展示旧记忆作为矛盾检测上下文
    assert "芒果" in second


async def test_remember_exact_duplicate_dedups(injected_mgr):
    """完全相同的 category+content 重复调用不产生重复行（manager 层去重）。"""
    from tools.memory import remember

    first = await remember.ainvoke({"category": "重要的事", "content": "生日是3月5日"})
    second = await remember.ainvoke({"category": "重要的事", "content": "生日是3月5日"})

    assert await injected_mgr.count() == 1
    assert "收好" in first
    assert "收好" in second


async def test_remember_context_includes_ids_and_guidance(injected_mgr):
    """已有同类别记忆时，返回上下文包含旧记忆 ID 与矛盾处理指引。"""
    from tools.memory import remember

    await remember.ainvoke({"category": "冷冷喜欢的", "content": "火锅"})
    result = await remember.ainvoke({"category": "冷冷喜欢的", "content": "清淡的菜"})

    mems = await injected_mgr.recall_by_category("冷冷喜欢的")
    old = next(m for m in mems if m["content"] == "火锅")
    assert old["id"] in result
    assert "update_memory" in result


async def test_remember_manager_not_injected():
    """MemoryManager 未注入时返回友好降级文案，不抛异常。"""
    from tools import memory as mem_tools
    from tools.memory import remember

    original = mem_tools._memory_manager
    mem_tools._memory_manager = None
    try:
        result = await remember.ainvoke({"category": "x", "content": "y"})
        assert "记忆盒子还没有打开" in result
    finally:
        mem_tools._memory_manager = original


# ── recall ────────────────────────────────────────────────────


async def test_recall_shows_most_relevant_top10(injected_mgr):
    """回归：recall 展示相关性最高的前 10 条，而不是最低的 10 条。

    构造 12 条记忆，把其中 2 条的 created_at 回拨到很久以前，
    使时间衰减因子把它们排到相关性末尾。工具输出应排除这两条、
    包含最新的记忆，且恰好展示 10 条。
    """
    from tools.memory import recall

    for i in range(12):
        await injected_mgr.add("测试类别", f"记忆条目{i}号")

    # 把「记忆条目0号」「记忆条目1号」回拨到 200 天前 → 相关性垫底
    await injected_mgr._db.execute(
        "UPDATE memories SET created_at = ? WHERE content IN (?, ?)",
        ("2026-01-01 10:00", "记忆条目0号", "记忆条目1号"),
    )
    await injected_mgr._db.commit()

    result = await recall.ainvoke({"category": "测试类别"})

    # 恰好展示 10 条
    assert result.count("📌") == 10

    # 最相关的（最新的）在列，最不相关的（回拨的）不在列
    assert "记忆条目11号" in result
    assert "记忆条目0号" not in result
    assert "记忆条目1号" not in result


async def test_recall_empty_returns_friendly_text(injected_mgr):
    """无记忆时返回友好文案。"""
    from tools.memory import recall

    result = await recall.ainvoke({})
    assert "还没有记住" in result
