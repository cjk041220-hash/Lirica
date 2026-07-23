"""Push 路由测试 — DELETE /api/push/subscribe 同时接受 JSON body 与 query 参数。

回归：前端 fetch DELETE 发送 PushSubscription JSON body，旧版只接受 query
参数（endpoint）→ 前端请求被 FastAPI 判为 422，取消订阅失败。
新版从 body 解析 endpoint，query 参数作为兼容回退。

使用 httpx.AsyncClient + ASGITransport 直接驱动路由（不经完整 lifespan），
且与 aiosqlite fixture 共享同一事件循环，规避跨线程/跨 loop 访问连接。
"""
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.push import router as push_router


@pytest.fixture
async def client(tmp_db, monkeypatch):
    """挂载 push 路由的最小 app，注入临时数据库。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    app = FastAPI()
    app.include_router(push_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def _count_sub(tmp_db):
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM push_subscriptions")
    return (await cursor.fetchone())[0]


async def test_unsubscribe_via_json_body(client, tmp_db):
    """前端发送 PushSubscription JSON body → 应成功删除。"""
    from utils.web_push import save_subscription

    await save_subscription("https://push.example.com/body", "p", "a")
    assert await _count_sub(tmp_db) == 1

    r = await client.request(
        "DELETE",
        "/api/push/subscribe",
        json={"endpoint": "https://push.example.com/body", "keys": {"p256dh": "p", "auth": "a"}},
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert await _count_sub(tmp_db) == 0


async def test_unsubscribe_via_query_param(client, tmp_db):
    """兼容回退：query 参数 endpoint 仍可用。"""
    from utils.web_push import save_subscription

    await save_subscription("https://push.example.com/query", "p", "a")

    r = await client.request(
        "DELETE",
        "/api/push/subscribe",
        params={"endpoint": "https://push.example.com/query"},
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert await _count_sub(tmp_db) == 0


async def test_unsubscribe_missing_endpoint_returns_error(client):
    """既无 body 又无 query → 返回 error，不抛 422。"""
    r = await client.request("DELETE", "/api/push/subscribe")
    assert r.status_code == 200
    assert r.json()["status"] == "error"


async def test_unsubscribe_unknown_endpoint_not_found(client):
    """endpoint 不存在 → not_found。"""
    r = await client.request(
        "DELETE",
        "/api/push/subscribe",
        json={"endpoint": "https://nope.example.com/x"},
    )
    assert r.status_code == 200
    assert r.json()["status"] == "not_found"


async def test_unread_and_vapid_key_endpoints(client, monkeypatch):
    """unread 返回结构稳定；vapid-key 在未配置时 enabled=false。"""
    from config.settings import get_settings

    s = get_settings()
    monkeypatch.setattr(s, "vapid_public_key", "")

    r = await client.get("/api/push/unread")
    assert r.status_code == 200
    data = r.json()
    assert "messages" in data and isinstance(data["messages"], list)

    r = await client.get("/api/push/vapid-key")
    assert r.status_code == 200
    assert r.json()["enabled"] is False
