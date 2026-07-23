"""Web Push 发送器 — 基于 pywebpush + VAPID 签名。

用于向 PWA 客户端（iOS Safari / Chrome）推送主动消息通知。

v2.1 修复：
- webpush（requests 同步实现）→ webpush_async（aiohttp 异步实现）+ 显式 timeout。
  旧版在 async 函数里直接调用同步 HTTP 请求，阻塞整个事件循环，且默认
  timeout=None——一个"能连上不响应"的订阅端点可挂死整个服务
  （聊天流式、心跳任务共用同一事件循环，全部陪葬）。
- 多个订阅通过 asyncio.gather 并发发送，单设备失败不影响其他设备。
- 410（订阅过期）检测优先使用异常携带的 response.status，字符串匹配兜底。
- save_subscription 改用 INSERT ... ON CONFLICT 原子 UPSERT，
  并发订阅同一 endpoint 不再触发 IntegrityError（旧版先查后插存在竞态）。
"""
import asyncio
import json
import uuid
from datetime import datetime, timezone, timedelta

from pywebpush import webpush_async, WebPushException

from config.settings import get_settings
from database.core import get_db
from utils.logger import get_logger

log = get_logger(__name__)

# 单次推送超时（秒）。aiohttp 超时抛 asyncio.TimeoutError，
# 半死端点不会无限占用事件循环。
_PUSH_TIMEOUT = 10.0


def _get_vapid_claims() -> dict:
    """构建 VAPID 声明。

    注意：webpush_async 会原地修改传入的 claims 字典（填充 aud/exp），
    因此每次调用都必须传入一个全新的字典，不能在多个订阅间共享。
    """
    s = get_settings()
    return {
        "sub": s.vapid_claim_mailto,
    }


def _is_stale_subscription(exc: WebPushException) -> bool:
    """判断推送失败是否因为订阅已过期（410 Gone / 404 Not Found / 用户取消）。

    410：RFC 8030 标准的订阅失效信号；
    404：WNS 等部分推送服务对已失效订阅返回 404 而非 410。
    """
    response = getattr(exc, "response", None)
    status = getattr(response, "status", None)
    if status in (404, 410):
        return True
    # 兜底：异常文本匹配（部分错误路径不携带 response 对象）
    text = str(exc)
    return "410" in text or "404" in text or "Gone" in text


async def send_push(title: str, body: str, url: str = "/") -> int:
    """向所有已订阅的设备发送推送通知（异步并发，互不阻塞）。

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

    async def _send_one(sub_id: str, endpoint: str, p256dh: str, auth: str) -> tuple:
        """发送单个订阅。返回 (状态, sub_id, endpoint, 异常|None)，失败不外抛。"""
        subscription_info = {
            "endpoint": endpoint,
            "keys": {
                "p256dh": p256dh,
                "auth": auth,
            },
        }
        try:
            await webpush_async(
                subscription_info=subscription_info,
                data=payload,
                vapid_private_key=s.vapid_private_key,
                vapid_claims=_get_vapid_claims(),  # 每次全新字典，见函数说明
                timeout=_PUSH_TIMEOUT,
                # TTL 必须显式给非零值：pywebpush 默认 ttl=0，
                # 而 WNS（Windows 推送服务）在默认 cache-policy 下
                # 收到 TTL: 0 会返回 400 "Ttl value conflicts with
                # X-WNS-Cache-Policy"，导致所有 Windows 设备收不到推送。
                # 86400s = 设备离线时通知最多保留一天。
                ttl=86400,
            )
            return ("ok", sub_id, endpoint, None)
        except WebPushException as e:
            return ("failed", sub_id, endpoint, e)
        except Exception as e:  # 超时 / 网络错误等
            return ("error", sub_id, endpoint, e)

    # 并发发送所有订阅，单设备失败由 _send_one 内部消化，不影响其他设备
    results = await asyncio.gather(
        *(_send_one(sub_id, endpoint, p256dh, auth)
          for sub_id, endpoint, p256dh, auth in subs)
    )

    sent = 0
    stale_ids = []
    for status, sub_id, endpoint, exc in results:
        if status == "ok":
            sent += 1
        elif status == "failed":
            if _is_stale_subscription(exc):
                # 410 Gone = 订阅已过期/被用户取消 → 标记清理
                stale_ids.append(sub_id)
                log.info("推送订阅已过期，将清理: %s", endpoint[:50])
            else:
                log.warning("推送发送失败 (%s): %s", endpoint[:50], exc)
        else:
            log.error("推送发送异常 (%s): %s", endpoint[:50], exc)

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
    """保存推送订阅信息（原子 UPSERT，按 endpoint 去重）。

    使用 INSERT ... ON CONFLICT，由数据库层保证原子性：
    同一 endpoint 的并发订阅不会触发 UNIQUE 冲突（IntegrityError）。

    Returns:
        订阅 ID（已存在的订阅返回原 ID）
    """
    _HKT = timezone(timedelta(hours=8))
    now = datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    sub_id = uuid.uuid4().hex[:16]

    conn = await get_db()

    await conn.execute(
        """INSERT INTO push_subscriptions
           (id, endpoint, p256dh, auth, user_agent, created_at)
           VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT(endpoint) DO UPDATE SET
               p256dh = excluded.p256dh,
               auth = excluded.auth,
               user_agent = excluded.user_agent""",
        (sub_id, endpoint, p256dh, auth, user_agent, now),
    )
    await conn.commit()

    cursor = await conn.execute(
        "SELECT id FROM push_subscriptions WHERE endpoint = ?",
        (endpoint,),
    )
    row = await cursor.fetchone()
    return row[0]
