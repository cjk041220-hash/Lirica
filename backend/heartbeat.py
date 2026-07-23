"""心跳调度器 —— 时间感知的心情与日常事件更新 + 主动消息。

一天被划分为四个时段，每个时段拥有独立的心情池和事件池。
心情和事件的选取限制在当前时段对应的池中，确保小汐的
状态符合时间情境（例如深夜不会出现"活泼"）。

主动消息系统：
    每次心跳时判断是否适合主动给冷冷发消息。
    触发条件：非深夜 + 距上次主动消息 >= 4h + 50% 随机概率。
    消息由 LLM 以小汐人格生成，存入 DB 后通过 Web Push 推送。

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
