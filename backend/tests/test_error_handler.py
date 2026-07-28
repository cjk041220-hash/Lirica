"""全局异常兜底 + 请求 ID 中间件 行为钉死。

- 未捕获异常 → 500 + JSON{detail, request_id} + X-Request-Id 头，且 body 的
  request_id 与响应头一致；异常文本（含潜在 URL/Key）不得出现在响应体。
- HTTPException → 不被兜底 handler 覆盖（保留原状态码，如 418）。
- 正常响应也带 X-Request-Id（中间件对一切 http 响应生效）。
"""
import httpx
import pytest
from fastapi import FastAPI, HTTPException

from api.middleware.request_id import RequestIdMiddleware


def _build_app():
    app = FastAPI()
    app.add_middleware(RequestIdMiddleware)

    @app.get("/boom")
    async def boom():
        # 异常文本带 URL+key，验证兜底绝不把它回给客户端
        raise RuntimeError("kaboom https://x.test/?key=LEAKY_SECRET")

    @app.get("/tea")
    async def tea():
        raise HTTPException(status_code=418, detail="I'm a teapot")

    @app.get("/ok")
    async def ok():
        return {"ok": True}

    return app


@pytest.fixture
async def client():
    # httpx ASGITransport 不把 app 异常抛回调用方（ServerErrorMiddleware 发 500），
    # 故可直接断言响应体/头
    transport = httpx.ASGITransport(app=_build_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def test_unhandled_returns_500_json_with_request_id(client):
    resp = await client.get("/boom")
    assert resp.status_code == 500
    body = resp.json()
    assert body["detail"]            # 固定文案，非空
    assert body["request_id"]        # 非空 rid
    assert resp.headers.get("x-request-id") == body["request_id"]
    # 绝不回泄漏异常文本（URL / Key / 原始消息）
    assert "LEAKY_SECRET" not in resp.text
    assert "kaboom" not in resp.text


async def test_http_exception_not_overridden(client):
    resp = await client.get("/tea")
    assert resp.status_code == 418
    assert resp.json()["detail"] == "I'm a teapot"
    # 兜底 handler 没把它改成 500 固定文案；头仍由中间件注入
    assert resp.headers.get("x-request-id")


async def test_normal_response_carries_request_id(client):
    resp = await client.get("/ok")
    assert resp.status_code == 200
    assert resp.headers.get("x-request-id")
