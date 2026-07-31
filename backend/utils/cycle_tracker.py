"""生理期追踪器 — 记录、预测、对话修正。

根据历史记录计算平均周期，预测下一次经期。预测日由事件检测器
（utils/events.detect_cycle_events）派生成日历上的「身体的日子」，
关心提示统一走事件通道（提示词注入 + 主动消息），本模块只管账本。
"""
import datetime
from datetime import timedelta

from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import HKT as _HKT
_DATE_FMT = "%Y-%m-%d"


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


async def delete_record(date_str: str) -> bool:
    """删除一条生理期记录（冷冷说「那天根本不算」）。

    Args:
        date_str: 要删除的开始日期，格式 YYYY-MM-DD

    Returns:
        是否真的删掉了（坏日期格式或记录不存在返回 False）
    """
    try:
        datetime.datetime.strptime(date_str, _DATE_FMT)
    except (TypeError, ValueError):
        log.warning("生理期记录删除被拒绝：日期格式非法 %r（需要 YYYY-MM-DD）", date_str)
        return False
    conn = await get_db()
    try:
        cursor = await conn.execute(
            "DELETE FROM cycle_records WHERE start_date = ?", (date_str,)
        )
        await conn.commit()
        return cursor.rowcount > 0
    except Exception as e:
        log.error("生理期记录删除失败: %s", e)
        return False


async def move_record(old_date: str, new_date: str) -> str:
    """把一条记录挪到新日期（冷冷说「记错了，其实是 X 号来的」）。

    顺序纪律：先 INSERT OR IGNORE 新日期占位（连同旧备注），占位失败
    说明新日期已有记录 → 不动旧的直接返回，避免「毁旧不成反丢」；
    占位成功再删旧记录。单连接单 commit 保证原子性。

    Returns:
        "moved"    — 挪动成功
        "not_found" — 旧日期没有记录
        "exists"   — 新日期已有记录（旧记录保持不动）；old==new 也归到这里
    """
    try:
        datetime.datetime.strptime(old_date, _DATE_FMT)
        datetime.datetime.strptime(new_date, _DATE_FMT)
    except (TypeError, ValueError):
        log.warning(
            "生理期记录挪动被拒绝：日期格式非法 %r → %r（需要 YYYY-MM-DD）",
            old_date, new_date,
        )
        return "not_found"
    if old_date == new_date:
        return "exists"
    conn = await get_db()
    try:
        cursor = await conn.execute(
            "SELECT note FROM cycle_records WHERE start_date = ?", (old_date,)
        )
        row = await cursor.fetchone()
        if row is None:
            return "not_found"
        old_note = row[0] or ""
        cursor = await conn.execute(
            "INSERT OR IGNORE INTO cycle_records (start_date, note) VALUES (?, ?)",
            (new_date, old_note),
        )
        if cursor.rowcount == 0:
            return "exists"
        await conn.execute(
            "DELETE FROM cycle_records WHERE start_date = ?", (old_date,)
        )
        await conn.commit()
        return "moved"
    except Exception as e:
        log.error("生理期记录挪动失败: %s", e)
        return "not_found"


async def update_note(date_str: str, note: str) -> bool:
    """补/改某次生理期的备注（冷冷说「那次痛得很厉害」）。

    Returns:
        是否更新成功（坏日期格式或记录不存在返回 False）
    """
    try:
        datetime.datetime.strptime(date_str, _DATE_FMT)
    except (TypeError, ValueError):
        log.warning("生理期备注更新被拒绝：日期格式非法 %r（需要 YYYY-MM-DD）", date_str)
        return False
    conn = await get_db()
    try:
        cursor = await conn.execute(
            "UPDATE cycle_records SET note = ? WHERE start_date = ?",
            (note, date_str),
        )
        await conn.commit()
        return cursor.rowcount > 0
    except Exception as e:
        log.error("生理期备注更新失败: %s", e)
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
        }
        如果记录不足 2 条，返回 None。关心窗口的判定由事件检测器
        （utils/events.detect_cycle_events）按 days_until_next 自行掌握。
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

    return {
        "avg_cycle": avg_cycle,
        "last_start": last_start.strftime(_DATE_FMT),
        "next_predicted": next_predicted.strftime(_DATE_FMT),
        "days_until_next": days_until,
        "records_count": len(records),
    }


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
