"""请求 ID 中间件 + 未捕获异常兜底 — 纯 ASGI（不缓冲响应体，保护 SSE）。

为每个 HTTP 请求生成短 request_id，写入 scope 并在响应头回写 X-Request-Id，
便于把一次报错的日志、响应、前端反馈对上号。同时在最外层兜底真正未捕获的
异常 → 统一 500 JSON + rid（细节脱敏后只进日志，响应体只回固定文案）。

为何在此兜底而不用 app.add_exception_handler(Exception, …)：
    Starlette 1.x 的异常处理在路由层 wrap_app_handling_exceptions 进行，按
    scope["starlette.exception_handlers"] 查 handler；实测对类型型 Exception
    不生效（仍 re-raise），而 HTTPException 在此层已被转成响应、不再冒泡。
    故在最外层中间件 try/except 既能接住真正未捕获异常，又不会误吞
    HTTPException（401/422/404/429 等已在路由层成响应，到不了这里）。

刻意用 send 包装而非 BaseHTTPMiddleware——后者会缓冲响应体，直接弄坏
/api/chat/stream 的 SSE（同 SecurityHeadersMiddleware 的理由）。
"""
import uuid

from starlette.datastructures import State
from starlette.responses import JSONResponse

from utils.errors import sanitize_sensitive_text
from utils.logger import get_logger

log = get_logger(__name__)


class RequestIdMiddleware:
    """生成 request_id（scope 存值 + 响应头回写）+ 兜底未捕获异常。"""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        rid = uuid.uuid4().hex[:12]
        # 普通 scope 键：经 request.scope 读取，无 Starlette State 语义依赖
        scope["x_request_id"] = rid
        # 顺手写到 request.state，便于业务代码按需读取（失败不影响主流程）
        try:
            st = scope.get("state")
            if not isinstance(st, State):
                st = State()
                scope["state"] = st
            st.request_id = rid
        except Exception:
            pass

        response_started = False

        async def send_wrapper(message):
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
                headers = list(message.get("headers", []))
                if b"x-request-id" not in {h[0] for h in headers}:
                    headers.append((b"x-request-id", rid.encode("ascii")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as exc:
            # 响应已起（流式中途炸）→ 无法改写状态码，记日志后交外层处理
            if response_started:
                log.exception(
                    "流式响应中途未捕获异常 [rid=%s] %s: %s",
                    rid, type(exc).__name__, sanitize_sensitive_text(str(exc)),
                )
                raise
            # 响应未起 → 统一 500 JSON + rid；异常文本绝不回客户端
            log.exception(
                "未捕获异常 [rid=%s] %s: %s",
                rid, type(exc).__name__, sanitize_sensitive_text(str(exc)),
            )
            body = JSONResponse(
                status_code=500,
                content={
                    "detail": "小汐这边出了点状况，已经记下来了～",
                    "request_id": rid,
                },
                headers={"x-request-id": rid},
            )
            await body(scope, receive, send)
