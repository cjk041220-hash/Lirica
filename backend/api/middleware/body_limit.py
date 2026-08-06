"""请求体大小上限中间件 — 防内存耗尽（纯 ASGI，不缓冲正常响应）。

FastAPI/uvicorn 本身不设请求体上限：攻击者可以 POST 一个几 GB 的
JSON 把进程内存吃光（单 worker 架构下 = 直接打瘫小汐）。反代层只有
Nginx 有隐式 1MB 默认上限，Caddy 与直连回环都没兜底，故在应用层
做一道拓扑无关的硬限制。

合法请求体上限测算：最大的业务请求是 /api/chat（message ≤2000 字，
UTF-8 下 ≤6KB）与推送订阅（几 KB）——1MB 给了 ~170 倍余量。

实现分两路：
1. 声明 Content-Length 且超限 → 直接 413，连 body 都不读（最省资源）。
2. 无 Content-Length（chunked）→ 有界缓冲读取，超限 413；不超限则
   把已读 body 回放给下游（对 ≤1MB 的请求 FastAPI 反正要全量读入，
   提前读不改变语义）。
"""
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

# 1 MiB —— 见模块 docstring 测算
MAX_BODY_BYTES = 1024 * 1024

_413_DETAIL = "请求太大了，小汐装不下这么多内容～"


class RequestBodyLimitMiddleware:
    """拒绝超过 MAX_BODY_BYTES 的请求体（413）。"""

    def __init__(self, app: ASGIApp, max_bytes: int = MAX_BODY_BYTES):
        self.app = app
        self.max_bytes = max_bytes

    async def _reject(self, scope: Scope, receive: Receive, send: Send):
        response = JSONResponse({"detail": _413_DETAIL}, status_code=413)
        await response(scope, receive, send)

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        declared = headers.get(b"content-length")

        # ── 路径 1：声明了 Content-Length ──
        if declared is not None:
            try:
                declared_size = int(declared.decode())
            except (ValueError, UnicodeDecodeError):
                declared_size = None
            if declared_size is not None and declared_size > self.max_bytes:
                await self._reject(scope, receive, send)
                return
            # 声明且未超限：直通，不做额外缓冲（保持流式语义）
            await self.app(scope, receive, send)
            return

        # ── 路径 2：未声明 Content-Length（chunked 等）→ 有界缓冲 ──
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                # 客户端中途断开：把已读部分交下游走正常断连处理
                break
            body.extend(message.get("body", b""))
            if len(body) > self.max_bytes:
                await self._reject(scope, receive, send)
                return
            if not message.get("more_body"):
                break

        # 回放已缓冲的 body 给下游
        replayed = False

        async def replay_receive() -> dict:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {
                    "type": "http.request",
                    "body": bytes(body),
                    "more_body": False,
                }
            return await receive()

        await self.app(scope, replay_receive, send)
