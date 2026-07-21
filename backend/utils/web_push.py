"""Web Push 发送器 — 基于 pywebpush + VAPID 签名。

用于向 PWA 客户端（iOS Safari / Chrome）推送主动消息通知。
"""
import json
import uuid
from datetime import datetime, timezone, timedelta

from pywebpush import webpush, WebPushException

from config.settings import get_settings
from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)


def _get_vapid_claims() -> dict:
    """构建 VAPID 声明。"""
    s = get_settings()
    return {
        "sub": s.vapid_claim_mailto,
    }


async def send_push(title: str, body: str, url: str = "/") -> int:
    """向所有已订阅的设备发送推送通知。

    Args:
        title: 通知标题
        body: 通知正文
        url: 点击通知后打开的页面路径

    Returns:
        成功发送的数量
    """
    s = get_settings()
    if not s.vapid_public_key or not s.vapid_private_key:
        log.warning("VAPID 密钥未配置，跳过推送")
        return 0

    # 读取所有订阅
    conn = await get_db()
    cursor = await conn.execute("SELECT id, endpoint, p256dh, auth FROM push_subscriptions")
    subs = await cursor.fetchall()

    if not subs:
        log.debug("无推送订阅，跳过")
        return 0

    payload = json.dumps({
        "title": title,
        "body": body,
        "url": url,
        "icon": "/icon-192.png",
        "badge": "/badge-72.png",
    })

    sent = 0
    stale_ids = []

    for sub_id, endpoint, p256dh, auth in subs:
        subscription_info = {
            "endpoint": endpoint,
            "keys": {
                "p256dh": p256dh,
                "auth": auth,
            },
        }

        try:
            webpush(
                subscription_info=subscription_info,
                data=payload,
                vapid_private_key=s.vapid_private_key,
                vapid_claims=_get_vapid_claims(),
            )
            sent += 1
        except WebPushException as e:
            # 410 Gone = 订阅已过期/被用户取消
            if "410" in str(e) or "Gone" in str(e):
                stale_ids.append(sub_id)
                log.info("推送订阅已过期，将清理: %s", endpoint[:50])
            else:
                log.warning("推送发送失败 (%s): %s", endpoint[:50], e)
        except Exception as e:
            log.error("推送发送异常 (%s): %s", endpoint[:50], e)

    # 清理过期订阅
    if stale_ids:
        placeholders = ",".join("?" for _ in stale_ids)
        await conn.execute(
            f"DELETE FROM push_subscriptions WHERE id IN ({placeholders})",
            stale_ids,
        )
        await conn.commit()
        log.info("已清理 %d 个过期推送订阅", len(stale_ids))

    return sent


async def save_subscription(
    endpoint: str,
    p256dh: str,
    auth: str,
    user_agent: str = "",
) -> str:
    """保存推送订阅信息（UPSERT，按 endpoint 去重）。

    Returns:
        订阅 ID
    """
    _HKT = timezone(timedelta(hours=8))
    now = datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    sub_id = uuid.uuid4().hex[:16]

    conn = await get_db()

    # UPSERT: endpoint 已存在则更新密钥
    cursor = await conn.execute(
        "SELECT id FROM push_subscriptions WHERE endpoint = ?",
        (endpoint,),
    )
    existing = await cursor.fetchone()

    if existing:
        await conn.execute(
            "UPDATE push_subscriptions SET p256dh = ?, auth = ?, user_agent = ? WHERE endpoint = ?",
            (p256dh, auth, user_agent, endpoint),
        )
        await conn.commit()
        return existing[0]

    await conn.execute(
        """INSERT INTO push_subscriptions
           (id, endpoint, p256dh, auth, user_agent, created_at)
           VALUES (?, ?, ?, ?, ?, ?)""",
        (sub_id, endpoint, p256dh, auth, user_agent, now),
    )
    await conn.commit()
    return sub_id
