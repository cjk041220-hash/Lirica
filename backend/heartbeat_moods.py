"""心跳「心情气候」纯数据与纯逻辑 —— 从 heartbeat.py 安全拆出。

内容：时段池、时段匹配、周末标签、心情涟漪加权抽样。全部为只读数据
或纯/近纯函数，**不含调度、不含 DB 写入、不含 LLM 调用**，故可独立成
模块而不触碰任何测试的 patch / 改写 / 读文件契约。

注意：本模块的 _TIME_FMT 与 heartbeat 模块的同名常量须保持一致
（均为 "%Y-%m-%d %H:%M"，HKT 分钟精度、字典序可比）。它是 1 行常量，
刻意在两边各持一份，避免 heartbeat ↔ 本模块的循环导入。
"""
import datetime
import random

from utils.logger import get_logger

log = get_logger(__name__)

_TIME_FMT = "%Y-%m-%d %H:%M"


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


def weekend_label(now_dt: datetime.datetime) -> str | None:
    """HKT 星期六/日 → 「周六」/「周日」；工作日返回 None（不注入噪音）。

    节假日历没有可靠数据源，明确不做；周末是纯 weekday() 计算。
    """
    return {5: "周六", 6: "周日"}.get(now_dt.weekday())


# ── 心情涟漪：时段池内加权抽样 ────────────────────────────────
# 记忆不是只存不用的——近期发生的事可以影响小汐的「心情气候」。
# 信号 → 池内 mood 乘数，未列出的 mood 恒为 1.0。只在池内倾斜、
# 绝不跨池（深夜池没有「活泼」，权重再怎么调也选不出来——时段池
# 是第一道防线的铁律不变）。不新增 mood 词 → 前端 moodToSprite
# 映射与立绘素材零影响。
_CARE_EVENT_TYPES = {"exam", "interview", "typhoon", "gale", "thunderstorm", "rainstorm"}
_JOY_EVENT_TYPES = {"birthday", "anniversary", "trip", "concert", "memory_echo"}

MOOD_AFFINITY = {
    "care":    {"担心": 2.5, "满足": 0.8, "兴奋": 0.5, "活泼": 0.7},
    "joy":     {"期待": 2.0, "开心": 1.8, "满足": 1.5, "担心": 0.5},
    "talked":  {"满足": 1.5, "期待": 1.3},   # 近 24h 冷冷来说过话
    "weekend": {"期待": 1.4, "满足": 1.3, "懒洋洋": 1.6},
    "inertia": 1.3,                          # 当前心情惯性：防每 2h 跳一个心情
}


def _mood_weights(period_moods: list[str], *, care: bool, joy: bool,
                  talked_recently: bool, is_weekend: bool,
                  current_mood: str | None) -> list[float]:
    """纯函数：按时段池顺序返回权重向量。

    所有权重 >= 0.2：倾斜不是锁死——担心×2.5 也不是必中，
    给随机性留余地，心情才不会变成信号的提线木偶。
    """
    weights = []
    for m in period_moods:
        w = 1.0
        if care:
            w *= MOOD_AFFINITY["care"].get(m, 1.0)
        if joy:
            w *= MOOD_AFFINITY["joy"].get(m, 1.0)
        if talked_recently:
            w *= MOOD_AFFINITY["talked"].get(m, 1.0)
        if is_weekend:
            w *= MOOD_AFFINITY["weekend"].get(m, 1.0)
        if current_mood and m == current_mood:
            w *= MOOD_AFFINITY["inertia"]
        weights.append(max(w, 0.2))
    return weights


async def _choose_mood(period: dict, *, app=None,
                       now_dt: datetime.datetime,
                       current_mood: str | None = None) -> str:
    """加权选 mood。任何信号获取失败 → 优雅退回均匀 random.choice。

    信号源（全部被动读取，绝不触碰热度计数器）：
    - 近 24h 冷冷说过话 → 满足/期待 上浮
    - 活跃事件：台风/考试类 → 担心 上浮；生日/周年类 → 期待/开心 上浮
    - 周末 → 期待/满足/懒洋洋 上浮
    - 惯性：当前 mood ×1.3
    """
    try:
        care = joy = talked = False
        mgr = getattr(app.state, "memory_manager", None) if app is not None else None
        if mgr is not None:
            recent = await mgr.recent(10)  # recent() 天生被动，不污染计数器
            cutoff = (now_dt - datetime.timedelta(hours=24)).strftime(_TIME_FMT)
            talked = any((m.get("created_at") or "") >= cutoff for m in recent)
        from utils import events as events_mgr
        active = await events_mgr.get_active_events(limit=5)
        types = {e.get("event_type") for e in active}
        care = bool(types & _CARE_EVENT_TYPES)
        joy = bool(types & _JOY_EVENT_TYPES)
        weights = _mood_weights(
            period["moods"], care=care, joy=joy, talked_recently=talked,
            is_weekend=weekend_label(now_dt) is not None,
            current_mood=current_mood,
        )
        return random.choices(period["moods"], weights=weights, k=1)[0]
    except Exception:
        log.exception("心情涟漪计算失败，退回均匀随机")
        return random.choice(period["moods"])
