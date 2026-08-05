"""异常感知关心（Spec B）—— 纯函数检测器 + 冷却台账测试。"""
import datetime as dt

import pytest

from agent import care
from utils.timezone import HKT


def _now(d=5, h=2, mi=14):
    """固定基准：2026-08-05（周三）HKT。"""
    return dt.datetime(2026, 8, d, h, mi, tzinfo=HKT)


# ── 纯函数检测器 ──────────────────────────────────────────────


def test_late_night_window_boundaries():
    """深夜窗口边界：00:00 / 04:59 触发，05:00 不触发。"""
    assert [h["type"] for h in care.detect_care_hints(_now(h=0, mi=0), [], [])] == [care.LATE_NIGHT]
    assert [h["type"] for h in care.detect_care_hints(_now(h=4, mi=59), [], [])] == [care.LATE_NIGHT]
    assert care.detect_care_hints(_now(h=5, mi=0), [], []) == []


def test_late_night_counts_nights_dedup_by_day():
    """近 7 天深夜消息按天去重计数；历史晚数 + 当前这晚，N≥2 才注明。"""
    now = _now(d=5, h=2)
    # 两个时间戳同在 08-03（算一晚）、一个在 08-04 → 历史 2 晚
    times = [_now(d=3, h=1, mi=0), _now(d=3, h=3, mi=30), _now(d=4, h=2, mi=0)]

    hints = care.detect_care_hints(now, times, [])
    assert len(hints) == 1
    assert "第 3 个熬夜的夜晚" in hints[0]["text"]  # 历史 2 晚 + 当前

    hints_first = care.detect_care_hints(now, [], [])
    assert hints_first[0]["text"] == "冷冷现在还没睡。"  # 首晚不注明


def test_event_combo_absorbs_late_night():
    """24h 内有考试 + 深夜 → event_combo 命中并吞并 late_night。"""
    now = _now(d=5, h=2)
    events = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-05"}]

    hints = care.detect_care_hints(now, [], events)
    assert [h["type"] for h in hints] == [care.EVENT_COMBO]
    assert "今天有「期末考」，她还没睡" == hints[0]["text"]

    # 明天有考试 → 「明天」措辞
    events_tm = [{"title": "模拟考", "event_type": "exam", "event_date": "2026-08-06"}]
    hints_tm = care.detect_care_hints(now, [], events_tm)
    assert hints_tm[0]["text"] == "明天有「模拟考」，她还没睡"

    # 跨多天（昨天开始、明天结束）→ 仍算今天
    events_span = [{"title": "考试周", "event_type": "exam",
                    "event_date": "2026-08-04", "end_date": "2026-08-06"}]
    hints_span = care.detect_care_hints(now, [], events_span)
    assert hints_span[0]["text"] == "今天有「考试周」，她还没睡"


def test_event_combo_negative_cases():
    """事件在后天 / 非考试类型 / 白天有考试 → 不构成 combo。"""
    now = _now(d=5, h=2)
    far = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-07"}]
    assert [h["type"] for h in care.detect_care_hints(now, [], far)] == [care.LATE_NIGHT]

    trip = [{"title": "旅行", "event_type": "trip", "event_date": "2026-08-05"}]
    assert [h["type"] for h in care.detect_care_hints(now, [], trip)] == [care.LATE_NIGHT]

    daytime_exam = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-05"}]
    assert care.detect_care_hints(_now(d=5, h=15), [], daytime_exam) == []


def test_returned_gap_thresholds():
    """距上一条用户消息：4 天不触发 / 5 天触发 / 回来后不再触发。"""
    now = _now(d=5, h=15)

    assert care.detect_care_hints(now, [now - dt.timedelta(days=4, hours=23)], []) == []

    hints = care.detect_care_hints(now, [now - dt.timedelta(days=5)], [])
    assert [h["type"] for h in hints] == [care.RETURNED]
    assert "她已经 5 天没来了，这是回来了" == hints[0]["text"]

    # 触发后当前消息归档为最新 → 间隔归零 → 下一条不再触发
    assert care.detect_care_hints(now, [now - dt.timedelta(minutes=1)], []) == []


def test_returned_not_fired_for_empty_history():
    """从未对话（新库）→ 无「回来」可言。"""
    assert care.detect_care_hints(_now(h=15), [], []) == []
