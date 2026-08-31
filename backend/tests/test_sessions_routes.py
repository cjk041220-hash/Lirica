"""Sessions 路由测试 — /api/sessions（列表 / 建行 / 回看 / 删除）。

覆盖 2026-08-30 对话管理优化引入的两条新语义：
- POST /api/sessions：点「开一段新聊天」即落空会话行，列表里立刻可见
  （修「惰性创建」——以前要等第一条消息才建行，新会话在列表里不存在）
- DELETE /api/sessions/{id}：连同 checkpoints.db 里的同名线程一起清掉
  （否则界面历史已空、小汐的上下文里还留着整段对话）
"""
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.sessions import router as sessions_router


@pytest.fixture
async def client(tmp_db, monkeypatch):
    """挂载 sessions 路由的最小 app，注入临时数据库。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    app = FastAPI()
    app.include_router(sessions_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
def purged(monkeypatch):
    """捕获删除会话时请求清理的 checkpoint 线程 id。"""
    calls = []

    async def fake_purge(thread_id, db_path=None):
        calls.append(thread_id)
        return 0

    monkeypatch.setattr("api.routes.sessions.delete_checkpoint_thread", fake_purge)
    return calls


# ── POST /api/sessions ────────────────────────────────────────

async def test_create_session_makes_row_visible_in_list(client, tmp_db):
    """建行后立刻出现在列表里——这是「点新聊天后列表里没有它」的修复点。"""
    r = await client.post("/api/sessions?session_id=s-fresh")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "session_id": "s-fresh"}

    listed = (await client.get("/api/sessions")).json()["sessions"]
    assert [s["id"] for s in listed] == ["s-fresh"]
    assert listed[0]["message_count"] == 0
    assert listed[0]["kind"] == "chat"


async def test_create_session_is_idempotent(client, tmp_db):
    """重复建行不覆盖既有标题与类型（用户可能重复点新聊天/刷新重放）。"""
    await client.post("/api/sessions?session_id=s-dup")
    await tmp_db.execute(
        "UPDATE chat_sessions SET title = '聊到一半' WHERE id = 's-dup'"
    )
    await tmp_db.commit()

    assert (await client.post("/api/sessions?session_id=s-dup")).status_code == 200

    cursor = await tmp_db.execute(
        "SELECT title, kind, is_era FROM chat_sessions WHERE id = 's-dup'"
    )
    assert await cursor.fetchone() == ("聊到一半", "chat", 0)


async def test_create_session_rejects_bad_id(client, tmp_db):
    """id 必须满足 ^[a-z0-9-]{1,64}$（与对话端点同一契约）。"""
    assert (await client.post("/api/sessions?session_id=BAD_ID")).status_code == 422
    assert (await client.post("/api/sessions")).status_code == 422


# ── DELETE /api/sessions/{id} ─────────────────────────────────

async def test_delete_session_purges_checkpoint_thread(client, tmp_db, purged):
    """删会话连 LLM 上下文线程一起清——否则界面空了、小汐的记忆没空。"""
    from memory import chat_archive

    await chat_archive.record_user_message("s-gone", "在吗")

    r = await client.delete("/api/sessions/s-gone")
    assert r.status_code == 200
    assert purged == ["s-gone"]
    assert (await client.get("/api/sessions")).json()["sessions"] == []


async def test_delete_missing_session_404(client, tmp_db, purged):
    """删不存在的会话 → 404，且不触发线程清理。"""
    r = await client.delete("/api/sessions/s-never")
    assert r.status_code == 404
    assert purged == []


async def test_delete_session_rejects_bad_id(client, tmp_db):
    assert (await client.delete("/api/sessions/BAD_ID")).status_code == 422


# ── GET /api/sessions 列表契约 ────────────────────────────────

async def test_list_returns_kind_and_timestamps(client, tmp_db):
    """列表必须带 kind（前端按它出「碎碎念」标签）与两个时间戳（时间分组）。"""
    from memory import chat_archive

    await chat_archive.record_user_message("s-1", "今天过得怎么样")

    session = (await client.get("/api/sessions")).json()["sessions"][0]
    assert session["kind"] == "chat"
    assert session["is_era"] is False
    assert "created_at" in session and "updated_at" in session
    assert session["preview"] == "今天过得怎么样"


async def test_list_era_session_carries_solo_kind(client, tmp_db):
    """小汐独处的会话 kind='solo'——前端据此出「碎碎念」标签。"""
    from memory import chat_archive

    await chat_archive.record_solo_message("e-1", "我在看云。", is_era=1)

    session = (await client.get("/api/sessions")).json()["sessions"][0]
    assert session["kind"] == "solo"
    assert session["title"] == "小汐的碎碎念"


async def test_messages_endpoint_returns_created_at(client, tmp_db):
    """回看接口带 created_at——会话内的时间分隔条全靠它。"""
    from memory import chat_archive

    await chat_archive.record_user_message("s-t", "在吗")
    await chat_archive.record_assistant_message("s-t", "在的")

    msgs = (await client.get("/api/sessions/s-t/messages")).json()["messages"]
    assert [m["role"] for m in msgs] == ["user", "assistant"]
    assert all(m["created_at"] for m in msgs)
