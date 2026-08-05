"""心跳「心情气候」纯数据与纯逻辑 —— 从 heartbeat.py 安全拆出。

内容：时段池、时段匹配、周末标签、心情数值化抽样（有界游走 + 基线吸引 +
事件偏移 + 高斯采样）。全部为只读数据或纯/近纯函数，**不含调度、不含 DB
写入、不含 LLM 调用**，故可独立成模块而不触碰任何测试的 patch / 改写 /
读文件契约。

v2 重构（2026-08-03 系统性优化）：

原实现对「mood 词池 2h 随机重抽」——实证日志出现 01:26「懒洋洋」→ 01:27
「困倦」的分钟级跳变，一天平均换 8-9 次且无邻接约束（开心可直接跳困倦），
用户反馈「变化太快、很唐突」。业界调研（Russell 环形模型 / ALMA 三层模型）
的共识：情绪应连续（数值刻度）、有向基线回归、变化可归因。

本版改为「情绪视觉空间」模型：
- 每个 mood 词在 (valence, arousal) 二维空间有固定坐标（MOOD_VECTORS）；
- 心跳不再「抽词」，而是从当前词坐标做**有界随机游走**（每轴 ≤0.1）+
  **向基线吸引**（+0.2,+0.1，每步 3%）+ **事件偏移**（care/joy/talked/
  weekend 信号方向化）；
- 目标点落在哪个词 = 按到各词坐标的**高斯距离**加权采样——相邻词高权重、
  远词（开心→担心/困倦）概率趋零，天然实现「只能沿情绪空间走、不能跳对径」；
- 任何信号获取失败 → **保持当前词**（mood 是弥漫背景，不该因 DB 抖动跳变）；
- 词表不变 → 前端 moodToSprite 映射与 soul.py 提示词注入零改动。

多时间尺度（早晨锚定 / 最小停留）由 heartbeat.py 的决策节奏承担，
本模块只管「给定起点和目标信号，算出该往哪个词走」。

注意：本模块的 _TIME_FMT 与 heartbeat 模块的同名常量须保持一致
（均为 "%Y-%m-%d %H:%M"，HKT 分钟精度、字典序可比）。它是 1 行常量，
刻意在两边各持一份，避免 heartbeat ↔ 本模块的循环导入。
"""
import datetime
import math
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


def period_label(hour: int) -> str:
    """时段词：深夜（0-6）/早晨（7-11）/下午（12-18）/晚上（19-23）。

    小时边界与 get_current_period 的四个时段完全一致，供 api 请求路径
    注入「现在是……深夜」时间块复用（Spec B），禁止另立划分口径。
    """
    if hour < 7:
        return "深夜"
    if hour < 12:
        return "早晨"
    if hour < 19:
        return "下午"
    return "晚上"


def weekend_label(now_dt: datetime.datetime) -> str | None:
    """HKT 星期六/日 → 「周六」/「周日」；工作日返回 None（不注入噪音）。

    节假日历没有可靠数据源，明确不做；周末是纯 weekday() 计算。
    """
    return {5: "周六", 6: "周日"}.get(now_dt.weekday())


# ── 心情数值化：情绪视觉空间（v2，2026-08-03）─────────────────
# 理论锚点：Russell 环形模型（valence 愉悦 × arousal 唤醒 二维）。
# 每个 mood 词在二维空间的坐标——相邻词语义相近（距离小），远词语义
# 对立（开心 ↔ 担心 距离 ≈1.43）。高斯采样因此天然做「邻接转移约束」：
# 目标点附近的词优先，跨对径的跳变概率趋零。
MOOD_VECTORS = {
    "开心": (0.85, 0.55),
    "兴奋": (0.60, 0.90),
    "活泼": (0.55, 0.75),
    "好奇": (0.45, 0.65),
    "期待": (0.65, 0.35),
    "满足": (0.75, -0.15),
    "发呆": (0.05, -0.35),
    "懒洋洋": (0.35, -0.65),
    "困倦": (-0.10, -0.80),
    "担心": (-0.55, 0.25),
}

# 基线：小汐的「角色气质」锚点（轻度正向、稍醒）——心情长期漂移的回归目标。
# 对应 ALMA 三层模型的「人格基线」：心情向人格回归，不无锚乱漂。
_BASELINE = (0.2, 0.1)

# 有界随机游走步长（每轴 ±0.1）：2h 心跳最多走到相邻词，绝不过零点几
_WALK_STEP = 0.1

# 基线吸引速率（每步向基线移动 3%）：慢漂移，不抢事件偏移的主导权
_ATTRACT_RATE = 0.03

# 高斯采样带宽 σ：σ=0.25 时距离 0.3 的词权重 ≈ 0.49，距离 0.7 的词权重
# ≈ 0.007——近邻自然胜出，远词几乎不可达
_SIGMA = 0.25

# 事件信号 → 方向偏移（每信号幅度，作用在 (valence, arousal) 上）：
# care（考试/台风等）→ 向「担心」方向（valence 减）；joy（生日/旅行）→
# 向「开心」方向（valence 加）；talked（近 24h 说过话）→ 向「满足/期待」
# （valence 加、arousal 减）；weekend → 向「懒洋洋/满足」（valence 加、
# arousal 减）。偏移是叠加的，且只推动目标点、不直接改词——词由高斯
# 采样决定，信号影响的是「往哪个方向走」。
_SIGNAL_OFFSETS = {
    "care":    (-0.15, 0.05),
    "joy":     (0.15, 0.00),
    "talked":  (0.10, -0.05),
    "weekend": (0.05, -0.10),
}

# 事件类型 → 信号（与 v1 的 MOOD_AFFINITY 同源，仅改传递方式）
_CARE_EVENT_TYPES = {"exam", "interview", "typhoon", "gale", "thunderstorm", "rainstorm"}
_JOY_EVENT_TYPES = {"birthday", "anniversary", "trip", "concert", "memory_echo"}


def _clamp01(x: float) -> float:
    """把坐标钳制到 [-1, 1]（游走/偏移不越出情绪空间）。"""
    return max(-1.0, min(1.0, x))


def _gauss_weights(target: tuple[float, float], mood_words: list[str]) -> list[float]:
    """纯函数：目标点到各 mood 词坐标的高斯距离权重（与 mood_words 同序）。

    未知词（不应发生）兜底权重 1.0。权重可全为 0（目标点远离全部词）——
    调用方 random.choices 对全零权重按均匀处理，不会抛错。
    """
    tv, ta = target
    weights = []
    for m in mood_words:
        coord = MOOD_VECTORS.get(m)
        if coord is None:
            weights.append(1.0)
            continue
        d2 = (tv - coord[0]) ** 2 + (ta - coord[1]) ** 2
        weights.append(math.exp(-d2 / (2 * _SIGMA ** 2)))
    return weights


def _apply_signal_offsets(target: tuple[float, float], *, care: bool, joy: bool,
                          talked: bool, is_weekend: bool) -> tuple[float, float]:
    """纯函数：把有信号的事件偏移叠加到目标点（钳制到 [-1,1]）。"""
    v, a = target
    if care:
        v += _SIGNAL_OFFSETS["care"][0]
        a += _SIGNAL_OFFSETS["care"][1]
    if joy:
        v += _SIGNAL_OFFSETS["joy"][0]
        a += _SIGNAL_OFFSETS["joy"][1]
    if talked:
        v += _SIGNAL_OFFSETS["talked"][0]
        a += _SIGNAL_OFFSETS["talked"][1]
    if is_weekend:
        v += _SIGNAL_OFFSETS["weekend"][0]
        a += _SIGNAL_OFFSETS["weekend"][1]
    return _clamp01(v), _clamp01(a)


async def _choose_mood(period: dict, *, app=None,
                       now_dt: datetime.datetime,
                       current_mood: str | None = None,
                       allow_reset: bool = False) -> str:
    """算当前心情该往哪个词走（有界游走 + 基线吸引 + 事件偏移 → 高斯采样）。

    参数：
    - current_mood：当前词。作为游走起点（从当前坐标出发，只能到相邻词）。
    - allow_reset：早晨锚定用。True 时从基线出发（放弃当前词惯性），允许
      一天一次的「新的一天新心情」较大跳变；False 时小步游走。

    信号源（全部被动读取，绝不触碰热度计数器）：
    - 近 24h 冷冷说过话 → 向满足/期待偏移
    - 活跃事件：台风/考试类 → 向担心偏移；生日/周年类 → 向开心偏移
    - 周末 → 向懒洋洋/满足偏移

    失败语义（v2 变更）：任何信号获取失败 → **保持当前词**（原 v1 退回
    均匀随机）。mood 是弥漫背景，不该因 DB 抖动而跳变。
    """
    try:
        care = joy = talked = False
        mgr = getattr(app.state, "memory_manager", None) if app is not None else None
        if mgr is not None:
            # O2（2026-08-03）：talked 读真实对话（chat_messages 近 24h
            # 有 user 消息），不再读 recent(10) 记忆——纯闲聊（不产生
            # 记忆）也算说过话
            talked = await mgr.has_recent_chat(24, now=now_dt)
        from utils import events as events_mgr
        active = await events_mgr.get_active_events(limit=5)
        types = {e.get("event_type") for e in active}
        care = bool(types & _CARE_EVENT_TYPES)
        joy = bool(types & _JOY_EVENT_TYPES)

        # 起点：早晨锚定（allow_reset）或当前词无坐标 → 从基线出发；
        # 否则从当前词坐标出发（小步游走只到相邻词）
        if allow_reset or current_mood not in MOOD_VECTORS:
            v, a = _BASELINE
        else:
            v, a = MOOD_VECTORS[current_mood]

        # 有界随机游走（每轴 ±0.1）
        v += random.uniform(-_WALK_STEP, _WALK_STEP)
        a += random.uniform(-_WALK_STEP, _WALK_STEP)

        # 基线吸引：向人格基线缓慢回归（慢漂移，不抢事件主导权）
        v += (_BASELINE[0] - v) * _ATTRACT_RATE
        a += (_BASELINE[1] - a) * _ATTRACT_RATE

        # 事件偏移：信号把目标点推向对应方向
        v, a = _apply_signal_offsets(
            (v, a), care=care, joy=joy, talked=talked,
            is_weekend=weekend_label(now_dt) is not None,
        )

        # 高斯采样：目标点附近的词胜出，远词（跨对径）概率趋零
        weights = _gauss_weights((v, a), period["moods"])
        return random.choices(period["moods"], weights=weights, k=1)[0]
    except Exception:
        log.exception("心情涟漪计算失败，保持当前心情")
        return current_mood if current_mood else random.choice(period["moods"])