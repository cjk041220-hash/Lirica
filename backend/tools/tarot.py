"""塔罗牌占卜工具 — 韦特塔罗（RWS），仅供娱乐参考。

小汐会去映海里看看，帮冷冷抽几张牌。

content_and_artifact 契约（与地图工具同构）：LLM 只见 content 人格化
文本，结构化卡片数据走 ToolMessage.artifact 下行，前端渲染可翻牌卡面。
所有降级分支返回 (友好文案, None) —— 前端无 artifact 自动回退纯文本。
"""
import random
from pathlib import Path

import yaml
from langchain_core.tools import tool

from utils.logger import get_logger

log = get_logger(__name__)

# 加载 78 张韦特塔罗牌数据（失败时降级为空牌组，不影响其他工具加载）
_DECK_PATH = Path(__file__).resolve().parent / "tarot_deck.yaml"
try:
    with open(_DECK_PATH, encoding="utf-8") as _f:
        TAROT_DECK: list[dict] = yaml.safe_load(_f)["cards"]
except Exception:
    TAROT_DECK: list[dict] = []

# 牌阵定义
_SPREADS = {
    "single": {"name": "单牌占卜", "positions": 1, "positions_desc": ["当前问题"]},
    "three": {
        "name": "三牌占卜",
        "positions": 3,
        "positions_desc": ["过去", "现在", "未来"],
    },
    "celtic": {
        "name": "凯尔特十字",
        "positions": 10,
        "positions_desc": [
            "当前状况", "挑战与阻碍", "最佳路径与建议", "潜在基础",
            "过去", "近期未来", "自我形象", "外在因素与他人影响",
            "希望与恐惧", "最终结果",
        ],
    },
}

# 花色 → 图标（与前端 SUIT_ICONS 同源，前端渲染时自行映射）
_SUIT_ICONS = {
    "大阿尔卡纳": "🌟", "权杖": "🔥", "圣杯": "💧",
    "宝剑": "⚔️", "星币": "🪙",
}


@tool(response_format="content_and_artifact")
async def tarot(question: str = "", spread_type: str = "three") -> tuple[str, dict | None]:
    """韦特塔罗牌占卜（RWS），仅供娱乐参考。

    当冷冷想算一算、问一问、或者心情不好的时候，小汐会去映海里看看。

    Args:
        question: 占卜的问题
        spread_type: 牌阵类型，可选 single（单牌）、three（三牌）、celtic（凯尔特十字）
    """
    if not TAROT_DECK:
        return "小汐的塔罗牌组还没有准备好……映海里的牌被浪花卷走了，等它们漂回来再试试吧~", None

    if not question:
        return "小汐不知道要问什么呀……告诉小汐你想问的事情，小汐去映海里帮你看看~", None

    if spread_type not in _SPREADS:
        options = "/".join(_SPREADS)
        return f"小汐不认得这个牌阵呀……可选：{options}，重新跟小汐说一次好不好？", None

    spread = _SPREADS[spread_type]

    # 牌组数据损坏（缺字段/牌数不足/含义为空）时降级为友好文案，
    # 而不是把异常抛给 Agent 击穿整轮对话
    try:
        # 洗牌
        deck = TAROT_DECK.copy()
        random.shuffle(deck)

        if len(deck) < spread["positions"]:
            log.warning("塔罗牌组数量不足（%d < %d）", len(deck), spread["positions"])
            return "小汐的牌组好像被浪花卷走了几张……凑不齐这个牌阵，换个简单的牌阵再试试？", None

        # 抽牌：LLM 只见文本；卡片数据进 artifact 供前端渲染
        result = f"🐋 小汐去映海里看了一下「{question}」……\n\n"
        result += f"📜 牌阵：{spread['name']}\n\n"

        cards = []
        for i in range(spread["positions"]):
            card = deck[i].copy()
            is_reversed = random.random() < 0.15
            position = spread["positions_desc"][i]
            meanings = card["reversed"] if is_reversed else card["upright"]
            status = "逆位" if is_reversed else "正位"
            fortune = random.choice(meanings)

            result += f"**{position}**\n"
            result += f"{_SUIT_ICONS.get(card.get('suit', ''), '🃏')} **{card['name']}**（{status}）\n"
            result += f"   💬 {fortune}\n\n"

            cards.append({
                "name": card["name"],
                "symbol": card.get("symbol", ""),
                "suit": card.get("suit", "大阿尔卡纳"),
                "element": card.get("element", ""),
                "keywords": card.get("keywords", []),
                "is_reversed": is_reversed,
                "position": position,
                "fortune": fortune,
                "meaning": meanings,
            })

        result += "——小汐只能帮冷冷看到这里啦，但不管抽到什么牌，小汐都会在这里陪着你哦。🐋"

        return result, {
            "spread_type": spread_type,
            "spread_name": spread["name"],
            "cards": cards,
        }
    except Exception:
        log.exception("塔罗占卜失败（牌组数据可能损坏）")
        return "呜……小汐在映海里洗牌的时候把牌弄散了……等小汐捡回来，再试一次好不好？", None
