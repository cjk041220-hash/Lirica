"""认证 + 限流中间件测试。

回归覆盖：
- XFF 伪造绕过限流（🔴）：默认不信任 X-Forwarded-For，限流按直连 IP 归因
- /api/push/* 纳入认证保护（🟠 #18），vapid-key 白名单豁免
- Token 常量时间比较（hmac.compare_digest）
"""
import pytest

from api.middleware import auth as auth_mod
from api.middleware.auth import AuthAndRateLimitMiddleware


class _InnerApp:
    """最小 ASGI 内层应用：记录被调用次数。"""

    def __init__(self):
        self.calls = 0

    async def __call__(self, scope, receive, send):
        self.calls += 1


@pytest.fixture(autouse=True)
def clean_buckets(monkeypatch):
    """每个测试独立的限流桶。"""
    monkeypatch.setattr(auth_mod, "_rate_buckets", {})


async def _invoke(mw, inner, path, *, headers=None, client=("1.2.3.4", 40000), method="GET"):
    """驱动一次中间件调用，返回 (响应状态码或 200=透传, 内层调用次数)。"""
    scope = {
        "type": "http",
        "path": path,
        "method": method,
        "headers": headers or [],
        "client": client,
    }
    statuses = []

    async def receive():
        return {"type": "http.request"}

    async def send(message):
        if message["type"] == "http.response.start":
            statuses.append(message["status"])

    await mw(scope, receive, send)
    return (statuses[0] if statuses else 200), inner.calls


# ── XFF 信任 ──────────────────────────────────────────────────


def test_xff_ignored_by_default():
    """回归：默认不读 XFF，直连 IP 才是限流归因（防伪造绕过）。"""
    scope = {
        "headers": [(b"x-forwarded-for", b"9.9.9.9")],
        "client": ("1.2.3.4", 40000),
    }
    assert auth_mod._get_client_ip(scope, trust_proxy=False) == "1.2.3.4"


def test_xff_rightmost_used_when_trusted():
    """trust_proxy=True 时取最右侧值（可信代理追加的真实客户端）。"""
    scope = {
        "headers": [(b"x-forwarded-for", b"spoofed.by.attacker, 10.0.0.7")],
        "client": ("10.0.0.1", 40000),  # 可信代理本身
    }
    assert auth_mod._get_client_ip(scope, trust_proxy=True) == "10.0.0.7"


async def test_xff_spoofing_cannot_bypass_rate_limit():
    """回归：同一客户端伪造不同 XFF 不能绕过限流（默认配置）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="")

    # chat 限流 10 次/分钟：同一 client 带 12 个不同伪造 XFF 打 12 次
    statuses = []
    for i in range(12):
        status, _ = await _invoke(
            mw, inner, "/api/chat",
            headers=[(b"x-forwarded-for", f"8.8.{i}.{i}".encode())],
            client=("1.2.3.4", 40000),
        )
        statuses.append(status)

    assert statuses[:10] == [200] * 10
    assert statuses[10:] == [429, 429]  # 第 11、12 次被限流


# ── /api/push 保护（#18）──────────────────────────────────────


async def test_push_unread_requires_token_when_configured():
    """配置 token 后，/api/push/unread 无 token 返回 401。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")

    status, _ = await _invoke(mw, inner, "/api/push/unread")
    assert status == 401
    assert inner.calls == 0


async def test_push_endpoints_pass_with_valid_token():
    """带正确 token 时 push 端点放行。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")
    headers = [(b"authorization", b"Bearer secret-token")]

    for path in ("/api/push/unread", "/api/push/subscribe", "/api/push/read"):
        status, _ = await _invoke(mw, inner, path, headers=headers)
        assert status == 200
    assert inner.calls == 3


async def test_vapid_key_public_even_with_token():
    """vapid-key 白名单：即使配置了 token 也免认证（前端订阅前置信息）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")

    status, _ = await _invoke(mw, inner, "/api/push/vapid-key")
    assert status == 200
    assert inner.calls == 1


async def test_no_token_configured_everything_open():
    """未配置 token 时不启用认证（本地开发默认行为不变）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="")

    status, _ = await _invoke(mw, inner, "/api/push/unread")
    assert status == 200


# ── Token 比较 ────────────────────────────────────────────────


def test_token_matches_constant_time():
    """_token_matches 的边界行为。"""
    assert auth_mod._token_matches("Bearer abc123", "abc123") is True
    assert auth_mod._token_matches("Bearer wrong", "abc123") is False
    assert auth_mod._token_matches("abc123", "abc123") is False       # 缺 Bearer 前缀
    assert auth_mod._token_matches("Bearer ", "abc123") is False      # 空 token
    assert auth_mod._token_matches("", "abc123") is False


async def test_wrong_token_returns_401_not_429():
    """受保护路径带错误 token → 401（而非被限流逻辑误伤）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")

    status, _ = await _invoke(
        mw, inner, "/api/memories",
        headers=[(b"authorization", b"Bearer not-the-token")],
    )
    assert status == 401


# ── 限流分桶 ──────────────────────────────────────────────────


async def test_rate_limit_per_prefix_independent():
    """不同端点前缀独立计数：chat 打满不影响 memories。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="")

    for _ in range(10):
        await _invoke(mw, inner, "/api/chat")
    status_chat, _ = await _invoke(mw, inner, "/api/chat")
    status_mem, _ = await _invoke(mw, inner, "/api/memories")

    assert status_chat == 429
    assert status_mem == 200


async def test_push_rate_limited():
    """push 端点限流生效（30 次/分钟）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="")

    statuses = [
        (await _invoke(mw, inner, "/api/push/unread"))[0] for _ in range(32)
    ]
    assert statuses[:30] == [200] * 30
    assert 429 in statuses[30:]


# ── 登记纪律（P0 回归：/api/diaries 漏登记裸奔）──────────────
#
# status 路由文件一次注册 /api/status 与 /api/diaries 两个端点，
# 而 _PROTECTED_PREFIXES 曾只登记 /api/status —— 日记（含冷冷的私人
# 内容）在公网部署下无认证、无限流。startswith 匹配让漏登记静默。
# 以下测试遍历所有挂载路由，漏登记直接拦下。


def _all_mounted_api_paths() -> set[str]:
    """收集所有挂载到 /api 前缀下的路由路径（与 server.py 的路由器清单一致）。"""
    from api.routes.chat import router as chat_router
    from api.routes.config import router as config_router
    from api.routes.push import router as push_router
    from api.routes.sessions import router as sessions_router
    from api.routes.status import router as status_router

    paths = set()
    for router in (chat_router, config_router, push_router, sessions_router, status_router):
        for route in router.routes:
            paths.add("/api" + route.path)
    return paths


def test_every_api_route_is_authenticated_or_whitelisted():
    """每条 /api/* 路由必须命中 _PROTECTED_PREFIXES 或在 _PUBLIC_PATHS 白名单。"""
    for path in _all_mounted_api_paths():
        protected = any(path.startswith(p) for p in auth_mod._PROTECTED_PREFIXES)
        public = path in auth_mod._PUBLIC_PATHS
        assert protected or public, (
            f"路由 {path} 既不在 _PROTECTED_PREFIXES 也不在 _PUBLIC_PATHS —— "
            "配置 XIAOXI_API_TOKEN 后将无认证裸奔（/api/diaries 事故形态）。"
            "新增路由必须在 api/middleware/auth.py 登记。"
        )


def test_every_protected_prefix_has_rate_limit():
    """每个受保护前缀都必须有限流档位（漏配 = 可被无限刷）。"""
    for prefix in auth_mod._PROTECTED_PREFIXES:
        assert prefix in auth_mod._RATE_LIMITS, f"{prefix} 缺少限流档位"


async def test_diaries_requires_token_when_configured():
    """直接钉死：/api/diaries 配置 token 后必须认证（P0 行为级回归）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")

    status, _ = await _invoke(mw, inner, "/api/diaries")
    assert status == 401
    assert inner.calls == 0

    status, _ = await _invoke(
        mw, inner, "/api/diaries",
        headers=[(b"authorization", b"Bearer secret-token")],
    )
    assert status == 200
    assert inner.calls == 1


async def test_sessions_requires_token_when_configured():
    """/api/sessions 存全量对话原文，敏感度不低于日记：
    配置 token 后必须认证（同 test_diaries 规格的行为级回归）。"""
    inner = _InnerApp()
    mw = AuthAndRateLimitMiddleware(inner, api_token="secret-token")

    status, _ = await _invoke(mw, inner, "/api/sessions")
    assert status == 401
    assert inner.calls == 0

    status, _ = await _invoke(
        mw, inner, "/api/sessions",
        headers=[(b"authorization", b"Bearer secret-token")],
    )
    assert status == 200
    assert inner.calls == 1


async def test_rate_limit_history_separate_from_chat():
    """history 走独立桶：聊天桶刷满后，同 IP 的 history 仍可用。

    顺序回归——_check_rate 首前缀匹配即 break，history 必须排在 chat
    之前，否则会被 10 次/分钟的聊天桶吞掉（实测复现：刷新+连聊误触 429）。
    """
    ip = "9.9.9.9"
    for _ in range(10):
        assert auth_mod._check_rate("/api/chat", ip) is True
    assert auth_mod._check_rate("/api/chat", ip) is False  # 第 11 次被限
    assert auth_mod._check_rate("/api/chat/history", ip) is True  # 独立桶不受影响


def test_rate_limit_health_has_bucket():
    """健康检查有独立限流桶（公网不可无限频刷的轻量 DoS 面）。"""
    assert auth_mod._RATE_LIMITS.get("/api/health", 0) >= 1
