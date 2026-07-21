"""WebSocket 拒绝中间件 — 小汐不支持 WebSocket 连接。"""
from starlette.types import ASGIApp, Receive, Scope, Send


class RejectWebSocketMiddleware:
    """拒绝所有 WebSocket 连接。"""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] == "websocket":
            await send({"type": "websocket.close", "code": 1008})
            return
        await self.app(scope, receive, send)
