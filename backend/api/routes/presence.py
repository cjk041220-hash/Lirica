"""presence 路由 — /api/presence：冷冷在线状态上报。

era 会话路由（era.py）判定的数据源：后端据此知道冷冷「在不在」、
「当前活跃在哪个会话」，从而决定主动消息是追加进当前对话（在线）
还是新开/追加独处时间线（离线）。

前端调用时机（usePresenceReport hook）：
- 页面加载 / 回到前台（visibilitychange→visible）/ 每 2 分钟
  → POST {session_id: 当前会话, leaving: false}
- 页面关闭 / 隐藏（pagehide）→ sendBeacon POST {leaving: true}，
  后端把 last_seen_at 回填到超时窗口之前 → 立即判定离线
  （用户裁定「关页面即离线」；手机杀进程等无信号场景由
  presence_timeout_minutes 兜底）。
"""
import datetime
import re

from fastapi import APIRouter
from pydantic import BaseModel

from database.core import get_db
from utils.logger import get_logger
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

router = APIRouter()

# 与 chat.py 的 _SESSION_ID_RE 同一契约
_SID_RE = re.compile(r"^[a-z0-9-]{1,64}$")

_TIME_FMT = "%Y-%m-%d %H:%M"


class PresenceRequest(BaseModel):
    session_id: str = ""
    leaving: bool = False


@router.post("/presence")
async def report_presence(req: PresenceRequest):
    """上报在线状态（幂等；失败由前端静默吞掉，不影响主流程）。

    leaving=True：页面关闭/隐藏，回填一个早于超时窗口的时间戳让
    era 路由立即判定离线，active_session_id 清空。
    leaving=False：刷新 last_seen_at 与当前活跃会话。
    """
    now = datetime.datetime.now(_HKT)
    session_id = req.session_id.strip() if req.session_id else ""
    if session_id and not _SID_RE.match(session_id):
        # 非法 sid 按「无活跃会话」处理（不报错——前端可能传了
        # 未生成完的临时值，报错会变成可见噪音）
        log.warning("presence：非法 session_id 已忽略: %r", session_id)
        session_id = ""

    if req.leaving:
        from config.settings import get_settings

        timeout = get_settings().presence_timeout_minutes
        last_seen = (now - datetime.timedelta(minutes=timeout + 1)).strftime(_TIME_FMT)
        session_id = None  # 离开即无活跃会话
    else:
        last_seen = now.strftime(_TIME_FMT)
        session_id = session_id or None

    conn = await get_db()
    cursor = await conn.execute(
        "UPDATE life_state SET last_seen_at = ?, active_session_id = ? WHERE id = 1",
        (last_seen, session_id),
    )
    if cursor.rowcount == 0:
        # 防御性兜底：life_state 行缺失（理论上 init_db 恒会建）也
        # 不崩——插入一行再更新
        await conn.execute(
            "INSERT OR IGNORE INTO life_state "
            "(id, mood, daily_event, updated_at, last_seen_at, active_session_id) "
            "VALUES (1, NULL, NULL, ?, ?, ?)",
            (now.strftime(_TIME_FMT), last_seen, session_id),
        )
    await conn.commit()
    return {"status": "ok"}
