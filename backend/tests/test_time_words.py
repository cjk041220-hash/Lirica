"""utils/time_words 单元测试 — 相对时间词改写为绝对日期。

钉死 2026-08-05 便于断言（today 可注入，与 utils.events 的 _today()
纪律一致，不依赖运行日）。
"""
import datetime

from utils.time_words import normalize_time_words

TODAY = datetime.date(2026, 8, 5)


def test_today_rewritten():
    assert normalize_time_words("考试在明天", today=TODAY) == "考试在8月6日"


def test_day_after_tomorrow():
    assert normalize_time_words("后天考试", today=TODAY) == "8月7日考试"


def test_day_before_after_before_after_order():
    """大后天/大前天必须先于后天/前天替换（子串包含顺序）。"""
    assert normalize_time_words("大后天出门", today=TODAY) == "8月8日出门"
    assert normalize_time_words("大前天的事", today=TODAY) == "8月2日的事"


def test_yesterday_and_day_before():
    assert normalize_time_words("昨天去看医生", today=TODAY) == "8月4日去看医生"
    assert normalize_time_words("前天吵架了", today=TODAY) == "8月3日吵架了"


def test_period_words():
    """明早/明晚/今晚/昨晚带时段后缀。"""
    assert normalize_time_words("明早要早起", today=TODAY) == "8月6日早上要早起"
    assert normalize_time_words("明晚有约", today=TODAY) == "8月6日晚上有约"
    assert normalize_time_words("今晚早点睡", today=TODAY) == "8月5日晚上早点睡"
    assert normalize_time_words("昨晚没睡好", today=TODAY) == "8月4日晚上没睡好"


def test_multiple_words_in_one_text():
    assert (
        normalize_time_words("明天考试，后天出发，今天先复习", today=TODAY)
        == "8月6日考试，8月7日出发，8月5日先复习"
    )


def test_no_time_words_unchanged():
    assert normalize_time_words("喜欢吃芒果", today=TODAY) == "喜欢吃芒果"


def test_empty_and_none():
    assert normalize_time_words("", today=TODAY) == ""
    assert normalize_time_words(None, today=TODAY) is None


def test_default_today_is_hkt():
    """不注入 today 时用 HKT 当天（不炸即可，具体日期随运行日）。"""
    out = normalize_time_words("今天下雨")
    assert "月" in out and "日" in out and "下雨" in out
