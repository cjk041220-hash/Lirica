"""推送路由 — /api/push。

提供：
- POST /api/push/subscribe — 注册推送订阅
- DELETE /api/push/subscribe — 取消推送订阅
- GET /api/push/unread — 获取未读的主动消息
- POST /api/push/read — 标记主动消息已读
- GET /api/push/vapid-key — 获取 VAPID 公钥（前端订阅时需要）
"""
import json

from fastapi import APIRouter, Request
from pydantic import BaseModel

from config.settings import get_settings
from database.core import get_db
from utils.web_push import save_subscription
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────────


class PushSubscriptionRequest(BaseModel):
    endpoint: str
    keys: dict  # {"p256dh": "...", "auth": "..."}


class MarkReadRequest(BaseModel):
    message_ids: list[str]


# ── 端点 ──────────────────────────────────────────────────────


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
        return {"status": "error", "message": "缺少 keys.p256dh 或 keys.auth"}

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
           WHERE status = 'delivered' AND read_at IS NULL
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
    from datetime import timezone, timedelta
    _HKT = timezone(timedelta(hours=8))
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
