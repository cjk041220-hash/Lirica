"""认证 + 限流中间件。

- 如果配置了 XIAOXI_API_TOKEN，则对受保护路径要求 Bearer Token
- 对聊天和记忆端点做基于 IP 的速率限制
"""
import time

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


# 受保护的 API 路径前缀（需要 Bearer Token 认证）
_PROTECTED_PREFIXES = ("/api/chat", "/api/config", "/api/memories")

# 简单的内存限流器（每 IP）
_rate_buckets: dict[str, list[float]] = {}

_RATE_LIMITS = {
    "/api/chat": 10,       # 聊天：10 次/分钟
    "/api/config": 10,     # 配置：10 次/分钟
    "/api/memories": 30,   # 记忆管理：30 次/分钟
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


def _get_client_ip(scope: Scope) -> str:
    """从 ASGI scope 提取客户端 IP。"""
    # 优先取 X-Forwarded-For（如有反向代理）
    headers = dict(scope.get("headers", []))
    forwarded = headers.get(b"x-forwarded-for")
    if forwarded:
        return forwarded.decode().split(",")[0].strip()
    # 回退到直连 IP
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

    def __init__(self, app: ASGIApp, api_token: str):
        self.app = app
        self.api_token = api_token

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path: str = scope.get("path", "")
        method: str = scope.get("method", "")
        ip = _get_client_ip(scope)
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
        if prefix and self.api_token:
            headers = dict(scope.get("headers", []))
            auth = headers.get(b"authorization", b"").decode()
            expected = f"Bearer {self.api_token}"
            if auth != expected:
                # OPTIONS 预检不需要认证
                if method != "OPTIONS":
                    response = JSONResponse(
                        {"detail": "需要有效的 API Token 才能访问此接口"},
                        status_code=401,
                    )
                    await response(scope, receive, send)
                    return

        await self.app(scope, receive, send)
