"""生理期追踪器 — 记录、预测、主动关心。

根据历史记录计算平均周期，预测下一次经期。
在经期前后窗口期，心跳系统会生成关心身体的主动消息。
"""
import datetime
from datetime import timedelta

from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import HKT as _HKT
_DATE_FMT = "%Y-%m-%d"

# 经期关心窗口：预测日期前 N 天到后 N 天
_CARE_WINDOW_DAYS = 2

# 痛经严重程度提醒
_PAIN_NOTE = "痛经很厉害，有时会昏迷"


async def add_record(date_str: str, note: str = "") -> bool:
    """添加一条生理期记录。

    Args:
        date_str: 开始日期，格式 YYYY-MM-DD
        note: 可选备注

    Returns:
        是否成功插入（重复日期或非法日期格式返回 False）
    """
    # 入口拒绝坏日期：get_cycle_stats 对全部记录 strptime，
    # 一条坏数据即抛 ValueError，会静默禁用整个生理期关心功能
    try:
        datetime.datetime.strptime(date_str, _DATE_FMT)
    except (TypeError, ValueError):
        log.warning("生理期记录被拒绝：日期格式非法 %r（需要 YYYY-MM-DD）", date_str)
        return False
    conn = await get_db()
    try:
        cursor = await conn.execute(
            "INSERT OR IGNORE INTO cycle_records (start_date, note) VALUES (?, ?)",
            (date_str, note),
        )
        await conn.commit()
        # INSERT OR IGNORE 命中 UNIQUE 冲突时静默跳过且 rowcount=0，
        # 用 rowcount 区分真实插入与重复日期（修复：此前恒返回 True）
        return cursor.rowcount > 0
    except Exception as e:
        log.error("生理期记录插入失败: %s", e)
        return False


async def get_records() -> list[dict]:
    """获取所有生理期记录，按日期倒序。"""
    conn = await get_db()
    cursor = await conn.execute(
        "SELECT start_date, note FROM cycle_records ORDER BY start_date DESC"
    )
    rows = await cursor.fetchall()
    return [{"start_date": r[0], "note": r[1]} for r in rows]


async def get_cycle_stats() -> dict | None:
    """计算周期统计信息。

    Returns:
        {
            "avg_cycle": int,         # 平均周期天数
            "last_start": str,        # 最近一次开始日期
            "next_predicted": str,    # 预测下次开始日期
            "days_until_next": int,   # 距离预测还有几天（负数=已过）
            "is_in_care_window": bool # 当前是否在关心窗口内
        }
        如果记录不足 2 条，返回 None。
    """
    records = await get_records()
    if len(records) < 2:
        return None

    # 按日期排序（升序）计算间隔。
    # 逐条 try/except：存量数据（如迁移回填）无格式约束，一条坏数据
    # 不应该让 strptime 抛 ValueError 静默禁用整个生理期关心功能
    dates = []
    for r in records:
        try:
            dates.append(datetime.datetime.strptime(r["start_date"], _DATE_FMT).date())
        except (TypeError, ValueError):
            log.warning("生理期记录日期非法，已跳过: %r", r.get("start_date"))
    dates.sort()
    if len(dates) < 2:
        return None

    # 计算每两次之间的间隔
    intervals = []
    for i in range(1, len(dates)):
        delta = (dates[i] - dates[i - 1]).days
        intervals.append(delta)

    avg_cycle = round(sum(intervals) / len(intervals))
    last_start = dates[-1]

    today = datetime.datetime.now(_HKT).date()
    next_predicted = last_start + timedelta(days=avg_cycle)
    days_until = (next_predicted - today).days

    # 关心窗口：预测日期前后各 CARE_WINDOW_DAYS 天
    window_start = next_predicted - timedelta(days=_CARE_WINDOW_DAYS)
    window_end = next_predicted + timedelta(days=_CARE_WINDOW_DAYS)
    is_in_care_window = window_start <= today <= window_end

    return {
        "avg_cycle": avg_cycle,
        "last_start": last_start.strftime(_DATE_FMT),
        "next_predicted": next_predicted.strftime(_DATE_FMT),
        "days_until_next": days_until,
        "is_in_care_window": is_in_care_window,
        "records_count": len(records),
    }


async def get_care_context() -> str | None:
    """如果当前在关心窗口内，返回注入系统提示词的上下文文本。

    这段文本会在 build_system_prompt 时注入，让小汐知道该关心冷冷的身体。
    不在窗口内时返回 None。
    """
    stats = await get_cycle_stats()
    if stats is None or not stats["is_in_care_window"]:
        return None

    days = stats["days_until_next"]

    if days > 0:
        timing = f"大约还有 {days} 天"
    elif days == 0:
        timing = "今天可能就是"
    else:
        timing = "已经开始了"

    return (
        f"## 冷冷的身体状况\n"
        f"根据记录推算，冷冷的生理期{timing}。"
        f"她痛经{_PAIN_NOTE}。\n"
        f"如果她在线，可以自然地关心一下她身体怎么样、有没有不舒服。"
        f"不要直接说'你生理期到了'，用关心的方式旁敲侧击就好。"
    )


async def seed_initial_records(records: list[str]) -> int:
    """批量写入初始生理期记录。

    仅供测试使用：生产环境的初始记录由数据库迁移 v4 直接写入。

    Args:
        records: 日期字符串列表，格式 YYYY-MM-DD

    Returns:
        成功插入的数量
    """
    inserted = 0
    for date_str in records:
        if await add_record(date_str):
            inserted += 1
    return inserted
