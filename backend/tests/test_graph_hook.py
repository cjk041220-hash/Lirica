"""pre_model_hook 话题提取测试（B1）：query 取自最近一条 human 消息。"""
from langchain_core.messages import AIMessage, HumanMessage

from agent.graph import make_pre_model_hook


class _StubBuilder:
    """记录 build 收到的 query。"""

    def __init__(self):
        self.seen = {}

    async def build(self, max_items=30, query=""):
        self.seen["query"] = query
        self.seen["max_items"] = max_items
        return ""


async def test_hook_passes_last_human_as_query():
    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    state = {"messages": [
        HumanMessage(content="第一句话"),
        AIMessage(content="小汐的回复"),
        HumanMessage(content="最近又想吃火锅了"),
    ]}
    await hook(state)
    assert builder.seen["query"] == "最近又想吃火锅了"
    assert builder.seen["max_items"] == 5


async def test_hook_skips_tool_and_ai_messages():
    """query 只看 human：工具消息与 AI 消息不充当话题。"""
    from langchain_core.messages import ToolMessage

    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    state = {"messages": [
        HumanMessage(content="查下天气"),
        AIMessage(content="", tool_calls=[
            {"name": "get_weather", "args": {}, "id": "t1"}
        ]),
        ToolMessage(content="晴天 30 度", tool_call_id="t1"),
    ]}
    await hook(state)
    assert builder.seen["query"] == "查下天气"


async def test_hook_multimodal_content_degrades_to_empty():
    """多模态（list 型 content）取空串，降级为纯时近/热度召回。"""
    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    state = {"messages": [
        HumanMessage(content=[{"type": "image_url", "image_url": {"url": "..."}}]),
    ]}
    await hook(state)
    assert builder.seen["query"] == ""


async def test_hook_no_human_gives_empty_query():
    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    await hook({"messages": [AIMessage(content="自言自语")]})
    assert builder.seen["query"] == ""
