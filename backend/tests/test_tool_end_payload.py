"""SSE tool_end 载荷提取测试（chat.py _tool_end_payload）。

content_and_artifact 契约：on_tool_end 的 data.output 是 ToolMessage，
content 是渲染文本（str() 是 pydantic repr，含 tool_call_id 噪声），
artifact 分两类——地图 nav（含 lat/lon）与塔罗 tarot（含非空 cards）。
判据互斥：nav 要求 lat+lon，tarot 要求 cards 非空列表。
"""
from langchain_core.messages import ToolMessage

from api.routes.chat import _tool_end_payload

NAV = {"name": "尖沙咀站", "lat": 22.297, "lon": 114.172, "coord": "gcj02"}

TAROT_ARTIFACT = {
    "spread_type": "three",
    "spread_name": "三牌占卜",
    "cards": [{"name": "恋人", "symbol": "VI", "position": "过去"}],
}


def test_extracts_content_artifact_nav():
    tm = ToolMessage(content="渲染文本", artifact=NAV, tool_call_id="call_1")
    assert _tool_end_payload(tm) == ("渲染文本", NAV, None)


def test_extracts_content_artifact_tarot():
    """塔罗 artifact（含 cards）→ tarot 透传，nav 为 None。"""
    tm = ToolMessage(content="塔罗文本", artifact=TAROT_ARTIFACT, tool_call_id="call_1")
    text, nav, tarot = _tool_end_payload(tm)
    assert text == "塔罗文本"
    assert nav is None
    assert tarot == TAROT_ARTIFACT


def test_tarot_artifact_not_mistaken_for_nav():
    """回归：塔罗 artifact 是 dict 但无 lat/lon → 绝不进 nav（防地图按钮错挂）。"""
    tm = ToolMessage(content="x", artifact=TAROT_ARTIFACT, tool_call_id="call_1")
    text, nav, _ = _tool_end_payload(tm)
    assert nav is None


def test_nav_artifact_not_mistaken_for_tarot():
    """nav 无 cards 字段 → tarot 为 None。"""
    tm = ToolMessage(content="x", artifact=NAV, tool_call_id="call_1")
    _, _, tarot = _tool_end_payload(tm)
    assert tarot is None


def test_tarot_artifact_without_cards_rejected():
    """tarot 判据：cards 必须是非空列表；缺失/为空 → 不透传。"""
    for bad in (
        {"spread_type": "three"},
        {"spread_type": "three", "cards": []},
        {"spread_type": "three", "cards": "not-a-list"},
    ):
        tm = ToolMessage(content="x", artifact=bad, tool_call_id="call_1")
        text, nav, tarot = _tool_end_payload(tm)
        assert text == "x" and nav is None and tarot is None


def test_repr_noise_gone():
    """str(ToolMessage) 是 pydantic repr；content 提取后无 tool_call_id 噪声。"""
    tm = ToolMessage(
        content="干净文本", tool_call_id="call_abc123", name="search_nearby",
    )
    text, nav, tarot = _tool_end_payload(tm)
    assert text == "干净文本"
    assert nav is None and tarot is None
    assert "tool_call_id" not in text and "call_abc123" not in text


def test_non_dict_artifact_ignored():
    """artifact 不是 dict → nav/tarot 均不下发（防御契约）。"""
    tm = ToolMessage(content="x", artifact=[1, 2, 3], tool_call_id="call_1")
    assert _tool_end_payload(tm) == ("x", None, None)


def test_plain_str_fallback():
    """非 ToolMessage（普通 str 工具输出）→ 原样兜底。"""
    assert _tool_end_payload("普通文本") == ("普通文本", None, None)


def test_empty_output_fallback():
    assert _tool_end_payload(None) == ("None", None, None)
