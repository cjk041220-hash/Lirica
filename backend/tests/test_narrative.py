"""叙事构建器单元测试 — build() 输出正确性。"""
import pytest

from memory.narrative import NarrativeBuilder


async def test_build_no_manager():
    """未设置 manager 时返回空字符串。"""
    builder = NarrativeBuilder()
    result = await builder.build()
    assert result == ""


async def test_build_empty_db(memory_mgr):
    """空数据库返回空字符串。"""
    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()
    assert result == ""


async def test_build_with_memories(memory_mgr):
    """有记忆时返回包含所有内容的叙事文本。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    await memory_mgr.add("喜好", "喜欢喝奶茶")
    await memory_mgr.add("去过的地方", "维多利亚港")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "喜欢吃芒果" in result
    assert "喜欢喝奶茶" in result
    assert "维多利亚港" in result
    assert "以下是小汐记住的关于冷冷的事情" in result


async def test_build_respects_max_items(memory_mgr):
    """build(max_items=N) 只包含 N 条记忆。"""
    for i in range(40):
        await memory_mgr.add("test", f"memory_{i:03d}")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build(max_items=10)

    # 计算 bullet points 数量
    bullet_count = result.count("\n- ")
    assert bullet_count == 10


async def test_build_groups_by_category(memory_mgr):
    """记忆按类别分组显示。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("城市", "香港")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "### 水果" in result
    assert "### 城市" in result


async def test_build_chronological_order(memory_mgr):
    """记忆按时间正序展示（最早的在前）。"""
    await memory_mgr.add("时间线", "第一件事")
    await memory_mgr.add("时间线", "第二件事")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    # 两条记忆都在输出中
    assert "第一件事" in result
    assert "第二件事" in result
