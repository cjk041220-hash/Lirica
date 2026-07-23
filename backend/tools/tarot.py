"""塔罗牌占卜工具 — 韦特塔罗（RWS），仅供娱乐参考。

小汐会去映海里看看，帮冷冷抽几张牌。
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


@tool
async def tarot(question: str = "", spread_type: str = "three") -> str:
    """韦特塔罗牌占卜（RWS），仅供娱乐参考。

    当冷冷想算一算、问一问、或者心情不好的时候，小汐会去映海里看看。

    Args:
        question: 占卜的问题
        spread_type: 牌阵类型，可选 single（单牌）、three（三牌）、celtic（凯尔特十字）
    """
    if not TAROT_DECK:
        return "小汐的塔罗牌组还没有准备好……映海里的牌被浪花卷走了，等它们漂回来再试试吧~"

    if not question:
        return "小汐不知道要问什么呀……告诉小汐你想问的事情，小汐去映海里帮你看看~"

    if spread_type not in _SPREADS:
        spread = _SPREADS["three"]
    else:
        spread = _SPREADS[spread_type]

    # 牌组数据损坏（缺字段/牌数不足/含义为空）时降级为友好文案，
    # 而不是把异常抛给 Agent 击穿整轮对话
    try:
        # 洗牌
        deck = TAROT_DECK.copy()
        random.shuffle(deck)

        if len(deck) < spread["positions"]:
            log.warning("塔罗牌组数量不足（%d < %d）", len(deck), spread["positions"])
            return "小汐的牌组好像被浪花卷走了几张……凑不齐这个牌阵，换个简单的牌阵再试试？"

        # 抽牌
        result = f"🐋 小汐去映海里看了一下「{question}」……\n\n"
        result += f"📜 牌阵：{spread['name']}\n\n"

        for i in range(spread["positions"]):
            card = deck[i].copy()
            is_reversed = random.random() < 0.15
            position = spread["positions_desc"][i]
            meanings = card["reversed"] if is_reversed else card["upright"]
            status = "逆位" if is_reversed else "正位"
            fortune = random.choice(meanings)

            suit_icon = {
                "大阿尔卡纳": "🌟", "权杖": "🔥", "圣杯": "💧",
                "宝剑": "⚔️", "星币": "🪙",
            }.get(card.get("suit", ""), "🃏")

            result += f"**{position}**\n"
            result += f"{suit_icon} **{card['name']}**（{status}）\n"
            result += f"   💬 {fortune}\n\n"

        result += "——小汐只能帮冷冷看到这里啦，但不管抽到什么牌，小汐都会在这里陪着你哦。🐋"

        return result
    except Exception:
        log.exception("塔罗占卜失败（牌组数据可能损坏）")
        return "呜……小汐在映海里洗牌的时候把牌弄散了……等小汐捡回来，再试一次好不好？"
