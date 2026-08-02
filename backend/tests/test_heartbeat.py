"""心跳调度器单元测试 — 时段匹配逻辑。"""
import pytest

from heartbeat import (
    get_current_period,
    TIME_PERIODS,
    LATE_NIGHT,
    MORNING,
    DAYTIME,
    EVENING,
)


async def test_get_current_period_late_night():
    """凌晨 3 点 → 深夜时段。"""
    period = get_current_period(3)
    assert period is TIME_PERIODS[LATE_NIGHT]
    assert "困倦" in period["moods"]


async def test_get_current_period_morning():
    """上午 9 点 → 早晨时段。"""
    period = get_current_period(9)
    assert period is TIME_PERIODS[MORNING]
    assert "好奇" in period["moods"]


async def test_get_current_period_daytime():
    """下午 3 点 → 白天时段。"""
    period = get_current_period(15)
    assert period is TIME_PERIODS[DAYTIME]
    assert "开心" in period["moods"]


async def test_get_current_period_evening():
    """晚上 9 点 → 夜晚时段。"""
    period = get_current_period(21)
    assert period is TIME_PERIODS[EVENING]
    assert "满足" in period["moods"]


async def test_get_current_period_boundary_7():
    """7:00 属于早晨（左闭右开）。"""
    period = get_current_period(7)
    assert period is TIME_PERIODS[MORNING]


async def test_get_current_period_boundary_0():
    """0:00 属于深夜。"""
    period = get_current_period(0)
    assert period is TIME_PERIODS[LATE_NIGHT]


async def test_get_current_period_boundary_23():
    """23:00 属于夜晚。"""
    period = get_current_period(23)
    assert period is TIME_PERIODS[EVENING]


async def test_check_and_proactive_message_updates_db(tmp_db):
    """心跳任务执行后 life_state 表被更新。"""
    from unittest.mock import patch
    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    original = hb.get_db
    hb.get_db = mock_get_db

    try:
        await hb.check_and_proactive_message()

        cursor = await tmp_db.execute("SELECT mood, daily_event FROM life_state WHERE id = 1")
        row = await cursor.fetchone()
        assert row is not None
        assert row[0] is not None  # mood 已更新
        assert row[1] is not None  # daily_event 已更新
    finally:
        hb.get_db = original


# ── 调度注册回归测试（v0.4.5 修复 P0：心跳协程从未被 await）──────────
#
# v0.4.4 缺陷：心跳 job 注册为
#     scheduler.add_job(lambda: check_and_proactive_message(app), ...)
# AsyncIOScheduler 只对协程函数走 await 路径，lambda 被同步调用后
# 协程对象直接丢弃 → 心跳链路（生命状态刷新 / 随机搭话 / 生理期关心）
# 整体静默，日志伴随 "coroutine ... was never awaited"。
#
# 以下两个测试钉死修复：
# 1. 注册形态正确（job.func 必须是协程函数）
# 2. 真实调度器触发后，主动消息真的落库（端到端管线）


def test_registered_jobs_are_awaitable():
    """所有心跳 job 必须以协程函数（async def）直接注册。

    若注册成 lambda / 普通函数包裹，AsyncIOScheduler 会同步调用它、
    丢弃返回的协程对象，任务永不执行。
    """
    import inspect
    from apscheduler.schedulers.asyncio import AsyncIOScheduler
    import heartbeat as hb

    test_scheduler = AsyncIOScheduler()
    original = hb.scheduler
    hb.scheduler = test_scheduler
    try:
        hb.register_jobs(app=None)
        jobs = {j.id: j for j in test_scheduler.get_jobs()}
        assert "proactive_check" in jobs, "心跳 job proactive_check 未注册"
        assert "daily_morning_greeting" in jobs, "早安 job daily_morning_greeting 未注册"
        assert "daily_evening_reminder" in jobs, "前夜提醒 job daily_evening_reminder 未注册"
        assert "event_watchdog" in jobs, "事件看门狗 job event_watchdog 未注册"
        assert "daily_diary" in jobs, "自动日记 job daily_diary 未注册"
        for job_id, job in jobs.items():
            assert inspect.iscoroutinefunction(job.func), (
                f"{job_id} 注册为 {job.func!r}，不是协程函数 —— "
                "AsyncIOScheduler 会同步调用并丢弃协程，任务永不执行"
            )
    finally:
        hb.scheduler = original


# ── 事件看门狗 ────────────────────────────────────────────────


async def test_event_watchdog_runs_detectors_and_refreshes():
    """12 点（6 小时检测点）：归档 + 两个检测器都跑，有变动则刷新提示词。"""
    from unittest.mock import AsyncMock, patch
    import datetime as _dt
    from datetime import timezone, timedelta
    import heartbeat as hb

    from utils.timezone import HKT as _HKT
    fake_now = _dt.datetime(2026, 7, 25, 12, 0, tzinfo=_HKT)  # 12 % 6 == 0

    with patch("utils.events.archive_expired", new_callable=AsyncMock, return_value=1) as archive, \
         patch("utils.events.detect_profile_events", new_callable=AsyncMock, return_value=0) as profile, \
         patch("utils.events.detect_weather_events", new_callable=AsyncMock, return_value=0) as weather, \
         patch("utils.events.detect_cycle_events", new_callable=AsyncMock, return_value=0) as cycle, \
         patch("heartbeat.datetime") as mock_dt, \
         patch("heartbeat._refresh_agent", new_callable=AsyncMock) as refresh:
        mock_dt.datetime.now.return_value = fake_now
        await hb.event_watchdog(app=object())

    archive.assert_awaited_once()
    profile.assert_awaited_once()
    weather.assert_awaited_once()     # 12 点是 6 小时检测点
    cycle.assert_awaited_once()       # 生理期检测每次都跑（纯本地毫秒级）
    refresh.assert_awaited_once()     # 归档了 1 条 → 上下文变了 → 刷新提示词


async def test_event_watchdog_skips_weather_off_schedule():
    """非 6 小时点不跑天气检测；无变动时不刷新提示词。"""
    from unittest.mock import AsyncMock, patch
    import datetime as _dt
    from datetime import timezone, timedelta
    import heartbeat as hb

    from utils.timezone import HKT as _HKT
    fake_now = _dt.datetime(2026, 7, 25, 13, 0, tzinfo=_HKT)  # 13 % 6 != 0

    with patch("utils.events.archive_expired", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_profile_events", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_weather_events", new_callable=AsyncMock) as weather, \
         patch("utils.events.detect_cycle_events", new_callable=AsyncMock, return_value=0), \
         patch("heartbeat.datetime") as mock_dt, \
         patch("heartbeat._refresh_agent", new_callable=AsyncMock) as refresh:
        mock_dt.datetime.now.return_value = fake_now
        await hb.event_watchdog(app=object())

    weather.assert_not_awaited()      # 不是 6 小时检测点
    refresh.assert_not_awaited()      # 无变动不刷新


async def test_event_watchdog_cycle_drift_alone_triggers_refresh():
    """纯周期漂移（其他检测器全 0，只有 cycle 返回归档数）也刷新提示词。"""
    from unittest.mock import AsyncMock, patch
    import datetime as _dt
    import heartbeat as hb
    from utils.timezone import HKT as _HKT

    fake_now = _dt.datetime(2026, 7, 25, 13, 0, tzinfo=_HKT)

    with patch("utils.events.archive_expired", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_profile_events", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_weather_events", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_anniversary_events", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_memory_echoes", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_cycle_events", new_callable=AsyncMock, return_value=2) as cycle, \
         patch("heartbeat.datetime") as mock_dt, \
         patch("heartbeat._refresh_agent", new_callable=AsyncMock) as refresh:
        mock_dt.datetime.now.return_value = fake_now
        await hb.event_watchdog(app=object())

    cycle.assert_awaited_once()
    refresh.assert_awaited_once()     # 漂移归档算事件变动 → 必须刷新


# ── 自动日记 ──────────────────────────────────────────────────


async def test_daily_diary_writes_entry(tmp_db):
    """自动日记任务：LLM 生成内容写入 diaries 表（当天一篇）。"""
    from unittest.mock import AsyncMock, MagicMock, patch
    import heartbeat as hb

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(
        return_value=MagicMock(content="今天映海很安静。小汐数了数水母，然后想了冷冷一会儿。")
    )

    original_get_db = hb.get_db

    async def mock_get_db():
        return tmp_db

    hb.get_db = mock_get_db
    try:
        with patch("heartbeat.get_db", mock_get_db), \
             patch("utils.events.get_db", return_value=tmp_db), \
             patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
            await hb.daily_diary()
    finally:
        hb.get_db = original_get_db

    cursor = await tmp_db.execute("SELECT date, title, content FROM diaries")
    row = await cursor.fetchone()
    assert row is not None
    assert "水母" in row[2]
    assert "月" in row[1] and "日" in row[1]  # 标题形如 07月25日


async def test_daily_diary_does_not_overwrite_manual_entry(tmp_db):
    """回归：白天冷冷已让小汐手记过日记时，自动日记追加而不是覆盖。"""
    from unittest.mock import AsyncMock, MagicMock, patch
    import datetime
    from datetime import timezone, timedelta

    from utils.timezone import HKT as _HKT
    today = datetime.datetime.now(_HKT).date().isoformat()

    # 白天手记的一篇
    await tmp_db.execute(
        "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
        (today, "手写标题", "白天冷冷让小汐记下的事。"),
    )
    await tmp_db.commit()

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(return_value=MagicMock(content="晚上的补记内容。"))

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        import heartbeat as hb
        await hb.daily_diary()

    cursor = await tmp_db.execute("SELECT content FROM diaries WHERE date = ?", (today,))
    content = (await cursor.fetchone())[0]
    assert "白天冷冷让小汐记下的事。" in content  # 手记内容保留
    assert "晚上的补记内容。" in content          # 晚间补记追加
    assert content.index("白天") < content.index("晚上")  # 顺序正确


async def test_daily_diary_llm_failure_uses_fallback(tmp_db):
    """LLM 挂了也有兜底文案，不会留空日记。"""
    from unittest.mock import AsyncMock, MagicMock, patch

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(side_effect=RuntimeError("API down"))

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        import heartbeat as hb
        await hb.daily_diary()

    cursor = await tmp_db.execute("SELECT content FROM diaries")
    row = await cursor.fetchone()
    assert row is not None
    assert len(row[0]) >= 5  # 兜底文案非空


async def test_daily_diary_preserves_manual_title(tmp_db):
    """晚间补记不得覆盖白天手记的自定义标题（此前被「MM月DD日」冲掉）。"""
    from unittest.mock import AsyncMock, MagicMock, patch
    import datetime

    from utils.timezone import HKT as _HKT
    today = datetime.datetime.now(_HKT).date().isoformat()

    await tmp_db.execute(
        "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
        (today, "冷冷起的标题", "白天的手记。"),
    )
    await tmp_db.commit()

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(return_value=MagicMock(content="晚上的补记。"))

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        import heartbeat as hb
        await hb.daily_diary()

    cursor = await tmp_db.execute(
        "SELECT title, content FROM diaries WHERE date = ?", (today,)
    )
    title, content = await cursor.fetchone()
    assert title == "冷冷起的标题"          # 白天标题保留
    assert "白天的手记。" in content        # 内容追加不覆盖
    assert "晚上的补记。" in content


async def test_morning_greeting_malformed_daily_keeps_current_weather(tmp_db):
    """今日预报字段畸形（[None]）时：保留已构建的当前天气，只降级预报段。

    旧写法 int(None) 抛 TypeError 被外层 except 吞掉，把当前天气
    整段清空，日志还谎报「天气 API 调用失败」。
    """
    from unittest.mock import AsyncMock, MagicMock, patch

    malformed = {
        "current": {
            "weather_code": 1,
            "temperature_2m": 28.5,
            "apparent_temperature": 31.0,
            "relative_humidity_2m": 80,
            "wind_speed_10m": 12,
        },
        # Open-Meteo 该字段可能返回 null 元素 —— int(None) 曾炸掉整段
        "daily": {"precipitation_probability_max": [None]},
    }

    async def fake_fetch(client, lat, lon):
        return malformed

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(return_value=MagicMock(content="早安呀冷冷～今天也要开开心心的哦！"))
    captured = {}

    async def fake_ainvoke(messages, **kwargs):
        captured["messages"] = messages
        return MagicMock(content="早安呀冷冷～今天也要开开心心的哦！")

    fake_llm.ainvoke = fake_ainvoke

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("tools.weather._fetch_open_meteo", fake_fetch), \
         patch("utils.web_push.send_push", AsyncMock(return_value=0)), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        import heartbeat as hb
        await hb.daily_morning_greeting()

    human = captured["messages"][-1]
    # 当前天气保住了（气温 28.5），预报段被优雅省略
    assert "当前天气" in human.content
    assert "28.5" in human.content


async def test_background_llm_calls_use_runtime_model(tmp_db):
    """三处后台轻量 LLM 调用的 model 维度须跟随用户运行时配置。

    temperature/max_tokens 保持各任务的轻量值（主动消息 200）。
    """
    from unittest.mock import AsyncMock, MagicMock, patch
    import heartbeat as hb
    from api.routes import config as cfg

    calls = []

    def capture_llm(model=None, temperature=None, max_tokens=None):
        calls.append({"model": model, "temperature": temperature, "max_tokens": max_tokens})
        fake = MagicMock()
        fake.ainvoke = AsyncMock(return_value=MagicMock(content="小汐在想你~冷冷今天还好吗？"))
        return fake

    original_cfg = cfg._runtime_config.copy()
    cfg._runtime_config["model"] = "deepseek-v4-pro"
    try:
        with patch("heartbeat.get_db", return_value=tmp_db), \
             patch("utils.events.get_db", return_value=tmp_db), \
             patch("providers.deepseek.create_deepseek_llm", capture_llm):
            text = await hb._generate_proactive_message("开心", "在窗边看雨", 15)
    finally:
        cfg._runtime_config.clear()
        cfg._runtime_config.update(original_cfg)

    assert len(text) >= 5
    assert calls, "create_deepseek_llm 应被调用"
    assert calls[0]["model"] == "deepseek-v4-pro"   # 跟随运行时配置
    assert calls[0]["max_tokens"] == 200            # 轻量约束保持


async def test_scheduler_actually_runs_proactive_chain(tmp_db):
    """集成回归：真实调度器触发心跳后，主动消息确实落库。

    使用与生产完全相同的注册入口 heartbeat.register_jobs()（含启动脉冲
    next_run_time=now）。若注册方式再次退化为 lambda 包裹 async，
    job 本体不会执行，本测试将超时断言失败。

    为避免依赖真实 LLM / 推送 / 当前时刻，桩替换三处外部依赖，
    其余链路（scheduler → check_and_proactive_message →
    _try_proactive_message → INSERT proactive_messages）全部走真实代码。
    """
    import asyncio
    from apscheduler.schedulers.asyncio import AsyncIOScheduler
    import heartbeat as hb
    import utils.web_push as wp

    async def mock_get_db():
        return tmp_db

    async def always_send(now_dt=None):
        return True  # 跳过 4h 间隔 / 50% 概率 / 深夜判断，保证确定性

    async def fake_generate(mood, daily_event, hour, now_dt=None):
        # now_dt：周末感知沿链下传的时间（v5.1 新增，此处忽略）
        return "冷冷，小汐在想你~（调度集成测试）"

    async def fake_send_push(title, body, url="/"):
        return 0  # 无订阅设备路径：消息仍应标记 delivered 供页面拉取

    async def noop():
        pass  # 跳过 checkpoint 裁剪 / 数据库备份

    test_scheduler = AsyncIOScheduler()
    saved = {
        "scheduler": hb.scheduler,
        "get_db": hb.get_db,
        "should": hb._should_send_proactive,
        "generate": hb._generate_proactive_message,
        "maintenance": hb._periodic_maintenance,
        "push": wp.send_push,
    }
    hb.scheduler = test_scheduler
    hb.get_db = mock_get_db
    hb._should_send_proactive = always_send
    hb._generate_proactive_message = fake_generate
    hb._periodic_maintenance = noop
    wp.send_push = fake_send_push
    try:
        hb.register_jobs(app=None)
        test_scheduler.start()

        # 最多等 5 秒，轮询 DB 直到主动消息落库
        count = 0
        for _ in range(50):
            await asyncio.sleep(0.1)
            cursor = await tmp_db.execute("SELECT COUNT(*) FROM proactive_messages")
            (count,) = await cursor.fetchone()
            if count >= 1:
                break

        assert count >= 1, (
            "调度器启动 5 秒后仍无主动消息落库 —— "
            "心跳 job 本体没有真正执行（协程未被 await？）"
        )

        # 消息应被标记为 stored（无推送订阅时的存储待拉取状态）
        cursor = await tmp_db.execute(
            "SELECT status FROM proactive_messages ORDER BY created_at DESC LIMIT 1"
        )
        (status,) = await cursor.fetchone()
        assert status == "stored"

        # 生命状态也应被心跳刷新（不再是最初的种子值）
        cursor = await tmp_db.execute("SELECT updated_at FROM life_state WHERE id = 1")
        (updated_at,) = await cursor.fetchone()
        assert updated_at != "2026-07-21 10:00", "life_state 未被心跳刷新"
    finally:
        test_scheduler.shutdown(wait=False)
        hb.scheduler = saved["scheduler"]
        hb.get_db = saved["get_db"]
        hb._should_send_proactive = saved["should"]
        hb._generate_proactive_message = saved["generate"]
        hb._periodic_maintenance = saved["maintenance"]
        wp.send_push = saved["push"]


# ── _should_send_proactive 闸门逻辑（时间只取一次，对齐本文件纪律）──


async def _should_send_at(tmp_db, monkeypatch, now_dt, seed_offset=None):
    """固定 now_dt + 概率门常开，按 offset 种入上条主动消息（None=无历史）。"""
    import datetime as dt
    from types import SimpleNamespace
    import heartbeat as hb

    if seed_offset is not None:
        last = (now_dt - seed_offset).strftime(hb._TIME_FMT)
        await tmp_db.execute(
            "INSERT INTO proactive_messages (id, content, created_at, status) "
            "VALUES (?, ?, ?, ?)",
            (f"t-{now_dt:%H%M}", "测试消息", last, "stored"),
        )

    async def mock_get_db():
        return tmp_db

    monkeypatch.setattr(hb, "get_db", mock_get_db)
    monkeypatch.setattr(hb, "random", SimpleNamespace(random=lambda: 0.1))
    return await hb._should_send_proactive(now_dt=now_dt)


async def test_should_send_respects_min_interval(tmp_db, monkeypatch):
    """距上条 3h59m < 4h：白天 + 概率门常开也不发。"""
    import datetime as dt
    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 2, 15, 0, tzinfo=hb._HKT)
    assert await _should_send_at(
        tmp_db, monkeypatch, now_dt, dt.timedelta(hours=3, minutes=59)
    ) is False


async def test_should_send_after_min_interval(tmp_db, monkeypatch):
    """距上条 4h01m ≥ 4h：白天 + 概率门常开 → 发。"""
    import datetime as dt
    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 2, 15, 0, tzinfo=hb._HKT)
    assert await _should_send_at(
        tmp_db, monkeypatch, now_dt, dt.timedelta(hours=4, minutes=1)
    ) is True


async def test_should_send_quiet_hours(tmp_db, monkeypatch):
    """早 7 点前安静时段：无历史消息也不发（深夜不打扰）。"""
    import datetime as dt
    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 2, 6, 30, tzinfo=hb._HKT)
    assert await _should_send_at(tmp_db, monkeypatch, now_dt) is False


# ── 周末感知（批次 A）──────────────────────────────────────────


def test_weekend_label():
    """周六/日 → 标签；工作日 → None（固定日期构造，不依赖运行当天）。"""
    import datetime as dt
    from heartbeat import weekend_label
    from utils.timezone import HKT

    sat = dt.datetime(2026, 8, 1, 14, 0, tzinfo=HKT)   # 周六
    sun = dt.datetime(2026, 8, 2, 14, 0, tzinfo=HKT)   # 周日
    mon = dt.datetime(2026, 8, 3, 14, 0, tzinfo=HKT)   # 周一
    assert weekend_label(sat) == "周六"
    assert weekend_label(sun) == "周日"
    assert weekend_label(mon) is None


async def test_proactive_prompt_mentions_weekend(tmp_db):
    """周六的主动消息 prompt 带周末素材；工作日不带。LLM 全 mock。"""
    import datetime as dt
    import database.core as db_core
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import patch
    from utils.timezone import HKT

    original = db_core._db
    db_core._db = tmp_db
    captured = {}

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="冷冷，小汐在想你~")

    try:
        with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
             patch("utils.events.get_active_events", return_value=[]), \
             patch("utils.events.get_recent_proactive_texts", return_value=[]):
            sat = dt.datetime(2026, 8, 1, 14, 0, tzinfo=HKT)
            await heartbeat._generate_proactive_message("开心", "映海很清澈", 14, now_dt=sat)
            assert "今天是周六" in captured["human"]

            mon = dt.datetime(2026, 8, 3, 14, 0, tzinfo=HKT)
            await heartbeat._generate_proactive_message("开心", "映海很清澈", 14, now_dt=mon)
            assert "周六" not in captured["human"]
    finally:
        db_core._db = original


async def test_proactive_message_receives_cycle_event_memo(tmp_db):
    """关心收敛回归：活跃 cycle 事件经事件通道进主动消息 prompt（带 memo）。

    生理期关心不再由 _generate_proactive_message 硬编码，而是随
    get_active_events 的事件列表注入——memo 里的关心纪律一并带到。
    """
    import datetime as dt
    import database.core as db_core
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch
    from utils.timezone import HKT

    original = db_core._db
    db_core._db = tmp_db
    captured = {}

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="冷冷，今天身体怎么样呀？")

    today = dt.datetime.now(HKT).date()
    fake_cycle_event = {
        "id": "c1", "title": "身体的日子", "event_type": "health",
        "event_date": (today + dt.timedelta(days=1)).isoformat(),
        "end_date": (today + dt.timedelta(days=3)).isoformat(),
        "severity": "normal", "source": "cycle",
        "memo": "按记录推算，这可能是冷冷身体不舒服的日子（她痛经很厉害，有时会昏迷）。",
    }

    try:
        with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
             patch("utils.events.get_active_events", new=AsyncMock(return_value=[fake_cycle_event])), \
             patch("utils.events.get_recent_proactive_texts", new=AsyncMock(return_value=[])):
            await heartbeat._generate_proactive_message("担心", "映海很安静", 14)
        assert "身体的日子" in captured["human"]
        assert "痛经" in captured["human"]           # memo 关心纪律随事件注入
        assert "小汐按日子推算的" in captured["human"]  # source 备注
    finally:
        db_core._db = original


async def test_morning_prompt_mentions_weekend(tmp_db):
    """周六早安 prompt 带「不用上课」素材。LLM/天气/推送全 mock。"""
    import datetime as dt
    import database.core as db_core
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import patch
    from utils.timezone import HKT

    original = db_core._db
    db_core._db = tmp_db
    captured = {}

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="冷冷早安呀～今天也要好好的！")

    async def fake_fetch(client, lat, lon):
        return {}

    try:
        with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
             patch("memory.location_store.get_any", return_value=None), \
             patch("tools.weather._fetch_open_meteo", side_effect=fake_fetch), \
             patch("utils.web_push.send_push", return_value=0), \
             patch("heartbeat.datetime") as mock_dt:
            mock_dt.datetime.now.return_value = dt.datetime(2026, 8, 1, 8, 0, tzinfo=HKT)
            mock_dt.datetime.strptime.side_effect = dt.datetime.strptime
            mock_dt.timedelta = dt.timedelta
            await heartbeat.daily_morning_greeting()

        assert "周六" in captured["human"]
        assert "不用上课" in captured["human"]
    finally:
        db_core._db = original


# ── 心情涟漪（批次 C）──────────────────────────────────────────


def test_mood_weights_signals():
    """纯函数权重：信号抬升目标 mood，所有权重 >= 0.2，池约束优先。"""
    from heartbeat import _mood_weights

    evening = ["满足", "发呆", "期待", "担心", "困倦"]
    base = _mood_weights(evening, care=False, joy=False, talked_recently=False,
                         is_weekend=False, current_mood=None)
    assert base == [1.0] * 5

    cared = _mood_weights(evening, care=True, joy=False, talked_recently=False,
                          is_weekend=False, current_mood=None)
    assert cared[evening.index("担心")] > 1.0
    assert all(w >= 0.2 for w in cared)

    # 池里没有「担心」→ care 信号不会凭空造出它，只抑制池内的活泼/兴奋类
    morning = ["好奇", "期待", "活泼", "开心"]
    m_cared = _mood_weights(morning, care=True, joy=False, talked_recently=False,
                            is_weekend=False, current_mood=None)
    assert "担心" not in morning
    assert m_cared[morning.index("活泼")] < 1.0
    assert m_cared[morning.index("好奇")] == 1.0  # 未列出的 mood 恒 1.0

    # 惯性：当前 mood 乘 1.3
    inertial = _mood_weights(evening, care=False, joy=False, talked_recently=False,
                             is_weekend=False, current_mood="困倦")
    assert inertial[evening.index("困倦")] == pytest.approx(1.3)


async def test_choose_mood_fallback_on_event_failure(tmp_db):
    """事件查询炸了 → 优雅退回均匀随机，返回池内词，不抛。"""
    import datetime as dt
    import heartbeat
    from unittest.mock import patch
    from utils.timezone import HKT

    with patch("utils.events.get_active_events", side_effect=RuntimeError("boom")):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT],
            now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT]["moods"]


async def test_choose_mood_memory_failure_graceful(tmp_db):
    """memory_manager.recent 炸了 → 同样退回均匀随机。"""
    import datetime as dt
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch
    from utils.timezone import HKT

    broken_mgr = SimpleNamespace(recent=AsyncMock(side_effect=RuntimeError("db lost")))
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=broken_mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]


async def test_choose_mood_reads_recent_memories(tmp_db):
    """正常路径：读近 10 条记忆（被动）+ 活跃事件，返回池内词。"""
    import datetime as dt
    import heartbeat
    from memory.manager import MemoryManager
    from types import SimpleNamespace
    from unittest.mock import patch
    from utils.timezone import HKT

    mgr = MemoryManager(tmp_db)
    await mgr.add("日常", "冷冷来聊过天")
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]
    # 被动性护栏：recent() 不刷计数器（choose 路径同样不刷）
    cursor = await tmp_db.execute("SELECT access_count FROM memories")
    assert (await cursor.fetchone())[0] == 0


# ── 看门狗段间隔离（批次 D 接线回归）───────────────────────────


async def test_watchdog_isolation_echo_stage(tmp_db):
    """记忆回声段抛异常 → 归档/档案检测照常执行，watchdog 不向外抛。"""
    import database.core as db_core
    import heartbeat
    from unittest.mock import AsyncMock, patch

    original = db_core._db
    db_core._db = tmp_db
    try:
        with patch("utils.events.archive_expired", new=AsyncMock(return_value=0)) as m_arch, \
             patch("utils.events.detect_profile_events", new=AsyncMock(return_value=0)) as m_prof, \
             patch("utils.events.detect_weather_events", new=AsyncMock(return_value=0)), \
             patch("utils.events.detect_anniversary_events", new=AsyncMock(return_value=0)), \
             patch("utils.events.detect_cycle_events", new=AsyncMock(return_value=0)), \
             patch("utils.events.detect_memory_echoes", new=AsyncMock(side_effect=RuntimeError("boom"))):
            await heartbeat.event_watchdog()  # 不抛
        m_arch.assert_awaited_once()
        m_prof.assert_awaited_once()
    finally:
        db_core._db = original
