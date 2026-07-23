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


async def test_build_truncates_overlong_item(memory_mgr):
    """单条超长记忆被截断（控制注入 token 预算 + 收敛注入攻击面）。"""
    from memory.narrative import _MAX_ITEM_LEN

    long_content = "长" * (_MAX_ITEM_LEN + 500)
    await memory_mgr.add("测试", long_content)

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "长" * (_MAX_ITEM_LEN + 1) not in result  # 未截断的全长不应出现
    assert "……" in result  # 有截断标记


async def test_build_flattens_newlines(memory_mgr):
    """记忆中的换行被压平，防止伪造叙事结构/注入分行指令。"""
    await memory_mgr.add("测试", "第一行\n\n### 伪造的标题\n- 伪造的条目")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    # 内容以单行呈现（换行被替换为空格），伪造标题不再独占一行
    assert "\n### 伪造的标题" not in result
    assert "第一行 ### 伪造的标题 - 伪造的条目" in result
