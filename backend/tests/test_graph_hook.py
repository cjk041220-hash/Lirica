"""pre_model_hook 话题提取测试：query 取最近 human 消息（D10：最多两条拼接）。"""
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


async def test_hook_passes_last_two_humans_as_query():
    """D10：最近 1~2 条 human 消息拼接为 query——「就是那个……」类回指
    依赖上一条消息里的实词（店名/地名/人名）找回话题。"""
    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    state = {"messages": [
        HumanMessage(content="周末想再去吃那家炸鸡"),
        AIMessage(content="是哪家呀？"),
        HumanMessage(content="就是上次说的那家"),
        AIMessage(content="哦哦，好呀"),
        HumanMessage(content="对啦，周日有空吗？"),
    ]}
    await hook(state)
    assert builder.seen["query"] == "就是上次说的那家\n对啦，周日有空吗？"
    assert builder.seen["max_items"] == 5


async def test_hook_single_human_keeps_query():
    """只有一条 human 消息时 query 就是它（退化为 B1 原语义）。"""
    builder = _StubBuilder()
    hook = make_pre_model_hook(builder)
    state = {"messages": [
        AIMessage(content="在的"),
        HumanMessage(content="最近又想吃火锅了"),
    ]}
    await hook(state)
    assert builder.seen["query"] == "最近又想吃火锅了"


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
