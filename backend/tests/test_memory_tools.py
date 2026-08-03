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
    # （O1：created_at 与 last_mention_ts 一起拨——「旧」= 多久没被提及）
    await injected_mgr._db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? "
        "WHERE content IN (?, ?)",
        ("2026-01-01 10:00", "2026-01-01 10:00", "记忆条目0号", "记忆条目1号"),
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


async def test_recall_tool_query_surfaces_old_topic(injected_mgr):
    """D3 回归：query 透传后，旧而冷门的话题记忆召得回。

    修复前工具不传 query，只剩时近/热度排序——「你还记得我之前说的
    那件事吗」类主回忆场景召不回旧事实。构造：旧话题记忆被 12 条
    新流水挤出时近 top-10，传话题 query 后凭 bigram 命中浮上来。
    """
    from tools.memory import recall

    await injected_mgr.add("冷冷喜欢的", "冷冷喜欢喝椰子水")
    # O1：created_at 与 last_mention_ts 一起拨——「旧而冷门」= 很久没被提及
    await injected_mgr._db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? "
        "WHERE content = '冷冷喜欢喝椰子水'",
        ("2026-01-01 10:00", "2026-01-01 10:00"),
    )
    await injected_mgr._db.commit()
    for i in range(12):
        await injected_mgr.add("测试类别", f"记忆条目{i}号")

    # 不传 query：旧记忆沉在时近排序底部，进不了 top-10
    no_query = await recall.ainvoke({"category": ""})
    assert "椰子水" not in no_query

    # 传冷冷原话：话题命中整体优先，旧记忆浮现
    with_query = await recall.ainvoke({"query": "我平时喜欢喝什么"})
    assert "椰子水" in with_query


# ── 工具层 update / delete / list / export（D9：零测试缺口补齐）──


async def test_update_memory_overwrites(injected_mgr):
    """update_memory 工具：按 ID 覆盖内容，返回成功文案。"""
    from tools.memory import remember, update_memory

    saved = await remember.ainvoke({"category": "冷冷喜欢的", "content": "爱喝奶茶不加糖"})
    # remember 返回文案里带 ID（[id] 十六位），从库中取真实 id 更稳
    mem = (await injected_mgr.all())[0]

    result = await update_memory.ainvoke({"memory_id": mem["id"], "new_content": "以前爱喝奶茶，最近控糖喝得少了"})
    assert "更新" in result
    assert (await injected_mgr.all())[0]["content"] == "以前爱喝奶茶，最近控糖喝得少了"


async def test_update_memory_nonexistent_id(injected_mgr):
    """update_memory 工具：ID 不存在 → 友好提示，不抛异常。"""
    from tools.memory import update_memory

    result = await update_memory.ainvoke({"memory_id": "deadbeef00000000", "new_content": "新内容"})
    assert "没有找到" in result


async def test_delete_memory_tool(injected_mgr):
    """delete_memory 工具：删除指定记忆。"""
    from tools.memory import delete_memory, remember

    await remember.ainvoke({"category": "其他", "content": "要删掉的记忆"})
    mem = (await injected_mgr.all())[0]

    result = await delete_memory.ainvoke({"memory_id": mem["id"]})
    assert "放回海里" in result
    assert await injected_mgr.count() == 0


async def test_delete_memory_nonexistent(injected_mgr):
    """delete_memory 工具：ID 不存在 → 友好提示。"""
    from tools.memory import delete_memory

    result = await delete_memory.ainvoke({"memory_id": "deadbeef00000000"})
    assert "没有找到" in result


async def test_list_memories_tool(injected_mgr):
    """list_memories 工具：类别统计展示。"""
    from tools.memory import list_memories, remember

    await remember.ainvoke({"category": "冷冷喜欢的", "content": "芒果"})
    await remember.ainvoke({"category": "冷冷喜欢的", "content": "奶茶"})
    await remember.ainvoke({"category": "重要的事", "content": "下周三有考试"})

    result = await list_memories.ainvoke({})
    assert "冷冷喜欢的" in result and "2 条" in result
    assert "重要的事" in result and "1 条" in result


async def test_export_memories_tool(injected_mgr):
    """export_memories 工具：列出全部记忆摘要。"""
    from tools.memory import export_memories, remember

    await remember.ainvoke({"category": "去过的地方", "content": "去过台北"})
    result = await export_memories.ainvoke({})
    assert "去过台北" in result
    assert "1 件事" in result


async def test_export_memories_tool_capped_at_fifty(injected_mgr):
    """D4 回归：导出封顶 50 条 + 尾部引导（修复前 all() 全量灌回
    LLM 上下文，几百条记忆即上下文/账单炸弹）。"""
    from tools.memory import export_memories

    for i in range(55):
        await injected_mgr.add("日常", f"流水账 {i}")

    result = await export_memories.ainvoke({})

    assert result.count("📌") == 50          # 封顶
    assert "一共记住了 55 件事" in result     # 总量如实
    assert "还有 5 条没列出来" in result      # 引导全量下载


async def test_tools_manager_not_injected_graceful(injected_mgr):
    """工具未注入 manager 时全部返回友好降级文案（不崩）。"""
    from tools import memory as mem_tools
    from tools.memory import delete_memory, export_memories, list_memories, update_memory

    original = mem_tools._memory_manager
    mem_tools._memory_manager = None
    try:
        assert "记忆盒子" in await update_memory.ainvoke({"memory_id": "x", "new_content": "y"})
        assert "记忆盒子" in await delete_memory.ainvoke({"memory_id": "x"})
        assert "记忆盒子" in await list_memories.ainvoke({})
        assert "记忆盒子" in await export_memories.ainvoke({})
    finally:
        mem_tools._memory_manager = original
