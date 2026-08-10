"""人格重锚中间件单测 — 每 N 次模型调用注入 PERSONA_ANCHOR（批次①）。

注入走 llm_input_messages（不进 checkpoint），与记忆叙事同机制；
计数器是中间件实例属性，跨会话累积——单用户设计下这是特性
（活跃用户上下文长得快、锚得更勤），不是缺陷。
"""
import pytest

from agent.graph import _PersonaAnchorMiddleware
from langchain_core.messages import HumanMessage, SystemMessage


def _make_state():
    return {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }


async def test_no_anchor_before_interval():
    """第 1..interval-1 次模型调用不注入锚。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        result = await mw.abefore_model(_make_state(), None)
        assert "llm_input_messages" not in result


async def test_anchor_injected_on_interval():
    """第 interval 次调用注入锚 SystemMessage，追加在系统段末尾。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        await mw.abefore_model(_make_state(), None)
    result = await mw.abefore_model(_make_state(), None)
    system_msgs = [m for m in result["llm_input_messages"]
                   if isinstance(m, SystemMessage)]
    assert len(system_msgs) == 2
    assert "冷冷感受永远第一" in system_msgs[-1].content


async def test_counter_resets_after_injection():
    """注入后计数归零，下一周期重复（8 的倍数次仍注入）。"""
    mw = _PersonaAnchorMiddleware(interval=4)
    for _ in range(3):
        await mw.abefore_model(_make_state(), None)
    result1 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" in result1  # 第 4 次注入
    result2 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" not in result2  # 归零后第 1 次不注入
    for _ in range(2):
        await mw.abefore_model(_make_state(), None)
    result4 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" in result4  # 第 8 次再次注入


async def test_anchor_content_is_soul_constant():
    """锚文本与 soul.PERSONA_ANCHOR 逐字节一致（防两处漂移）。"""
    from agent.soul import PERSONA_ANCHOR
    mw = _PersonaAnchorMiddleware(interval=1)
    result = await mw.abefore_model(_make_state(), None)
    system_msgs = [m for m in result["llm_input_messages"]
                   if isinstance(m, SystemMessage)]
    assert PERSONA_ANCHOR in system_msgs[-1].content
