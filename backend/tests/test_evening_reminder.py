"""前夜提醒测试 — daily_evening_reminder（每日 21:00：明日有日程才出声）。

钉死四条行为契约：
1. 无日程 → 完全静默（不调 LLM、不落库）——没安排时的安静也是体贴；
2. 有日程 → LLM 生成 + 走统一推送流水线（label=前夜提醒）；
3. 白名单过滤 → 台风等天气类事件不触发（它有聊天关心路径，不双重打扰）；
4. LLM 挂了 → 固定文案兜底，提醒不丢（同早安的降级纪律）。
"""
import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from utils import events as ev
from utils.timezone import HKT as _HKT


def _tomorrow() -> str:
    return (datetime.datetime.now(_HKT).date() + datetime.timedelta(days=1)).isoformat()


@pytest.fixture
async def hb_db(tmp_db):
    """heartbeat 与 utils.events 共用同一临时库。"""
    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        yield tmp_db


async def test_no_events_stays_silent(hb_db):
    store = AsyncMock()
    with patch("heartbeat._store_and_push_proactive", store), \
         patch("providers.deepseek.create_deepseek_llm",
               side_effect=AssertionError("无日程时不允许调用 LLM")):
        import heartbeat as hb
        await hb.daily_evening_reminder()
    store.assert_not_awaited()


async def test_tomorrow_event_triggers_reminder(hb_db):
    await ev.create_event("期中考试", "exam", _tomorrow(), severity="high")

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(
        return_value=MagicMock(content="对啦冷冷，明天期中考哦，今晚早点休息，可以的！")
    )
    store = AsyncMock()
    with patch("heartbeat._store_and_push_proactive", store), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm), \
         patch("api.routes.config.get_current_model", return_value="deepseek-v4-flash"):
        import heartbeat as hb
        await hb.daily_evening_reminder()

    store.assert_awaited_once()
    assert "期中考" in store.call_args.args[0]
    assert store.call_args.kwargs["label"] == "前夜提醒"

    # prompt 里带上了事件与「很重要」标记
    human = fake_llm.ainvoke.call_args.args[0][-1]
    assert "期中考试" in human.content
    assert "很重要" in human.content


async def test_typhoon_tomorrow_not_reminded(hb_db):
    """天气类事件是内心戏：走主动消息关心路径，前夜提醒不双份。"""
    await ev.create_event(
        f"{_tomorrow()} 可能有台风", "typhoon", _tomorrow(),
        source="weather", severity="high",
        dedupe_key=f"weather:wind:{_tomorrow()}",
    )
    store = AsyncMock()
    with patch("heartbeat._store_and_push_proactive", store):
        import heartbeat as hb
        await hb.daily_evening_reminder()
    store.assert_not_awaited()


async def test_llm_failure_falls_back_to_canned(hb_db):
    """LLM 挂了不丢提醒：固定文案兜底（含事件名），仍走流水线。"""
    await ev.create_event("牙医预约", "health", _tomorrow())

    store = AsyncMock()
    with patch("heartbeat._store_and_push_proactive", store), \
         patch("providers.deepseek.create_deepseek_llm",
               side_effect=RuntimeError("LLM 宕机")):
        import heartbeat as hb
        await hb.daily_evening_reminder()

    store.assert_awaited_once()
    content = store.call_args.args[0]
    assert "牙医预约" in content
    assert "别忘了" in content
