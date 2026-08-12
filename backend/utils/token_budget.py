"""TOKEN_COST_WARNING —— 本地 token 预算告警（P2-1，2026-08-12）。

账单异常的代码侧哨兵（此前只有「DeepSeek 控制台设账单告警」的人工叮嘱，
SERVER_ADMIN §10/§11 点名零实现）。

设计取舍（为什么是近似累计而非复读 usage）：
- 不读 DeepSeek 返回的 usage_metadata——streaming 下要另开 stream_usage，
  且 chat agent（graph 中间件）与后台任务（create_deepseek_llm 直连）两条
  调用链都得接，侵入大。沿用现有遥测的 cl100k 近似计数：对 DeepSeek 偏
  高估 1.3–2×，而高估正是预算监控要的安全余量（宁多报不漏报）。
- 只累计**输入** token：系统提示词 ~11K 是每次模型调用的常数成本，输入
  占花费绝大部分（输出多为几十到几百 token），输入累计足以当账单哨兵。
- 覆盖范围 = chat agent 每次模型调用（graph.py，含工具循环的多次调用——
  成本大头）。后台任务（主动消息/记忆提取/画像蒸馏/日记）量小有界，本版
  不累计；要全量记账再接统一拦截层。

进程内累计、按 HKT 日归零；重启清零（容器长驻，可接受——要跨重启持久
预算再落库）。默认关闭（token_cost_warning=0），关闭时零成本。
"""
import threading
from datetime import datetime

from config.settings import get_settings
from utils.logger import get_logger
from utils.timezone import HKT

log = get_logger(__name__)

_lock = threading.Lock()
_day: str | None = None     # 当前累计所属 HKT 日期
_used: int = 0              # 当日已累计输入 token
_next_warn_at: int = 0      # 下一个触发告警的累计阈值（按阈值倍数递增）


def enabled() -> bool:
    """预算告警是否开启（阈值 > 0）。供调用方决定是否值得跑 count_tokens。"""
    return get_settings().token_cost_warning > 0


def record_input(approx_tokens: int) -> None:
    """累计一次模型调用的近似输入 token；越过阈值倍数时打 WARNING。

    每个阈值倍数告一次（5M 阈值 → 5M/10M/15M…各一条），既能在费用失控时
    持续留痕，又不逐次调用刷屏。阈值或日期变化自动归零重计。
    """
    threshold = get_settings().token_cost_warning
    if threshold <= 0 or approx_tokens <= 0:
        return
    today = datetime.now(HKT).date().isoformat()
    global _day, _used, _next_warn_at
    with _lock:
        if _day != today:
            _day, _used, _next_warn_at = today, 0, threshold
        _used += approx_tokens
        while _used >= _next_warn_at:
            log.warning(
                "token 预算告警：当日累计输入约 %d tokens，已越过 %d 阈值线"
                "（cl100k 近似偏高估；折算花费对照 DeepSeek 控制台单价）",
                _used, _next_warn_at,
            )
            _next_warn_at += threshold


def _reset() -> None:
    """清空累计（测试用）。"""
    global _day, _used, _next_warn_at
    with _lock:
        _day, _used, _next_warn_at = None, 0, 0
