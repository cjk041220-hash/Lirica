"""心跳调度器单元测试 — 时段匹配逻辑。"""

from heartbeat import (
    DAYTIME,
    EVENING,
    LATE_NIGHT,
    MORNING,
    TIME_PERIODS,
    get_current_period,
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
    """心跳任务执行后 life_state 表被更新。

    二测（04 板块）：原用例两处非确定性——① `_should_send_proactive`
    的 random.random()<0.5 门 50% 概率真实调 DeepSeek LLM（.env 有 key
    即真打，慢/花钱/网络抖动靠 fallback 碰巧绿）；② `_periodic_maintenance`
    对**生产库**（backup_database 走模块级 DB_PATH，不认 patched get_db）
    每跑一次写一份备份进 backups/ 轮换 + 裁剪 checkpoints.db。两腿都钉死：
    主动消息生成/推送路径由 test_proactive.py 与同文件早安用例各自带
    patch 覆盖，维护腿由 test_backup_rotation/test_backup_permissions 覆盖。
    """
    from unittest.mock import AsyncMock, patch

    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    original = hb.get_db
    hb.get_db = mock_get_db
    import database.core as db_core
    original_core_db = db_core._db
    db_core._db = tmp_db

    try:
        with (
            patch("heartbeat._should_send_proactive", new=AsyncMock(return_value=False)),
            patch("heartbeat._periodic_maintenance", new=AsyncMock(return_value=None)),
        ):
            await hb.check_and_proactive_message()

        cursor = await tmp_db.execute("SELECT mood, daily_event FROM life_state WHERE id = 1")
        row = await cursor.fetchone()
        assert row is not None
        assert row[0] is not None  # mood 已更新
        assert row[1] is not None  # daily_event 已更新
    finally:
        hb.get_db = original
        db_core._db = original_core_db


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
    import datetime as _dt
    from unittest.mock import AsyncMock, patch

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
    import datetime as _dt
    from unittest.mock import AsyncMock, patch

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
    import datetime as _dt
    from unittest.mock import AsyncMock, patch

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
    import datetime
    from unittest.mock import AsyncMock, MagicMock, patch

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
    import datetime
    from unittest.mock import AsyncMock, MagicMock, patch

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
         patch("memory.chat_archive.get_db", return_value=tmp_db), \
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
             patch("memory.chat_archive.get_db", return_value=tmp_db), \
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

    async def fake_generate(mood, daily_event, hour, *, greeting_allowed=False, now_dt=None):
        # now_dt：周末感知沿链下传的时间（v5.1 新增，此处忽略）
        # greeting_allowed：v21 era 路由——开场允许问候，此处忽略
        return "冷冷，小汐在想你~（调度集成测试）"

    async def fake_send_push(title, body, url="/"):
        return 0  # 无订阅设备路径：消息仍应标记 delivered 供页面拉取

    async def noop():
        pass  # 跳过 checkpoint 裁剪 / 数据库备份

    test_scheduler = AsyncIOScheduler()
    # v21 起主动消息走 era 三写一推：档案行也要接到临时库（否则
    # record_solo_message 写生产库，污染真实数据库）
    import memory.chat_archive as _archive
    saved = {
        "scheduler": hb.scheduler,
        "get_db": hb.get_db,
        "should": hb._should_send_proactive,
        "generate": hb._generate_proactive_message,
        "maintenance": hb._periodic_maintenance,
        "push": wp.send_push,
        "archive_db": _archive.get_db,
    }
    hb.scheduler = test_scheduler
    hb.get_db = mock_get_db
    hb._should_send_proactive = always_send
    hb._generate_proactive_message = fake_generate
    hb._periodic_maintenance = noop
    _archive.get_db = mock_get_db
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
        _archive.get_db = saved["archive_db"]
        wp.send_push = saved["push"]


# ── _should_send_proactive 闸门逻辑（时间只取一次，对齐本文件纪律）──


async def _should_send_at(tmp_db, monkeypatch, now_dt, seed_offset=None):
    """固定 now_dt + 概率门常开，按 offset 种入上条主动消息（None=无历史）。"""
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


def test_period_label_boundaries():
    """时段词与 get_current_period 同一口径：0/6 深夜，7 早晨，12 下午，19 晚上。"""
    from heartbeat import period_label

    assert period_label(0) == "深夜"
    assert period_label(6) == "深夜"
    assert period_label(7) == "早晨"
    assert period_label(11) == "早晨"
    assert period_label(12) == "下午"
    assert period_label(18) == "下午"
    assert period_label(19) == "晚上"
    assert period_label(23) == "晚上"


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
    from types import SimpleNamespace
    from unittest.mock import patch

    import database.core as db_core
    import heartbeat
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
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    import database.core as db_core
    import heartbeat
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
    from types import SimpleNamespace
    from unittest.mock import patch

    import database.core as db_core
    import heartbeat
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


def test_gauss_weights_neighbors_preferred():
    """高斯权重：目标点近邻词权重高，远词（跨对径）权重趋零。"""
    from heartbeat_moods import MOOD_VECTORS, _gauss_weights

    happy = MOOD_VECTORS["开心"]
    weights = _gauss_weights(happy, ["开心", "活泼", "期待", "困倦", "担心"])
    assert weights[0] > weights[1]         # 自身（距离 0）权重最高
    assert weights[1] > weights[3]         # 近邻活泼 >> 困倦
    assert weights[1] > weights[4]         # 近邻活泼 >> 担心
    assert weights[4] < 0.01               # 跨对径词（担心）几乎不可达


def test_apply_signal_offsets_directions():
    """信号偏移方向：care 压低 valence、joy 抬升 valence、talked/weekend 压 arousal。"""
    from heartbeat_moods import _apply_signal_offsets

    base = (0.0, 0.0)
    cared = _apply_signal_offsets(base, care=True, joy=False, talked=False, is_weekend=False)
    assert cared[0] < 0  # care → 向「担心」方向（valence 减）
    joyed = _apply_signal_offsets(base, care=False, joy=True, talked=False, is_weekend=False)
    assert joyed[0] > 0  # joy → 向「开心」方向（valence 加）
    talked = _apply_signal_offsets(base, care=False, joy=False, talked=True, is_weekend=False)
    assert talked[1] < 0  # talked → 向「满足/期待」（arousal 减）
    weekend = _apply_signal_offsets(base, care=False, joy=False, talked=False, is_weekend=True)
    assert weekend[1] < 0  # weekend → 向「懒洋洋」（arousal 减）
    # 钳制：叠加后仍在 [-1, 1]
    far = _apply_signal_offsets((1.0, 1.0), care=True, joy=True, talked=True, is_weekend=True)
    assert all(-1.0 <= x <= 1.0 for x in far)


async def test_choose_mood_walk_stays_adjacent(tmp_db):
    """数值化游走有界性：从「满足」出发大量采样，远词泄漏率必须极低。

    高斯采样是几何设计（远词权重 exp(-d²/2σ²) 趋零而非恒零），晚安心境池
    本身含「担心/困倦」（事件信号要靠几何连续性平滑抵达），故不能用
    「N 次采样零容忍」断言——实测单步尾部概率约 0.02%，100 次零容忍
    会让本测试每 ~50 次运行误红一次（2026-08-07 全量回归实测 1 例，
    蒙特卡洛 30 万样本复测 0.021% 吻合）。改为有界尾部断言：1500 样本
    期望泄漏 ~0.3 次，容忍 ≤3；若泄漏率恶化一个数量级（游走步长/σ
    失控），本断言开始稳定报警。
    """
    import datetime as dt
    from unittest.mock import patch

    import heartbeat
    from utils.timezone import HKT

    leaks = 0
    n = 1500
    with patch("utils.events.get_active_events", return_value=[]):
        for _ in range(n):
            mood = await heartbeat._choose_mood(
                heartbeat.TIME_PERIODS[heartbeat.EVENING],
                now_dt=dt.datetime.now(HKT),
                current_mood="满足",
            )
            if mood in ("担心", "困倦"):
                leaks += 1
    assert leaks <= 3, f"从满足游走泄漏率异常：{leaks}/{n} 落到了担心/困倦"


def test_is_morning_reset():
    """早晨锚定判定：6-8 点且当日未重置 → True；其余 False。"""
    import datetime as dt

    from heartbeat import _is_morning_reset

    now = dt.datetime(2026, 8, 3, 8, 0).strftime("%Y-%m-%d %H:%M")
    assert _is_morning_reset(7, "2026-08-02 07:00", now) is True   # 昨天切过 → 可重置
    assert _is_morning_reset(8, "2026-08-03 07:00", now) is False  # 今天已重置 → 不再
    assert _is_morning_reset(6, None, now) is True                 # 无历史 → 可重置
    assert _is_morning_reset(14, "2026-08-02 07:00", now) is False  # 非窗口 → 否
    assert _is_morning_reset(9, "2026-08-02 07:00", now) is False   # 9 点不在窗口


def test_should_hold_mood():
    """最小停留：6h 内换词 → 拦；6h 外 / 早晨锚定 / 同词 / 无历史 → 放行。"""
    import datetime as dt

    import heartbeat as hb
    from heartbeat import _should_hold_mood

    # 时间戳与 now 同用 _HKT（函数内部按 HKT 解析 mood_changed_at，
    # 换别的时区会算出错误的时差）
    now = dt.datetime(2026, 8, 3, 10, 0, tzinfo=hb._HKT)
    changed_2h = (now - dt.timedelta(hours=2)).strftime("%Y-%m-%d %H:%M")
    changed_8h = (now - dt.timedelta(hours=8)).strftime("%Y-%m-%d %H:%M")
    assert _should_hold_mood("满足", "开心", False, changed_2h, now) is True
    assert _should_hold_mood("满足", "开心", False, changed_8h, now) is False
    assert _should_hold_mood("满足", "开心", True, changed_2h, now) is False  # 早晨锚定豁免
    assert _should_hold_mood("满足", "满足", False, changed_2h, now) is False  # 同词
    assert _should_hold_mood("满足", "开心", False, None, now) is False        # 无历史
    assert _should_hold_mood("满足", "开心", False, "垃圾时间戳", now) is False  # 异常放行


async def test_choose_mood_fallback_on_event_failure(tmp_db):
    """事件查询炸了 → 优雅退回均匀随机，返回池内词，不抛。"""
    import datetime as dt
    from unittest.mock import patch

    import heartbeat
    from utils.timezone import HKT

    with patch("utils.events.get_active_events", side_effect=RuntimeError("boom")):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT],
            now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT]["moods"]


async def test_choose_mood_talked_signal_from_chat_messages(tmp_db):
    """O2：talked 信号读 chat_messages（真实对话），不是记忆。"""
    import datetime as dt
    from types import SimpleNamespace
    from unittest.mock import patch

    import heartbeat
    from memory.manager import MemoryManager
    from utils.timezone import HKT

    now = dt.datetime.now(HKT)
    await tmp_db.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s-1', 'user', '刚刚聊过天', ?)",
        (now.strftime("%Y-%m-%d %H:%M"),),
    )
    await tmp_db.commit()
    mgr = MemoryManager(tmp_db)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=now,
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]
    # 记忆库不再被读：库是空的（旧实现读 recent(10) 记忆，这里必须不依赖）
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 0


async def test_choose_mood_no_recent_chat_ok(tmp_db):
    """O2：无对话 / 对话超 24h → 不炸，仍正常采样。"""
    import datetime as dt
    from types import SimpleNamespace
    from unittest.mock import patch

    import heartbeat
    from memory.manager import MemoryManager
    from utils.timezone import HKT

    old = (dt.datetime.now(HKT) - dt.timedelta(hours=25)).strftime("%Y-%m-%d %H:%M")
    await tmp_db.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s-1', 'user', '昨天聊过', ?)",
        (old,),
    )
    await tmp_db.commit()
    mgr = MemoryManager(tmp_db)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT]["moods"]


async def test_choose_mood_chat_query_failure_graceful(tmp_db):
    """O2：has_recent_chat 炸了 → 保持当前词（失败语义同 v2）。"""
    import datetime as dt
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    import heartbeat
    from utils.timezone import HKT

    broken_mgr = SimpleNamespace(has_recent_chat=AsyncMock(side_effect=RuntimeError("db lost")))
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=broken_mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]


async def test_choose_mood_calls_has_recent_chat(tmp_db):
    """O2 接线：talked 信号来自 has_recent_chat（chat_messages 时间），
    不再读 recent(10) 记忆。"""
    import datetime as dt
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    import heartbeat
    from memory.manager import MemoryManager
    from utils.timezone import HKT

    mgr = MemoryManager(tmp_db)
    mgr.has_recent_chat = AsyncMock(return_value=True)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mgr.has_recent_chat.await_count == 1


# ── 心情节奏（v2 多时间尺度，2026-08-03 系统性优化）────────────


async def _run_heartbeat_at(tmp_db, monkeypatch, now_dt, fake_mood):
    """注入时间点 + 假 _choose_mood 跑一次心跳，返回 (row, 调用观测)。

    row = (mood, daily_event, mood_changed_at)；seen 记录 _choose_mood
    收到的 allow_reset。首位：最小停留 / 早晨锚定 / 日级事件全在
    check_and_proactive_message 的决策节奏里，测试从整条链路断言。
    """
    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    monkeypatch.setattr(hb, "get_db", mock_get_db)

    # 心跳可能走进 _generate_proactive_message → recent_messages，
    # 档案读库同样接到临时库
    async def fake_archive_get_db():
        return tmp_db

    import memory.chat_archive as _archive
    monkeypatch.setattr(_archive, "get_db", fake_archive_get_db)

    seen = {}

    async def fake_choose_mood(period, *, app=None, now_dt=None,
                               current_mood=None, allow_reset=False):
        seen["allow_reset"] = allow_reset
        return fake_mood

    monkeypatch.setattr(hb, "_choose_mood", fake_choose_mood)
    # 二测（04 板块）：维护腿对生产库有真实副作用——backup_database 走
    # 模块级 DB_PATH（不认 patched get_db）每跑写一份生产快照进
    # data/backups/ 轮换（可挤掉真实备份）、prune_checkpoints 会裁生产
    # checkpoints.db、extract_new_memories 会对生产真实会话调真实 LLM
    #（付费+网络，慢时全量跑卡 240s+）。三条全钉死：run_status_decay
    # 仍走 get_db 建全局连接，test_heartbeat_triggers_global_db_connection
    # 的语义不受影响；extract 由 test_memory_extract.py 带 mock 覆盖
    import database.core as _core
    import memory.extract as _extract
    async def _noop(*a, **k):
        return None
    monkeypatch.setattr(_core, "backup_database", _noop)
    monkeypatch.setattr(_core, "prune_checkpoints", _noop)
    monkeypatch.setattr(_extract, "extract_new_memories", _noop)
    await hb.check_and_proactive_message(now_dt=now_dt)
    cursor = await tmp_db.execute(
        "SELECT mood, daily_event, mood_changed_at FROM life_state WHERE id = 1"
    )
    return await cursor.fetchone(), seen


async def test_morning_reset_allows_mood_change_and_refreshes_event(tmp_db, monkeypatch):
    """早晨 7 点且昨天切过 → allow_reset=True；换词、刷新 daily_event、更新 mood_changed_at。"""
    import datetime as dt

    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 3, 7, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', daily_event = '昨天的旧事', "
        "mood_changed_at = '2026-08-02 07:00' WHERE id = 1"
    )
    await tmp_db.commit()

    (mood, daily_event, changed_at), seen = await _run_heartbeat_at(
        tmp_db, monkeypatch, now_dt, "期待"
    )
    assert seen["allow_reset"] is True
    assert mood == "期待"
    assert daily_event != "昨天的旧事"  # 早晨锚定刷新碎碎念
    assert changed_at.startswith("2026-08-03")


async def test_morning_already_reset_skips_allow_reset(tmp_db, monkeypatch):
    """早晨 7 点但今天已重置过 → 不传 allow_reset（早晨锚定一天一次）。"""
    import datetime as dt

    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 3, 7, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood_changed_at = '2026-08-03 06:30' WHERE id = 1"
    )
    await tmp_db.commit()
    _, seen = await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")
    assert seen["allow_reset"] is False


async def test_min_hold_blocks_intraday_mood_change(tmp_db, monkeypatch):
    """日内 2h 前换过词 → 换词被 6h 最小停留拦回；日级事件与时间戳保持。"""
    import datetime as dt

    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 3, 10, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', daily_event = '今天的碎碎念', "
        "mood_changed_at = '2026-08-03 08:00' WHERE id = 1"
    )
    await tmp_db.commit()
    (mood, daily_event, changed_at), _ = await _run_heartbeat_at(
        tmp_db, monkeypatch, now_dt, "开心"
    )
    assert mood == "满足"             # 2h 前换过 → 6h 最小停留拦住
    assert daily_event == "今天的碎碎念"  # 非早晨 → 日级事件保持
    assert changed_at == "2026-08-03 08:00"  # 未换词不改时间戳


async def test_min_hold_passes_after_six_hours(tmp_db, monkeypatch):
    """日内 8h 前换过词 → 允许换词；mood_changed_at 刷新；daily_event 仍保持。"""
    import datetime as dt

    import heartbeat as hb

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', daily_event = '今天的碎碎念', "
        "mood_changed_at = '2026-08-03 08:00' WHERE id = 1"
    )
    await tmp_db.commit()
    (mood, daily_event, changed_at), _ = await _run_heartbeat_at(
        tmp_db, monkeypatch, now_dt, "开心"
    )
    assert mood == "开心"             # 8h 前换过 → 放行
    assert daily_event == "今天的碎碎念"  # 非早晨 → 日级事件保持
    assert changed_at.startswith("2026-08-03 16:0")  # 换词刷新


# ── 看门狗段间隔离（批次 D 接线回归）───────────────────────────


async def test_watchdog_isolation_echo_stage(tmp_db):
    """记忆回声段抛异常 → 归档/档案检测照常执行，watchdog 不向外抛。"""
    from unittest.mock import AsyncMock, patch

    import database.core as db_core
    import heartbeat

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


# ── 情绪联动：心跳换词落情绪核心记忆（schema v18）────────────


async def test_heartbeat_mood_change_writes_mood_memory(tmp_db, monkeypatch):
    """换词放行（距上次 ≥6h）：心情变化落情绪核心记忆（core 行、固定 id）。"""
    import datetime as dt

    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    cursor = await tmp_db.execute(
        "SELECT status, category, content FROM memories WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    row = await cursor.fetchone()
    assert row is not None
    assert row[0] == "core"
    assert row[1] == "小汐的心情"
    assert "开心" in row[2] and "2026-08-03 16:00" in row[2]


async def test_heartbeat_second_change_grows_chain(tmp_db, monkeypatch):
    """第二次换词：旧心情落 superseded 链（回放账本在心跳链路上成立）。"""
    import datetime as dt

    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID, MemoryManager

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()
    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    now_dt2 = dt.datetime(2026, 8, 4, 7, 0, tzinfo=hb._HKT)  # 次日早晨锚定
    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt2, "期待")

    history = await MemoryManager(tmp_db).history(MOOD_MEMORY_ID)
    assert len(history) == 1 and "开心" in history[0]["content"]
    cursor = await tmp_db.execute(
        "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert "期待" in (await cursor.fetchone())[0]


async def test_heartbeat_no_change_no_mood_memory(tmp_db, monkeypatch):
    """心情词未变：不落情绪记忆（首次部署生命状态行已有 mood 的场景）。"""
    import datetime as dt

    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '开心', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_heartbeat_hold_blocks_mood_memory(tmp_db, monkeypatch):
    """最小停留拦回的换词：life_state 不变，情绪记忆也不落。"""
    import datetime as dt

    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 10, 0, tzinfo=hb._HKT)  # 距 08:00 仅 2h
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    (mood, _, _), _ = await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    assert mood == "满足"  # 被 6h 最小停留拦回
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_daily_diary_excludes_mood_memory(tmp_db):
    """自动日记素材：情绪核心记忆是小汐自己的账本，不算「冷冷新告诉小汐的事」。"""
    from unittest.mock import AsyncMock, MagicMock, patch

    import heartbeat as hb
    from memory.manager import MemoryManager
    from utils.timezone import now_str

    mgr = MemoryManager(tmp_db)
    await mgr.upsert_mood_memory("开心", now=now_str())  # created_at = 今天
    # 注意：内容刻意不含相对时间词——存储侧时间词归一化（批次 H）会把
    # 「今天」改写为绝对日期，日记素材随之携带绝对日期，断言用永恒词
    await mgr.add("冷冷的信息", "冷冷说想去海边")

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(return_value=MagicMock(content="今天的日记内容。"))

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        await hb.daily_diary()

    material = fake_llm.ainvoke.await_args[0][0][1].content
    assert "冷冷说想去海边" in material
    assert "小汐的心情" not in material


# ── 全局连接收尾（防解释器退出挂起，2026-08-04 排查）──────────


async def test_heartbeat_triggers_global_db_connection(tmp_db, monkeypatch):
    """机制钉死：心跳经 _periodic_maintenance 会触发真实 database.core
    全局连接（monkeypatch 只盖住 heartbeat.get_db 的引用，盖不住
    database.core 模块内部调用）——这正是「单文件跑挂起」的泄漏源。
    修复前提不成立（不触发）时本测试失败，防止未来重构悄悄绕开。
    """
    import datetime as dt

    import heartbeat as hb
    from database import core

    core._db = None  # 隔离：本用例前不应有全局连接
    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    assert core._db is not None  # 心跳确实惰性建出了全局真实连接
    await core.close_db_connection()  # 本用例自清理，不留泄漏
    assert core._db is None


async def test_close_db_connection_idempotent(tmp_db):
    """close_db_connection 幂等：已关/未建都静默成功（conftest session
    收尾 fixture 每次跑都会调用它，绝不能抛异常。"""
    from database import core

    core._db = None
    await core.close_db_connection()  # 未建：no-op 不报错
    assert core._db is None


# ── Spec A：主动消息历史尾巴注入 ──────────────────────────────


def _capturing_llm(captured, reply="冷冷，小汐在想你~"):
    """FakeLLM 工厂：捕获 HumanMessage 内容（不真调模型）。"""
    from types import SimpleNamespace

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content=reply)

    return FakeLLM()


async def test_proactive_prompt_includes_history_tail(tmp_db):
    """patch recent_messages 返回「吵架尾巴」→ prompt 含历史块与
    独处变体引导词（「接着自己的心事往下写/别装没发生过」）——
    捕获 LLM 入参，不真调模型。

    v21 起默认走独处变体（greeting_allowed=False）：尾巴引导语从
    「自然地搭话」改为「接着自己的心事往下写」（她在忙，不是对话
    时间）。「递个台阶」的搭话引导只在开场（greeting_allowed=True）
    出现，见 test_proactive_opener_tail_keeps_approach_guidance。
    """
    import datetime as dt
    from unittest.mock import AsyncMock, patch

    import heartbeat
    from utils.timezone import HKT

    captured = {}
    tail = [
        {"role": "user", "content": "JK 算什么东西，你也配替他说话？",
         "created_at": "2026-08-04 23:41"},
        {"role": "assistant", "content": "你这么说，我不高兴了。",
         "created_at": "2026-08-04 23:42"},
    ]

    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(return_value=tail)):
        await heartbeat._generate_proactive_message(
            "不开心", "映海很安静", 23,
            now_dt=dt.datetime(2026, 8, 5, 9, 0, tzinfo=HKT))

    human = captured["human"]
    assert "你最近和冷冷说过这些话（最新在最后）" in human
    assert "[08-04 23:41] 冷冷：JK 算什么东西，你也配替他说话？" in human
    assert "[08-04 23:42] 小汐：你这么说，我不高兴了。" in human
    assert "接着你自己的心事往下写" in human
    assert "别装没发生过" in human


async def test_proactive_opener_tail_keeps_approach_guidance(tmp_db):
    """开场（greeting_allowed=True）的尾巴引导语保持「搭话/递台阶」原版。"""
    import datetime as dt
    from unittest.mock import AsyncMock, patch

    import heartbeat
    from utils.timezone import HKT

    captured = {}
    tail = [
        {"role": "user", "content": "……我刚才是不是说太过分了？",
         "created_at": "2026-08-04 23:41"},
        {"role": "assistant", "content": "（安静了一会儿）是有点过分。",
         "created_at": "2026-08-04 23:42"},
    ]

    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(return_value=tail)):
        await heartbeat._generate_proactive_message(
            "不开心", "映海很安静", 8, greeting_allowed=True,
            now_dt=dt.datetime(2026, 8, 5, 9, 0, tzinfo=HKT))

    human = captured["human"]
    assert "自然地搭话" in human
    assert "递个台阶" in human
    assert "别装没发生过" in human


async def test_proactive_prompt_history_read_failure_degrades(tmp_db):
    """recent_messages 抛异常 → prompt 照常构建、无历史块、消息照常生成。"""
    from unittest.mock import AsyncMock, patch

    import heartbeat

    captured = {}
    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(side_effect=RuntimeError("db lost"))):
        text = await heartbeat._generate_proactive_message("开心", "映海很清澈", 15)

    assert "你最近和冷冷说过这些话" not in captured["human"]
    assert len(text) >= 5  # 消息照常生成（降级不阻断）


async def test_proactive_prompt_empty_history_no_block(tmp_db):
    """空历史（新库/从未对话）→ prompt 不出现历史块。"""
    from unittest.mock import AsyncMock, patch

    import heartbeat

    captured = {}
    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(return_value=[])):
        await heartbeat._generate_proactive_message("开心", "映海很清澈", 15)

    assert "你最近和冷冷说过这些话" not in captured["human"]


async def test_morning_prompt_includes_history_tail(tmp_db):
    """早安对称用例：昨晚的对话尾巴进早安 prompt（「昨晚你们聊到这里」块）。"""
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    import database.core as db_core
    import heartbeat

    original = db_core._db
    db_core._db = tmp_db
    captured = {}
    tail = [
        {"role": "user", "content": "我先睡了，晚安。", "created_at": "2026-08-05 23:30"},
        {"role": "assistant", "content": "晚安冷冷，做个好梦。", "created_at": "2026-08-05 23:31"},
    ]

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="冷冷早安呀～")

    async def fake_fetch(client, lat, lon):
        return {}

    try:
        with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
             patch("memory.location_store.get_any", return_value=None), \
             patch("tools.weather._fetch_open_meteo", side_effect=fake_fetch), \
             patch("utils.web_push.send_push", return_value=0), \
             patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=tail)):
            await heartbeat.daily_morning_greeting()

        human = captured["human"]
        assert "昨晚你们聊到这里（最新在最后）" in human
        assert "[08-05 23:30] 冷冷：我先睡了，晚安。" in human
        assert "[08-05 23:31] 小汐：晚安冷冷，做个好梦。" in human
        assert "如果昨晚收尾僵着，早上是你先开口" in human
    finally:
        db_core._db = original


# ── v21 era 路由：问候禁令 + 会话归属 ─────────────────────────


async def test_proactive_solo_variant_guards_mechanical_greeting(tmp_db):
    """独处变体 prompt：拦固定句式问候与反复问好（打卡机感），
    但不禁止自然流露的招呼（用户 2026-08-10 修正：「倒也不是说完全
    禁止问好，只是别一天早中晚都问好」）。"""
    from unittest.mock import AsyncMock, MagicMock, patch

    captured = {}
    fake_llm = MagicMock()

    async def fake_ainvoke(messages, **kwargs):
        captured["human"] = messages[-1].content
        return MagicMock(content="我在看云，想起你。")

    fake_llm.ainvoke = fake_ainvoke

    with patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm), \
         patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])), \
         patch("utils.events.get_active_events", new=AsyncMock(return_value=[])), \
         patch("utils.events.get_recent_proactive_texts", new=AsyncMock(return_value=[])):
        import heartbeat as hb
        text = await hb._generate_proactive_message("开心", "映海很安静", 15)

    human = captured["human"]
    assert "独处时刻，冷冷不在身边" in human
    # 拦的是「固定句式开场 + 反复问好」的机械模式，不是问候本身
    assert "别拿「早安/午安/晚上好，冷冷」这种固定句式当开场白" in human
    assert "一天里也别反复问好" in human
    assert "不是自动问候的机器" in human
    # 自然流露的招呼被保留（「偶尔想打招呼也可以自然带一句」）
    assert "偶尔想打招呼也可以自然带一句" in human
    assert text == "我在看云，想起你。"


async def test_proactive_opener_allows_greeting(tmp_db):
    """era 开场消息：先一句时段问候开场，再接心里的话。"""
    from unittest.mock import AsyncMock, MagicMock, patch

    captured = {}
    fake_llm = MagicMock()

    async def fake_ainvoke(messages, **kwargs):
        captured["human"] = messages[-1].content
        return MagicMock(content="早安呀，冷冷。今天映海的云很好看。")

    fake_llm.ainvoke = fake_ainvoke

    with patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm), \
         patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])), \
         patch("utils.events.get_active_events", new=AsyncMock(return_value=[])), \
         patch("utils.events.get_recent_proactive_texts", new=AsyncMock(return_value=[])):
        import heartbeat as hb
        text = await hb._generate_proactive_message("开心", "映海很安静", 8,
                                                    greeting_allowed=True)

    assert "先说一句当天的问候开场" in captured["human"]
    assert text.startswith("早安呀，冷冷")


async def test_try_proactive_message_routes_to_era(tmp_db, monkeypatch):
    """era 路由：离线 + 开放 era → 追加进时间线，独处变体（不问候）。"""
    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    async def always_send(now_dt=None):
        return True

    monkeypatch.setattr(hb, "get_db", mock_get_db)
    monkeypatch.setattr(hb, "_should_send_proactive", always_send)

    seen = {}

    async def fake_target(conn, now_dt, timeout_minutes):
        return ("era", "e-1", False)

    async def fake_generate(mood, daily_event, hour, *, greeting_allowed=False, now_dt=None):
        seen["greeting_allowed"] = greeting_allowed
        return "我在看云。"

    appended = []

    async def fake_append(app, sid, content, *, kind, label="主动消息"):
        appended.append((sid, content, kind, label))

    monkeypatch.setattr("era.resolve_proactive_target", fake_target)
    monkeypatch.setattr(hb, "_generate_proactive_message", fake_generate)
    monkeypatch.setattr("era.append_proactive_turn", fake_append)

    await hb._try_proactive_message("开心", "映海很安静", 15)

    assert seen["greeting_allowed"] is False       # 追加消息不问候
    assert appended == [("e-1", "我在看云。", "era", "主动消息")]


async def test_try_proactive_message_opener_greets(tmp_db, monkeypatch):
    """era 路由：离线开新窗 → 开场消息允许问候（greeting_allowed=True）。"""
    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    async def always_send(now_dt=None):
        return True

    monkeypatch.setattr(hb, "get_db", mock_get_db)
    monkeypatch.setattr(hb, "_should_send_proactive", always_send)

    seen = {}

    async def fake_target(conn, now_dt, timeout_minutes):
        return ("new_era", "e-new", True)

    async def fake_generate(mood, daily_event, hour, *, greeting_allowed=False, now_dt=None):
        seen["greeting_allowed"] = greeting_allowed
        return "早安呀，冷冷。我在看云。"

    appended = []

    async def fake_append(app, sid, content, *, kind, label="主动消息"):
        appended.append((sid, content, kind, label))

    monkeypatch.setattr("era.resolve_proactive_target", fake_target)
    monkeypatch.setattr(hb, "_generate_proactive_message", fake_generate)
    monkeypatch.setattr("era.append_proactive_turn", fake_append)

    await hb._try_proactive_message("开心", "映海很安静", 8)

    assert seen["greeting_allowed"] is True        # 开场允许问候
    assert appended == [("e-new", "早安呀，冷冷。我在看云。", "new_era", "主动消息")]


async def test_morning_greeting_era_solo_variant(tmp_db, monkeypatch):
    """era 已开放 → 早安走独处变体（不问候），且不查天气。"""
    from unittest.mock import AsyncMock

    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    async def fake_target(conn, now_dt, timeout_minutes):
        return ("era", "e-1", False)

    generated = {}

    async def fake_generate(mood, daily_event, hour, *, greeting_allowed=False, now_dt=None):
        generated["greeting_allowed"] = greeting_allowed
        return "我在看云，想起你。"

    appended = []

    async def fake_append(app, sid, content, *, kind, label="主动消息"):
        appended.append((sid, content, kind, label))

    monkeypatch.setattr(hb, "get_db", mock_get_db)
    monkeypatch.setattr("era.resolve_proactive_target", fake_target)
    monkeypatch.setattr(hb, "_generate_proactive_message", fake_generate)
    monkeypatch.setattr("era.append_proactive_turn", fake_append)
    # 独处变体不该走天气段——真被调用会断言失败（也不该发起网络请求）
    monkeypatch.setattr("tools.weather._fetch_open_meteo",
                        AsyncMock(side_effect=AssertionError("独处变体不该查天气")))

    await hb.daily_morning_greeting()

    assert generated["greeting_allowed"] is False
    assert appended == [("e-1", "我在看云，想起你。", "era", "早安消息")]


async def test_morning_greeting_opener_uses_normal_prompt(tmp_db, monkeypatch):
    """离线开新窗 → 早安作为时间线开场：问候照旧（含天气素材段）。"""
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    import heartbeat as hb

    async def mock_get_db():
        return tmp_db

    async def fake_target(conn, now_dt, timeout_minutes):
        return ("new_era", "e-new", True)

    captured = {}

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="早安呀，冷冷。今天要开开心心的哦！")

    async def fake_fetch(client, lat, lon):
        return {}

    appended = []

    async def fake_append(app, sid, content, *, kind, label="主动消息"):
        appended.append((sid, content, kind, label))

    monkeypatch.setattr(hb, "get_db", mock_get_db)
    monkeypatch.setattr("era.resolve_proactive_target", fake_target)
    monkeypatch.setattr("era.append_proactive_turn", fake_append)

    with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
         patch("memory.location_store.get_any", return_value=None), \
         patch("tools.weather._fetch_open_meteo", side_effect=fake_fetch), \
         patch("utils.web_push.send_push", return_value=0):
        await hb.daily_morning_greeting()

    assert "早安问候" in captured["human"]          # 开场问候照旧
    assert appended[0][0] == "e-new"
    assert appended[0][2] == "new_era"
