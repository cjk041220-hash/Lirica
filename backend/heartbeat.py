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

结构说明（安全拆分）：
    时段池 / 时段匹配 / 周末标签 / 心情涟漪加权抽样等「只读数据 + 纯逻辑」
    已拆至 heartbeat_moods 模块，本文件再导出它们，使
    `from heartbeat import TIME_PERIODS / _choose_mood / weekend_label …`
    以及 heartbeat.X 直读契约保持不变（测试零修改）。
    而 scheduler / register_jobs 以及所有被测试 patch / 改写模块属性的函数
    （get_db / datetime / random / _refresh_agent 经 patch 拦截者）**必须留在
    本文件**——它们的内部名字解析依赖本模块命名空间，外移会令补丁失效、
    甚至让集成测试写穿生产库。故本文件须保持为单一真实文件，且不得
    在此文本中提及对话档案模块名（有结构性隔离自检会扫描本文件文本）。
"""
import datetime
import random
import uuid

from apscheduler.schedulers.asyncio import AsyncIOScheduler

from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import HKT as _HKT, DEFAULT_LAT, DEFAULT_LON
_TIME_FMT = "%Y-%m-%d %H:%M"

scheduler = AsyncIOScheduler()

# ── 再导出：纯数据/纯逻辑（定义见 heartbeat_moods）──────────────
# 保持 from heartbeat import … 与 heartbeat.X 直读契约不变。
from heartbeat_moods import (
    LATE_NIGHT, MORNING, DAYTIME, EVENING, TIME_PERIODS,
    get_current_period, weekend_label,
    _mood_weights, _choose_mood,
)


# ── 心跳任务 ──────────────────────────────────────────────────

async def check_and_proactive_message(app=None):
    """根据当前时间段更新小汐的生命状态（时间加权心情 + 日常事件）。

    Args:
        app: FastAPI 应用实例。传入时会在更新后自动刷新 system prompt，
             确保小汐的心情与实际状态同步。
    """
    # 只取一次当前时间：两次 now() 之间可能跨整点，
    # 导致落库时间戳（06:59）与心情时段池（7 点档）错位
    now_dt = datetime.datetime.now(_HKT)
    now = now_dt.strftime("%Y-%m-%d %H:%M")
    hour = now_dt.hour

    period = get_current_period(hour)
    daily_event = random.choice(period["events"])

    try:
        conn = await get_db()
        # UPSERT：更新 id=1，不存在则插入。
        # 顺手取回当前 mood 作心情涟漪的惯性信号，不多查一次库
        cursor = await conn.execute("SELECT id, mood FROM life_state WHERE id = 1")
        existing = await cursor.fetchone()
        current_mood = existing[1] if existing else None

        # 心情涟漪：时段池内加权抽样（关心/开心信号 + 周末 + 惯性），
        # 任何环节失败都优雅退回均匀随机
        mood = await _choose_mood(period, app=app, now_dt=now_dt,
                                  current_mood=current_mood)

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

        # 主动消息（判断是否触发 → 生成 → 存储 → 推送）。
        # now_dt 沿链下传，不在下游再取一次 now（跨整点错位是本文件
        # 已经踩过的坑，L131-132 注释为证）
        await _try_proactive_message(mood, daily_event, hour, now_dt=now_dt)

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
    """定期维护：裁剪 checkpoint + 备份数据库 + 记忆分层衰减 +
    记忆后台提取 + 记忆健康检查。

    随心跳每 2 小时执行一次，对单用户应用频率足够。
    """
    from database.core import prune_checkpoints, backup_database
    from memory.manager import run_status_decay

    try:
        await prune_checkpoints()
    except Exception as e:
        log.error("checkpoint 裁剪失败：%s", e)

    try:
        await backup_database()
    except Exception as e:
        log.error("数据库备份失败：%s", e)

    # 记忆分层衰减（B2）：高频记忆晋升 core、长期冷落记忆归档冷藏。
    # 无时效性，2h 频率绰绰有余；与前两段隔离，互不牵连
    try:
        await run_status_decay()
    except Exception as e:
        log.error("记忆分层衰减失败：%s", e)

    # 记忆后台提取（2026-08-01 架构评审 D1/D2）：会话后异步提炼新
    # 事实，兜底纯 agentic 写路径（实盘曾 31 条消息零触发）。无新消息
    # 时纯读近零开销；内部已记日志，这里只管与前段隔离
    try:
        from memory.extract import extract_new_memories
        await extract_new_memories()
    except Exception as e:
        log.error("记忆后台提取失败：%s", e)

    # 记忆健康检查（D3）：有对话却零记忆 / 长期无写入时告警（24h 节流，
    # 防 12 条/天的刷屏）。空库也打 INFO 遥测
    try:
        from memory.extract import check_and_report_memory_health
        await check_and_report_memory_health()
    except Exception as e:
        log.error("记忆健康检查失败：%s", e)

    # 记忆周期整合（dreaming-lite，体检 R1）：按类别合并近义重复记忆。
    # 24h 节流 + 单次 ≤3 合并；业界共识动作（ChatGPT Dreaming / Letta
    # sleep-time / LangMem consolidation），与小汐心跳维护窗口天然契合
    try:
        from memory.consolidate import consolidate_memories
        await consolidate_memories()
    except Exception as e:
        log.error("记忆周期整合失败：%s", e)


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
        log.warning("主动消息间隔查询失败，本次跳过")
        return False

    # 随机概率
    return random.random() < _PROACTIVE_PROBABILITY


async def _generate_proactive_message(mood: str, daily_event: str, hour: int,
                                      now_dt: datetime.datetime | None = None) -> str:
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

    # 周末感知：周六/日注入一行素材（「冷冷不用上课」），工作日无噪音
    wk = weekend_label(now_dt or datetime.datetime.now(_HKT))
    weekend_line = f"今天是{wk}，冷冷不用上课。\n" if wk else ""

    user_prompt = (
        f"现在是{period}，你的心情是「{mood}」，今天经历了「{daily_event}」。\n"
        f"{weekend_line}"
        f"用一两句话主动跟冷冷说话。可以是想念、分享心情、关心、撒娇。\n"
        f"要自然、简短、符合你的性格。不要问客服式的套话。"
    )

    # 生理期关心已收敛进事件系统：预测窗口内的 cycle 事件会随下面
    # 的活跃事件列表一起注入（memo 里带完整的关心纪律），且自带
    # 24h 防复读——不再在这里硬编码第二路关心提示。

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
    # model 跟随用户在控制台的选择（运行时配置），temperature/max_tokens
    # 保持本任务的轻量值——主动消息不需要 8192 tokens 的发挥空间
    from api.routes.config import get_current_model
    llm = create_deepseek_llm(model=get_current_model(), temperature=1.0, max_tokens=200)
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


async def _mark_stored(msg_id: str, label: str = "主动消息") -> None:
    """推送失败的兜底：把 pending 降级为 stored。

    不做这一步，消息会永远停在 pending——/api/push/unread 只捞
    delivered/stored，这条消息对冷冷彻底不可见（静默丢消息）。
    """
    try:
        conn = await get_db()
        await conn.execute(
            "UPDATE proactive_messages SET status = 'stored' WHERE id = ? AND status = 'pending'",
            (msg_id,),
        )
        await conn.commit()
        log.info("%s推送失败，已降级为 stored [%s]", label, msg_id[:8])
    except Exception as e:  # 兜底路径不允许再向外抛
        log.warning("%s状态兜底失败 [%s]: %s", label, msg_id[:8], e)


async def sweep_orphan_proactive_messages() -> int:
    """启动清扫：把滞留的 pending 主动消息降级为 stored（进程崩溃兜底）。

    _store_and_push_proactive 先存 pending 再推送；存完与改状态之间
    进程崩溃（kill -9 / OOM / 断电）会让该行永久停在 pending，而
    /api/push/unread 只读 delivered/stored → 消息对冷冷彻底不可见。
    这是 _mark_stored 的姐妹路径：推送失败降级 stored 是一条路，
    进程崩溃降级 stored 是另一条路，对冷冷同义——消息可见，只是
    没响那一声。

    在 lifespan 启动时（scheduler.start 与 ASGI 服务之前）无条件全扫：
    此刻进程内不存在任何在途推送，全扫永远正确。**不加时间窗**——
    清扫只跑这一次，带窗（如 created_at < now-10min）会永久漏掉最
    典型的崩溃时机：崩溃前几分钟内刚存的行。
    """
    conn = await get_db()
    cursor = await conn.execute(
        "UPDATE proactive_messages SET status = 'stored' WHERE status = 'pending'"
    )
    await conn.commit()
    if cursor.rowcount > 0:
        log.info("启动清扫：%d 条孤儿 pending 主动消息降级 stored", cursor.rowcount)
    return cursor.rowcount


async def _store_and_push_proactive(content: str, *, label: str = "主动消息") -> None:
    """主动消息 / 早安共用的「存 pending → 推送 → 失败降级 stored」流水线。

    统一两处曾经的重复实现，并固定更安全的语义：**存库失败则不推送**。
    否则设备会收到一条 DB 里根本没有的通知，未读轮询也拉不到，形成
    「响了却查无此消息」的静默不一致（原早安路径的缺陷）。
    推送失败（含 web_push 不可用）降级为 stored，供页面打开时轮询拉取。
    """
    now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)
    msg_id = uuid.uuid4().hex[:16]

    # 1) 存 pending —— 失败即止步，不推送（见 docstring 的安全语义）
    try:
        conn = await get_db()
        await conn.execute(
            """INSERT INTO proactive_messages (id, content, created_at, status)
               VALUES (?, ?, ?, 'pending')""",
            (msg_id, content, now),
        )
        await conn.commit()
    except Exception as e:
        log.error("%s存储失败: %s", label, e)
        return
    log.info("%s已生成 [%s]: %s", label, msg_id[:8], content[:50])

    # 2) 推送 —— 失败降级 stored（复用上面的连接；单连接架构下与重取等价）
    try:
        from utils.web_push import send_push
        sent = await send_push(title="小汐", body=content, url="/")
        if sent > 0:
            await conn.execute(
                "UPDATE proactive_messages SET delivered_at = ?, status = 'delivered' WHERE id = ?",
                (now, msg_id),
            )
            await conn.commit()
            log.info("%s已推送至 %d 个设备", label, sent)
        else:
            # 无订阅设备，仅存储供轮询拉取（从未推送过，不叫 delivered）
            await conn.execute(
                "UPDATE proactive_messages SET status = 'stored' WHERE id = ?",
                (msg_id,),
            )
            await conn.commit()
    except ImportError:
        log.warning("web_push 模块不可用，%s仅存储在数据库中", label)
        await _mark_stored(msg_id, label=label)
    except Exception as e:
        log.warning("%s推送失败: %s", label, e)
        await _mark_stored(msg_id, label=label)


async def _try_proactive_message(mood: str, daily_event: str, hour: int,
                                 now_dt: datetime.datetime | None = None):
    """尝试生成并推送一条主动消息。"""
    try:
        if not await _should_send_proactive():
            return

        # 生成消息
        content = await _generate_proactive_message(mood, daily_event, hour,
                                                    now_dt=now_dt)

        # 存 pending → 推送 → 失败降级 stored（与早安共用；存库失败则不推送）
        await _store_and_push_proactive(content, label="主动消息")

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

    # 相识周年检测（first_met_on 未设置时恒为 0，零开销）
    created_anniv = 0
    try:
        created_anniv = await events_mgr.detect_anniversary_events()
    except Exception:
        log.exception("事件看门狗：纪念日检测失败")

    # 记忆回声检测（小本本：久远记忆限频 7 天 1 条，检测本身毫秒级）
    created_echo = 0
    try:
        created_echo = await events_mgr.detect_memory_echoes()
    except Exception:
        log.exception("事件看门狗：记忆回声检测失败")

    # 生理期预测检测（cycle_records 派生「身体的日子」；返回值含
    # 漂移归档数——对话修正记录后预测变了，纯归档也算事件变动）
    created_cycle = 0
    try:
        created_cycle = await events_mgr.detect_cycle_events()
    except Exception:
        log.exception("事件看门狗：生理期检测失败")

    total_created = created_profile + created_weather + created_anniv + created_echo + created_cycle
    if archived or total_created:
        log.info(
            "事件看门狗：归档 %d 条，新建 %d 条（档案 %d / 天气 %d / 纪念日 %d / 回声 %d / 周期 %d）",
            archived, total_created, created_profile, created_weather,
            created_anniv, created_echo, created_cycle,
        )
        # 事件上下文变了，刷新 system prompt 让小汐立刻感知
        if app is not None:
            await _refresh_agent(app)


# ── 每日早安问候 ──────────────────────────────────────────────

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
        lat, lon = DEFAULT_LAT, DEFAULT_LON
        location_known = False
        log.info("无缓存坐标，使用默认香港坐标")

    # 2. 获取天气 —— 网络 try 只包网络请求；拼接移出 try 之外。
    #    旧写法里今日预报取值异常（如 precipitation_probability_max
    #    为 [None] 时 int(None) 抛 TypeError）会把已成功构建的当前
    #    天气整段丢弃，日志还谎报「天气 API 调用失败」误导排障。
    weather_text = ""
    data = {}
    try:
        async with _httpx.AsyncClient(timeout=10) as client:
            data = await _fetch_open_meteo(client, lat, lon)
        log.info("天气数据获取成功")
    except Exception as e:
        log.warning("天气 API 调用失败，将省略天气部分: %s", e)

    current = data.get("current") or {}
    if current:
        code = current.get("weather_code", 0)
        weather_text = (
            f"当前天气：{_WMO.get(code, '未知')}，"
            f"气温 {current.get('temperature_2m', '?')}°C"
            f"（体感 {current.get('apparent_temperature', '?')}°C），"
            f"湿度 {current.get('relative_humidity_2m', '?')}%，"
            f"风速 {current.get('wind_speed_10m', '?')} km/h。"
        )

        # 今日预报：字段畸形（[None] / 空列表）只降级本段，
        # 不抹掉已构建好的当前天气
        daily = data.get("daily") or {}
        if daily:
            try:
                tm = daily.get("temperature_2m_max") or ["?"]
                tn = daily.get("temperature_2m_min") or ["?"]
                wc = daily.get("weather_code") or [0]
                rp = daily.get("precipitation_probability_max") or [0]
                weather_text += (
                    f" 今天 {_WMO.get(wc[0], '未知')}，"
                    f"{tn[0]}°C ~ {tm[0]}°C"
                )
                if rp and rp[0] is not None and int(rp[0]) > 50:
                    weather_text += f"，降水概率 {rp[0]}%"
            except (TypeError, ValueError, IndexError) as e:
                log.warning("今日预报字段异常，省略预报段: %s", e)

    # 3. 构建 prompt（时间只取一次，避免跨整点漂移）
    now_dt = datetime.datetime.now(_HKT)
    today = now_dt.strftime("%Y年%m月%d日")
    wk = weekend_label(now_dt)
    weekend_part = (
        f"今天是{wk}，冷冷不用上课，可能还在睡，语气可以再懒一点。\n" if wk else ""
    )

    if weather_text:
        if location_known:
            weather_part = f"冷冷那边现在的天气：{weather_text}"
        else:
            weather_part = f"小汐不知道冷冷现在在哪里呢，不过香港今天的天气是：{weather_text}"
    else:
        weather_part = "小汐今天没查到天气信息"

    # 今日日程（日历白名单过滤）：早安顺口提今天的事，标了很重要的
    # 认真说一次。明天的事归 23:00 前夜提醒管，早安不越界抢话。
    # 失败只省略本段，不拖累早安（同天气段的降级纪律）
    schedule_part = ""
    try:
        from utils import events as events_mgr
        today_iso = now_dt.date().isoformat()
        todays = await events_mgr.list_events_for_calendar(today_iso, today_iso)
        if todays:
            ev_lines = [
                f"- 「{ev['title']}」" + ("（标了很重要）" if ev.get("severity") == "high" else "")
                for ev in todays
            ]
            schedule_part = (
                "\n今天冷冷有这些安排：\n" + "\n".join(ev_lines)
                + "\n在问候里自然提一下就好，重要的事认真说一次，别排队念清单。\n"
            )
    except Exception as e:
        log.warning("早安问候：获取今日日程失败，省略日程段: %s", e)

    prompt_text = (
        f"今天是{today}，早上 8 点。\n"
        f"{weekend_part}"
        f"{weather_part}\n"
        f"{schedule_part}\n"
        f"请给冷冷发一条早安问候消息。要求：\n"
        f"1. 包含早安问候\n"
        f"2. 自然地提到天气信息（如果有的话）\n"
        f"3. 推荐一道适合今天的美食\n"
        f"4. 保持小汐的语气风格，温暖可爱\n"
        f"5. 简短自然，不要超过 150 字\n"
        f"6. 最后附一句「今日一歌」：推荐一首冷冷可能喜欢的歌（她喜欢日系、"
        f"二次元音乐，追过 LoveLive 缪斯、玩过 BanG Dream），只写歌名，一句话带过"
    )

    # 4. LLM 生成（model 跟随用户运行时配置，token 保持轻量）
    content = ""
    try:
        from api.routes.config import get_current_model
        llm = create_deepseek_llm(model=get_current_model(), temperature=1.0, max_tokens=200)
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

    # 存 pending → 推送 → 失败降级 stored（与主动消息共用；存库失败则不推送）
    await _store_and_push_proactive(content, label="早安消息")

    log.info("早安问候任务完成")


# ── 前夜提醒 ──────────────────────────────────────────────────


async def daily_evening_reminder():
    """每日 23:00 HKT 前夜提醒：明天有日程就关心一句，没有就安静。

    与主动消息的区别：主动消息是「随兴搭话」（有 4h 间隔 + 概率闸门，
    heartbeat.py:167-193），提醒是「答应过冷冷的惦记」，必须可靠——
    因此独立成 cron、不走闸门，像早安/日记一样直走统一流水线。

    只看日历可见的日程类（list_events_for_calendar 白名单）：台风的
    关心走主动消息事件上下文路径，不双重打扰；记忆回声是当天内心戏，
    不属于「明天的安排」。

    失败处理：外层整体 try 吞异常——提醒任务宁可静默，不可向调度器
    抛错，更不可在没日程时骚扰。
    """
    from utils import events as events_mgr

    try:
        tomorrow = (datetime.datetime.now(_HKT).date() + datetime.timedelta(days=1)).isoformat()
        events = await events_mgr.list_events_for_calendar(tomorrow, tomorrow)
        if not events:
            log.info("前夜提醒：明日无日程，静默")
            return

        from langchain_core.messages import SystemMessage, HumanMessage
        from agent.soul import BASE_PERSONA
        from providers.deepseek import create_deepseek_llm
        from api.routes.config import get_current_model

        lines = []
        for ev in events:
            tag = "（冷冷标了很重要）" if ev.get("severity") == "high" else ""
            memo = f"——补充：{ev['memo']}" if ev.get("memo") else ""
            lines.append(f"- 「{ev['title']}」{tag}{memo}")
        schedule_text = "\n".join(lines)

        # 防复读：12 小时内已说过的关心不许再说第二遍
        # （傍晚的主动消息可能已经提过明天，别车轱辘话）
        anti_repeat = ""
        recent = await events_mgr.get_recent_proactive_texts(hours=12, limit=3)
        if recent:
            said = "\n".join(f"- 「{t[:60]}」" for t in recent)
            anti_repeat = f"\n\n你最近 12 小时已经说过这些话，不要重复同样的关心：\n{said}"

        prompt = (
            f"明天冷冷有这些事：\n{schedule_text}"
            f"{anti_repeat}\n\n"
            f"用一两句话，以前一天晚上的口吻轻轻提醒冷冷，像「对啦，明天有……，"
            f"早点准备/早点休息哦」。自然、简短、不唠叨。"
            f"标了很重要的事，认真说一次，别轻飘飘带过。"
        )

        content = ""
        try:
            llm = create_deepseek_llm(model=get_current_model(), temperature=1.0, max_tokens=150)
            response = await llm.ainvoke([
                SystemMessage(content=BASE_PERSONA),
                HumanMessage(content=prompt),
            ])
            content = response.content.strip()
        except Exception as e:
            log.warning("前夜提醒：LLM 生成失败，走固定文案（提醒不丢）: %s", e)

        if len(content) > 150:
            content = content[:150]
        if len(content) < 5:
            content = f"对啦冷冷，明天有「{events[0]['title']}」，别忘了哦~"

        await _store_and_push_proactive(content, label="前夜提醒")
    except Exception as e:
        log.error("前夜提醒失败: %s", e)


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
        # 读面谓词与全项目约定对齐 IN ('active','core')（体检 D6：
        # 此处是唯一违约点。当天记忆其实不可能是 core——晋升要求 ≥30
        # 天龄——当前无害；但谓词漂移遇上阈值调低/手动晋升就静默漏数，
        # 一致性债当即清）
        cursor = await conn.execute(
            """SELECT category, content FROM memories
               WHERE status IN ('active', 'core') AND created_at LIKE ?
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

    # 「不用 emoji」是有意比 BASE_PERSONA（已有限度开放）更严格：
    # 日记是写给自己的，干净文本更像日记；任务级指令比 System 的
    # BASE 更具体，LLM 会以具体者为准
    prompt_text = (
        "\n".join(material)
        + "\n\n请以小汐自己的口吻写一篇短日记（80-150字），记今天小汐是怎么过的、"
        "在想什么。第一人称，温柔简短，不要机械罗列素材，像女孩子写给自己看的日记。"
        "不用 emoji，不用 markdown 格式。"
    )

    # ── LLM 生成（独立轻量调用，不走 Agent 图）──────────────
    # model 跟随用户运行时配置，max_tokens 保持日记任务的轻量值
    content = ""
    try:
        from api.routes.config import get_current_model
        llm = create_deepseek_llm(model=get_current_model(), temperature=1.0, max_tokens=400)
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
        # 晚间补记只追加 content；白天 write_diary 手记的标题不被
        # 自动标题（「MM月DD日」）覆盖，空标题才补上（NULLIF 兜底）
        await conn.execute(
            """INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)
               ON CONFLICT(date) DO UPDATE SET
                   content = excluded.content,
                   title = COALESCE(NULLIF(diaries.title, ''), excluded.title)""",
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
    # 前夜提醒：每日 23:00 HKT（明日有日程才出声；独立于主动消息闸门。
    # 冷冷是夜猫子，21 点提醒太早——23 点才是她的「该收尾准备明天了」）
    scheduler.add_job(
        daily_evening_reminder,
        "cron",
        hour=23,
        minute=0,
        timezone="Asia/Hong_Kong",
        id="daily_evening_reminder",
        replace_existing=True,
        misfire_grace_time=3600,
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
