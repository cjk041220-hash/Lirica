"""塔罗工具测试 — content_and_artifact 契约（牌面可视化 #tarot-cards）。

v0.4.2 的 tarot 是唯一没有 try/except 的工具，牌组数据损坏会抛异常
击穿整轮对话（#10 回归）；本次改造输出 (文本, 卡片数据) 二元组：
LLM 只见文本，卡片数据经 ToolMessage.artifact 下行给前端渲染。
"""
import pytest
from langchain_core.messages import ToolMessage


_MINI_DECK = [
    {
        "name": "测试牌A",
        "name_en": "Test A",
        "symbol": "A",
        "suit": "圣杯",
        "element": "水",
        "keywords": ["关键词A1", "关键词A2"],
        "upright": ["正位含义A1", "正位含义A2"],
        "reversed": ["逆位含义A1"],
    },
    {
        "name": "测试牌B",
        "name_en": "Test B",
        "symbol": "2",
        "suit": "宝剑",
        "element": "风",
        "keywords": ["关键词B1"],
        "upright": ["正位含义B1"],
        "reversed": ["逆位含义B1"],
    },
    {
        "name": "测试牌C",
        "name_en": "Test C",
        "symbol": "3",
        "suit": "权杖",
        "element": "火",
        "keywords": ["关键词C1"],
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


def _tool_call(tool, args: dict):
    """以图内 ToolCall 形态调用工具 → ToolMessage（含 content/artifact）。

    langchain-core 1.4.9 实证（见 test_map_engine.py 头部）：直接
    ainvoke(纯参数) 时 tool_call_id 为 None，_format_output 只回 content
    字符串；图内 ToolNode 传 ToolCall（含 type/id）才构造 ToolMessage。
    与 test_map_engine.py 的 _graph_call 同构。
    """
    return tool.ainvoke({
        "type": "tool_call", "name": tool.name, "id": "call_test", "args": args,
    })


async def test_tarot_single_spread_content_and_artifact(mini_deck):
    """单牌占卜：content 是人格化文本，artifact 是单张卡片数据。"""
    from tools.tarot import tarot

    msg = await _tool_call(tarot, {"question": "今天运势如何", "spread_type": "single"})
    assert isinstance(msg, ToolMessage)
    assert "单牌占卜" in msg.content
    assert "小汐都会在这里陪着你" in msg.content
    artifact = msg.artifact
    assert artifact["spread_type"] == "single"
    assert artifact["spread_name"] == "单牌占卜"
    cards = artifact["cards"]
    assert len(cards) == 1
    card = cards[0]
    assert card["position"] == "当前问题"
    assert card["name"] == "测试牌A" or card["name"] == "测试牌B" or card["name"] == "测试牌C"
    # 前端渲染所需的全部字段
    for key in ("name", "symbol", "suit", "element", "keywords", "is_reversed", "position", "fortune", "meaning"):
        assert key in card
    assert card["is_reversed"] in (True, False)
    assert len(card["meaning"]) >= 1
    assert card["fortune"] in card["meaning"]


async def test_tarot_three_spread_three_cards(mini_deck):
    """三牌占卜：三张卡，位置为过去/现在/未来。"""
    from tools.tarot import tarot

    msg = await _tool_call(tarot, {"question": "感情", "spread_type": "three"})
    assert "过去" in msg.content and "现在" in msg.content and "未来" in msg.content
    positions = [c["position"] for c in msg.artifact["cards"]]
    assert positions == ["过去", "现在", "未来"]
    assert len(msg.artifact["cards"]) == 3


async def test_tarot_missing_question_friendly_no_artifact(mini_deck):
    """没有问题时返回引导文案且无 artifact（前端回退纯文本）。"""
    from tools.tarot import tarot

    msg = await _tool_call(tarot, {"question": "", "spread_type": "three"})
    assert "不知道要问什么" in msg.content
    assert msg.artifact is None


async def test_tarot_invalid_spread_reports_options(mini_deck):
    """非法牌阵友好报错列可选值（对齐 SonettoHere 参考实现，不再静默回退）。"""
    from tools.tarot import tarot

    msg = await _tool_call(tarot, {"question": "x", "spread_type": "mega"})
    assert "single" in msg.content and "three" in msg.content and "celtic" in msg.content
    assert msg.artifact is None


async def test_tarot_empty_deck_degrades(mini_deck, monkeypatch):
    """牌组为空时友好降级且无 artifact。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [])
    msg = await _tool_call(tarot_mod.tarot, {"question": "x", "spread_type": "single"})
    assert "还没有准备好" in msg.content
    assert msg.artifact is None


async def test_tarot_corrupt_deck_missing_keys_degrades(monkeypatch):
    """回归：牌数据缺字段（KeyError）时降级为友好文案，不抛异常。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [{"name": "坏牌"}])
    msg = await _tool_call(tarot_mod.tarot, {"question": "x", "spread_type": "single"})
    assert "弄散" in msg.content or "凑不齐" in msg.content
    assert msg.artifact is None


async def test_tarot_empty_meanings_degrades(monkeypatch):
    """回归：含义列表为空（random.choice IndexError）时降级。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [{"name": "空牌", "suit": "圣杯", "upright": [], "reversed": []}])
    msg = await _tool_call(tarot_mod.tarot, {"question": "x", "spread_type": "single"})
    assert "弄散" in msg.content


async def test_tarot_insufficient_cards_for_spread_degrades(mini_deck):
    """牌数不足牌阵要求时降级，不 IndexError。"""
    from tools.tarot import tarot

    msg = await _tool_call(tarot, {"question": "x", "spread_type": "celtic"})  # 需要 10 张
    assert "凑不齐" in msg.content
    assert msg.artifact is None
