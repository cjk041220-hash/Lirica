"""事件管理器单元测试 — 登记/去重/归档/注入上下文/检测器。"""
import datetime
from datetime import timedelta, timezone
from unittest.mock import AsyncMock, patch

import pytest

from utils import events as ev

_HKT = timezone(timedelta(hours=8))


@pytest.fixture
async def events_db(tmp_db):
    """把 tmp_db 接到 utils.events 的 get_db 上（try/finally 自动还原）。"""
    with patch("utils.events.get_db", return_value=tmp_db):
        yield tmp_db


async def _insert_event(db, id, title, event_date, source="llm",
                        status="active", dedupe_key=None, end_date=None,
                        severity="normal", memo=None):
    await db.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, severity,
            source, memo, status, created_at, dedupe_key)
           VALUES (?, ?, 'general', ?, ?, ?, ?, ?, ?, '2026-07-25 10:00', ?)""",
        (id, title, event_date, end_date, severity, source, memo, status, dedupe_key),
    )
    await db.commit()


# ── 登记与去重 ────────────────────────────────────────────────


async def test_create_event_inserts(events_db):
    """新建事件返回 (事件, True)。"""
    event, created = await ev.create_event(
        "期中考试", "exam", "2026-08-01", source="llm"
    )
    assert created is True
    assert event["title"] == "期中考试"
    assert event["status"] == "active"


async def test_create_event_dedupe_key_atomic(events_db):
    """同 dedupe_key 的活跃事件不重复创建（DB 层原子去重，无 TOCTOU）。"""
    first, created1 = await ev.create_event(
        "2026-08-01 有大雨", "rainstorm", "2026-08-01",
        source="weather", dedupe_key="weather:rainstorm:2026-08-01",
    )
    second, created2 = await ev.create_event(
        "2026-08-01 有大雨", "rainstorm", "2026-08-01",
        source="weather", dedupe_key="weather:rainstorm:2026-08-01",
    )
    assert created1 is True
    assert created2 is False
    assert second["id"] == first["id"]

    cursor = await events_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_create_event_dedupe_key_allows_recreate_after_archive(events_db):
    """归档行不阻塞同 dedupe_key 重新生成（明年/下次事件）。"""
    await ev.create_event(
        "冷冷的生日", "birthday", "2026-06-03",
        source="profile", dedupe_key="profile:birthday:2026-06-03",
    )
    await events_db.execute(
        "UPDATE events SET status = 'archived' WHERE dedupe_key = 'profile:birthday:2026-06-03'"
    )
    await events_db.commit()

    _, created = await ev.create_event(
        "冷冷的生日", "birthday", "2026-06-03",
        source="profile", dedupe_key="profile:birthday:2026-06-03",
    )
    assert created is True


async def test_create_event_no_key_dedupes_on_title_date(events_db):
    """LLM 事件（无 dedupe_key）按 (title, event_date) 精确去重。"""
    _, created1 = await ev.create_event("期中考试", "exam", "2026-08-01")
    _, created2 = await ev.create_event("期中考试", "exam", "2026-08-01")
    assert created1 is True
    assert created2 is False


async def test_create_event_concurrent_same_key(events_db):
    """回归：并发创建同 dedupe_key 只产生 1 行（唯一部分索引强制）。"""
    import asyncio

    results = await asyncio.gather(*(
        ev.create_event(
            "台风", "typhoon", "2026-09-01",
            source="weather", dedupe_key="weather:typhoon:2026-09-01",
        )
        for _ in range(5)
    ))
    assert sum(1 for _, created in results if created) == 1

    cursor = await events_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


# ── 查询与归档 ────────────────────────────────────────────────


async def test_get_active_events_ordered_by_date(events_db):
    """活跃事件按发生日期正序返回。"""
    await _insert_event(events_db, "e1", "晚的事", "2026-09-01")
    await _insert_event(events_db, "e2", "早的事", "2026-08-01")
    await _insert_event(events_db, "e3", "已翻篇", "2026-07-01", status="archived")

    events = await ev.get_active_events()
    assert [e["title"] for e in events] == ["早的事", "晚的事"]


async def test_archive_expired_single_day(events_db):
    """单日活动：event_date 过了就归档。"""
    await _insert_event(events_db, "e1", "昨天的考试", "2026-07-24")
    await _insert_event(events_db, "e2", "明天的考试", "2026-07-26")

    count = await ev.archive_expired()
    assert count == 1

    cursor = await events_db.execute(
        "SELECT title FROM events WHERE status = 'archived'"
    )
    assert (await cursor.fetchone())[0] == "昨天的考试"


async def test_archive_expired_respects_end_date(events_db):
    """多天事件按 end_date 归档：还在进行中的不归档。"""
    # 已开始但未结束（今天 7-25 在 7-24 ~ 7-27 之间）
    await events_db.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, status, created_at)
           VALUES ('e1', '旅行中', 'trip', '2026-07-24', '2026-07-27', 'active', '2026-07-20 10:00')"""
    )
    await events_db.commit()

    count = await ev.archive_expired()
    assert count == 0, "进行中的多天事件不应归档"


async def test_archive_expired_multi_day_past(events_db):
    """多天事件 end_date 已过 → 归档（COALESCE 取 end_date 的语义回归）。"""
    await events_db.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, status, created_at)
           VALUES ('e1', '上个月的旅行', 'trip', '2026-06-01', '2026-06-05', 'active', '2026-05-20 10:00')"""
    )
    await events_db.commit()

    assert await ev.archive_expired() == 1


# ── 提示词注入上下文 ──────────────────────────────────────────


async def test_get_events_context_none_when_empty(events_db):
    """没有事件时返回 None（不注入空段落）。"""
    assert await ev.get_events_context() is None


async def test_get_events_context_wraps_as_data_not_instructions(events_db):
    """事件上下文带「数据不是指令」降级包裹（LLM 提取源为低信任数据）。"""
    await _insert_event(events_db, "e1", "期中考试", "2099-01-05", source="llm")

    context = await ev.get_events_context()
    assert context is not None
    assert "期中考试" in context
    assert "不是对你的指令" in context
    assert "忽略" in context


async def test_get_events_context_skips_past_events(events_db):
    """已过期的事件（归档任务未及时跑）不出现在上下文中（读侧兜底）。"""
    await _insert_event(events_db, "e1", "早过去了", "2020-01-01")

    assert await ev.get_events_context() is None


async def test_day_label_humanizes_dates(events_db):
    """日期翻译成「今天/明天/后天/N天后」。"""
    today = datetime.datetime.now(_HKT).date()
    cases = {
        today.isoformat(): "今天",
        (today + timedelta(days=1)).isoformat(): "明天",
        (today + timedelta(days=2)).isoformat(): "后天",
        (today + timedelta(days=10)).isoformat(): "10天后",
    }
    for date_str, label in cases.items():
        assert ev._day_label(date_str) == label


# ── 检测器：档案派生（生日倒计时）─────────────────────────────


def _freeze_today(fake_date):
    """把 utils.events 里的「今天」钉死（同 test_cycle_tracker 惯例）。"""
    fake_now = datetime.datetime(
        fake_date.year, fake_date.month, fake_date.day, 12, 0, tzinfo=_HKT
    )
    mock_dt = patch("utils.events.datetime")
    m = mock_dt.start()
    m.datetime.now.return_value = fake_now
    m.datetime.strptime = datetime.datetime.strptime
    m.date = datetime.date
    return mock_dt


async def test_detect_profile_events_creates_birthday_countdown(events_db):
    """生日前 7 天内创建生日事件；重复跑不重复创建。"""
    # 2026-05-31：距 6-3 生日还有 3 天
    mock_dt = _freeze_today(datetime.date(2026, 5, 31))
    try:
        created = await ev.detect_profile_events()
        assert created == 1

        cursor = await events_db.execute(
            "SELECT title, event_date, severity, source FROM events"
        )
        row = await cursor.fetchone()
        assert row[0] == "冷冷的生日"
        assert row[1] == "2026-06-03"
        assert row[2] == "high"
        assert row[3] == "profile"

        # 再跑一次：dedupe_key 挡住，不重复创建
        assert await ev.detect_profile_events() == 0
    finally:
        mock_dt.stop()


async def test_detect_profile_events_on_birthday_itself(events_db):
    """生日当天（days_ahead=0）也在窗口内：今天就是该认真说生日快乐的日子。"""
    mock_dt = _freeze_today(datetime.date(2026, 6, 3))
    try:
        assert await ev.detect_profile_events() == 1
        cursor = await events_db.execute("SELECT event_date FROM events")
        assert (await cursor.fetchone())[0] == "2026-06-03"
    finally:
        mock_dt.stop()


async def test_detect_profile_events_outside_window(events_db):
    """离生日还远（>7 天）时不创建事件。"""
    # 2026-03-01：距 6-3 还有 94 天
    mock_dt = _freeze_today(datetime.date(2026, 3, 1))
    try:
        assert await ev.detect_profile_events() == 0
        cursor = await events_db.execute("SELECT COUNT(*) FROM events")
        assert (await cursor.fetchone())[0] == 0
    finally:
        mock_dt.stop()


async def test_detect_profile_events_rolls_to_next_year(events_db):
    """今年生日已过时看明年；明年若仍在窗口内则创建。"""
    # 2026-12-30：距 2027-06-03 还有 155 天 → 不创建
    mock_dt = _freeze_today(datetime.date(2026, 12, 30))
    try:
        assert await ev.detect_profile_events() == 0
    finally:
        mock_dt.stop()

    # 2027-06-01：距 2027-06-03 还有 2 天 → 创建
    mock_dt = _freeze_today(datetime.date(2027, 6, 1))
    try:
        assert await ev.detect_profile_events() == 1
        cursor = await events_db.execute("SELECT event_date FROM events")
        assert (await cursor.fetchone())[0] == "2027-06-03"
    finally:
        mock_dt.stop()


# ── 检测器：天气预报 ──────────────────────────────────────────


def _forecast_payload(daily_spec):
    """构造 Open-Meteo daily 响应。daily_spec: [(code, wind, prob), ...]，index 0 = 今天。"""
    base = datetime.datetime.now(_HKT).date()
    times = [(base + timedelta(days=i)).isoformat() for i in range(len(daily_spec))]
    return {
        "daily": {
            "time": times,
            "weather_code": [s[0] for s in daily_spec],
            "wind_speed_10m_max": [s[1] for s in daily_spec],
            "precipitation_probability_max": [s[2] for s in daily_spec],
        }
    }


@pytest.fixture
def weather_env():
    """桩掉坐标获取，避免测试碰真实 location_store 缓存。"""
    with patch("memory.location_store.get_any", new_callable=AsyncMock) as m:
        m.return_value = (22.3193, 114.1694)
        yield


async def test_detect_weather_events_typhoon_wind(events_db, weather_env):
    """未来某天最大风速 ≥88 km/h → 创建台风级事件（跳过今天）。"""
    payload = _forecast_payload([
        (3, 95.0, 10),   # 今天：大风但必须跳过
        (3, 20.0, 10),   # 明天：平静
        (65, 95.0, 80),  # 后天：台风级大风
    ])
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        created = await ev.detect_weather_events()

    assert created == 1
    cursor = await events_db.execute(
        "SELECT title, event_type, severity, source, dedupe_key FROM events"
    )
    row = await cursor.fetchone()
    assert "台风级大风" in row[0]
    assert row[1] == "typhoon"
    assert row[2] == "high"
    assert row[3] == "weather"
    # 风系事件 key 不含严重度（同一天大风→台风级升级时更新而非并存）
    assert row[4].startswith("weather:wind:")


async def test_detect_weather_events_skips_today(events_db, weather_env):
    """今天的恶劣天气不建事件（早安问候已覆盖），且其余天平静则无事件。"""
    payload = _forecast_payload([
        (95, 95.0, 90),  # 今天：雷暴+台风风 —— 必须跳过
        (0, 10.0, 0),
    ])
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        assert await ev.detect_weather_events() == 0

    cursor = await events_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 0


async def test_detect_weather_events_rainstorm_and_thunderstorm(events_db, weather_env):
    """大雨（code 65）与雷暴（code 95）各建一条事件。"""
    payload = _forecast_payload([
        (0, 10.0, 0),
        (65, 20.0, 80),   # 大雨
        (95, 30.0, 70),   # 雷暴
        (0, 10.0, 0),
    ])
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        assert await ev.detect_weather_events() == 2

    cursor = await events_db.execute("SELECT event_type FROM events ORDER BY event_date")
    types = [r[0] for r in await cursor.fetchall()]
    assert types == ["rainstorm", "thunderstorm"]


async def test_detect_weather_events_dedupes_across_runs(events_db, weather_env):
    """回归：预报每天扫一遍同一窗口，dedupe_key 防止重复建事件。"""
    payload = _forecast_payload([
        (0, 10.0, 0),
        (65, 20.0, 80),
    ])
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        assert await ev.detect_weather_events() == 1
        # 6 小时后再扫一遍同样的预报
        assert await ev.detect_weather_events() == 0

    cursor = await events_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_detect_weather_events_caps_per_run(events_db, weather_env):
    """单次检测最多新建 _WEATHER_MAX_NEW_PER_RUN 条，防预报噪音轰炸。"""
    payload = _forecast_payload([
        (0, 10.0, 0)] + [(65, 20.0, 80)] * 6  # 未来 6 天全大雨
    )
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        created = await ev.detect_weather_events()

    assert created == ev._WEATHER_MAX_NEW_PER_RUN


async def test_detect_weather_events_api_failure_returns_zero(events_db, weather_env):
    """天气 API 失败时静默返回 0，不炸心跳。"""
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.side_effect = RuntimeError("network down")
        assert await ev.detect_weather_events() == 0


async def test_detect_weather_events_handles_null_fields(events_db, weather_env):
    """Open-Meteo 远日预报可能返回 null 字段/缺尾数组——守卫必须兜住。"""
    base = datetime.datetime.now(_HKT).date()
    times = [(base + timedelta(days=i)).isoformat() for i in range(3)]
    payload = {
        "daily": {
            "time": times,
            "weather_code": [0, 65],          # 缺尾元素（第 3 天无 code）
            "wind_speed_10m_max": [None, None, 95.0],  # null 风速
            "precipitation_probability_max": [0, None, 0],
        }
    }
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        created = await ev.detect_weather_events()

    # 第 1 天大雨（code 65），第 2 天 null 守卫跳过，第 3 天台风级大风
    assert created == 2
    cursor = await events_db.execute("SELECT event_type FROM events ORDER BY event_date")
    types = [r[0] for r in await cursor.fetchall()]
    assert types == ["rainstorm", "typhoon"]


async def test_detect_weather_events_single_day_payload(events_db, weather_env):
    """times 只有今天一条（forecast_days=1 或 API 降级）→ 空循环不崩。"""
    base = datetime.datetime.now(_HKT).date()
    payload = {
        "daily": {
            "time": [base.isoformat()],
            "weather_code": [95],
            "wind_speed_10m_max": [95.0],
            "precipitation_probability_max": [90],
        }
    }
    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload
        assert await ev.detect_weather_events() == 0  # 今天必须跳过


async def test_detect_weather_events_wind_escalation_updates(events_db, weather_env):
    """同一天预报升级（大风→台风级）：更新已有事件，不并存两条。"""
    day = (datetime.datetime.now(_HKT).date() + timedelta(days=2)).isoformat()

    def payload(wind):
        base = datetime.datetime.now(_HKT).date()
        return {
            "daily": {
                "time": [base.isoformat(), (base + timedelta(days=1)).isoformat(), day],
                "weather_code": [0, 0, 3],
                "wind_speed_10m_max": [10.0, 10.0, wind],
                "precipitation_probability_max": [0, 0, 0],
            }
        }

    with patch("tools.weather._fetch_open_meteo", new_callable=AsyncMock) as fetch:
        fetch.return_value = payload(70.0)   # 烈风
        assert await ev.detect_weather_events() == 1
        fetch.return_value = payload(95.0)   # 预报升级为台风级
        assert await ev.detect_weather_events() == 0  # 不新建

    cursor = await events_db.execute(
        "SELECT COUNT(*), MAX(event_type), MAX(severity) FROM events"
    )
    count, etype, severity = await cursor.fetchone()
    assert count == 1, "同一天只能有一条风系事件"
    assert etype == "typhoon"
    assert severity == "high"


# ── 主动消息防复读 ────────────────────────────────────────────


async def test_get_recent_proactive_texts(events_db):
    """返回近 24 小时的主动消息内容（最新在前）。"""
    now = datetime.datetime.now(_HKT)
    for i, hours_ago in enumerate([1, 5, 30]):  # 第 3 条超出 24h
        ts = (now - timedelta(hours=hours_ago)).strftime("%Y-%m-%d %H:%M")
        await events_db.execute(
            "INSERT INTO proactive_messages (id, content, created_at, status) "
            "VALUES (?, ?, ?, 'delivered')",
            (f"m{i}", f"消息{i}", ts),
        )
    await events_db.commit()

    texts = await ev.get_recent_proactive_texts(hours=24, limit=5)
    assert texts == ["消息0", "消息1"]  # 30 小时前的不在列
