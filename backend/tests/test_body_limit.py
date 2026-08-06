"""请求体大小上限中间件测试（体检 7.18 修复回归）。

覆盖：
- 声明 Content-Length 超限 → 413，且不读 body、不进内层
- 声明 Content-Length 未超限 → 直通（不额外缓冲）
- chunked（无 Content-Length）超限 → 413
- chunked 未超限 → body 回放给内层，内容无损
- 非法 Content-Length 值 → 直通（交给 uvicorn/框架层处理）
"""
from api.middleware.body_limit import RequestBodyLimitMiddleware

LIMIT = 10  # 测试用小上限


class _BodyEchoApp:
    """内层应用：读完 body 并记录。"""

    def __init__(self):
        self.calls = 0
        self.body = b""

    async def __call__(self, scope, receive, send):
        self.calls += 1
        chunks = []
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                break
            chunks.append(message.get("body", b""))
            if not message.get("more_body"):
                break
        self.body = b"".join(chunks)


def _make_scope(headers=None):
    return {"type": "http", "method": "POST", "path": "/api/chat",
            "headers": headers or []}


async def _run(mw, inner, headers, body_messages):
    """驱动一次请求；body_messages 是 receive 依次返回的消息列表。"""
    statuses = []
    responses = []

    messages = list(body_messages)

    async def receive():
        if messages:
            return messages.pop(0)
        return {"type": "http.disconnect"}

    async def send(message):
        if message["type"] == "http.response.start":
            statuses.append(message["status"])
        elif message["type"] == "http.response.body":
            responses.append(message.get("body", b""))

    await mw(_make_scope(headers), receive, send)
    return (statuses[0] if statuses else 200), b"".join(responses)


def _mw(inner):
    return RequestBodyLimitMiddleware(inner, max_bytes=LIMIT)


async def test_declared_over_limit_rejected_without_reading():
    inner = _BodyEchoApp()
    status, body = await _run(
        _mw(inner), inner,
        headers=[(b"content-length", str(LIMIT + 1).encode())],
        body_messages=[],  # 根本不该读 body
    )
    assert status == 413
    assert inner.calls == 0
    assert b"\xe8\xa3\x85\xe4\xb8\x8d\xe4\xb8\x8b" in body  # 「装不下」


async def test_declared_within_limit_passes_through():
    inner = _BodyEchoApp()
    payload = b"x" * LIMIT
    status, _ = await _run(
        _mw(inner), inner,
        headers=[(b"content-length", str(LIMIT).encode())],
        body_messages=[{"type": "http.request", "body": payload,
                        "more_body": False}],
    )
    assert status == 200
    assert inner.calls == 1
    assert inner.body == payload


async def test_chunked_over_limit_rejected():
    inner = _BodyEchoApp()
    status, _ = await _run(
        _mw(inner), inner,
        headers=[],  # 无 content-length = chunked
        body_messages=[
            {"type": "http.request", "body": b"a" * 6, "more_body": True},
            {"type": "http.request", "body": b"b" * 6, "more_body": True},
        ],
    )
    assert status == 413
    assert inner.calls == 0


async def test_chunked_within_limit_replayed():
    inner = _BodyEchoApp()
    status, _ = await _run(
        _mw(inner), inner,
        headers=[],
        body_messages=[
            {"type": "http.request", "body": b"abc", "more_body": True},
            {"type": "http.request", "body": b"defg", "more_body": False},
        ],
    )
    assert status == 200
    assert inner.calls == 1
    assert inner.body == b"abcdefg"


async def test_empty_body_ok():
    inner = _BodyEchoApp()
    status, _ = await _run(
        _mw(inner), inner,
        headers=[],
        body_messages=[{"type": "http.request", "body": b"",
                        "more_body": False}],
    )
    assert status == 200
    assert inner.body == b""


async def test_invalid_content_length_passes_through():
    inner = _BodyEchoApp()
    status, _ = await _run(
        _mw(inner), inner,
        headers=[(b"content-length", b"not-a-number")],
        body_messages=[{"type": "http.request", "body": b"hi",
                        "more_body": False}],
    )
    assert status == 200
    assert inner.calls == 1


async def test_non_http_scope_passthrough():
    inner = _BodyEchoApp()
    mw = _mw(inner)
    scope = {"type": "lifespan"}
    seen = []

    async def receive():
        return {"type": "lifespan.startup"}

    async def send(message):
        seen.append(message)

    await mw(scope, receive, send)
    assert inner.calls == 1
