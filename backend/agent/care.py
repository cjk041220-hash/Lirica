"""异常感知关心（Spec B）—— 检测器与冷却台账。

所有异常都发生在冷冷来聊天那一刻：深夜聊天、考前熬夜、消失后回来，
全是她发消息时才能观测到。本模块服务请求路径（api/routes/chat.py 的
_resolve_user_content 单一入口）：纯函数 detect_care_hints 产出提示，
冷却台账保证同一由头不唠叨（项目前科：LLM 自觉纪律实盘不可靠——
「别每次都说」必须由确定性状态保证，不能押在模型自觉上）。

提示只是感知与许可，不是指令：说不说、怎么说完全留给 LLM 按人格
发挥（与事件上下文「数据不是指令」的既有纪律同调）。
"""
import datetime

from database.core import get_db
from utils.logger import get_logger
from utils.timezone import HKT

log = get_logger(__name__)

# 提示类型（也是 care_ledger 的主键）
LATE_NIGHT = "late_night"
EVENT_COMBO = "event_combo"
RETURNED = "returned"

# 各类型独立冷却（小时）。returned 不记台账：≥5 天间隔条件天然单次触发
COOLDOWN_HOURS = {LATE_NIGHT: 72, EVENT_COMBO: 24}

_LATE_NIGHT_START = 0    # HKT 0:00
_LATE_NIGHT_END = 5      # – 4:59（5 点起天快亮了，劝睡反而别扭）
_RETURNED_GAP_DAYS = 5
_NIGHT_COUNT_WINDOW_DAYS = 7
# Spec B 提及 exam/interview；interview 不在系统事件类型白名单
# （utils.events._EVENT_TYPES），第一期 combo 只认 exam
_COMBO_EVENT_TYPES = ("exam",)

_TIME_FMT = "%Y-%m-%d %H:%M"


def _is_late_night(now_dt: datetime.datetime) -> bool:
    return _LATE_NIGHT_START <= now_dt.hour < _LATE_NIGHT_END


def _late_night_days(recent_user_times, now_dt) -> int:
    """近 7 天深夜（0-5 点）用户消息按天去重计数。

    窗口为 [now-7d, now]：晚于 now 的时间戳（测试里归档时钟与注入
    时钟不同源时可能出现）一律排除。
    """
    cutoff = now_dt - datetime.timedelta(days=_NIGHT_COUNT_WINDOW_DAYS)
    days = {
        t.date() for t in recent_user_times
        if cutoff <= t <= now_dt and _is_late_night(t)
    }
    return len(days)


def detect_care_hints(now_dt, recent_user_times, active_events, *,
                      last_user_time=None) -> list[dict]:
    """纯函数：由 (当前时间, 最近用户消息时间, 活跃事件) 产出关心提示。

    返回 [{"type": ..., "text": ...}]。优先级纪律：同一请求
    event_combo 命中则不发 late_night（更具体的理由吞掉泛化的，
    避免一句话两个唠叨由头）。
    """
    hints: list[dict] = []

    # ── returned：距上一条用户消息 ≥5 天 ────────────────────
    last = last_user_time if last_user_time is not None else (
        max(recent_user_times) if recent_user_times else None
    )
    if last is not None and last <= now_dt:
        gap_days = (now_dt - last).total_seconds() / 86400
        if gap_days >= _RETURNED_GAP_DAYS:
            hints.append({
                "type": RETURNED,
                "text": f"她已经 {int(gap_days)} 天没来了，这是回来了",
            })

    if not _is_late_night(now_dt):
        return hints

    # ── event_combo：24h 内（今天/明天）有考试类事件 ─────────
    today = now_dt.date()
    tomorrow = today + datetime.timedelta(days=1)
    combo_event = None
    for ev in active_events:
        if ev.get("event_type") not in _COMBO_EVENT_TYPES:
            continue
        try:
            start = datetime.date.fromisoformat(ev.get("event_date") or "")
            end = (datetime.date.fromisoformat(ev["end_date"])
                   if ev.get("end_date") else start)
        except ValueError:
            continue
        if start <= tomorrow and end >= today:
            combo_event = (ev, "今天" if start <= today else "明天")
            break

    # ── 吞并规则：combo 命中则不发泛化的 late_night ──────────
    if combo_event:
        ev, day_word = combo_event
        hints.append({
            "type": EVENT_COMBO,
            "text": f"{day_word}有「{ev['title']}」，她还没睡",
        })
    else:
        nights = _late_night_days(recent_user_times, now_dt) + 1  # +当前这晚
        text = "冷冷现在还没睡。"
        if nights >= 2:
            text += f"近 7 天里这是第 {nights} 个熬夜的夜晚"
        hints.append({"type": LATE_NIGHT, "text": text})

    return hints


# ── 冷却台账（care_ledger，迁移 v19）──────────────────────────


async def load_ledger() -> dict[str, str]:
    """读取冷却台账 {type: last_at}。

    读取失败降级为**不滤**（返回空 dict——宁可多关心，不因 DB 抖动
    丢感知；与项目「失败放行」惯例一致）。
    """
    try:
        conn = await get_db()
        cursor = await conn.execute("SELECT type, last_at FROM care_ledger")
        return {r[0]: r[1] for r in await cursor.fetchall()}
    except Exception:
        log.warning("冷却台账读取失败，降级为不滤", exc_info=True)
        return {}


async def record_hint(hint_type: str, now: str) -> None:
    """注入即记账：冷却写时机 = 注入时（LLM 最后没提这茬也算冷却开始）。
    写入失败只记日志。"""
    try:
        conn = await get_db()
        await conn.execute(
            """INSERT INTO care_ledger (type, last_at) VALUES (?, ?)
               ON CONFLICT(type) DO UPDATE SET last_at = excluded.last_at""",
            (hint_type, now),
        )
        await conn.commit()
    except Exception:
        log.warning("冷却台账写入失败（type=%s）", hint_type, exc_info=True)


def filter_cooled(hints: list[dict], ledger: dict[str, str],
                  now_dt: datetime.datetime) -> list[dict]:
    """冷却期内的同类提示滤掉；各类型独立；returned 永不滤。

    台账时间戳异常 → 放行（失败语义同读取：宁可多关心）。
    """
    passed = []
    for h in hints:
        cooldown = COOLDOWN_HOURS.get(h["type"])
        if cooldown is None:
            passed.append(h)
            continue
        last = ledger.get(h["type"])
        if not last:
            passed.append(h)
            continue
        try:
            last_dt = datetime.datetime.strptime(last, _TIME_FMT).replace(tzinfo=HKT)
        except (ValueError, TypeError):
            passed.append(h)
            continue
        if (now_dt - last_dt).total_seconds() >= cooldown * 3600:
            passed.append(h)
    return passed
