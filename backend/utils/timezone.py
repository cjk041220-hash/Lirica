"""项目共享时区 / 地域常量 — 冷冷在香港（UTC+8）。

时区与「定位不可用时的回退坐标」全项目统一从此处导入，避免重复定义
（HKT 生产 8 处 + 测试 7 处；默认坐标原在 heartbeat 与 events 各一份）。
"""
import datetime as _dt
from datetime import timedelta, timezone

HKT = timezone(timedelta(hours=8))

# 默认回退坐标：定位缓存不可用时用香港（与 HKT 时区对应）。
# heartbeat 早安任务与 utils/events 天气检测共用，单一真相源。
DEFAULT_LAT = 22.3193
DEFAULT_LON = 114.1694

_TIME_FMT = "%Y-%m-%d %H:%M"


def now_str() -> str:
    """当前 HKT 时间的格式化字符串。

    全项目统一调用，消除 memory/manager.py 与 utils/events.py 中
    重复定义的 _now() 副本。
    """
    return _dt.datetime.now(HKT).strftime(_TIME_FMT)
