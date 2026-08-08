"""认证 + 限流中间件。

- 如果配置了 XIAOXI_API_TOKEN，则对受保护路径要求 Bearer Token
- 对聊天、配置、记忆、推送端点做基于 IP 的速率限制
- X-Forwarded-For 仅在显式开启 trust_proxy（部署在可信反向代理之后）时
  才用于限流归因，否则攻击者可伪造 XFF 完全绕过限流
- Token 比较使用 hmac.compare_digest（常量时间，防时序侧信道）
- 429 响应带 Retry-After 头（客户端可据此退避）；OPTIONS 预检不占限流桶
"""
import math
import time

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from utils.security import bearer_token_matches as _token_matches

# 受保护的 API 路径前缀（需要 Bearer Token 认证）
# 新增路由必须在这里登记，否则裸奔无认证（公网部署时是安全漏洞）
# 登记纪律回归测试：tests/test_auth_middleware.py 遍历所有挂载路由，
# 漏登记会直接被测试拦下（/api/diaries 就曾这样漏过）
_PROTECTED_PREFIXES = (
    "/api/chat", "/api/config", "/api/events", "/api/memories", "/api/push",
    "/api/status", "/api/diaries", "/api/sessions",
)

# 公开端点白名单（即使配置了 token 也免认证）：
# - vapid-key 仅返回 VAPID 公钥，是前端发起订阅前的必需信息，不含敏感数据
# - health 未认证时只返回 {"status": "ok"}（供存活监控），完整诊断
#   信息由端点内部自行鉴权（hmac 常量时间比较），分级设计，显式豁免
_PUBLIC_PATHS = ("/api/push/vapid-key", "/api/health")

# 简单的内存限流器（每 IP）
_rate_buckets: dict[str, list[float]] = {}

# 前缀匹配由 _RATE_PREFIXES_LONGEST_FIRST 保证「最长前缀优先」——
# /api/chat/history 不会被 /api/chat 先吃掉共用聊天的 10 次/分钟桶
#（实测复现过的 429 误触）。新增前缀无需再记得手工摆顺序。
_RATE_LIMITS = {
    "/api/chat/history": 60,  # 历史只读：独立桶，不与聊天 LLM 成本桶相争
    "/api/chat": 10,          # 聊天：10 次/分钟（LLM 成本防线，不放宽）
    # 配置：30 次/分钟。重建 Agent 只是进程内对象替换，开销很小；
    # 温度滑杆已做 250ms 前端防抖。10 次/分钟对设置页交互过紧
    # （连点模型+风格+拖温度即可触发 429），30 足够防滥用。
    "/api/config": 30,
    "/api/events": 30,        # 日历面板：30 次/分钟（打开拉一次 + 聊完重拉 + CRUD）
    "/api/memories": 30,      # 记忆管理：30 次/分钟
    "/api/push": 30,          # 推送订阅/已读：30 次/分钟
    # 状态/日记只读：30 次/分钟。前端只在挂载 + 回前台 + 聊完天时拉取，
    # life_state 后端每 2 小时才变一次，无需高频轮询
    "/api/status": 30,
    "/api/diaries": 30,       # 日记列表：同状态端点档位
    "/api/sessions": 60,      # 对话档案只读为主 + 偶发删除：独立宽松桶
    # 健康检查：公网可无限频刷是轻量 DoS 面，给宽松桶；
    # Docker/监控探针走容器内 127.0.0.1 独立桶，不受此限
    "/api/health": 120,
}

# 最长前缀优先的遍历序（结构保证，不再依赖字典字面量插入顺序）
_RATE_PREFIXES_LONGEST_FIRST = tuple(sorted(_RATE_LIMITS, key=len, reverse=True))


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


def _check_rate(path: str, ip: str) -> tuple[bool, int | None]:
    """检查 IP 是否超出限流。返回 (是否放行, 若被限则是 Retry-After 秒数)。

    按 (ip, prefix) 组合键分桶，不同端点独立计数。
    Retry-After = 桶内最早记录再过多久跌出 60 秒窗口（客户端退避依据）。
    """
    global _cleanup_counter

    # 定期清理过期 bucket
    _cleanup_counter += 1
    if _cleanup_counter >= _CLEANUP_INTERVAL or len(_rate_buckets) > 1000:
        _cleanup_counter = 0
        _cleanup_stale_buckets()

    # 精确匹配限流规则（最长前缀优先，见 _RATE_PREFIXES_LONGEST_FIRST）
    limit = 0
    matched_prefix = ""
    for prefix in _RATE_PREFIXES_LONGEST_FIRST:
        if path.startswith(prefix):
            limit = _RATE_LIMITS[prefix]
            matched_prefix = prefix
            break
    if limit == 0:
        return True, None  # 无限制

    now = time.time()
    bucket_key = f"{ip}:{matched_prefix}"
    bucket = _rate_buckets.setdefault(bucket_key, [])
    # 清理 60 秒前的记录
    cutoff = now - 60
    bucket[:] = [t for t in bucket if t > cutoff]
    if len(bucket) >= limit:
        # bucket 按时间顺序追加，[0] 是最早记录；过滤后必满足 now - bucket[0] < 60
        retry_after = max(1, math.ceil(60 - (now - bucket[0])))
        return False, retry_after
    # 单 bucket 硬上限：异常流量下防止单 IP 时间戳列表撑爆内存
    if len(bucket) < 200:
        bucket.append(now)
    return True, None


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
        if method == "OPTIONS":
            # CORS 预检请求：不进限流桶、不计数（否则高频预检会
            # 消耗真实请求的配额，实测过的 429 误触形态之一）
            pass
        else:
            allowed, retry_after = _check_rate(path, ip)
            if not allowed:
                response = JSONResponse(
                    {"detail": "请求太频繁了，等一会儿再来找小汐玩吧~"},
                    status_code=429,
                    headers={"Retry-After": str(retry_after)},
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
