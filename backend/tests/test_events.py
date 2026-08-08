"""事件管理器单元测试 — 登记/去重/归档/注入上下文/检测器。"""
import datetime
from datetime import timedelta
from unittest.mock import AsyncMock, patch

import pytest

from utils import events as ev
from utils.timezone import HKT as _HKT


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
    """活跃事件按发生日期正序返回。日期用动态偏移（写死的未来日期
    一过零点就变过去日期被读面过滤，2026-08-02 体检实锤）。"""
    await _insert_event(events_db, "e1", "晚的事", _date_offset(30))
    await _insert_event(events_db, "e2", "早的事", _date_offset(3))
    await _insert_event(events_db, "e3", "已翻篇", _date_offset(-30), status="archived")

    events = await ev.get_active_events()
    assert [e["title"] for e in events] == ["早的事", "晚的事"]


def _date_offset(days: int) -> str:
    """相对今天的日期字符串。归档测试不能硬编码日期：
    「2026-07-26」写的时候是「明天」，两天之后就成「昨天」，
    断言随真实日期推移整体失效（2026-07-27 凌晨实测翻红）。"""
    return (datetime.datetime.now(_HKT).date() + timedelta(days=days)).isoformat()


async def test_archive_expired_single_day(events_db):
    """单日活动：event_date 过了就归档。"""
    await _insert_event(events_db, "e1", "昨天的考试", _date_offset(-1))
    await _insert_event(events_db, "e2", "明天的考试", _date_offset(1))

    count = await ev.archive_expired()
    assert count == 1

    cursor = await events_db.execute(
        "SELECT title FROM events WHERE status = 'archived'"
    )
    assert (await cursor.fetchone())[0] == "昨天的考试"


async def test_archive_expired_respects_end_date(events_db):
    """多天事件按 end_date 归档：还在进行中的不归档。"""
    # 已开始但未结束（昨天开始、后天结束）
    await events_db.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, end_date, status, created_at)
           VALUES ('e1', '旅行中', 'trip', ?, ?, 'active', '2026-07-20 10:00')""",
        (_date_offset(-1), _date_offset(2)),
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


# ── 检测器：相识纪念日 ────────────────────────────────────────


async def _set_first_met(events_db, value):
    """写入/清空 first_met_on（None → NULL）。"""
    await events_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1", (value,)
    )
    await events_db.commit()


async def test_anniversary_detector_creates_and_dedupes(events_db):
    """周年前 3 天内创建事件；同日重跑不重复；归档后明年可续期。"""
    fixed = datetime.date(2026, 3, 12)          # 距 2026-03-14 还有 2 天
    await _set_first_met(events_db, "2024-03-14")

    with patch.object(ev, "_today", return_value=fixed):
        assert await ev.detect_anniversary_events() == 1
        assert await ev.detect_anniversary_events() == 0  # 活跃行去重

    cursor = await events_db.execute(
        "SELECT title, severity, source, dedupe_key FROM events"
    )
    title, severity, source, dedupe = await cursor.fetchone()
    assert "2 周年" in title
    assert severity == "high"
    assert source == "profile"
    assert dedupe == "profile:anniversary:2026-03-14"

    # 归档（今年已过）后重跑 → 不重建同一条；换到明年窗口内 → 续期新建
    await events_db.execute("UPDATE events SET status = 'archived'")
    await events_db.commit()
    with patch.object(ev, "_today", return_value=datetime.date(2027, 3, 12)):
        assert await ev.detect_anniversary_events() == 1
    cursor = await events_db.execute(
        "SELECT dedupe_key FROM events WHERE status = 'active'"
    )
    assert (await cursor.fetchone())[0] == "profile:anniversary:2027-03-14"


async def test_anniversary_detector_outside_window(events_db):
    """距周年超过 3 天 → 不创建。"""
    await _set_first_met(events_db, "2024-03-14")
    with patch.object(ev, "_today", return_value=datetime.date(2026, 3, 1)):
        assert await ev.detect_anniversary_events() == 0


async def test_anniversary_detector_no_or_bad_date(events_db):
    """first_met_on 缺失 / 非法 / 晚于今天 → 0，不抛。"""
    assert await ev.detect_anniversary_events() == 0  # NULL（夹具默认）

    await _set_first_met(events_db, "not-a-date")
    assert await ev.detect_anniversary_events() == 0

    future = (ev._today() + timedelta(days=30)).isoformat()
    await _set_first_met(events_db, future)
    assert await ev.detect_anniversary_events() == 0


async def test_anniversary_detector_leap_day(events_db):
    """2/29 相识 → 平年落在 2/28，不抛 ValueError。"""
    await _set_first_met(events_db, "2024-02-29")
    with patch.object(ev, "_today", return_value=datetime.date(2026, 2, 27)):
        assert await ev.detect_anniversary_events() == 1
    cursor = await events_db.execute("SELECT dedupe_key FROM events")
    assert (await cursor.fetchone())[0] == "profile:anniversary:2026-02-28"


# ── 检测器：记忆回声 ──────────────────────────────────────────


async def _seed_old_memory(events_db, mem_id, content, days_ago=120):
    """直接插入一条久远记忆（绕过 add 的 now 时间戳）。"""
    from utils.timezone import _TIME_FMT, HKT

    old = (datetime.datetime.now(HKT) - timedelta(days=days_ago)).strftime(_TIME_FMT)
    await events_db.execute(
        "INSERT INTO memories (id, content, category, created_at, access_count, status) "
        "VALUES (?, ?, '日常', ?, 0, 'active')",
        (mem_id, content, old),
    )
    await events_db.commit()


async def test_memory_echo_creates_once_per_memory(events_db):
    """每条记忆一生只 echo 一次；限频绕过后也不会重复同一条。"""
    await _seed_old_memory(events_db, "mem-a", "喜欢下雨天")

    assert await ev.detect_memory_echoes() == 1
    cursor = await events_db.execute(
        "SELECT dedupe_key, source, event_type FROM events"
    )
    dedupe, source, etype = await cursor.fetchone()
    assert dedupe == "memory-echo:mem-a"
    assert source == "memory"
    assert etype == "memory_echo"

    # 把 created_at 拨到 8 天前绕过全局限频——同一条记忆仍不重复
    old_ts = (datetime.datetime.now(_HKT) - timedelta(days=8)).strftime("%Y-%m-%d %H:%M")
    await events_db.execute("UPDATE events SET created_at = ?", (old_ts,))
    await events_db.commit()
    assert await ev.detect_memory_echoes() == 0

    # 来第二条旧记忆 → echo 的是另一条（mem-a 的事件仍在活跃期，
    # 次日才归档，所以这里断言 mem-b 被新建，而不是唯一活跃行）
    await _seed_old_memory(events_db, "mem-b", "想去北海道")
    assert await ev.detect_memory_echoes() == 1
    cursor = await events_db.execute(
        "SELECT COUNT(*) FROM events "
        "WHERE status = 'active' AND dedupe_key = 'memory-echo:mem-b'"
    )
    assert (await cursor.fetchone())[0] == 1


async def test_memory_echo_rate_limit(events_db):
    """7 天内已有 echo（哪怕已归档）→ 有新候选也不创建。"""
    await _seed_old_memory(events_db, "mem-a", "旧事一")
    await _seed_old_memory(events_db, "mem-b", "旧事二")
    assert await ev.detect_memory_echoes() == 1  # 用掉本周配额
    assert await ev.detect_memory_echoes() == 0  # 还剩候选，但限频


async def test_memory_echo_archived_still_excluded(events_db):
    """已归档的 echo 同样排除（一生一次跨归档）。"""
    await _seed_old_memory(events_db, "mem-a", "旧事")
    old_ts = (datetime.datetime.now(_HKT) - timedelta(days=30)).strftime("%Y-%m-%d %H:%M")
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at, dedupe_key) "
        "VALUES ('e-old', '想起旧事', 'memory_echo', '2026-01-01', 'normal', 'memory', "
        "'archived', ?, 'memory-echo:mem-a')",
        (old_ts,),
    )
    await events_db.commit()
    assert await ev.detect_memory_echoes() == 0  # 唯一候选已被说过


async def test_memory_echo_skips_already_echoed_candidate(events_db):
    """部分候选已 echo → 点查排除后选中未 echo 的那条（体检 P2-16 点查化）。

    fresh 只剩 mem-b，random.choice 在单元素列表上结果确定。
    """
    await _seed_old_memory(events_db, "mem-a", "旧事一")
    await _seed_old_memory(events_db, "mem-b", "旧事二")
    old_ts = (datetime.datetime.now(_HKT) - timedelta(days=30)).strftime("%Y-%m-%d %H:%M")
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at, dedupe_key) "
        "VALUES ('e-old', '想起旧事', 'memory_echo', '2026-01-01', 'normal', 'memory', "
        "'archived', ?, 'memory-echo:mem-a')",
        (old_ts,),
    )
    await events_db.commit()
    assert await ev.detect_memory_echoes() == 1
    cursor = await events_db.execute(
        "SELECT dedupe_key FROM events WHERE status = 'active'"
    )
    assert (await cursor.fetchone())[0] == "memory-echo:mem-b"


async def test_memory_echo_no_candidates(events_db):
    """空记忆表 / 只有新记忆 → 0，不抛。"""
    assert await ev.detect_memory_echoes() == 0

    # 只有 90 天内的新记忆
    from memory.manager import MemoryManager
    mgr = MemoryManager(events_db)
    await mgr.add("日常", "刚发生的事")
    assert await ev.detect_memory_echoes() == 0


async def test_memory_echo_event_archives_next_day(events_db):
    """echo 事件 event_date=今天 → 次日自动归档（翻篇承诺）。"""
    await _seed_old_memory(events_db, "mem-a", "旧事")
    assert await ev.detect_memory_echoes() == 1

    # 模拟次日：把事件日期拨到昨天再归档
    yesterday = (ev._today() - timedelta(days=1)).isoformat()
    await events_db.execute("UPDATE events SET event_date = ?", (yesterday,))
    await events_db.commit()
    assert await ev.archive_expired() == 1


# ── 软删墓碑（v9）与日历写面 ─────────────────────────────────


async def test_tombstone_blocks_recreate(events_db):
    """删掉的事件成墓碑：同 key 重建被挡（LLM 重新提取/检测器再生成）。

    v9 的核心承诺——「删了就是删了」，不与归档（翻篇可再生）混淆。
    """
    saved, _ = await ev.create_event("期中考试", "exam", "2026-08-01")
    deleted = await ev.soft_delete_event(saved["id"])
    assert deleted is not None and deleted["status"] == "active"  # 返回被删前的行

    again, created = await ev.create_event("期中考试", "exam", "2026-08-01")
    assert again is None and created is False          # 墓碑挡下，三态之 (None, False)

    cursor = await events_db.execute("SELECT COUNT(*), status FROM events GROUP BY status")
    rows = await cursor.fetchall()
    assert rows == [(1, "deleted")]                    # 始终只有一行，且是墓碑


async def test_tombstone_force_revives_same_id(events_db):
    """force=True 原 id 复活（日历语义：同标题同日重加 = 反悔删除）。"""
    saved, _ = await ev.create_event("期中考试", "exam", "2026-08-01", memo="旧备注")
    await ev.soft_delete_event(saved["id"])

    revived, created = await ev.create_event(
        "期中考试", "exam", "2026-08-01",
        source="user", memo="新备注", force=True,
    )
    assert created is True
    assert revived["id"] == saved["id"]                # 原 id 翻回
    assert revived["status"] == "active"
    assert revived["source"] == "user"                 # 复活后的来源是新入口
    assert revived["memo"] == "新备注"                 # 内容覆盖

    cursor = await events_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_soft_delete_not_found(events_db):
    """删不存在的 id → None，不抛。"""
    assert await ev.soft_delete_event("nonexistent12345") is None


async def test_soft_delete_readonly_raises(events_db):
    """系统派生的生日/纪念日拒删（人删了也会和下一轮自动生成打架）。"""
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at, dedupe_key) VALUES ('b1', '冷冷的生日', 'birthday', "
        "'2026-06-03', 'high', 'profile', 'active', '2026-07-25 10:00', "
        "'profile:birthday:2026-06-03')"
    )
    await events_db.commit()
    with pytest.raises(ValueError):
        await ev.soft_delete_event("b1")


async def test_update_event_fields(events_db):
    """白名单字段更新；空串显式清空 end_date / memo。"""
    saved, _ = await ev.create_event(
        "台北旅行", "trip", "2026-08-10",
        end_date="2026-08-12", memo="要带护照",
    )
    updated = await ev.update_event(
        saved["id"], title="台北自由行", event_date="2026-08-11",
        end_date="", memo="",
    )
    assert updated["title"] == "台北自由行"
    assert updated["event_date"] == "2026-08-11"
    assert updated["end_date"] is None                 # 空串 = 显式清空
    assert updated["memo"] is None


async def test_update_event_keeps_dedupe_key(events_db):
    """编辑不改写 dedupe_key：key 是事件身份，换了会破坏墓碑不变式。"""
    saved, _ = await ev.create_event("期中考试", "exam", "2026-08-01")
    await ev.update_event(saved["id"], title="期末考试", event_date="2026-08-20")
    cursor = await events_db.execute("SELECT dedupe_key FROM events WHERE id = ?", (saved["id"],))
    assert (await cursor.fetchone())[0] == "llm:期中考试:2026-08-01"  # 原 key 不动


async def test_update_event_readonly_raises(events_db):
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('a1', '相识周年的日子', 'anniversary', "
        "'2026-08-03', 'high', 'profile', 'active', '2026-07-25 10:00')"
    )
    await events_db.commit()
    with pytest.raises(ValueError):
        await ev.update_event("a1", title="改不动的")


async def test_update_event_bad_date_raises(events_db):
    saved, _ = await ev.create_event("考试", "exam", "2026-08-01")
    with pytest.raises(ValueError):
        await ev.update_event(saved["id"], event_date="下周三")
    with pytest.raises(ValueError):
        await ev.update_event(saved["id"], end_date="2026-07-01")  # 早于开始


async def test_update_event_archived_to_future_revives(events_db):
    """归档行被改到未来日期 → 自动翻回 active（反悔改期）。"""
    await _insert_event(events_db, "e-old", "延期的约定", "2026-07-01", status="archived")
    updated = await ev.update_event("e-old", event_date="2026-09-01")
    assert updated["status"] == "active"


async def test_update_event_not_found(events_db):
    assert await ev.update_event("nonexistent12345", title="x") is None


async def test_calendar_range_filters_types_and_status(events_db):
    """日历读面：只给冷冷看日程类 + 只读纪念日，含 archived 回看，排除墓碑与内心戏。"""
    await _insert_event(events_db, "c1", "期末考试", "2026-08-05")                      # general ✓
    await _insert_event(events_db, "c2", "期中考过了", "2026-07-10", status="archived")  # archived 回看 ✓
    await _insert_event(events_db, "c3", "删掉的", "2026-08-06", status="deleted")       # 墓碑 ✗
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES "
        "('c4', '可能有台风', 'typhoon', '2026-08-07', 'high', 'weather', 'active', '2026-07-25 10:00'),"
        "('c5', '想起旧事', 'memory_echo', '2026-08-07', 'normal', 'memory', 'active', '2026-07-25 10:00'),"
        "('c6', '冷冷的生日', 'birthday', '2026-08-08', 'high', 'profile', 'active', '2026-07-25 10:00')"
    )
    await events_db.commit()

    events = await ev.list_events_for_calendar("2026-08-01", "2026-08-31")
    got = {e["id"] for e in events}
    assert got == {"c1", "c6"}    # 默认读面（提醒路径）：台风不可见（日历面板显式 include_weather）；回声永远不上日历
    events_jul = await ev.list_events_for_calendar("2026-07-01", "2026-07-31")
    assert {e["id"] for e in events_jul} == {"c2"}   # 过往月份可翻看


async def test_calendar_range_intersection_multiday(events_db):
    """多天事件与查询区间相交即出现（跨月旅行在两个月的日历上都在）。"""
    await _insert_event(events_db, "t1", "台北旅行", "2026-08-30", end_date="2026-09-02")
    assert {e["id"] for e in await ev.list_events_for_calendar("2026-08-01", "2026-08-31")} == {"t1"}
    assert {e["id"] for e in await ev.list_events_for_calendar("2026-09-01", "2026-09-30")} == {"t1"}
    assert await ev.list_events_for_calendar("2026-09-03", "2026-09-30") == []  # 结束后不再出现


async def test_calendar_weather_visible_when_include_weather(events_db):
    """天气事件默认不上日历（提醒路径保持内心戏），日历面板开 include_weather 后可见。"""
    for i, etype in enumerate(["typhoon", "gale", "thunderstorm", "rainstorm"]):
        await events_db.execute(
            "INSERT INTO events (id, title, event_type, event_date, severity, source, "
            "status, created_at) VALUES "
            f"('w{i}', '2026-08-{7 + i:02d} 有{etype}', '{etype}', '2026-08-{7 + i:02d}', "
            f"'normal', 'weather', 'active', '2026-07-25 10:00')"
        )
    await events_db.commit()

    assert await ev.list_events_for_calendar("2026-08-01", "2026-08-31") == []
    got = {e["id"] for e in await ev.list_events_for_calendar(
        "2026-08-01", "2026-08-31", include_weather=True)}
    assert got == {"w0", "w1", "w2", "w3"}


# ── 完成态（v20：「办完了，别再惦记」）────────────────────────


async def test_set_event_completed_marks_and_unmarks(events_db):
    """勾完成写时间戳、取消勾清空；重复勾幂等（刷新时间戳）。"""
    await _insert_event(events_db, "d1", "期中考试", "2099-08-05")
    updated = await ev.set_event_completed("d1", True)
    assert updated["completed_at"] is not None
    updated2 = await ev.set_event_completed("d1", True)
    assert updated2["completed_at"] >= updated["completed_at"]
    unmarked = await ev.set_event_completed("d1", False)
    assert unmarked["completed_at"] is None


async def test_set_event_completed_rejects_readonly(events_db):
    """生日/相识纪念日与天气事件拒勾（预报是自动检测的信息，不是要办的事）。"""
    await events_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES "
        "('b1', '冷冷的生日', 'birthday', '2099-06-03', 'high', 'profile', 'active', '2026-07-25 10:00'),"
        "('w1', '2026-08-08 有雷暴', 'thunderstorm', '2026-08-08', 'normal', 'weather', 'active', '2026-07-25 10:00')"
    )
    await events_db.commit()
    with pytest.raises(ValueError, match="readonly"):
        await ev.set_event_completed("b1", True)
    with pytest.raises(ValueError, match="readonly"):
        await ev.set_event_completed("w1", True)


async def test_set_event_completed_not_found(events_db):
    assert await ev.set_event_completed("nonexistent12345", True) is None


async def test_get_active_events_excludes_completed(events_db):
    """完成的事件从 LLM 读侧消失（小汐不再惦记）；取消完成即回来。"""
    await _insert_event(events_db, "e1", "期中考试", "2099-08-05")
    await _insert_event(events_db, "e2", "体检", "2099-08-10")
    await ev.set_event_completed("e1", True)
    assert {e["id"] for e in await ev.get_active_events()} == {"e2"}
    await ev.set_event_completed("e1", False)
    assert {e["id"] for e in await ev.get_active_events()} == {"e1", "e2"}


async def test_get_events_context_excludes_completed(events_db):
    """完成的事件不进提示词注入（主动消息/早安不再围绕它关心）。"""
    await _insert_event(events_db, "e1", "期中考试", "2099-08-05")
    await _insert_event(events_db, "e2", "台北旅行", "2099-08-20")
    await ev.set_event_completed("e2", True)
    context = await ev.get_events_context()
    assert context is not None
    assert "期中考试" in context
    assert "台北旅行" not in context


async def test_calendar_completed_visibility(events_db):
    """日历读面：已完成的事件默认不打扰提醒路径，include_completed=True 时可见（划线 ✓）。"""
    await _insert_event(events_db, "d1", "期中考试", "2099-08-05")
    await _insert_event(events_db, "d2", "台北旅行", "2099-08-20")
    await ev.set_event_completed("d1", True)
    assert {e["id"] for e in await ev.list_events_for_calendar("2099-08-01", "2099-08-31")} == {"d2"}
    got = {e["id"] for e in await ev.list_events_for_calendar(
        "2099-08-01", "2099-08-31", include_completed=True)}
    assert got == {"d1", "d2"}


async def test_create_event_force_revive_clears_completed(events_db):
    """墓碑 force 复活（冷冷重加同一件事）清空完成态——重新开始惦记。"""
    event, _ = await ev.create_event("期中考试", "exam", "2099-08-05", source="user")
    await ev.set_event_completed(event["id"], True)
    await ev.soft_delete_event(event["id"])
    revived, created = await ev.create_event(
        "期中考试", "exam", "2099-08-05", source="user", force=True)
    assert created is True
    assert revived["completed_at"] is None


async def test_find_similar_active_bidirectional(events_db):
    """标题互含双向命中，跨日期不泄漏。"""
    await _insert_event(events_db, "s1", "期中考试", "2026-08-01")
    # 输入是既有标题的子串
    hit = await ev.find_similar_active("2026-08-01", "期中")
    assert [e["id"] for e in hit] == ["s1"]
    # 输入包含既有标题
    hit2 = await ev.find_similar_active("2026-08-01", "下周三期中考试")
    assert [e["id"] for e in hit2] == ["s1"]
    # 不同日期不命中
    assert await ev.find_similar_active("2026-08-02", "期中") == []
    # 已删除的不提示
    await ev.soft_delete_event("s1")
    assert await ev.find_similar_active("2026-08-01", "期中") == []


# ── 检测器：生理期预测派生 ────────────────────────────────────
#
# 日期全部相对今天构造（_date_offset）：检测器与 get_cycle_stats
# 共用真实时钟，不 patch 时间也能保持确定性。
# 基准场景 A：记录 [-85, -55, -25] → 间隔 30/30 → 平均 30
#            → 预测 = today+5（落在 -2..7 创建窗口内）


@pytest.fixture
async def cycle_events_db(tmp_db):
    """检测器跨模块消费：tmp_db 同时接到 utils.events 与
    utils.cycle_tracker 的 get_db 上（后者经 detect_cycle_events 调
    get_cycle_stats 读 cycle_records）。"""
    with patch("utils.events.get_db", return_value=tmp_db), \
         patch("utils.cycle_tracker.get_db", return_value=tmp_db):
        yield tmp_db


async def _seed_cycle_records(db, offsets):
    for off in offsets:
        await db.execute(
            "INSERT OR IGNORE INTO cycle_records (start_date, note) VALUES (?, '')",
            (_date_offset(off),),
        )
    await db.commit()


async def _cycle_events(db):
    cursor = await db.execute(
        "SELECT * FROM events WHERE source = 'cycle' ORDER BY event_date"
    )
    from database.core import rows_to_dicts
    return rows_to_dicts(cursor, await cursor.fetchall())


async def test_detect_cycle_events_insufficient_records(cycle_events_db):
    """记录不足 2 条：不创建事件，返回 0。"""
    await _seed_cycle_records(cycle_events_db, [-25])
    assert await ev.detect_cycle_events() == 0
    assert await _cycle_events(cycle_events_db) == []


async def test_detect_cycle_events_creates_in_window(cycle_events_db):
    """预测在窗口内：创建 health/cycle 事件，字段齐备，memo 不泄漏原始日期。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    predicted = _date_offset(5)

    assert await ev.detect_cycle_events() == 1

    rows = await _cycle_events(cycle_events_db)
    assert len(rows) == 1
    e = rows[0]
    assert e["title"] == "身体的日子"
    assert e["event_type"] == "health"        # 日历可见白名单内，零前端改动
    assert e["source"] == "cycle"
    assert e["severity"] == "normal"
    assert e["status"] == "active"
    assert e["event_date"] == predicted
    assert e["end_date"] == _date_offset(7)   # 预测 +2，archive_expired 第 4 天翻篇
    assert e["dedupe_key"] == f"cycle:{predicted}"
    assert "痛经" in e["memo"]
    # PII 回归：memo 绝不引述任何原始记录日期
    for off in (-85, -55, -25):
        assert _date_offset(off) not in e["memo"]


async def test_detect_cycle_events_out_of_window(cycle_events_db):
    """预测太远（today+25）：不创建。"""
    await _seed_cycle_records(cycle_events_db, [-65, -35, -5])
    assert await ev.detect_cycle_events() == 0
    assert await _cycle_events(cycle_events_db) == []


async def test_detect_cycle_events_idempotent(cycle_events_db):
    """同预测重跑：dedupe 挡住，第二次返回 0、只有一行。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    assert await ev.detect_cycle_events() == 1
    assert await ev.detect_cycle_events() == 0
    assert len(await _cycle_events(cycle_events_db)) == 1


async def test_detect_cycle_events_drift_archives_and_recreates(cycle_events_db):
    """对话修正导致预测漂移：旧未来事件归档、新事件创建，返回 2。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    old_predicted = _date_offset(5)
    await ev.detect_cycle_events()

    # 补一条 today-20 的记录：间隔 30/30/5 → 平均 22 → 预测漂到 today+2
    await _seed_cycle_records(cycle_events_db, [-20])
    assert await ev.detect_cycle_events() == 2

    rows = await _cycle_events(cycle_events_db)
    by_status = {r["dedupe_key"]: r["status"] for r in rows}
    assert by_status[f"cycle:{old_predicted}"] == "archived"
    assert by_status[f"cycle:{_date_offset(2)}"] == "active"


async def test_detect_cycle_events_drift_back_regenerates(cycle_events_db):
    """预测漂回旧日期时能重新生成——归档（非墓碑）释放 dedupe 槽位。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    old_predicted = _date_offset(5)
    await ev.detect_cycle_events()

    await _seed_cycle_records(cycle_events_db, [-20])        # 漂到 today+2
    await ev.detect_cycle_events()
    await cycle_events_db.execute(
        "DELETE FROM cycle_records WHERE start_date = ?", (_date_offset(-20),)
    )
    await cycle_events_db.commit()                           # 漂回 today+5

    # 返回值 2 = 漂移归档 today+2 一条 + 重建 today+5 一条
    assert await ev.detect_cycle_events() == 2
    rows = await _cycle_events(cycle_events_db)
    statuses = {(r["dedupe_key"], r["status"]) for r in rows}
    assert (f"cycle:{old_predicted}", "active") in statuses   # 原槽位重新激活
    assert (f"cycle:{_date_offset(2)}", "archived") in statuses


async def test_detect_cycle_events_records_drop_below_two_archives(cycle_events_db):
    """记录被删到不足 2 条：预测失效，滞留的未来事件仍被归档。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    await ev.detect_cycle_events()

    await cycle_events_db.execute(
        "DELETE FROM cycle_records WHERE start_date IN (?, ?)",
        (_date_offset(-85), _date_offset(-55)),
    )
    await cycle_events_db.commit()
    assert await ev.detect_cycle_events() == 1  # 纯归档，也计入返回值触发刷新
    assert all(r["status"] == "archived" for r in await _cycle_events(cycle_events_db))


async def test_detect_cycle_events_in_progress_not_archived(cycle_events_db):
    """进行中事件（event_date <= today）不被漂移清理撤掉，保住当下关心。"""
    await _seed_cycle_records(cycle_events_db, [-85, -55, -25])
    await _insert_event(
        cycle_events_db, "c_old", "身体的日子", _date_offset(0),
        source="cycle", dedupe_key=f"cycle:{_date_offset(0)}",
    )

    assert await ev.detect_cycle_events() == 1  # 只新建 today+5，不归档 today
    rows = {r["dedupe_key"]: r["status"] for r in await _cycle_events(cycle_events_db)}
    assert rows[f"cycle:{_date_offset(0)}"] == "active"
    assert rows[f"cycle:{_date_offset(5)}"] == "active"
