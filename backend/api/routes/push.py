"""推送路由 — /api/push。

提供：
- POST /api/push/subscribe — 注册推送订阅
- DELETE /api/push/subscribe — 取消推送订阅
- GET /api/push/unread — 获取未读的主动消息
- POST /api/push/read — 标记主动消息已读
- GET /api/push/vapid-key — 获取 VAPID 公钥（前端订阅时需要）
"""
import json
from urllib.parse import urlparse

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from config.settings import get_settings
from database.core import get_db
from utils.logger import get_logger
from utils.web_push import save_subscription

log = get_logger(__name__)

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────────


class PushSubscriptionRequest(BaseModel):
    endpoint: str
    keys: dict  # {"p256dh": "...", "auth": "..."}


class MarkReadRequest(BaseModel):
    # 上限防 DoS：不限长会动态拼出超大 IN 列表，可撞 SQLite 绑定变量
    # 上限抛 OperationalError → 500
    message_ids: list[str] = Field(default_factory=list, max_length=200)


# ── 端点 ──────────────────────────────────────────────────────

# 受信任的浏览器推送服务域名（Chrome/Edge=FCM、Firefox=Mozilla、
# Safari=Apple、Windows=WNS）。endpoint 必须是 https 且 host 命中此表——
# 否则持 token 者可注册任意 URL，让 webpush 把带 VAPID 签名的主动消息
# POST 到该地址（受限 SSRF + 内容外泄原语，安全审查 P2）
_PUSH_HOST_SUFFIXES = (
    "push.services.mozilla.com",
    "mozaws.net",
    "fcm.googleapis.com",
    "notify.windows.com",
    "wns.windows.com",
    "push.apple.com",
    "web.push.apple.com",
)


def _is_allowed_push_endpoint(endpoint: str) -> bool:
    try:
        u = urlparse(endpoint)
    except Exception:
        return False
    if u.scheme != "https" or not u.hostname:
        return False
    host = u.hostname.lower()
    return any(host == s or host.endswith("." + s) for s in _PUSH_HOST_SUFFIXES)


@router.get("/push/vapid-key")
async def get_vapid_key():
    """返回 VAPID 公钥，前端注册推送订阅时需要。"""
    s = get_settings()
    if not s.vapid_public_key:
        return {"key": None, "enabled": False}
    return {"key": s.vapid_public_key, "enabled": True}


@router.post("/push/subscribe")
async def subscribe(req: PushSubscriptionRequest, request: Request):
    """注册推送订阅。"""
    p256dh = req.keys.get("p256dh", "")
    auth = req.keys.get("auth", "")

    if not p256dh or not auth:
        # 业务错误用 4xx 而非 200+error：此前 200 让前端 usePush
        # 的 `!res.ok → 清理订阅 + 返回 false` 分支永远不生效，
        # 订阅失败被误判为成功（setSubscribed(true)）
        raise HTTPException(400, "缺少 keys.p256dh 或 keys.auth")

    if not _is_allowed_push_endpoint(req.endpoint):
        raise HTTPException(400, "endpoint 不是受信任的推送服务地址")

    user_agent = request.headers.get("user-agent", "")[:200]
    sub_id = await save_subscription(req.endpoint, p256dh, auth, user_agent)

    log.info("推送订阅已注册: %s", req.endpoint[:60])
    return {"status": "ok", "id": sub_id}


@router.delete("/push/subscribe")
async def unsubscribe(request: Request, endpoint: str | None = None):
    """取消推送订阅。

    endpoint 可通过两种方式提供：
    - query 参数：DELETE /api/push/subscribe?endpoint=...
    - JSON body：前端 fetch 直接发送 PushSubscription JSON
      （{"endpoint": "...", "keys": {...}}），取其中的 endpoint 字段
    """
    if not endpoint:
        try:
            body = await request.json()
            if isinstance(body, dict):
                endpoint = body.get("endpoint", "")
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass

    if not endpoint:
        return {"status": "error", "message": "缺少 endpoint"}

    conn = await get_db()
    cursor = await conn.execute(
        "DELETE FROM push_subscriptions WHERE endpoint = ?",
        (endpoint,),
    )
    await conn.commit()

    if cursor.rowcount > 0:
        return {"status": "ok"}
    return {"status": "not_found"}


@router.get("/push/unread")
async def get_unread():
    """获取未读的主动消息（最多 10 条）。"""
    conn = await get_db()
    cursor = await conn.execute(
        """SELECT id, content, created_at FROM proactive_messages
           WHERE status IN ('delivered', 'stored') AND read_at IS NULL
           ORDER BY created_at DESC LIMIT 10"""
    )
    rows = await cursor.fetchall()

    messages = [
        {"id": r[0], "content": r[1], "created_at": r[2]}
        for r in rows
    ]
    return {"messages": messages, "count": len(messages)}


@router.post("/push/read")
async def mark_read(req: MarkReadRequest):
    """标记主动消息为已读。"""
    if not req.message_ids:
        return {"status": "ok", "updated": 0}

    import datetime

    from utils.timezone import HKT as _HKT
    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")

    conn = await get_db()
    placeholders = ",".join("?" for _ in req.message_ids)
    cursor = await conn.execute(
        f"""UPDATE proactive_messages
            SET read_at = ?, status = 'read'
            WHERE id IN ({placeholders}) AND read_at IS NULL""",
        (now, *req.message_ids),
    )
    await conn.commit()

    return {"status": "ok", "updated": cursor.rowcount}
