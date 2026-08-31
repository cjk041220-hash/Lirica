"""Status 路由测试 — /api/status（今日状态）与 /api/diaries（日记列表）。

使用 httpx.AsyncClient + ASGITransport 直接驱动路由（同 test_push_routes 模式）。
"""
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.status import router as status_router


@pytest.fixture
async def client(tmp_db, monkeypatch):
    """挂载 status 路由的最小 app，注入临时数据库。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    app = FastAPI()
    app.include_router(status_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


# ── /api/status ───────────────────────────────────────────────


async def test_status_returns_life_state(client, tmp_db):
    """返回小汐今日心情与日常事件（碎碎念）。"""
    await tmp_db.execute(
        "UPDATE life_state SET mood = ?, daily_event = ? WHERE id = 1",
        ("好奇", "在映海里捡到一颗亮亮的贝壳"),
    )
    await tmp_db.commit()

    r = await client.get("/api/status")
    assert r.status_code == 200
    data = r.json()
    assert data["mood"] == "好奇"
    assert data["daily_event"] == "在映海里捡到一颗亮亮的贝壳"


async def test_status_empty_row_returns_nulls(client, tmp_db):
    """life_state 行不存在时返回 null 字段，不 500。"""
    await tmp_db.execute("DELETE FROM life_state")
    await tmp_db.commit()

    r = await client.get("/api/status")
    assert r.status_code == 200
    assert r.json()["mood"] is None
    assert r.json()["unseen_era_id"] is None


# ── /api/status v21 扩展：presence / unseen_era ───────────────


async def test_status_returns_presence_fields(client, tmp_db):
    """返回 last_seen_at / active_session_id（presence 回显）。"""
    await tmp_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 14:00', "
        "active_session_id = 's-act' WHERE id = 1"
    )
    await tmp_db.commit()

    r = await client.get("/api/status")
    data = r.json()
    assert data["last_seen_at"] == "2026-08-09 14:00"
    assert data["active_session_id"] == "s-act"
    assert data["unseen_era_id"] is None


async def test_status_unseen_era_id_when_unread_proactive(client, tmp_db):
    """era 会话仍是碎碎念（kind='solo'）且有未读主动消息 → 返回 id。"""
    await tmp_db.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era, kind) "
        "VALUES ('e-1', '小汐的碎碎念', '2026-08-09 08:00', '2026-08-09 12:00', 1, 'solo')"
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, session_id) "
        "VALUES ('p-1', '我在看云。', '2026-08-09 10:00', 'delivered', 'e-1')"
    )
    await tmp_db.commit()

    r = await client.get("/api/status")
    assert r.json()["unseen_era_id"] == "e-1"


async def test_status_unseen_era_none_when_all_read(client, tmp_db):
    """era 未读全部已读 → unseen_era_id 为 null（不打断当前会话）。"""
    await tmp_db.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era, kind) "
        "VALUES ('e-2', '小汐的碎碎念', '2026-08-09 08:00', '2026-08-09 12:00', 1, 'solo')"
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, read_at, session_id) "
        "VALUES ('p-2', '我在看云。', '2026-08-09 10:00', 'read', '2026-08-09 11:00', 'e-2')"
    )
    await tmp_db.commit()

    r = await client.get("/api/status")
    assert r.json()["unseen_era_id"] is None


async def test_status_unseen_era_none_after_user_replied(client, tmp_db):
    """v24：era 被接话（kind='chat'）→ 不再当作「小汐独处说了话」的信号。

    来源仍是 era（is_era=1），但类型已经变成对话——此时她正在这段会话
    里聊着，切换信号会把她从自己刚说话的地方拽走。
    """
    await tmp_db.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era, kind) "
        "VALUES ('e-3', '小汐的碎碎念', '2026-08-09 08:00', '2026-08-09 12:00', 1, 'chat')"
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, session_id) "
        "VALUES ('p-3', '我在看云。', '2026-08-09 10:00', 'delivered', 'e-3')"
    )
    await tmp_db.commit()

    r = await client.get("/api/status")
    assert r.json()["unseen_era_id"] is None


# ── /api/diaries ──────────────────────────────────────────────


async def test_diaries_lists_by_date_desc(client, tmp_db):
    """日记按日期倒序返回。"""
    await tmp_db.execute(
        "INSERT INTO diaries (date, title, content) VALUES ('2026-07-23', '07月23日', '前天的日记')"
    )
    await tmp_db.execute(
        "INSERT INTO diaries (date, title, content) VALUES ('2026-07-25', '07月25日', '今天的日记')"
    )
    await tmp_db.commit()

    r = await client.get("/api/diaries")
    assert r.status_code == 200
    diaries = r.json()["diaries"]
    assert len(diaries) == 2
    assert diaries[0]["date"] == "2026-07-25"  # 最新在前
    assert diaries[0]["content"] == "今天的日记"


async def test_diaries_respects_limit(client, tmp_db):
    """limit 参数生效。"""
    for i in range(5):
        await tmp_db.execute(
            "INSERT INTO diaries (date, title, content) VALUES (?, ?, ?)",
            (f"2026-07-0{i+1}", f"07月0{i+1}日", f"日记{i}"),
        )
    await tmp_db.commit()

    r = await client.get("/api/diaries", params={"limit": 3})
    assert len(r.json()["diaries"]) == 3


async def test_diaries_empty(client):
    """没有日记时返回空列表，不 500。"""
    r = await client.get("/api/diaries")
    assert r.status_code == 200
    assert r.json()["diaries"] == []
