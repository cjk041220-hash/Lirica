"""Presence 路由测试 — /api/presence（在线状态上报，era 路由的数据源）。

使用 httpx.AsyncClient + ASGITransport 直接驱动路由（同 test_push_routes 模式）。
"""
import datetime

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.presence import router as presence_router
from config.settings import get_settings
from utils.timezone import HKT as _HKT


@pytest.fixture
async def client(tmp_db, monkeypatch):
    """挂载 presence 路由的最小 app，注入临时数据库。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    app = FastAPI()
    app.include_router(presence_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def _life_state(tmp_db):
    cursor = await tmp_db.execute(
        "SELECT last_seen_at, active_session_id FROM life_state WHERE id = 1"
    )
    return await cursor.fetchone()


async def test_presence_reports_online(client, tmp_db):
    """在线上报：写 last_seen_at（当前时间）+ active_session_id。"""
    r = await client.post("/api/presence", json={"session_id": "s-act"})
    assert r.status_code == 200
    assert r.json()["status"] == "ok"

    last_seen, active = await _life_state(tmp_db)
    assert active == "s-act"
    # last_seen 是刚刚（分钟精度，与 now 同分钟内）
    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    assert last_seen == now


async def test_presence_leaving_marks_offline(client, tmp_db, monkeypatch):
    """leaving=True：last_seen 回填到超时窗口之前 → 立即判离线。"""
    s = get_settings()
    monkeypatch.setattr(s, "presence_timeout_minutes", 45)

    r = await client.post("/api/presence", json={"session_id": "s-act", "leaving": True})
    assert r.status_code == 200

    last_seen, active = await _life_state(tmp_db)
    assert active is None  # 离开即无活跃会话
    # last_seen 至少早于 45 分钟 → is_online 判定必然 False
    from era import is_online
    now = datetime.datetime.now(_HKT)
    assert not is_online(now, last_seen, 45)


async def test_presence_empty_session_id_valid(client, tmp_db):
    """空 session_id 合法（页面加载还没定会话时上报）。"""
    r = await client.post("/api/presence", json={"session_id": ""})
    assert r.status_code == 200
    last_seen, active = await _life_state(tmp_db)
    assert active is None


async def test_presence_invalid_session_id_ignored(client, tmp_db):
    """非法 session_id（超长/字符越界）→ 按无活跃会话处理，不 422。"""
    r = await client.post("/api/presence", json={"session_id": "BAD SID!@#$" * 10})
    assert r.status_code == 200
    last_seen, active = await _life_state(tmp_db)
    assert active is None
