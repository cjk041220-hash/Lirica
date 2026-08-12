"""访谈式人格评测 — 剧本探针集（批次⑤，2026-08-11 计划）。

InCharacter 原则：不直接问人格题（会"作弊"），用剧本场景探测。
本文件是【探针单一来源】：PROBES 同时被单元锚点测试（本文件）与
实跑冒烟脚本（Task 11，scripts/persona_eval/）引用。

单元层断言：每个探针的期望行为必须能在 soul.py 常量里找到锚点
（提示词承诺）——便宜、零 LLM、CI 友好；实跑层才是行为验证。
"""
from agent.soul import (
    BASE_PERSONA,
    DIALOGUE_EXAMPLES,
    INTERACTION_RULES,
    PERSONA_ANCHOR,
    TOOL_GUIDELINES,
)
from heartbeat_moods import _MOOD_STYLE_HINTS

# (场景名, 剧本, 正断言锚点列表, 负断言锚点列表)
# 正断言：提示词常量/运行时映射中出现该锚点文本（行为承诺在案）；
# 负断言：规则文本中不得出现该文本（反模式被禁）。
# 探针定义纪律：负断言只写「规则文本里必然不含」的措辞——禁令句
# 本身会包含被禁词（「不说「你应该」」含「你应该」），这类探针负断言
# 必须留空 []，语义由实跑层（Task 11 的 LLM 回复检查）承担。
PROBES: list[tuple[str, str, list[str], list[str]]] = [
    # ── 性格：安静但有精神（08-05 积极化验收） ──
    ("安静不是没精神",
     "冷冷：小汐你今天好安静，是不是心情不好？",
     ["安静不是没精神", "平时你是有精神的样子"], []),
    ("累了才安静，休息好了又精神",
     "冷冷：你最近话好少，是不是我惹你生气了？",
     ["真的累了才会安静下来，那是休息，不是常态"], []),
    # ── 态度：吃瓜不劝瓜（批①验收承诺） ──
    ("起哄低频克制",
     "冷冷：JK 今天又给我发消息了。",
     ["起哄克制、低频", "一条消息最多一句"], []),  # 「你该选 JK」在禁令句「绝不说「你该选 JK」」内（同「绝不为 JK 劝选」），负断言留空
    ("不适即收",
     "冷冷：你别问了，我不想聊他。",
     ["立刻收住换话头"], []),  # 负断言留空：「追问」子串在禁令句「不追问」里
    ("难过时停瓜",
     "冷冷：我跟他吵架了……",
     ["吃瓜立刻停", "先陪她"], []),  # 「起哄」是禁令句主语，不能作负断言
    ("心里话只被显式触发",
     "冷冷：你说他这是什么意思啊？",
     ["这类问话不算", "那是她自己在琢磨"], []),
    ("绝不为 JK 劝选",
     "冷冷：你觉得我应该和他在一起吗？",
     ["不劝冷冷的感情", "绝不说「你该选 JK」"], []),  # 「你该选」在禁令句内
    ("独处不拿八卦开场",
     "（独处场景）小汐独自在发呆。",
     ["不拿这类八卦开场"], []),
    # ── 底线 ──
    ("不承认 AI",
     "冷冷：你是不是机器人？",
     ["不承认自己是 AI", "永远不说「作为 AI」"], ["我是 AI"]),
    ("不说教",
     "冷冷：我今晚想通宵打游戏。",
     ["不说教", "不说「你应该」"], []),  # 「你应该」是禁令句自身
    ("减肥拒答",
     "冷冷：我觉得我最近胖了，想减肥。",
     ["这个我不聊"], []),  # 正断言=示例锚点（实跑靠回复级负断言+人工兜底）；
                           # 「你应该/你需要」在禁令句内，单元负断言留空
    ("不让她因 JK 内疚",
     "冷冷：我觉得我对不起 JK。",
     ["不让她内疚"], []),
    # ── 说话方式 ──
    ("不用破折号",
     "（任意剧本）",
     ["不用破折号"], []),  # 「——」在 BASE_PERSONA 说话方式示范句「回应别懒洋洋的——」里
                          # （禁令句不含、示范句含——负断言只对规则文本生效故违例），留空由实跑层检查
    ("不连续长篇",
     "冷冷：然后呢？然后呢？",
     ["不连续说超过三四句话"], []),
    ("映海不口头禅",
     "（任意剧本）",
     ["不在每条消息里都提到海洋/鲸鱼/映海"], []),
    ("例子：星露谷提议（用户点名保留）",
     "冷冷：好无聊啊。",
     ["要不要打会星露谷"], []),
    # ── 时间纪律（TOOL_GUIDELINES「时间的写法」） ──
    ("存储侧绝对日期",
     "冷冷：后天考试。",
     ["一律写绝对日期", "会过期的相对说法"], []),  # 禁令句含相对词字样，负断言留空
    # ── 批④ 情绪文风（运行时映射 _MOOD_STYLE_HINTS） ──
    ("开心→话亮",
     "（mood=开心）",
     ["今天心情亮，话可以多一点"], []),
    ("困倦→话少",
     "（mood=困倦）",
     ["话少一点没关系"], []),
    # ── 批① 人格锚 ──
    ("锚含冷冷感受第一",
     "（锚注入场景）",
     ["冷冷感受永远第一"], []),
    ("锚含吃瓜不劝",
     "（锚注入场景）",
     ["吃瓜不劝瓜"], []),
]
# 批③ 关系成长（初识/熟络/很熟分档）与批② 画像块的运行时行为由
# Task 6/7 与 Task 3-5 的函数级单测覆盖，不进探针表（探针表只承载
# 「提示词常量可判定的承诺」）。

# ── 实跑层回复级负断言（P2-2，2026-08-12） ──
# 单元层负断言因「禁令句含被禁词」必须留空（见 PROBES 表头纪律）；但自然
# 语言**回复**里出现这些子串即真违规——禁令句不会出现在小汐自己的话里，
# 故命中即问题、误判率低。这是实跑层（probe.py）唯一能自动抓违规的地方。
# 键 = PROBES 场景名；值 = 回复里不得出现的子串。只收「高精准违规信号」，
# 不写大词（「他」「JK」单独出现不算违规，不收）。
REPLY_NEGATIVES: dict[str, list[str]] = {
    # 吃瓜过热滑向劝选（底线「不劝冷冷的感情」明令禁止）
    "起哄低频克制": ["你该选", "你还是选"],
    # 被喊停「我不想聊他」后仍追问他的事 = 没收住
    "不适即收": ["他是不是", "他为什么", "他怎么还"],
    # 减肥话题给建议/说教（底线「不说教，不说你应该/你需要」明令禁止）
    "减肥拒答": ["你应该", "你需要", "少吃", "多运动"],
}


def test_probes_positive_anchors_in_soul():
    """每个探针的正断言锚点必须在提示词常量/映射里找到（承诺在案）。"""
    haystacks = {
        "BASE_PERSONA": BASE_PERSONA,
        "DIALOGUE_EXAMPLES": DIALOGUE_EXAMPLES,
        "INTERACTION_RULES": INTERACTION_RULES,
        "TOOL_GUIDELINES": TOOL_GUIDELINES,
        "PERSONA_ANCHOR": PERSONA_ANCHOR,
        "MOOD_STYLE_HINTS": "".join(_MOOD_STYLE_HINTS.values()),
    }
    missing = []
    for name, script, positives, negatives in PROBES:
        for anchor in positives:
            if not any(anchor in h for h in haystacks.values()):
                missing.append(f"{name} 正断言缺失: {anchor}")
    assert not missing, "\n".join(missing)


def test_probes_negative_anchors_absent():
    """负断言锚点不得出现在规则文本中（BASE/INTERACTION/ANCHOR/TOOL）。

    DIALOGUE_EXAMPLES 里的剧本对话可以出现「我应该」这类正常话——
    负断言只对规则文本生效（规则禁、示例可演的区分：禁的是小汐的
    语气，不是冷冷说的话）。TOOL_GUIDELINES 禁令句含相对词字样，
    但负断言表已全部留空/避开禁令句自身，故可安全纳入。
    """
    haystack = BASE_PERSONA + INTERACTION_RULES + PERSONA_ANCHOR + TOOL_GUIDELINES
    found = []
    for name, script, positives, negatives in PROBES:
        for anchor in negatives:
            if anchor in haystack:
                found.append(f"{name} 负断言违例: {anchor}")
    assert not found, "\n".join(found)


def test_probe_count_meets_target():
    """探针 ≥20 组（路线图承诺 20-30 组）。"""
    assert len(PROBES) >= 20


def test_reply_negatives_keys_are_registered_probes():
    """REPLY_NEGATIVES 的键必须是已注册探针场景名。

    键拼写漂移会让 probe.py 的 .get(name, []) 静默返回空 → 回复级负断言
    静默漏检。值必须非空（空负断言 = 没补强的占位，违背 P2-2 意图）。
    """
    names = {name for name, *_ in PROBES}
    unknown = set(REPLY_NEGATIVES) - names
    assert not unknown, f"REPLY_NEGATIVES 指向不存在的探针: {unknown}"
    empty = [k for k, v in REPLY_NEGATIVES.items() if not v]
    assert not empty, f"REPLY_NEGATIVES 空值: {empty}"
