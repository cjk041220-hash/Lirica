# 塔罗牌前端卡牌可视化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让塔罗占卜结果在聊天里以可翻牌的 CSS 卡牌网格呈现（单牌/三牌/凯尔特十字），LLM 人格化文本链路不变。

**Architecture:** 复用地图工具已验证的 `content_and_artifact` 契约：`tarot` 工具返回 `(人格化文本, 卡片数据字典)`，LLM 只见文本，卡片数据经 `ToolMessage.artifact` → SSE `tool_end` 事件 `tarot` 字段下行，前端 `useChat.js` 挂到 tool_hint 消息上，`MessageBubble` 渲染 `TarotCards` 组件（卡背 → 逐张 3D 翻牌 → 牌面网格）。降级分支（牌组损坏/错误）返回 `(文本, None)` → 前端无数据自动回退为现有文本折叠块。

**Tech Stack:** Python 3.13 / FastAPI / LangGraph（langchain `content_and_artifact`）、React 19 + Vite + Tailwind v4（`@theme` token）+ vitest + @testing-library/react。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-08-05-tarot-cards-design.md`（用户已确认）
- 不修改 `tarot_deck.yaml`（数据已达标）；不修改 LLM 人格文本内容（`content` 保持现状）
- 后端测试命令：`pytest backend/tests/test_tarot.py backend/tests/test_tool_end_payload.py -v`（项目根目录）
- 前端测试命令：`cd web && npm test`（vitest run）；lint：`cd web && npm run lint`（oxlint src）
- 逆位概率 15%、花色图标映射保持与后端文本一致：大阿尔卡纳🌟 / 权杖🔥 / 圣杯💧 / 宝剑⚔️ / 星币🪙
- 色板纪律（index.css 注释原文）：<12px 的彩色文字一律用 raspberry；plum/cream 可作正文；白字只在 raspberry 底上
- 前端所有新文件用函数组件 + named export default，测试文件与组件同目录命名 `<Name>.test.jsx`（与 MapNavButtons 惯例一致）
- 提交信息末尾带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: 后端 tarot 工具改 content_and_artifact + 非法牌阵严格校验

**Files:**
- Modify: `backend/tools/tarot.py:43-102`（工具签名与 _run 主体）
- Test: `backend/tests/test_tarot.py`（全量适配 + 新用例）

**Interfaces:**
- Consumes: `TAROT_DECK`（list[dict]，每张含 name/name_en/symbol/suit/element/keywords/upright/reversed）、`_SPREADS`（dict，含 name/positions/positions_desc）
- Produces: `tarot` 工具改为 `@tool(response_format="content_and_artifact")`，`async def tarot(question: str = "", spread_type: str = "three") -> tuple[str, dict | None]`：
  - 正常：`(人格化文本, {"spread_type", "spread_name", "cards": [{"name", "symbol", "suit", "element", "keywords", "is_reversed", "position", "fortune", "meaning"}]})`
  - 降级（空牌组/损坏/凑不齐/无问题/非法牌阵）：`(友好文案, None)`
  - Task 2 依赖此返回契约；`_tool_end_payload` 判据为 `artifact["cards"]` 非空列表

- [ ] **Step 1: 先改测试为 content_and_artifact 断言（红）**

`backend/tests/test_tarot.py` 全文改写为以下内容（保留原降级用例并适配 ToolMessage 断言）：

```python
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


async def test_tarot_single_spread_content_and_artifact(mini_deck):
    """单牌占卜：content 是人格化文本，artifact 是单张卡片数据。"""
    from tools.tarot import tarot

    msg = await tarot.ainvoke({"question": "今天运势如何", "spread_type": "single"})
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

    msg = await tarot.ainvoke({"question": "感情", "spread_type": "three"})
    assert "过去" in msg.content and "现在" in msg.content and "未来" in msg.content
    positions = [c["position"] for c in msg.artifact["cards"]]
    assert positions == ["过去", "现在", "未来"]
    assert len(msg.artifact["cards"]) == 3


async def test_tarot_missing_question_friendly_no_artifact(mini_deck):
    """没有问题时返回引导文案且无 artifact（前端回退纯文本）。"""
    from tools.tarot import tarot

    msg = await tarot.ainvoke({"question": "", "spread_type": "three"})
    assert "不知道要问什么" in msg.content
    assert msg.artifact is None


async def test_tarot_invalid_spread_reports_options(mini_deck):
    """非法牌阵友好报错列可选值（对齐 SonettoHere 参考实现，不再静默回退）。"""
    from tools.tarot import tarot

    msg = await tarot.ainvoke({"question": "x", "spread_type": "mega"})
    assert "single" in msg.content and "three" in msg.content and "celtic" in msg.content
    assert msg.artifact is None


async def test_tarot_empty_deck_degrades(mini_deck, monkeypatch):
    """牌组为空时友好降级且无 artifact。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [])
    msg = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "还没有准备好" in msg.content
    assert msg.artifact is None


async def test_tarot_corrupt_deck_missing_keys_degrades(monkeypatch):
    """回归：牌数据缺字段（KeyError）时降级为友好文案，不抛异常。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [{"name": "坏牌"}])
    msg = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "弄散" in msg.content or "凑不齐" in msg.content
    assert msg.artifact is None


async def test_tarot_empty_meanings_degrades(monkeypatch):
    """回归：含义列表为空（random.choice IndexError）时降级。"""
    import importlib

    tarot_mod = importlib.import_module("tools.tarot")
    monkeypatch.setattr(tarot_mod, "TAROT_DECK", [{"name": "空牌", "suit": "圣杯", "upright": [], "reversed": []}])
    msg = await tarot_mod.tarot.ainvoke({"question": "x", "spread_type": "single"})
    assert "弄散" in msg.content


async def test_tarot_insufficient_cards_for_spread_degrades(mini_deck):
    """牌数不足牌阵要求时降级，不 IndexError。"""
    from tools.tarot import tarot

    msg = await tarot.ainvoke({"question": "x", "spread_type": "celtic"})  # 需要 10 张
    assert "凑不齐" in msg.content
    assert msg.artifact is None
```

- [ ] **Step 2: 运行测试确认失败（红）**

Run: `pytest backend/tests/test_tarot.py -v`
Expected: FAIL——现有实现返回 str 而非 ToolMessage，`msg.artifact` 属性不存在

- [ ] **Step 3: 改写 tarot.py 为 content_and_artifact**

`backend/tools/tarot.py` 全部内容替换为：

```python
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
```

- [ ] **Step 4: 运行测试确认通过（绿）**

Run: `pytest backend/tests/test_tarot.py -v`
Expected: 9 个用例全 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/tools/tarot.py backend/tests/test_tarot.py
git commit -m "feat(tarot): 工具改 content_and_artifact 契约 — 卡片数据经 artifact 下行，非法牌阵友好报错

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: chat.py _tool_end_payload 泛化（nav 判据收紧 + tarot 透传）

**Files:**
- Modify: `backend/api/routes/chat.py:37-49`（_tool_end_payload）、`backend/api/routes/chat.py:396-411`（on_tool_end 分支）
- Test: `backend/tests/test_tool_end_payload.py`（全量适配 + 新用例）

**Interfaces:**
- Consumes: Task 1 的 tarot 工具返回契约（ToolMessage.artifact 为含非空 `cards` 的 dict）
- Produces: `_tool_end_payload(output) -> tuple[str, dict | None, dict | None]`（text, nav, tarot）；SSE `tool_end` 事件可选带 `tarot` 字段（与 `nav` 并列）
- Task 3 前端 `useChat.js` 依赖 `parsed.tarot` 字段

**关键点：** 现有代码 `nav if isinstance(nav, dict) else None` 会把塔罗的 artifact（也是 dict）误判为 nav 下发——必须收紧 nav 判据（要求含 `lat`/`lon`，与前端 `isValidNav` 的 {name, lat, lon} 结构一致），否则地图按钮会出现在塔罗结果上。

- [ ] **Step 1: 先改测试（红）**

`backend/tests/test_tool_end_payload.py` 全文替换为：

```python
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
```

（`test_tarot_artifact_without_cards_rejected` 使用与其它用例相同的 `_tool_end_payload`。）

- [ ] **Step 2: 运行测试确认失败（红）**

Run: `pytest backend/tests/test_tool_end_payload.py -v`
Expected: FAIL——现有返回二元组，断言三元组全部报错

- [ ] **Step 3: 实现 _tool_end_payload 泛化**

`backend/api/routes/chat.py:37-49` 替换为：

```python
def _tool_end_payload(output: object) -> tuple[str, dict | None, dict | None]:
    """on_tool_end 输出 → (渲染文本, nav, tarot)。

    data.output 是 ToolMessage：content 才是渲染文本，str() 是 pydantic
    repr（含 tool_call_id 噪声）——顺带修掉折叠区显示 repr 的旧观感
    问题；artifact 分两类下发：地图工具的 nav（content_and_artifact
    契约，LLM 只见 content 不见 artifact）与塔罗的 tarot 卡片数据。
    nav 判据要求 lat+lon（与前端 isValidNav 结构一致），tarot 判据
    要求非空 cards 列表——两者互斥，防地图按钮错挂到塔罗结果上。
    普通 str 输出原样兜底。
    """
    text = getattr(output, "content", None)
    if text is None:
        text = str(output)
    artifact = getattr(output, "artifact", None)
    nav = artifact if isinstance(artifact, dict) and "lat" in artifact and "lon" in artifact else None
    tarot = (
        artifact
        if isinstance(artifact, dict) and isinstance(artifact.get("cards"), list) and artifact["cards"]
        else None
    )
    return str(text), nav, tarot
```

`backend/api/routes/chat.py:396-411` 的 on_tool_end 分支替换为：

```python
                        elif kind == "on_tool_end":
                            name = event.get("name", "unknown")
                            output = event.get("data", {}).get("output", "")
                            text, nav, tarot = _tool_end_payload(output)
                            payload = {
                                "type": "tool_end",
                                "name": name,
                                "output": str(text)[:500],
                            }
                            # 地图工具附带目的地信息（GCJ-02 + 名称），
                            # 前端据此渲染「看地图/去这里」；塔罗附带卡片
                            # 数据，前端渲染翻牌网格。无 artifact → 不
                            # 下发对应字段，旧前端忽略未知字段天然兼容
                            if nav is not None:
                                payload["nav"] = nav
                            if tarot is not None:
                                payload["tarot"] = tarot
                            yield f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"
```

- [ ] **Step 4: 运行测试确认通过（绿）**

Run: `pytest backend/tests/test_tool_end_payload.py -v`
Expected: 9 个用例全 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/api/routes/chat.py backend/tests/test_tool_end_payload.py
git commit -m "feat(chat): tool_end 透传塔罗卡片数据 — nav 判据收紧防错挂，与 nav 并列互斥

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 前端 utils/tarot.js 校验函数

**Files:**
- Create: `web/src/utils/tarot.js`
- Test: `web/src/utils/tarot.test.js`

**Interfaces:**
- Consumes: 后端 SSE `tool_end` 事件的 `tarot` 字段（Task 2 产出）
- Produces: `isValidTarot(t) -> boolean`——Task 4 的 useChat.js 用它决定是否挂载卡片数据

- [ ] **Step 1: 写失败测试**

`web/src/utils/tarot.test.js`：

```js
import { describe, it, expect } from 'vitest'
import { isValidTarot } from './tarot'

const GOOD = {
  spread_type: 'three',
  spread_name: '三牌占卜',
  cards: [
    { name: '恋人', symbol: 'VI', suit: '大阿尔卡纳', element: '风', keywords: ['真爱'], is_reversed: false, position: '过去', fortune: 'x', meaning: ['x'] },
    { name: '权杖Ace', symbol: 'A', suit: '权杖', element: '火', keywords: [], is_reversed: true, position: '现在', fortune: 'y', meaning: ['y'] },
  ],
}

describe('isValidTarot', () => {
  it('合法 artifact → true', () => {
    expect(isValidTarot(GOOD)).toBe(true)
  })

  it('single / celtic 牌阵合法', () => {
    expect(isValidTarot({ ...GOOD, spread_type: 'single', cards: GOOD.cards.slice(0, 1) })).toBe(true)
    expect(isValidTarot({ ...GOOD, spread_type: 'celtic' })).toBe(true)
  })

  it('未知牌阵 → false', () => {
    expect(isValidTarot({ ...GOOD, spread_type: 'mega' })).toBe(false)
  })

  it('cards 缺失 / 空 / 非数组 → false', () => {
    expect(isValidTarot({ spread_type: 'three' })).toBe(false)
    expect(isValidTarot({ ...GOOD, cards: [] })).toBe(false)
    expect(isValidTarot({ ...GOOD, cards: 'nope' })).toBe(false)
  })

  it('card 缺 name / 缺 position → false（严格 shape 校验）', () => {
    const { name, ...noName } = GOOD.cards[0]
    expect(isValidTarot({ ...GOOD, cards: [noName] })).toBe(false)
    const { position, ...noPos } = GOOD.cards[0]
    expect(isValidTarot({ ...GOOD, cards: [noPos] })).toBe(false)
  })

  it('null / undefined / 非对象 → false', () => {
    expect(isValidTarot(null)).toBe(false)
    expect(isValidTarot(undefined)).toBe(false)
    expect(isValidTarot('x')).toBe(false)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd web && npx vitest run src/utils/tarot.test.js`
Expected: FAIL——模块不存在

- [ ] **Step 3: 实现校验函数**

`web/src/utils/tarot.js`：

```js
// 塔罗 artifact 校验 — 与 mapLink.isValidNav 同风格：前端严格 shape 校验，
// 脏数据 → false → 不渲染卡片、回退纯文本（后端只做宽松类型检查）
export function isValidTarot(t) {
  if (!t || typeof t !== 'object') return false
  const { spread_type, cards } = t
  if (spread_type !== 'single' && spread_type !== 'three' && spread_type !== 'celtic') return false
  if (!Array.isArray(cards) || cards.length === 0) return false
  return cards.every(c =>
    c && typeof c === 'object'
    && typeof c.name === 'string' && c.name.length > 0
    && typeof c.position === 'string' && c.position.length > 0
  )
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd web && npx vitest run src/utils/tarot.test.js`
Expected: 6 个用例全 PASS

- [ ] **Step 5: 提交**

```bash
git add web/src/utils/tarot.js web/src/utils/tarot.test.js
git commit -m "feat(web): tarot artifact 严格 shape 校验（isValidTarot）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: TarotCards 组件 + settle 动画 token

**Files:**
- Create: `web/src/components/TarotCards.jsx`
- Test: `web/src/components/TarotCards.test.jsx`
- Modify: `web/src/index.css`（@theme 增 `--animate-tarot-settle` + @keyframes tarotSettle）

**Interfaces:**
- Consumes: Task 3 的 `isValidTarot` 通过的 artifact（`{spread_type, spread_name, cards: [{name, symbol, suit, element, keywords, is_reversed, position, fortune, meaning}]}`）
- Produces: `TarotCards({ tarot })`——Task 5 的 MessageBubble 在 `message.tarot` 存在时渲染；内部自管翻牌状态机，无外部依赖

**视觉规范（spec 第三节）：** 卡背=奶油粉底+blush 波点+honey ✦ twinkle；牌面=白→cream 渐变+blush 描边；位置 pill strawberry/15 底 raspberry 字；逆位徽标 raspberry 底白字；关键词 pill blush/40 底 plum/70 字；含义 10px plum/50；牌名 font-display plum。对比度纪律：<12px 彩色文字用 raspberry。布局：single→1 列居中 / three→3 列 / celtic→2×5 紧凑（位置 truncate+图标+牌名+逆位徽标，无关键词含义）。

- [ ] **Step 1: 加动画 token（红的前提——先有类名可断言）**

`web/src/index.css` 的 `@theme` 块内（`--animate-bubble-pop` 行后）加：

```css
  --animate-tarot-settle: tarotSettle 0.3s cubic-bezier(0.16, 1, 0.3, 1) backwards;
```

`@keyframes` 区（`wiggle` keyframes 后）加：

```css
@keyframes tarotSettle {
  0% { transform: scale(1.03); }
  100% { transform: scale(1); }
}
```

- [ ] **Step 2: 写组件测试（红）**

`web/src/components/TarotCards.test.jsx`：

```jsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, act } from '@testing-library/react'
import TarotCards from './TarotCards'

const CARDS = [
  { name: '恋人', symbol: 'VI', suit: '大阿尔卡纳', element: '风', keywords: ['吸引力', '真爱', '选择'], is_reversed: false, position: '过去', fortune: '让真心带领你做出选择', meaning: ['让真心带领你做出选择'] },
  { name: '权杖Ace', symbol: 'A', suit: '权杖', element: '火', keywords: ['开始', '灵感'], is_reversed: true, position: '现在', fortune: '新的热情即将点燃', meaning: ['新的热情即将点燃'] },
  { name: '星币十', symbol: '10', suit: '星币', element: '地', keywords: ['富足'], is_reversed: false, position: '未来', fortune: '长久的积累带来安稳', meaning: ['长久的积累带来安稳'] },
]
const THREE = { spread_type: 'three', spread_name: '三牌占卜', cards: CARDS }

// jsdom 无 matchMedia：统一 stub 为可控对象（默认 no-preference）
function stubMatchMedia(matches = false) {
  window.matchMedia = vi.fn().mockReturnValue({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })
}

describe('TarotCards', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    stubMatchMedia(false)
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('三牌 → 3 列网格，初始全部卡背（未翻）', () => {
    render(<TarotCards tarot={THREE} />)
    const grid = screen.getByTestId('tarot-cards')
    expect(grid.className).toContain('grid-cols-3')
    expect(screen.getAllByText('✦')).toHaveLength(3)  // 三张卡背星标
    // 牌面内容初始不可见（翻转中或未翻）
    expect(screen.queryByText('恋人')).toBeNull()
  })

  it('单牌 → 1 列居中网格', () => {
    render(<TarotCards tarot={{ spread_type: 'single', spread_name: '单牌占卜', cards: [CARDS[0]] }} />)
    expect(screen.getByTestId('tarot-cards').className).toContain('grid-cols-1')
  })

  it('凯尔特十字 → 5 列紧凑网格，显示位置/牌名/图标/逆位徽标', () => {
    const ten = Array.from({ length: 10 }, (_, i) => ({ ...CARDS[i % 3], position: `位置${i + 1}` }))
    render(<TarotCards tarot={{ spread_type: 'celtic', spread_name: '凯尔特十字', cards: ten }} />)
    const grid = screen.getByTestId('tarot-cards')
    expect(grid.className).toContain('grid-cols-5')
    expect(screen.getAllByText('✦')).toHaveLength(10)
  })

  it('错峰翻牌：推进 600ms+stagger 后牌面内容出现', () => {
    render(<TarotCards tarot={THREE} />)
    // 第一张 120ms 翻，第二张 240ms，第三张 360ms；翻转本身由 CSS transition
    // 驱动（jsdom 无样式计算），组件侧只推进 flipped 状态 → 内容随状态出现
    act(() => { vi.advanceTimersByTime(600) })
    expect(screen.getByText('恋人')).toBeTruthy()
    expect(screen.getByText('权杖Ace')).toBeTruthy()
    expect(screen.getByText('星币十')).toBeTruthy()
  })

  it('翻牌完成后容器带 settle 动画类', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => { vi.advanceTimersByTime(600) })
    expect(screen.getByTestId('tarot-cards').className).toContain('animate-tarot-settle')
  })

  it('逆位牌显示「逆位」徽标（raspberry 底白字）', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => { vi.advanceTimersByTime(600) })
    expect(screen.getAllByText('逆位')).toHaveLength(1)
  })

  it('reduced-motion：初始即全部正面，无翻牌延迟', () => {
    stubMatchMedia(true)
    render(<TarotCards tarot={THREE} />)
    expect(screen.getByText('恋人')).toBeTruthy()
    expect(screen.getByText('权杖Ace')).toBeTruthy()
    expect(screen.getByText('星币十')).toBeTruthy()
  })

  it('含义文字出现在牌面底部（三牌全信息款）', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => { vi.advanceTimersByTime(600) })
    expect(screen.getByText('让真心带领你做出选择')).toBeTruthy()
    expect(screen.getByText('新的热情即将点燃')).toBeTruthy()
    expect(screen.getByText('长久的积累带来安稳')).toBeTruthy()
  })
})
```

注意：`stubMatchMedia` 必须在 `render` 前调用（`usePrefersReducedMotion` 的惰性初始 state 在首次渲染时同步读取 matchMedia）。fake timers 下 `vi.advanceTimersByTime(600)` 内触发的 setState 用 `act` 包裹。

- [ ] **Step 3: 运行测试确认失败（红）**

Run: `cd web && npx vitest run src/components/TarotCards.test.jsx`
Expected: FAIL——组件不存在

- [ ] **Step 4: 实现组件**

`web/src/components/TarotCards.jsx`：

```jsx
// 塔罗牌可视化 — 卡背 → 逐张翻牌 → 牌面网格。
// 数据来自后端 tool_end 事件的 tarot artifact（content_and_artifact 契约）：
// LLM 只见文本，卡片数据只在 SSE 下行；MessageBubble 在 message.tarot
// 存在时渲染本组件（isValidTarot 已挡掉脏数据）。
// 动画纯 CSS transition + 单定时器错峰，无动画库依赖；reduced-motion
// 初始即全翻（matchMedia 惰性读取，无首帧卡背闪烁）。
import { useEffect, useState } from 'react'

// 花色图标（与后端工具文本映射同源）
const SUIT_ICONS = { 大阿尔卡纳: '🌟', 权杖: '🔥', 圣杯: '💧', 宝剑: '⚔️', 星币: '🪙' }
const DEFAULT_ICON = '🃏'
// 错峰间隔：每 120ms 翻一张（单张翻转 600ms 由 CSS transition 驱动）
const STAGGER_MS = 120

function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  })
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = (e) => setReduced(e.matches)
    mq.addEventListener?.('change', onChange)
    return () => mq.removeEventListener?.('change', onChange)
  }, [])
  return reduced
}

export default function TarotCards({ tarot }) {
  const { spread_type, cards } = tarot
  const reduced = usePrefersReducedMotion()
  const [flipped, setFlipped] = useState(reduced ? cards.length : 0)

  // 错峰翻牌：每 STAGGER_MS 推进一张；全翻或 reduced-motion 后停止
  useEffect(() => {
    if (reduced || flipped >= cards.length) return undefined
    const t = setTimeout(() => setFlipped((c) => Math.min(cards.length, c + 1)), STAGGER_MS)
    return () => clearTimeout(t)
  }, [flipped, reduced, cards.length])

  const settled = flipped >= cards.length
  const gridClass =
    spread_type === 'single' ? 'grid-cols-1 justify-items-center'
    : spread_type === 'celtic' ? 'grid-cols-5'
    : 'grid-cols-3'

  return (
    <div
      className={`mt-1.5 grid gap-1.5 ${gridClass} ${settled ? 'animate-tarot-settle' : ''}`}
      data-testid="tarot-cards"
    >
      {cards.map((card, i) => (
        <TarotCard key={i} card={card} flipped={i < flipped} compact={spread_type === 'celtic'} />
      ))}
    </div>
  )
}

function TarotCard({ card, flipped, compact }) {
  const icon = SUIT_ICONS[card.suit] ?? DEFAULT_ICON
  return (
    <div className="relative w-full max-w-[140px] aspect-[3/5]" style={{ perspective: '600px' }}>
      <div
        className={`relative h-full w-full transition-transform duration-[600ms] ${flipped ? '[transform:rotateY(180deg)]' : ''}`}
        style={{ transformStyle: 'preserve-3d', transitionTimingFunction: 'cubic-bezier(0.16,1,0.3,1)' }}
        data-flipped={flipped}
      >
        {/* 卡背：奶油粉底 + blush 波点 + honey ✦ 呼吸微光 */}
        <div className="absolute inset-0 rounded-[14px] border border-strawberry/40 bg-cream bg-[radial-gradient(circle,rgba(243,195,214,0.55)_1.5px,transparent_1.5px)] bg-[size:10px_10px] shadow-[0_1px_4px_rgba(110,74,86,0.08)] [backface-visibility:hidden] flex items-center justify-center">
          <span className="text-honey text-lg animate-twinkle">✦</span>
        </div>
        {/* 牌面：白→cream 渐变 + blush 描边（3D 翻转的另一面） */}
        <div className="absolute inset-0 rounded-[14px] border border-blush bg-gradient-to-b from-white to-cream shadow-[0_1px_4px_rgba(110,74,86,0.08)] [backface-visibility:hidden] [transform:rotateY(180deg)] flex flex-col items-center px-1 py-1.5 overflow-hidden">
          {compact ? (
            // 凯尔特十字紧凑款：位置 truncate + 图标 + 牌名 + 逆位徽标
            // （含义文字由小汐的解读文本承载，卡片只做视觉）
            <>
              <span className="w-full text-center text-[8px] leading-tight text-plum/40 truncate">{card.position}</span>
              <span className="text-base leading-none my-1">{icon}</span>
              <span className="font-display text-[11px] leading-tight text-plum text-center">{card.name}</span>
              {card.is_reversed && <span className="mt-0.5 text-[7px] px-1 py-px rounded-full bg-raspberry text-white">逆位</span>}
            </>
          ) : (
            <>
              <div className="w-full flex items-center justify-between px-0.5">
                <span className="text-[8px] px-1.5 py-px rounded-full bg-strawberry/15 text-raspberry">{card.position}</span>
                {card.is_reversed && <span className="text-[8px] px-1.5 py-px rounded-full bg-raspberry text-white">逆位</span>}
              </div>
              <span className="text-lg leading-none my-1">{card.symbol} {icon}</span>
              <span className="font-display text-[13px] leading-tight text-plum text-center">{card.name}</span>
              <div className="mt-1 flex flex-wrap justify-center gap-0.5">
                {(card.keywords || []).slice(0, 3).map((k) => (
                  <span key={k} className="text-[7px] px-1 py-px rounded-full bg-blush/40 text-plum/70">{k}</span>
                ))}
              </div>
              <span className="mt-auto pt-0.5 text-center text-[8px] leading-snug text-plum/50 line-clamp-2">{card.fortune}</span>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: 运行测试确认通过（绿）**

Run: `cd web && npx vitest run src/components/TarotCards.test.jsx`
Expected: 8 个用例全 PASS。若 `screen.getByText('恋人')` 因文本跨元素拆分报错，改用 `getByText('恋人')` 精确匹配确认（牌名是独立 `<span>`，应无此问题）。

- [ ] **Step 6: lint + 提交**

Run: `cd web && npm run lint`
Expected: 无报错

```bash
git add web/src/components/TarotCards.jsx web/src/components/TarotCards.test.jsx web/src/index.css
git commit -m "feat(web): TarotCards 组件 — 卡背/牌面/错峰翻牌/自适应网格/逆位徽标

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: useChat 挂载 tarot + MessageBubble 集成

**Files:**
- Modify: `web/src/hooks/useChat.js:240-260`（tool_end 分支）
- Modify: `web/src/components/MessageBubble.jsx:42-63`（tool_hint 分支）
- Test: `web/src/hooks/useChat.test.js`（新增 2 用例）

**Interfaces:**
- Consumes: Task 3 的 `isValidTarot`；Task 4 的 `TarotCards({ tarot })`
- Produces: 消息对象上 `tarot` 字段；tool_hint 渲染链：`message.tarot` → `<TarotCards>`

- [ ] **Step 1: 写失败测试（红）**

`web/src/hooks/useChat.test.js` 末尾（`tool_end nav 非法` 用例后）追加：

```js
  it('tool_end 携带 tarot → 卡片数据与 output 同一次挂到 tool_hint', async () => {
    const { result } = renderHook(() => useChat('s'))
    const s = await openStream(result)
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"tarot"}\n')
      s.emit('data: {"type":"tool_end","name":"tarot","output":"🐋 小汐去映海里看了一下……","tarot":{"spread_type":"three","spread_name":"三牌占卜","cards":[{"name":"恋人","symbol":"VI","position":"过去"},{"name":"魔术师","symbol":"I","position":"现在"},{"name":"女皇","symbol":"III","position":"未来"}]}}\n')
      s.emit('data: [DONE]\n')
      s.close()
    })
    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.tarot.cards).toHaveLength(3)
    expect(hint.tarot.spread_type).toBe('three')
    expect(hint.output).toBeTruthy()  // 与卡片同一次原子挂载
  })

  it('tool_end tarot 脏数据（缺 name）→ 不挂 tarot，output 正常挂', async () => {
    const { result } = renderHook(() => useChat('s'))
    const s = await openStream(result)
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"tarot"}\n')
      s.emit('data: {"type":"tool_end","name":"tarot","output":"🐋 文本","tarot":{"spread_type":"three","spread_name":"三牌占卜","cards":[{"symbol":"VI","position":"过去"}]}}\n')
      s.emit('data: [DONE]\n')
      s.close()
    })
    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.tarot).toBeUndefined()
    expect(hint.output).toBeTruthy()
  })
```

（沿用文件顶部的 `renderHook`/`openStream`/`act` 既有设施——若文件用自定义 stream 辅助函数，用例里的 `renderHook(() => useChat('s'))` 与 `openStream` 以文件内既有写法为准。）

- [ ] **Step 2: 运行测试确认失败（红）**

Run: `cd web && npx vitest run src/hooks/useChat.test.js`
Expected: 新 2 用例 FAIL（`hint.tarot` undefined），旧用例 PASS

- [ ] **Step 3: useChat.js tool_end 分支挂载 tarot**

`web/src/hooks/useChat.js`：
- import 区加：`import { isValidTarot } from '../utils/tarot'`
- tool_end 分支替换为：

```js
            if (parsed.type === 'tool_end') {
              const output = summarizeOutput(parsed.output)
              const nav = isValidNav(parsed.nav) ? parsed.nav : null
              const tarot = isValidTarot(parsed.tarot) ? parsed.tarot : null
              // 一次 setMessages 挂 output/nav/tarot——二次挂载会被
              // !prev[i].output 挡掉，必须一次到位保证「有卡片就有数据」的原子性
              if (output || nav || tarot) {
                setMessages(prev => {
                  for (let i = prev.length - 1; i >= 0; i--) {
                    if (prev[i].type === 'tool_hint' && prev[i].toolName === parsed.name && !prev[i].output) {
                      const updated = [...prev]
                      updated[i] = {
                        ...updated[i],
                        ...(output ? { output } : {}),
                        ...(nav ? { nav } : {}),
                        ...(tarot ? { tarot } : {}),
                      }
                      return updated
                    }
                  }
                  return prev
                })
              }
              continue
            }
```

- [ ] **Step 4: MessageBubble 渲染 TarotCards**

`web/src/components/MessageBubble.jsx`：
- import 区加：`import TarotCards from './TarotCards'`
- tool_hint 分支的折叠块后、MapNavButtons 前加：

```jsx
          {/* 塔罗可视化：tarot 数据合法才渲染（isValidTarot 已挡脏数据）；
             无数据时回退纯文本折叠块，协议行为不变 */}
          {message.tarot && <TarotCards tarot={message.tarot} />}
          {/* 地图联动：nav 合法才渲染（buildNavTargets 内部校验，null 不出现） */}
          {message.nav && <MapNavButtons nav={message.nav} />}
```

- [ ] **Step 5: 运行测试确认通过（绿）**

Run: `cd web && npx vitest run src/hooks/useChat.test.js src/components/TarotCards.test.jsx`
Expected: 全部 PASS

- [ ] **Step 6: lint + 提交**

Run: `cd web && npm run lint`
Expected: 无报错

```bash
git add web/src/hooks/useChat.js web/src/hooks/useChat.test.js web/src/components/MessageBubble.jsx
git commit -m "feat(web): tool_end 挂载 tarot 卡片数据并渲染 TarotCards（MessageBubble 集成）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 全量回归 + 冒烟验证

**Files:** 无改动（验证任务）

- [ ] **Step 1: 后端全量测试**

Run: `pytest backend/tests/ -v`
Expected: 全 PASS（含 test_tarot.py、test_tool_end_payload.py 及既有回归）

- [ ] **Step 2: 前端全量测试 + lint**

Run: `cd web && npm test && npm run lint`
Expected: 全 PASS、lint 无报错

- [ ] **Step 3: 冒烟——真实对话抽三牌**

按 `docs/README.md` 或记忆「前后端一体架构与本机联调技巧」启动前后端（dist 由 FastAPI 托管 :8000），对话输入「帮我算一卦」，观察：
1. 工具提示出现「小汐在洗塔罗牌……」
2. tool_end 后聊天里出现 3 张卡背，逐张翻牌（每 120ms 错峰）
3. 翻完后牌面显示位置 pill/图标/牌名/关键词/含义，容器 settle 动画
4. 小汐的回复文本正常（LLM 仍基于完整文本写解读）
5. 凯尔特十字：说「用凯尔特十字算」，出现 2×5 紧凑网格
6. 降级验证：断网/牌组异常时无卡片、纯文本兜底（可选，单测已覆盖）

- [ ] **Step 4: 提交冒烟结论**

无代码改动时跳过；若有冒烟期发现的小修，按 Task 格式补提交并注明。

---

## Self-Review 结论

- **Spec 覆盖**：架构/数据流（Task 1+2）✓；utils 校验（Task 3）✓；卡背/牌面/动画/布局/逆位/reduced-motion（Task 4）✓；useChat 挂载 + MessageBubble 集成（Task 5）✓；验收清单（Task 6）✓。spec 的「非目标」（不生成牌面图、不改人格文本、不做点牌展开）无对应任务——符合预期。
- **类型一致性**：`isValidTarot`（Task 3）→ useChat（Task 5）→ `TarotCards({ tarot })`（Task 4/5）签名一致；`_tool_end_payload` 三元组（Task 2）与 chat.py 调用处一致；artifact 卡片字段名（name/symbol/suit/element/keywords/is_reversed/position/fortune/meaning）在 Task 1 产出、Task 2 判据（cards）、Task 4 渲染三处一致。
- **已知风险**：`test_tarot_artifact_without_cards_rejected` 中 `_test_payload` 笔误已在 Step 1 说明修正；useChat.test.js 的辅助函数名以文件既有写法为准（Step 1 已注明）。
