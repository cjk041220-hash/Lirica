"""安全响应头中间件 — 纯 ASGI 实现（不缓冲响应体，保护 SSE 流式）。

公网部署的纵深防御：补一组基础安全头。刻意用 send 包装而非
BaseHTTPMiddleware——后者会缓冲响应体，直接弄坏 /api/chat/stream 的 SSE。

HSTS 不在此设置（交给反向代理 Caddy/Nginx，避免应用侧配错锁死域名）；
Permissions-Policy 也不设（默认即允许 Geolocation，设错会弄坏 GPS 功能）。
"""

# (头名, 值) —— ASGI headers 是小写字节对
_SECURITY_HEADERS = [
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"strict-origin-when-cross-origin"),
    # 现代浏览器已弃用内置 XSS 审计器，显式关闭避免它误伤（OWASP 现行建议）
    (b"x-xss-protection", b"0"),
    # 字体两域名（fonts.googleapis.com 样式表 / fonts.gstatic.com 字体文件）
    # 是展示体 ZCOOL KuaiLe 的加载链路（web/index.html 引用）——不放行则
    # 生产 :8000 托管下字体永远加载失败、静默回落系统圆体（dev 无 CSP
    # 不发作，体检 T1-3 实锤）。自托管字体落地后应回收这两域名收紧 CSP。
    (b"content-security-policy",
     b"default-src 'self'; script-src 'self'; "
     b"style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
     b"img-src 'self' data:; font-src 'self' data: https://fonts.gstatic.com; "
     b"connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'"),
]


class SecurityHeadersMiddleware:
    """在 http 响应起始注入安全头；非 http scope（lifespan 等）直通。"""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                # 不覆盖上游已设的同名头（如 CORS 的 access-control-*）
                existing = {h[0] for h in headers}
                headers.extend(h for h in _SECURITY_HEADERS if h[0] not in existing)
                message = {**message, "headers": headers}
            await send(message)

        await self.app(scope, receive, send_wrapper)
