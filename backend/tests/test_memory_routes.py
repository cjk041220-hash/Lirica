"""记忆路由回归（体检 T2-10）— /api/memories 的 clear / export / list。

修复前这些路由零测试：ec24e76「clear 响应不回显服务器路径」无回归钉，
任何人把 backup 改回完整路径测试都全绿。

使用 httpx.AsyncClient + ASGITransport 直接驱动路由（同 test_push_routes
模式，不经完整 lifespan）。**backup_before_clear 必须 mock**——路由无参
调用它，默认写真实的 backend/data/backups，不 mock 每次测试都在生产
备份目录攒垃圾。
"""
from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.chat import router as chat_router
from memory.manager import MemoryManager


@pytest.fixture
async def client(tmp_db):
    """挂载 chat 路由的最小 app，注入临时库上的 MemoryManager。"""
    app = FastAPI()
    app.state.memory_manager = MemoryManager(tmp_db)
    app.include_router(chat_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def _seed_active_and_archived(tmp_db):
    """一条活跃 + 一条冷藏（读面默认不可见，备份/回看可见）。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("日常", "活跃记忆")
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('x1', '冷藏记忆', '日常', '2026-01-01 10:00', 'archived')"
    )
    await tmp_db.commit()


# ── /memories/clear ───────────────────────────────────────────


async def test_clear_requires_confirm(client, tmp_db):
    """无 confirm=true → 400 拒绝，一条都不删。"""
    await _seed_active_and_archived(tmp_db)

    r = await client.post("/api/memories/clear")

    assert r.status_code == 400
    assert await MemoryManager(tmp_db).count() == 1  # 活跃行仍在


async def test_clear_with_confirm_empties_table(client, tmp_db, monkeypatch):
    """confirm=true → 备份后清空；cleared 含 archived；**响应只回备份
    文件名不回完整路径**（ec24e76 安全修复的回归钉）。"""
    await _seed_active_and_archived(tmp_db)
    fake_backup = AsyncMock(return_value=Path("/服务器内部/目录/memories_backup_fake.json"))
    monkeypatch.setattr(MemoryManager, "backup_before_clear", fake_backup)

    r = await client.post("/api/memories/clear", params={"confirm": True})

    assert r.status_code == 200
    body = r.json()
    fake_backup.assert_awaited_once()       # 备份先于清空
    assert body["cleared"] == 2              # 含 archived（v13 计数修复）
    assert body["backup"] == "memories_backup_fake.json"  # 只有文件名
    assert "服务器内部" not in r.text         # 路径不泄漏
    assert await MemoryManager(tmp_db).count() == 0


# ── /memories/export ──────────────────────────────────────────


async def test_export_default_excludes_archived(client, tmp_db):
    """默认导出不含冷藏（导出语义与读面一致）。"""
    await _seed_active_and_archived(tmp_db)

    r = await client.get("/api/memories/export")

    assert r.status_code == 200
    assert "attachment" in r.headers.get("content-disposition", "")
    contents = {m["content"] for m in r.json()["memories"]}
    assert contents == {"活跃记忆"}


async def test_export_include_archived(client, tmp_db):
    """include_archived=true 时冷藏行可回看（D8）。"""
    await _seed_active_and_archived(tmp_db)

    r = await client.get("/api/memories/export", params={"include_archived": True})

    contents = {m["content"] for m in r.json()["memories"]}
    assert contents == {"活跃记忆", "冷藏记忆"}


async def test_export_contains_all_columns(client, tmp_db):
    """导出是 SELECT * 全列——source/prev_content/hit_count 等审计字段
    不得从导出漏掉（有人把 SELECT * 改成列清单时这条会红）。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("日常", "有来源的记忆", source="chat:s-test")

    r = await client.get("/api/memories/export")

    row = r.json()["memories"][0]
    for col in ("id", "content", "category", "created_at", "access_count",
                "hit_count", "last_hit_date", "status", "source"):
        assert col in row, f"导出缺列：{col}"
    assert row["source"] == "chat:s-test"


# ── /memories/{id} 编辑与删除（体检 R4：记忆主权）────────────


async def test_patch_memory_updates_content(client, tmp_db):
    """PATCH 改记忆内容：覆盖语义（旧内容落 superseded 历史链）。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("冷冷的信息", "冷冷爱吃火锅")

    r = await client.request(
        "PATCH", f"/api/memories/{saved['id']}", json={"content": "冷冷不爱吃火锅了"}
    )
    assert r.status_code == 200

    cursor = await tmp_db.execute(
        "SELECT content FROM memories WHERE id = ?", (saved["id"],)
    )
    assert (await cursor.fetchone())[0] == "冷冷不爱吃火锅了"
    # 旧说法进了历史链（v15），没丢
    hist = await mgr.history(saved["id"])
    assert [h["content"] for h in hist] == ["冷冷爱吃火锅"]


async def test_patch_memory_not_found(client, tmp_db):
    r = await client.request(
        "PATCH", "/api/memories/deadbeefdeadbeef", json={"content": "新内容"}
    )
    assert r.status_code == 404


async def test_patch_memory_empty_content_rejected(client, tmp_db):
    """空内容 422（pydantic 校验，不走 update_content 的 False 路径）。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("日常", "记忆")

    r = await client.request(
        "PATCH", f"/api/memories/{saved['id']}", json={"content": "   "}
    )
    assert r.status_code == 422


async def test_delete_memory_route(client, tmp_db):
    """DELETE 删除记忆 + remaining 计数。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("日常", "要删的记忆")

    r = await client.request("DELETE", f"/api/memories/{saved['id']}")

    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert await mgr.count() == 0


async def test_delete_memory_not_found(client, tmp_db):
    r = await client.request("DELETE", "/api/memories/deadbeefdeadbeef")
    assert r.status_code == 404


# ── /memories（列表）──────────────────────────────────────────


async def test_export_include_superseded(client, tmp_db):
    """O3：导出路由支持 include_superseded=true（历史链可回看）。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("冷冷的信息", "旧说法")
    await mgr.update_content(saved["id"], "新说法")

    r = await client.get("/api/memories/export", params={"include_superseded": True})

    contents = {m["content"] for m in r.json()["memories"]}
    assert {"旧说法", "新说法"} <= contents


async def test_list_default_excludes_archived(client, tmp_db):
    """面板默认视图不含冷藏。"""
    await _seed_active_and_archived(tmp_db)

    r = await client.get("/api/memories")

    body = r.json()
    assert body["count"] == 1
    assert body["memories"][0]["content"] == "活跃记忆"


async def test_list_include_archived(client, tmp_db):
    """面板「已归档」标签页（include_archived=true）可见冷藏行。"""
    await _seed_active_and_archived(tmp_db)

    r = await client.get("/api/memories", params={"include_archived": True})

    body = r.json()
    assert body["count"] == 2
    assert {m["content"] for m in body["memories"]} == {"活跃记忆", "冷藏记忆"}
