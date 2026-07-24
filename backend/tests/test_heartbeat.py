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

    _HKT = timezone(timedelta(hours=8))
    fake_now = _dt.datetime(2026, 7, 25, 12, 0, tzinfo=_HKT)  # 12 % 6 == 0

    with patch("utils.events.archive_expired", new_callable=AsyncMock, return_value=1) as archive, \
         patch("utils.events.detect_profile_events", new_callable=AsyncMock, return_value=0) as profile, \
         patch("utils.events.detect_weather_events", new_callable=AsyncMock, return_value=0) as weather, \
         patch("heartbeat.datetime") as mock_dt, \
         patch("heartbeat._refresh_agent", new_callable=AsyncMock) as refresh:
        mock_dt.datetime.now.return_value = fake_now
        await hb.event_watchdog(app=object())

    archive.assert_awaited_once()
    profile.assert_awaited_once()
    weather.assert_awaited_once()     # 12 点是 6 小时检测点
    refresh.assert_awaited_once()     # 归档了 1 条 → 上下文变了 → 刷新提示词


async def test_event_watchdog_skips_weather_off_schedule():
    """非 6 小时点不跑天气检测；无变动时不刷新提示词。"""
    from unittest.mock import AsyncMock, patch
    import datetime as _dt
    from datetime import timezone, timedelta
    import heartbeat as hb

    _HKT = timezone(timedelta(hours=8))
    fake_now = _dt.datetime(2026, 7, 25, 13, 0, tzinfo=_HKT)  # 13 % 6 != 0

    with patch("utils.events.archive_expired", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_profile_events", new_callable=AsyncMock, return_value=0), \
         patch("utils.events.detect_weather_events", new_callable=AsyncMock) as weather, \
         patch("heartbeat.datetime") as mock_dt, \
         patch("heartbeat._refresh_agent", new_callable=AsyncMock) as refresh:
        mock_dt.datetime.now.return_value = fake_now
        await hb.event_watchdog(app=object())

    weather.assert_not_awaited()      # 不是 6 小时检测点
    refresh.assert_not_awaited()      # 无变动不刷新


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

    _HKT = timezone(timedelta(hours=8))
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

    async def always_send():
        return True  # 跳过 4h 间隔 / 50% 概率 / 深夜判断，保证确定性

    async def fake_generate(mood, daily_event, hour):
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

        # 消息应被标记为 delivered（无推送订阅时的待拉取状态）
        cursor = await tmp_db.execute(
            "SELECT status FROM proactive_messages ORDER BY created_at DESC LIMIT 1"
        )
        (status,) = await cursor.fetchone()
        assert status == "delivered"

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
