"""项目共享时区常量 — 冷冷在香港（UTC+8）。

全项目（生产 8 处 + 测试 7 处）统一从此处导入，避免重复定义。
"""
import datetime as _dt
from datetime import timezone, timedelta

HKT = timezone(timedelta(hours=8))

_TIME_FMT = "%Y-%m-%d %H:%M"


def now_str() -> str:
    """当前 HKT 时间的格式化字符串。

    全项目统一调用，消除 memory/manager.py 与 utils/events.py 中
    重复定义的 _now() 副本。
    """
    return _dt.datetime.now(HKT).strftime(_TIME_FMT)
