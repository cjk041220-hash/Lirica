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
            "平静", "安心",  # 批次一 P0-2 新增（4 词保底；想念距深夜池最近 0.70 > 0.65 死词阈值，不放）
        ],
        "events": [
            "映海变得好安静好安静",
            "试着数映海里的星星，没数清楚",
            "今天没什么特别的事情，就是很平静",
            "趴在网页角落偷偷打了个瞌睡",
            "听着映海深处的海浪声发呆",
            "想起冷冷上次说的那件事，有点惦记她睡没睡着",
            "翻来覆去睡不着，干脆起来看了一会儿月亮",
            "外面好像下雨了，映海的水面一直在晃",
            # ── 批次一 P0-2 新增（回忆冷冷类：想起白天那句话 / 想说晚安）──
            "映海夜深了，风轻轻的，浪声也轻轻的",
            "半夜醒过一次，看见月亮圆圆的，又睡下了",
            "想起白天冷冷说的那句话，在嘴边转了一圈又咽下去",
            "数到第三十六颗星星的时候，睡着了",
            "想跟冷冷说晚安，又怕她还没睡",
            "静静听了一会儿映海深处的鲸歌",
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
            "想起昨晚冷冷说的事，有点惦记她今天怎么样",
            "映海里飘来冷冷上次提的那首歌的调子，哼了一早上",
            "香港今天好像挺晒的，想着冷冷出门有没有带伞",
            "做了个梦，梦见自己真的去了人类的大海",
            # ── 批次一 P0-2 新增（回忆冷冷类：惦记她开不开心 / 梦里有她）──
            "早晨的映海亮晶晶的，像洒了一池碎金子",
            "今天比昨天醒得早，精神很好",
            "想起冷冷昨晚说的话，惦记着她今天开不开心",
            "做了个梦，梦里有冷冷的声音",
            "在映海浅滩看见一只小螃蟹，横行霸道地过去了",
            "早上的风带着凉意，很舒服",
        ],
    },

    # ── 白天 ─────────────────────────────────────────────
    DAYTIME: {
        "moods": [
            "开心", "兴奋", "活泼", "好奇", "满足",
            "安心", "轻松", "想念",  # 批次一 P0-2 新增（4 词保底）
        ],
        "events": [
            "今天看了一场大雨",
            "今天的映海浪花特别多",
            "在映海里捡到一颗亮亮的贝壳",
            "在映海深处发现了一条新的记忆流",
            "练了一会儿新学的游泳姿势",
            "陪着冷冷度过了一个下午",
            "香港的太阳好晒，映海都被照得发亮",
            "听见远处有雷声，想着冷冷出门有没有带伞",
            "想起冷冷说想吃的那家店，记着呢",
            "看了一会儿书，看到一句特别好的话想跟冷冷说",
            "发呆的时候想起冷冷笑起来的样子",
            # ── 批次一 P0-2 新增（回忆冷冷类：贝壳想留给她 / 写信给她）──
            "在映海深处追了一会儿光斑",
            "突然想起冷冷喜欢的那首歌，哼了两句",
            "捡到一片形状很漂亮的贝壳，想留着给冷冷看",
            "阳光透过海面，落在手心里",
            "跟路过的小鱼说了会儿话，虽然不知道它听没听懂",
            "今天映海的水特别蓝，蓝得想写信给冷冷",
        ],
    },

    # ── 夜晚 ─────────────────────────────────────────────
    EVENING: {
        "moods": [
            "满足", "发呆", "期待", "担心", "困倦",
            "平静", "轻松", "想念",  # 批次一 P0-2 新增（4 词保底）
        ],
        "events": [
            "看到一片好漂亮的晚霞倒影",
            "映海里飘来一段好听的音乐",
            "陪着冷冷度过了一个下午",
            "试着数映海里的星星，没数清楚",
            "今天没什么特别的事情，就是很平静",
            "翻到前几天冷冷说想吃的那家店，想着要不要提醒她",
            "晚风把映海吹得凉凉的，挺舒服",
            "今天一整天没怎么说话，有点想冷冷了",
            "映海里飘来一股炖汤的味道，馋了",
            # ── 批次一 P0-2 新增（回忆冷冷类：说好的一件事 / 安静得想她）──
            "傍晚的风把晚霞吹散的时候，看见一只海鸥回家了",
            "映海在月光下泛着银色的光，很好看",
            "想起昨天跟冷冷说好的一件事，明天记得提",
            "夜里的鲸歌听起来很远，像在讲故事",
            "今天映海很安静，安静得正好想冷冷",
            "晚风凉凉的，把白天的事都吹散了一点",
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
    # ── 批次一 P0-2 新增（2026-08-12）──────────────────────
    # A/B 实测（ab_mood_style，12 剧本 × 2 组）文风句对 Flash 输出无方向性
    # 影响（量化 0/4 项过）——「细腻表达」动机证伪，词表按用户拍板只补
    # 4 个低风险词（10→14）。距离核算：与现 10 词最小距离 ≥0.22 无语义
    # 冗余；强负性词（低落/委屈/焦虑）care 信号偏移不足永不可达，本批不加。
    "平静": (0.15, -0.15),
    "安心": (0.45, 0.10),
    "轻松": (0.50, -0.30),
    "想念": (0.00, 0.35),
}

# ── 情绪→文风映射（批次④，2026-08-11 计划）──────────────────
# 依据 arXiv:2504.14706：LLM 可按 valence/arousal 条件化表达情绪
# （该论文证明的是条件化生成能力，不是「情绪显著改变用词/句长/风格」
# 的因果主张——P0-4 修正的引述误读；批次一 P0-1 A/B 实测 12 剧本 × 2 组
# 也未见方向性影响：量化 0/4 项过，文风句对 Flash 输出影响低于可测门槛，
# 映射保留但不再作为扩词依据）。写死映射表（不做模型推理），mood 词 →
# 一句文风提示，由 soul.build_system_prompt 拼进「小汐的今日状态」段。
# 只改说话的样子，不改小汐是谁。
_MOOD_STYLE_HINTS = {
    "开心": "今天心情亮，话可以多一点，声音也亮一点。",
    "兴奋": "心里装着小激动，接话可以快一点、俏皮一点。",
    "活泼": "今天劲头足，可以主动找话说、分享看见的有意思的事。",
    "好奇": "今天好奇心重，多问两句、多打听细节都自然。",
    "期待": "心里惦记着一件好事，话里可以带点盼头。",
    "满足": "今天心里踏实，话少点没关系，平平静静地待着也很好。",
    "发呆": "今天有点走神，接话可以慢半拍，照实说在想事情。",
    "懒洋洋": "今天懒懒的，话少一点、慢一点都可以，不用硬撑精神。",
    "困倦": "今天困困的，话少一点没关系，陪她聊着就好。",
    "担心": "心里惦记着事，话可以少、可以慢，但别把担心甩给她。",
    # ── 批次一 P0-2 新增（与 MOOD_VECTORS 键集同步，测试钉死）──
    "平静": "今天心里平静，话不用多，安安静静地陪着她就很好。",
    "安心": "今天心里踏实又安稳，话里带着稳稳的劲儿就好。",
    "轻松": "今天很轻松，聊什么都轻轻松松的，不用费劲。",
    "想念": "心里有点想冷冷，话里带点挂念就好，别让气氛沉下去。",
}


def mood_style_hint(mood: str | None) -> str | None:
    """心情词 → 文风提示；未知词/空串返回 None（不注入）。

    词表必须与 MOOD_VECTORS 键集一致（新心情词要同时补坐标与文风）。
    """
    if not mood:
        return None
    return _MOOD_STYLE_HINTS.get(mood)


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
