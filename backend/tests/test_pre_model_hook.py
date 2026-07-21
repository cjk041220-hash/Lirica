"""pre_model_hook 单元测试 — 验证记忆注入和消息裁剪机制。

这是记忆系统 v2 重构的核心：
- 记忆通过 llm_input_messages 注入（不进 checkpoint）
- 旧消息通过 RemoveMessage 裁剪（防止状态膨胀）
"""
import pytest
from unittest.mock import AsyncMock

from langchain_core.messages import (
    AIMessage,
    HumanMessage,
    SystemMessage,
)

from agent.graph import make_pre_model_hook
from memory.narrative import NarrativeBuilder


# ── Fixtures ──────────────────────────────────────────────────


@pytest.fixture
def mock_narrative():
    """创建一个 mock NarrativeBuilder，返回预设记忆文本。"""
    builder = AsyncMock(spec=NarrativeBuilder)
    builder.build = AsyncMock(return_value="以下是小汐记住的关于冷冷的事情：\n\n### 喜好\n- 爱吃芒果")
    return builder


@pytest.fixture
def empty_narrative():
    """创建一个返回空记忆的 mock NarrativeBuilder。"""
    builder = AsyncMock(spec=NarrativeBuilder)
    builder.build = AsyncMock(return_value="")
    return builder


# ── 记忆注入测试 ──────────────────────────────────────────────


async def test_memory_injected_as_system_message(mock_narrative):
    """记忆叙事作为 SystemMessage 注入到 llm_input_messages。"""
    hook = make_pre_model_hook(mock_narrative)
    state = {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
            AIMessage(content="你好呀~", id="a1"),
        ]
    }

    result = await hook(state)
    llm_input = result["llm_input_messages"]

    # 应该包含：原始 SystemMessage + 记忆 SystemMessage + 对话消息
    system_msgs = [m for m in llm_input if isinstance(m, SystemMessage)]
    assert len(system_msgs) == 2
    assert "小汐的记忆" in system_msgs[1].content
    assert "爱吃芒果" in system_msgs[1].content


async def test_no_memory_no_injection(empty_narrative):
    """空记忆时不注入额外的 SystemMessage。"""
    hook = make_pre_model_hook(empty_narrative)
    state = {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }

    result = await hook(state)
    llm_input = result["llm_input_messages"]

    system_msgs = [m for m in llm_input if isinstance(m, SystemMessage)]
    assert len(system_msgs) == 1  # 只有原始的系统提示


async def test_memory_not_in_returned_messages(mock_narrative):
    """记忆 SystemMessage 只出现在 llm_input_messages 中，不出现在 messages 中。

    这是防止上下文污染的关键：llm_input_messages 不进 checkpoint。
    """
    hook = make_pre_model_hook(mock_narrative)
    state = {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }

    result = await hook(state)

    # messages 键要么不存在，要么只包含 RemoveMessage（不含记忆 SystemMessage）
    if "messages" in result:
        for m in result["messages"]:
            if isinstance(m, SystemMessage):
                assert "小汐的记忆" not in m.content


# ── 消息裁剪测试 ──────────────────────────────────────────────


async def test_no_trimming_when_under_limit(mock_narrative):
    """消息数量未超限时不裁剪。"""
    hook = make_pre_model_hook(mock_narrative, max_conversation_msgs=30)
    messages = [SystemMessage(content="你是小汐")]
    for i in range(10):
        messages.append(HumanMessage(content=f"msg {i}", id=f"h{i}"))
        messages.append(AIMessage(content=f"reply {i}", id=f"a{i}"))

    result = await hook({"messages": messages})

    # 不裁剪 → messages 键不包含 RemoveMessage
    if "messages" in result:
        from langchain_core.messages import RemoveMessage
        remove_msgs = [m for m in result["messages"] if isinstance(m, RemoveMessage)]
        assert len(remove_msgs) == 0


async def test_trimming_when_over_limit(mock_narrative):
    """消息数量超限时裁剪旧消息。"""
    hook = make_pre_model_hook(mock_narrative, max_conversation_msgs=6)
    messages = [SystemMessage(content="你是小汐")]
    # 创建 10 条对话消息（5 轮）
    for i in range(5):
        messages.append(HumanMessage(content=f"question {i}", id=f"h{i}"))
        messages.append(AIMessage(content=f"answer {i}", id=f"a{i}"))

    result = await hook({"messages": messages})

    from langchain_core.messages import RemoveMessage

    # 应该有 RemoveMessage
    assert "messages" in result
    remove_msgs = [m for m in result["messages"] if isinstance(m, RemoveMessage)]
    assert len(remove_msgs) > 0

    # llm_input_messages 中对话消息应被裁剪
    llm_input = result["llm_input_messages"]
    conv_msgs = [m for m in llm_input if not isinstance(m, SystemMessage)]
    assert len(conv_msgs) <= 6


async def test_trimming_preserves_human_boundary(mock_narrative):
    """裁剪从 HumanMessage 边界开始，不切断工具调用链。"""
    from langchain_core.messages import ToolMessage

    hook = make_pre_model_hook(mock_narrative, max_conversation_msgs=4)
    messages = [
        SystemMessage(content="你是小汐"),
        HumanMessage(content="old question", id="h0"),
        AIMessage(content="old answer", id="a0"),
        # 当前轮（包含工具调用）
        HumanMessage(content="查天气", id="h1"),
        AIMessage(content="", id="a1", tool_calls=[{"name": "get_weather", "args": {}, "id": "tc1"}]),
        ToolMessage(content="晴天 25°C", tool_call_id="tc1", id="t1"),
        AIMessage(content="今天天气很好~", id="a2"),
    ]

    result = await hook({"messages": messages})

    # 裁剪应该保留当前轮的完整工具调用链
    llm_input = result["llm_input_messages"]
    conv_msgs = [m for m in llm_input if not isinstance(m, SystemMessage)]

    # 确保没有孤立的 ToolMessage（前面没有对应的 AIMessage tool_call）
    for i, m in enumerate(conv_msgs):
        if isinstance(m, ToolMessage):
            # 前面的消息中应该有带 tool_calls 的 AIMessage
            has_tool_call = any(
                isinstance(prev, AIMessage) and getattr(prev, "tool_calls", None)
                for prev in conv_msgs[:i]
            )
            assert has_tool_call, f"ToolMessage {m.id} 前面没有对应的 tool_call"


# ── 无叙事构建器测试 ──────────────────────────────────────────


async def test_none_narrative_builder():
    """narrative_builder 为 None 时不报错。"""
    hook = make_pre_model_hook(None)
    state = {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }

    result = await hook(state)
    llm_input = result["llm_input_messages"]
    assert len(llm_input) == 2  # system + human，无记忆注入
