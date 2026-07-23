"""认证 + 限流中间件。

- 如果配置了 XIAOXI_API_TOKEN，则对受保护路径要求 Bearer Token
- 对聊天、配置、记忆、推送端点做基于 IP 的速率限制
- X-Forwarded-For 仅在显式开启 trust_proxy（部署在可信反向代理之后）时
  才用于限流归因，否则攻击者可伪造 XFF 完全绕过限流
- Token 比较使用 hmac.compare_digest（常量时间，防时序侧信道）
"""
import hmac
import time

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


# 受保护的 API 路径前缀（需要 Bearer Token 认证）
_PROTECTED_PREFIXES = ("/api/chat", "/api/config", "/api/memories", "/api/push")

# 公开端点白名单（即使配置了 token 也免认证）：
# vapid-key 仅返回 VAPID 公钥，是前端发起订阅前的必需信息，不含敏感数据
_PUBLIC_PATHS = ("/api/push/vapid-key",)

# 简单的内存限流器（每 IP）
_rate_buckets: dict[str, list[float]] = {}

_RATE_LIMITS = {
    "/api/chat": 10,       # 聊天：10 次/分钟
    "/api/config": 10,     # 配置：10 次/分钟
    "/api/memories": 30,   # 记忆管理：30 次/分钟
    "/api/push": 30,       # 推送订阅/已读：30 次/分钟
}


# ── 限流器定期清理 ──────────────────────────────────────────

_cleanup_counter = 0
_CLEANUP_INTERVAL = 100  # 每 100 次请求清理一次过期 bucket


def _cleanup_stale_buckets():
    """清除 2 分钟内未活跃的 bucket，防止内存泄漏。"""
    now = time.time()
    stale_keys = [
        key for key, bucket in _rate_buckets.items()
        if not bucket or (now - bucket[-1]) > 120
    ]
    for key in stale_keys:
        del _rate_buckets[key]


# ── 辅助函数 ──────────────────────────────────────────────────


def _get_client_ip(scope: Scope, trust_proxy: bool = False) -> str:
    """从 ASGI scope 提取客户端 IP。

    默认（trust_proxy=False）只使用直连 IP——无条件信任 X-Forwarded-For
    会让攻击者每个请求伪造一个新"IP"，完全绕过限流。
    仅当明确部署在可信反向代理之后（trust_proxy=True）时，才读取 XFF
    的最右侧值（由可信代理追加的真实客户端 IP，攻击者注入的在更左侧）。
    """
    if trust_proxy:
        headers = dict(scope.get("headers", []))
        forwarded = headers.get(b"x-forwarded-for")
        if forwarded:
            return forwarded.decode().split(",")[-1].strip()
    # 直连 IP
    client = scope.get("client")
    if client:
        return client[0]
    return "unknown"


def _get_path_prefix(path: str) -> str | None:
    """返回匹配的受保护路径前缀，无匹配返回 None。"""
    for prefix in _PROTECTED_PREFIXES:
        if path.startswith(prefix):
            return prefix
    return None


def _is_public_path(path: str) -> bool:
    """是否为免认证的公开端点。"""
    return path in _PUBLIC_PATHS


def _token_matches(provided_auth_header: str, expected_token: str) -> bool:
    """常量时间比较 Bearer Token，防时序侧信道。"""
    if not provided_auth_header.startswith("Bearer "):
        return False
    provided = provided_auth_header[len("Bearer "):]
    if not provided:
        return False
    return hmac.compare_digest(provided.encode("utf-8"), expected_token.encode("utf-8"))


def _check_rate(path: str, ip: str) -> bool:
    """检查 IP 是否超出限流。返回 True 表示允许通过。

    按 (ip, prefix) 组合键分桶，不同端点独立计数。
    """
    global _cleanup_counter

    # 定期清理过期 bucket
    _cleanup_counter += 1
    if _cleanup_counter >= _CLEANUP_INTERVAL:
        _cleanup_counter = 0
        _cleanup_stale_buckets()

    # 精确匹配限流规则
    limit = 0
    matched_prefix = ""
    for prefix, lim in _RATE_LIMITS.items():
        if path.startswith(prefix):
            limit = lim
            matched_prefix = prefix
            break
    if limit == 0:
        return True  # 无限制

    now = time.time()
    bucket_key = f"{ip}:{matched_prefix}"
    bucket = _rate_buckets.setdefault(bucket_key, [])
    # 清理 60 秒前的记录
    cutoff = now - 60
    bucket[:] = [t for t in bucket if t > cutoff]
    if len(bucket) >= limit:
        return False
    bucket.append(now)
    return True


# ── 中间件类 ──────────────────────────────────────────────────


class AuthAndRateLimitMiddleware:
    """认证 + 限流中间件。"""

    def __init__(self, app: ASGIApp, api_token: str, trust_proxy: bool = False):
        self.app = app
        self.api_token = api_token
        self.trust_proxy = trust_proxy

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path: str = scope.get("path", "")
        method: str = scope.get("method", "")
        ip = _get_client_ip(scope, trust_proxy=self.trust_proxy)
        prefix = _get_path_prefix(path)

        # ── 速率限制 ──
        if not _check_rate(path, ip):
            if method == "OPTIONS":
                # CORS 预检不拦截
                pass
            else:
                response = JSONResponse(
                    {"detail": "请求太频繁了，等一会儿再来找小汐玩吧~"},
                    status_code=429,
                )
                await response(scope, receive, send)
                return

        # ── 认证 ──
        if prefix and self.api_token and not _is_public_path(path):
            headers = dict(scope.get("headers", []))
            auth = headers.get(b"authorization", b"").decode()
            if not _token_matches(auth, self.api_token):
                # OPTIONS 预检不需要认证
                if method != "OPTIONS":
                    response = JSONResponse(
                        {"detail": "需要有效的 API Token 才能访问此接口"},
                        status_code=401,
                    )
                    await response(scope, receive, send)
                    return

        await self.app(scope, receive, send)
