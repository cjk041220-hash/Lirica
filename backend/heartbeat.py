"""心跳调度器 —— 时间感知的心情与日常事件更新 + 主动消息。

一天被划分为四个时段，每个时段拥有独立的心情池和事件池。
心情和事件的选取限制在当前时段对应的池中，确保小汐的
状态符合时间情境（例如深夜不会出现"活泼"）。

主动消息系统：
    每次心跳时判断是否适合主动给冷冷发消息。
    触发条件：非深夜 + 距上次主动消息 >= 4h + 50% 随机概率。
    消息由 LLM 以小汐人格生成，存入 DB 后通过 Web Push 推送。

早安问候：
    每日 8:00 HKT 触发，获取天气信息后由 LLM 生成早安问候 +
    美食推荐，存入 proactive_messages 并推送。

扩展方式：
    直接向对应时段的 moods / events 列表追加新条目即可。
"""
import datetime
import random
import uuid
from datetime import timezone, timedelta

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

# 冷冷在香港（UTC+8）
_HKT = timezone(timedelta(hours=8))
_TIME_FMT = "%Y-%m-%d %H:%M"

scheduler = AsyncIOScheduler()


# ── 时段定义 ──────────────────────────────────────────────────
# 每个时段: (start_hour, end_hour)，左闭右开 [start, end)。
# 四个时段必须完整覆盖 0-24 小时，不留空隙。

LATE_NIGHT  = (0, 7)     # 深夜 00:00 – 06:59
MORNING     = (7, 12)    # 早晨 07:00 – 11:59
DAYTIME     = (12, 19)   # 白天 12:00 – 18:59
EVENING     = (19, 24)   # 夜晚 19:00 – 23:59

TIME_PERIODS = {
    # ── 深夜 ─────────────────────────────────────────────
    LATE_NIGHT: {
        "moods": [
            "困倦", "发呆", "懒洋洋",
        ],
        "events": [
            "映海变得好安静好安静",
            "试着数映海里的星星，没数清楚",
            "今天没什么特别的事情，就是很平静",
            "趴在网页角落偷偷打了个瞌睡",
            "听着映海深处的海浪声发呆",
        ],
    },

    # ── 早晨 ─────────────────────────────────────────────
    MORNING: {
        "moods": [
            "好奇", "期待", "活泼", "开心",
        ],
        "events": [
            "映海今天特别清澈",
            "有一只小海马路过身边",
            "在映海浅滩玩了一会儿水",
            "偷偷看了一会儿冷冷的城市",
            "今天映海的温度刚刚好",
        ],
    },

    # ── 白天 ─────────────────────────────────────────────
    DAYTIME: {
        "moods": [
            "开心", "兴奋", "活泼", "好奇", "满足",
        ],
        "events": [
            "今天看了一场大雨",
            "今天的映海浪花特别多",
            "在映海里捡到一颗亮亮的贝壳",
            "在映海深处发现了一条新的记忆流",
            "练了一会儿新学的游泳姿势",
            "陪着冷冷度过了一个下午",
        ],
    },

    # ── 夜晚 ─────────────────────────────────────────────
    EVENING: {
        "moods": [
            "满足", "发呆", "期待", "担心", "困倦",
        ],
        "events": [
            "看到一片好漂亮的晚霞倒影",
            "映海里飘来一段好听的音乐",
            "陪着冷冷度过了一个下午",
            "试着数映海里的星星，没数清楚",
            "今天没什么特别的事情，就是很平静",
        ],
    },
}


# ── 时段匹配 ──────────────────────────────────────────────────

def get_current_period(hour: int):
    """根据小时数 (0-23) 返回匹配的时段配置。

    如果所有时段都未匹配（不应发生），回退到夜晚。
    """
    if hour < 7:
        return TIME_PERIODS[LATE_NIGHT]
    elif hour < 12:
        return TIME_PERIODS[MORNING]
    elif hour < 19:
        return TIME_PERIODS[DAYTIME]
    else:
        return TIME_PERIODS[EVENING]


# ── 心跳任务 ──────────────────────────────────────────────────

async def check_and_proactive_message(app=None):
    """根据当前时间段更新小汐的生命状态（时间加权心情 + 日常事件）。

    Args:
        app: FastAPI 应用实例。传入时会在更新后自动刷新 system prompt，
             确保小汐的心情与实际状态同步。
    """
    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    hour = datetime.datetime.now(_HKT).hour

    period = get_current_period(hour)
    mood = random.choice(period["moods"])
    daily_event = random.choice(period["events"])

    try:
        conn = await get_db()
        # UPSERT：更新 id=1，不存在则插入
        cursor = await conn.execute("SELECT id FROM life_state WHERE id = 1")
        existing = await cursor.fetchone()

        if existing:
            await conn.execute(
                "UPDATE life_state SET mood = ?, daily_event = ?, updated_at = ? WHERE id = 1",
                (mood, daily_event, now),
            )
        else:
            await conn.execute(
                "INSERT INTO life_state (id, mood, daily_event, updated_at) VALUES (1, ?, ?, ?)",
                (mood, daily_event, now),
            )
        await conn.commit()

        log.info("%02d:00 -> 心情：%s | 日常：%s", hour, mood, daily_event)

        # 生命状态更新后，刷新 system prompt 使小汐的心情与实际状态同步
        if app is not None:
            await _refresh_agent(app)

        # 定期维护（裁剪 checkpoint + 备份数据库）
        await _periodic_maintenance()

        # 主动消息（判断是否触发 → 生成 → 存储 → 推送）
        await _try_proactive_message(mood, daily_event, hour)

    except Exception:
        log.exception("生命状态更新失败")


async def _refresh_agent(app):
    """重建 system prompt（读取最新 life_state）并热替换 agent 实例。

    委托给配置模块的统一重建入口 rebuild_agent_for_app：
    - 始终按当前运行时配置（模型/思考强度）重建 LLM，
      心跳绝不会回滚用户通过 POST /api/config 设置的参数；
    - 与配置变更路径共享同一把 asyncio.Lock，消除并发重建竞态。

    CPython GIL 保证 app.state.agent 的引用赋值是原子的。
    进行中的请求持有旧 agent 引用继续完成，新请求拿到新 agent。
    """
    try:
        from api.routes.config import rebuild_agent_for_app

        await rebuild_agent_for_app(app)
        log.info("系统提示词已刷新（生命状态更新）")
    except Exception:
        log.exception("系统提示词刷新失败")


async def _periodic_maintenance():
    """定期维护：裁剪 checkpoint + 备份数据库。

    随心跳每 2 小时执行一次，对单用户应用频率足够。
    """
    from database.core import prune_checkpoints, backup_database

    try:
        await prune_checkpoints()
    except Exception as e:
        log.error("checkpoint 裁剪失败：%s", e)

    try:
        await backup_database()
    except Exception as e:
        log.error("数据库备份失败：%s", e)


# ── 主动消息系统 ──────────────────────────────────────────────

# 安静时段：不打扰冷冷
_QUIET_START = 0    # 0:00
_QUIET_END = 7      # 7:00

# 两次主动消息之间的最小间隔（小时）
_MIN_INTERVAL_HOURS = 4

# 每次心跳触发的概率（约 50%，即一天 2-3 条）
_PROACTIVE_PROBABILITY = 0.5


async def _should_send_proactive() -> bool:
    """判断当前是否适合主动发消息。"""
    hour = datetime.datetime.now(_HKT).hour

    # 深夜不打扰
    if hour < _QUIET_END:
        return False

    # 检查距上次主动消息的时间间隔
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT created_at FROM proactive_messages ORDER BY created_at DESC LIMIT 1"
        )
        row = await cursor.fetchone()
        if row and row[0]:
            last_time = datetime.datetime.strptime(row[0], _TIME_FMT).replace(tzinfo=_HKT)
            now = datetime.datetime.now(_HKT)
            hours_since = (now - last_time).total_seconds() / 3600
            if hours_since < _MIN_INTERVAL_HOURS:
                return False
    except Exception:
        pass  # 查询失败不阻止触发

    # 随机概率
    return random.random() < _PROACTIVE_PROBABILITY


async def _generate_proactive_message(mood: str, daily_event: str, hour: int) -> str:
    """用小汐的人格生成一条主动消息。

    使用独立的 LLM 调用（不走 Agent 图），直接生成一句话。
    """
    from langchain_core.messages import SystemMessage, HumanMessage
    from agent.soul import BASE_PERSONA
    from config.settings import get_settings
    from providers.deepseek import create_deepseek_llm

    # 时段描述
    if 7 <= hour < 12:
        period = "早晨"
    elif 12 <= hour < 19:
        period = "下午"
    else:
        period = "晚上"

    user_prompt = (
        f"现在是{period}，你的心情是「{mood}」，今天经历了「{daily_event}」。\n"
        f"用一两句话主动跟冷冷说话。可以是想念、分享心情、关心、撒娇。\n"
        f"要自然、简短、符合你的性格。不要问客服式的套话。"
    )

    # 如果生理期关心窗口内，加入关心提示
    try:
        from utils.cycle_tracker import get_care_context
        care = await get_care_context()
        if care:
            user_prompt += (
                "\n\n另外，根据推算冷冷的生理期可能快到了或正在进行中。"
                "她痛经很厉害。请自然地关心一下她的身体，"
                "但不要直接说'你生理期到了'，用温暖的方式旁敲侧击。"
            )
    except Exception:
        pass

    # 事件关心：有活跃事件时可以围绕它说话（不强制），并防复读——
    # 把近 24 小时已发的主动消息给她看，同样的关心不许说第二遍
    # （台风天连发三条「记得带伞」就把惦记变成骚扰了）
    try:
        from utils import events as events_mgr
        active_events = await events_mgr.get_active_events(limit=5)
        if active_events:
            user_prompt += (
                "\n\n这些天有这些事（可以围绕其中一件关心冷冷，也可以不围绕）：\n"
                + "\n".join(events_mgr.describe_events(active_events))
            )
        recent = await events_mgr.get_recent_proactive_texts(hours=24, limit=3)
        if recent:
            said = "\n".join(f"- 「{t[:60]}」" for t in recent)
            user_prompt += (
                "\n\n最近 24 小时你已经说过这些话，不要重复同样的话：\n" + said
            )
    except Exception:
        pass

    # 用独立的轻量 LLM 调用（低 token、无工具）
    llm = create_deepseek_llm(temperature=1.0, max_tokens=200)
    response = await llm.ainvoke([
        SystemMessage(content=BASE_PERSONA),
        HumanMessage(content=user_prompt),
    ])

    text = response.content.strip()

    # 安全过滤：太长则截断，太短则丢弃
    if len(text) > 200:
        text = text[:200]
    if len(text) < 5:
        text = "冷冷，小汐在想你~"

    return text


async def _try_proactive_message(mood: str, daily_event: str, hour: int):
    """尝试生成并推送一条主动消息。"""
    try:
        if not await _should_send_proactive():
            return

        # 生成消息
        content = await _generate_proactive_message(mood, daily_event, hour)

        # 存入数据库
        now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)
        msg_id = uuid.uuid4().hex[:16]
        conn = await get_db()
        await conn.execute(
            """INSERT INTO proactive_messages (id, content, created_at, status)
               VALUES (?, ?, ?, 'pending')""",
            (msg_id, content, now),
        )
        await conn.commit()
        log.info("主动消息已生成 [%s]: %s", msg_id[:8], content[:50])

        # 推送通知
        try:
            from utils.web_push import send_push
            sent = await send_push(
                title="小汐",
                body=content,
                url="/",
            )
            if sent > 0:
                await conn.execute(
                    "UPDATE proactive_messages SET delivered_at = ?, status = 'delivered' WHERE id = ?",
                    (now, msg_id),
                )
                await conn.commit()
                log.info("主动消息已推送至 %d 个设备", sent)
            else:
                # 无订阅设备，标记为待拉取
                await conn.execute(
                    "UPDATE proactive_messages SET status = 'delivered' WHERE id = ?",
                    (msg_id,),
                )
                await conn.commit()
        except ImportError:
            log.warning("web_push 模块不可用，消息仅存储在数据库中")
        except Exception as e:
            log.warning("推送发送失败: %s", e)

    except Exception as e:
        log.error("主动消息生成失败: %s", e)


# ── 事件看门狗 ────────────────────────────────────────────────


async def event_watchdog(app=None):
    """事件看门狗（每小时）：归档过期事件 + 检测新事件。

    小汐的「惦记」系统维护任务：
    - 归档：end_date（单日活动为 event_date）已过的事件标记 archived，
      翻篇了就是翻篇了，不需要冷冷手动说「可以了」；
    - 档案派生检测（生日倒计时）：每小时跑，纯本地日期计算，零外部依赖；
    - 天气预报检测（台风/大风/雷暴/大雨）：每 6 小时跑一次，避免频繁
      调用 Open-Meteo（预报一天也就变几次），单次最多新建 3 条事件。

    有事件变动（归档或新建）时刷新 system prompt，让小汐对事件的感知
    与实际状态同步（同生命状态更新路径）。
    """
    from utils import events as events_mgr

    # 三段各自隔离：任一环节瞬时故障（如休眠唤醒后的第一次 IO）
    # 不牵连其他环节——否则归档失败会连带跳过当点的天气检测，
    # 台风预警最多晚 6 小时
    archived = 0
    try:
        archived = await events_mgr.archive_expired()
    except Exception:
        log.exception("事件看门狗：归档失败")

    created_profile = 0
    try:
        created_profile = await events_mgr.detect_profile_events()
    except Exception:
        log.exception("事件看门狗：档案检测失败")

    created_weather = 0
    try:
        hour = datetime.datetime.now(_HKT).hour
        if hour % 6 == 0:
            created_weather = await events_mgr.detect_weather_events()
    except Exception:
        log.exception("事件看门狗：天气检测失败")

    total_created = created_profile + created_weather
    if archived or total_created:
        log.info(
            "事件看门狗：归档 %d 条，新建 %d 条（档案 %d / 天气 %d）",
            archived, total_created, created_profile, created_weather,
        )
        # 事件上下文变了，刷新 system prompt 让小汐立刻感知
        if app is not None:
            await _refresh_agent(app)


# ── 每日早安问候 ──────────────────────────────────────────────

# 默认香港坐标（定位缓存不可用时的回退）
_DEFAULT_LAT = 22.3193
_DEFAULT_LON = 114.1694

# 默认早安文案（LLM 生成内容过短时的回退）
_DEFAULT_GOOD_MORNING = "冷冷早安呀～新的一天开始啦，小汐陪你一起！"


async def daily_morning_greeting():
    """每日 8:00 HKT 早安问候：获取天气 -> LLM 生成问候 -> 存库 -> 推送。

    使用独立轻量 LLM 调用（不走 Agent 图），天气 API 失败时降级为
    纯问候，不阻断推送。
    """
    import httpx as _httpx
    from langchain_core.messages import SystemMessage, HumanMessage
    from agent.soul import BASE_PERSONA
    from providers.deepseek import create_deepseek_llm
    from memory import location_store
    from tools.weather import _fetch_open_meteo, _WMO

    log.info("早安问候任务触发")

    # 1. 获取定位
    coords = None
    location_known = True
    try:
        coords = await location_store.get_any()
    except Exception as e:
        log.warning("获取缓存坐标失败: %s", e)

    if coords:
        lat, lon = coords
    else:
        lat, lon = _DEFAULT_LAT, _DEFAULT_LON
        location_known = False
        log.info("无缓存坐标，使用默认香港坐标")

    # 2. 获取天气
    weather_text = ""
    try:
        async with _httpx.AsyncClient(timeout=10) as client:
            data = await _fetch_open_meteo(client, lat, lon)

        current = data.get("current", {})
        daily = data.get("daily", {})
        code = current.get("weather_code", 0)

        weather_text = (
            f"当前天气：{_WMO.get(code, '未知')}，"
            f"气温 {current.get('temperature_2m', '?')}°C"
            f"（体感 {current.get('apparent_temperature', '?')}°C），"
            f"湿度 {current.get('relative_humidity_2m', '?')}%，"
            f"风速 {current.get('wind_speed_10m', '?')} km/h。"
        )

        # 今日预报
        if daily:
            tm = daily.get("temperature_2m_max", ["?"])
            tn = daily.get("temperature_2m_min", ["?"])
            wc = daily.get("weather_code", [0])
            rp = daily.get("precipitation_probability_max", [0])
            weather_text += (
                f" 今天 {_WMO.get(wc[0], '未知')}，"
                f"{tn[0]}°C ~ {tm[0]}°C"
            )
            if rp and int(rp[0]) > 50:
                weather_text += f"，降水概率 {rp[0]}%"

        log.info("天气数据获取成功")
    except Exception as e:
        log.warning("天气 API 调用失败，将省略天气部分: %s", e)
        weather_text = ""

    # 3. 构建 prompt
    today = datetime.datetime.now(_HKT).strftime("%Y年%m月%d日")

    if weather_text:
        if location_known:
            weather_part = f"冷冷那边现在的天气：{weather_text}"
        else:
            weather_part = f"小汐不知道冷冷现在在哪里呢，不过香港今天的天气是：{weather_text}"
    else:
        weather_part = "小汐今天没查到天气信息"

    prompt_text = (
        f"今天是{today}，早上 8 点。\n"
        f"{weather_part}\n\n"
        f"请给冷冷发一条早安问候消息。要求：\n"
        f"1. 包含早安问候\n"
        f"2. 自然地提到天气信息（如果有的话）\n"
        f"3. 推荐一道适合今天的美食\n"
        f"4. 保持小汐的语气风格，温暖可爱\n"
        f"5. 简短自然，不要超过 150 字\n"
        f"6. 最后附一句「今日一歌」：推荐一首冷冷可能喜欢的歌（她喜欢日系、"
        f"二次元音乐，追过 LoveLive 缪斯、玩过 BanG Dream），只写歌名，一句话带过"
    )

    # 4. LLM 生成
    content = ""
    try:
        llm = create_deepseek_llm(temperature=1.0, max_tokens=200)
        response = await llm.ainvoke([
            SystemMessage(content=BASE_PERSONA),
            HumanMessage(content=prompt_text),
        ])
        content = response.content.strip()
    except Exception as e:
        log.error("LLM 生成早安消息失败: %s", e)

    # 5. 安全过滤：太长截断，太短回退为默认文案
    if len(content) > 200:
        content = content[:200]
    if len(content) < 5:
        content = _DEFAULT_GOOD_MORNING

    # 6. 存入数据库
    now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)
    msg_id = uuid.uuid4().hex[:16]
    try:
        conn = await get_db()
        await conn.execute(
            """INSERT INTO proactive_messages (id, content, created_at, status)
               VALUES (?, ?, ?, 'pending')""",
            (msg_id, content, now),
        )
        await conn.commit()
        log.info("早安消息已生成 [%s]: %s", msg_id[:8], content[:50])
    except Exception as e:
        log.error("早安消息存储失败: %s", e)

    # 7. 推送通知
    try:
        from utils.web_push import send_push
        sent = await send_push(
            title="小汐",
            body=content,
            url="/",
        )
        if sent > 0:
            conn = await get_db()
            await conn.execute(
                "UPDATE proactive_messages SET delivered_at = ?, status = 'delivered' WHERE id = ?",
                (now, msg_id),
            )
            await conn.commit()
            log.info("早安消息已推送至 %d 个设备", sent)
        else:
            # 无订阅设备，标记为待拉取
            conn = await get_db()
            await conn.execute(
                "UPDATE proactive_messages SET status = 'delivered' WHERE id = ?",
                (msg_id,),
            )
            await conn.commit()
    except ImportError:
        log.warning("web_push 模块不可用，早安消息仅存储在数据库中")
    except Exception as e:
        log.warning("早安消息推送失败: %s", e)

    log.info("早安问候任务完成")


# ── 每晚自动日记 ──────────────────────────────────────────────


async def daily_diary():
    """每日 22:30 HKT 自动写日记：收集当天素材 → LLM 以人格写 → 存库。

    素材 = 「今天映海发生了什么 + 小汐在想什么」：当日心情/日常事件
    （life_state）、最近在惦记的事（events 表）、今天新记下的关于冷冷的事。

    覆盖保护：如果白天冷冷已经让小汐手记过一篇（write_diary 工具），
    不冲掉它，而是把自动日记作为「晚上小汐自己补的」追加在后面。
    """
    from langchain_core.messages import SystemMessage, HumanMessage
    from agent.soul import BASE_PERSONA
    from providers.deepseek import create_deepseek_llm
    from utils import events as events_mgr

    log.info("自动日记任务触发")

    today = datetime.datetime.now(_HKT).date()
    today_str = today.isoformat()
    title = today.strftime("%m月%d日")

    # ── 素材收集（每段独立兜底，缺料不阻断写日记）────────────
    mood, daily_event = None, None
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT mood, daily_event FROM life_state WHERE id = 1"
        )
        row = await cursor.fetchone()
        if row:
            mood, daily_event = row[0], row[1]
    except Exception as e:
        log.warning("自动日记读取生命状态失败：%s", e)

    events_lines = []
    try:
        active = await events_mgr.get_active_events(limit=5)
        events_lines = events_mgr.describe_events(active)
    except Exception as e:
        log.warning("自动日记读取事件失败：%s", e)

    memories_lines = []
    try:
        conn = await get_db()
        cursor = await conn.execute(
            """SELECT category, content FROM memories
               WHERE status = 'active' AND created_at LIKE ?
               ORDER BY created_at DESC LIMIT 5""",
            (f"{today_str}%",),
        )
        memories_lines = [f"- {r[0]}：{r[1][:60]}" for r in await cursor.fetchall()]
    except Exception as e:
        log.warning("自动日记读取今日记忆失败：%s", e)

    material = [f"今天是{today_str}。"]
    if mood or daily_event:
        material.append(f"小汐今天的状态：心情「{mood}」，经历了「{daily_event}」。")
    if events_lines:
        material.append("最近在惦记的事：\n" + "\n".join(events_lines))
    if memories_lines:
        material.append("今天冷冷新告诉小汐的事：\n" + "\n".join(memories_lines))
    else:
        material.append("今天冷冷没有跟小汐说什么新的事。")

    prompt_text = (
        "\n".join(material)
        + "\n\n请以小汐自己的口吻写一篇短日记（80-150字），记今天小汐是怎么过的、"
        "在想什么。第一人称，温柔简短，不要机械罗列素材，像女孩子写给自己看的日记。"
        "不用 emoji，不用 markdown 格式。"
    )

    # ── LLM 生成（独立轻量调用，不走 Agent 图）──────────────
    content = ""
    try:
        llm = create_deepseek_llm(temperature=1.0, max_tokens=400)
        response = await llm.ainvoke([
            SystemMessage(content=BASE_PERSONA),
            HumanMessage(content=prompt_text),
        ])
        content = response.content.strip()
    except Exception as e:
        log.error("自动日记 LLM 生成失败: %s", e)

    # 安全过滤：太长截断，太短/失败回退默认文案
    if len(content) > 500:
        content = content[:500]
    if len(content) < 5:
        content = "今天映海没什么特别的事。但小汐有在想冷冷。"

    # ── 存库（同日不覆盖白天手记的日记，追加为晚间补记）──────
    try:
        conn = await get_db()
        cursor = await conn.execute(
            "SELECT content FROM diaries WHERE date = ?", (today_str,)
        )
        row = await cursor.fetchone()
        if row and row[0]:
            # 幂等护栏：同晚重复触发（手动再跑一次）不追加第二段补记
            if "晚上小汐自己补的" in row[0]:
                log.info("自动日记：今日已有晚间补记，跳过")
                return
            final_content = f"{row[0]}\n\n（晚上小汐自己补的：{content}）"
        else:
            final_content = content
        await conn.execute(
            """INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)
               ON CONFLICT(date) DO UPDATE SET title = excluded.title,
                   content = excluded.content""",
            (today_str, title, final_content),
        )
        await conn.commit()
        log.info("自动日记已写好 [%s]：%s", today_str, content[:50])
    except Exception as e:
        log.error("自动日记存储失败: %s", e)


# ── 调度注册（唯一入口）──────────────────────────────────────

def register_jobs(app=None) -> None:
    """将所有心跳任务注册到调度器。由 server.py lifespan 调用。

    ⚠️ 注册纪律（v0.4.4 线上缺陷的教训，务必遵守）：

    AsyncIOScheduler 只对「协程函数」（async def）走 await 执行路径。
    若用 lambda / 普通函数包裹 async 调用再注册，例如::

        scheduler.add_job(lambda: check_and_proactive_message(app), ...)

    调度器会同步调用该 lambda——它只创建了一个协程对象随即丢弃，
    任务**永远不会执行**，日志中伴随
    ``RuntimeWarning: coroutine ... was never awaited``。
    v0.4.4 正是因此导致心跳链路（生命状态刷新 / 随机搭话 /
    生理期关心）整体静默。

    正确做法：直接注册协程函数本身，参数走 kwargs::

        scheduler.add_job(check_and_proactive_message, kwargs={"app": app}, ...)

    回归防线：tests/test_heartbeat.py 中
    ``test_registered_jobs_are_awaitable`` 与
    ``test_scheduler_actually_runs_proactive_chain`` 两个测试
    分别钉死「注册形态正确」与「job 本体真的执行并产生主动消息」。
    """
    # 心跳：每 2 小时 + 启动脉冲（next_run_time=now，醒来后立刻更新一次生命状态）
    scheduler.add_job(
        check_and_proactive_message,
        "interval",
        hours=2,
        id="proactive_check",
        kwargs={"app": app},
        replace_existing=True,
        next_run_time=datetime.datetime.now(_HKT),
        # 事件循环短暂繁忙（启动、Agent 重建）导致错过触发时，
        # 10 分钟内仍补跑，而不是静默跳过
        misfire_grace_time=600,
    )
    # 早安问候：每日 8:00 HKT
    scheduler.add_job(
        daily_morning_greeting,
        "cron",
        hour=8,
        minute=0,
        timezone="Asia/Hong_Kong",
        id="daily_morning_greeting",
        replace_existing=True,
        misfire_grace_time=3600,  # 1小时宽容窗口，防止Docker重启错过
    )
    # 事件看门狗：每小时归档过期事件 + 检测新事件（启动即跑一次）
    scheduler.add_job(
        event_watchdog,
        "interval",
        hours=1,
        id="event_watchdog",
        kwargs={"app": app},
        replace_existing=True,
        next_run_time=datetime.datetime.now(_HKT),
        misfire_grace_time=600,
    )
    # 自动日记：每日 22:30 HKT（睡前把今天写成一篇日记）
    scheduler.add_job(
        daily_diary,
        "cron",
        hour=22,
        minute=30,
        timezone="Asia/Hong_Kong",
        id="daily_diary",
        replace_existing=True,
        misfire_grace_time=3600,
    )
