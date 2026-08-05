"""存储侧时间词归一化 — 相对时间词在落库时改写为绝对日期。

背景（2026-08-05 用户反馈）：小汐记「后天考试」这类安排时，LLM 写下的
记忆内容 / 事件备注里常常带着「今天/明天/后天/明早」等相对说法——日子
一过，召回时就读不懂了（用户两天前让记的「后天考试」，当天再问就被
答错；日历字段本身是绝对日期，问题出在 LLM 写的自由文本上）。

本函数是写路径的统一兜底：把日级相对词按写入当下（HKT）的日期改写为
「M月D日（早上/晚上）」，保证落库文本不随时间腐化。只做日级词
（今天/明天/后天/昨天/今晚/明早/明晚等）——周/月级词（下周、下个月）
语义模糊（跨周界怎么算？）交给提示词约束解决，见 soul.py「时间的写法」。

与提示词约束的分工：soul.py「时间的写法」与提取提示词先教模型直接写
绝对日期（少返工、对话转述也自然）；本函数是硬保证——模型没照办也拦
得住。读侧（事件上下文注入的「明天/后天」标签、对话里的当场说法）不
经过这里，相对说法依然可以在「当下」使用。
"""
import datetime

from utils.timezone import HKT

# (原词, 天数偏移, 时段后缀)。顺序敏感：大前天/大后天必须先于前天/后天，
# 否则「大后天」里的「后天」被先替换成日期，漏改首字。
# 明早/明晚必须先于明天？不依赖——三者无子串包含关系，任意序都正确。
_WORD_RULES = [
    ("大前天", -3, ""),
    ("大后天", 3, ""),
    ("前天", -2, ""),
    ("后天", 2, ""),
    ("明早", 1, "早上"),
    ("明晚", 1, "晚上"),
    ("明天", 1, ""),
    ("昨晚", -1, "晚上"),
    ("今晚", 0, "晚上"),
    ("昨天", -1, ""),
    ("今天", 0, ""),
]


def normalize_time_words(text: str, *, today: datetime.date | None = None) -> str:
    """把文本中的相对时间词改写为绝对日期；无时间词则原样返回。

    today 默认取 HKT 当天（可注入以便测试钉死日期，与 utils.events
    的 _today() 纪律一致）。
    """
    if not text:
        return text
    if today is None:
        today = datetime.datetime.now(HKT).date()
    out = text
    for word, delta, period in _WORD_RULES:
        if word not in out:
            continue
        d = today + datetime.timedelta(days=delta)
        replacement = f"{d.month}月{d.day}日"
        if period:
            replacement += period
        out = out.replace(word, replacement)
    return out
