"""塔罗工具测试 — 正常占卜 + 数据损坏降级（#10 回归）。

v0.4.2 的 tarot 是唯一没有 try/except 的工具，牌组数据损坏
（缺字段/牌数不足/含义为空）会抛异常击穿整轮对话。
"""
import pytest


_MINI_DECK = [
    {
        "name": "测试牌A",
        "suit": "圣杯",
        "upright": ["正位含义A1", "正位含义A2"],
        "reversed": ["逆位含义A1"],
    },
    {
        "name": "测试牌B",
        "suit": "宝剑",
        "upright": ["正位含义B1"],
        "reversed": ["逆位含义B1"],
    },
    {
        "name": "测试牌C",
        "suit": "权杖",
        "upright": ["正位含义C1"],
        "reversed": ["逆位含义C1"],
    },
]


@pytest.fixture
def mini_deck(monkeypatch):
    """注入迷你测试牌组（不依赖 tarot_deck.yaml 的完整性）。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", _MINI_DECK)
    return _MINI_DECK


async def test_tarot_single_spread_works(mini_deck):
    """单牌占卜正常输出牌名与含义。"""
    from tools.tarot import tarot

    result = await tarot.ainvoke({"question": "今天运势如何", "spread_type": "single"})
    assert "测试牌" in result
    assert "单牌占卜" in result
    assert "小汐都会在这里陪着你" in result


async def test_tarot_three_spread_works(mini_deck):
    """三牌占卜展示三个位置。"""
    from tools.tarot import tarot

    result = await tarot.ainvoke({"question": "感情", "spread_type": "three"})
    assert "过去" in result and "现在" in result and "未来" in result


async def test_tarot_missing_question_friendly(mini_deck):
    """没有问题时返回引导文案，不抽牌。"""
    from tools.tarot import tarot

    result = await tarot.ainvoke({"question": "", "spread_type": "three"})
    assert "不知道要问什么" in result


async def test_tarot_empty_deck_degrades(monkeypatch):
    """牌组为空时友好降级。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")

    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [])
    result = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "还没有准备好" in result


async def test_tarot_corrupt_deck_missing_keys_degrades(mini_deck, monkeypatch):
    """回归：牌数据缺字段（KeyError）时降级为友好文案，不抛异常。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")

    corrupt = [{"name": "坏牌"}]  # 缺 upright/reversed/suit
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", corrupt)

    result = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "弄散" in result or "凑不齐" in result  # 友好降级文案，不是异常


async def test_tarot_empty_meanings_degrades(monkeypatch):
    """回归：含义列表为空（random.choice IndexError）时降级。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")

    corrupt = [{"name": "空牌", "suit": "圣杯", "upright": [], "reversed": []}]
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", corrupt)

    result = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "弄散" in result


async def test_tarot_insufficient_cards_for_spread_degrades(mini_deck):
    """牌数不足牌阵要求时降级，不 IndexError。"""
    from tools.tarot import tarot

    result = await tarot.ainvoke({"question": "x", "spread_type": "celtic"})  # 需要 10 张
    assert "凑不齐" in result


async def test_agent_survives_tool_exception():
    """端到端验证 #10 总闸：工具抛异常不击穿对话，agent 拿到错误消息后继续。

    build_agent 用 ToolNode(handle_tool_errors=True) 包裹所有工具：
    异常被转成 status='error' 的 ToolMessage 交还 LLM。
    """
    from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
    from langchain_core.tools import tool
    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel

    from agent.graph import build_agent

    class FakeToolModel(FakeMessagesListChatModel):
        def bind_tools(self, tools, **kwargs):
            return self

    @tool
    def broken_tool(x: str) -> str:
        """一个会爆炸的工具。"""
        raise RuntimeError("boom")

    model = FakeToolModel(responses=[
        AIMessage(content="", tool_calls=[
            {"name": "broken_tool", "args": {"x": "1"}, "id": "call1", "type": "tool_call"}
        ]),
        AIMessage(content="工具坏了，但对话还在继续"),
    ])

    graph = build_agent(model=model, tools=[broken_tool], system_prompt="p")

    out = await graph.ainvoke(
        {"messages": [HumanMessage("hi")]},
        config={"configurable": {"thread_id": "t1"}},
    )

    msgs = out["messages"]
    tool_msgs = [m for m in msgs if isinstance(m, ToolMessage)]
    assert len(tool_msgs) == 1
    assert tool_msgs[0].status == "error"  # 异常被转成错误消息，而不是 500
    assert msgs[-1].content == "工具坏了，但对话还在继续"  # 对话正常收尾
