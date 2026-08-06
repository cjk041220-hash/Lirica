"""日历路由测试 — /api/events（冷冷的手动入口，与小汐的 LLM 工具一本账）。

httpx.AsyncClient + ASGITransport 最小 app（同 test_status_routes 模式）。
重点钉死联动语义：跨入口去重（小汐先记/冷冷再加）、墓碑复活、只读保护。
"""
import datetime

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from api.routes.events import router as events_router
from utils import events as events_mgr
from utils.timezone import HKT


def _d(days: int) -> str:
    """今天 + N 天的日期串（永不老化的事件日期——写死的未来日期一过
    零点就变过去日期吃 422，2026-08-02 体检实锤）。"""
    return (
        datetime.datetime.now(HKT) + datetime.timedelta(days=days)
    ).strftime("%Y-%m-%d")


@pytest.fixture
async def client(tmp_db, monkeypatch):
    """挂载 events 路由的最小 app，注入临时数据库。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    app = FastAPI()
    app.include_router(events_router, prefix="/api")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


_BDAY_ID = "bdaa000000000001"  # 16 位 hex（uuid4().hex[:16] 形态，过路由 pattern）


async def _seed_birthday(db):
    """种一条只读的生日事件（系统派生型）。"""
    await db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at, dedupe_key) VALUES (?, '冷冷的生日', "
        "'birthday', '2026-08-03', 'high', 'profile', 'active', "
        "'2026-07-25 10:00', 'profile:birthday:2026-08-03')",
        (_BDAY_ID,),
    )
    await db.commit()


# ── GET 月范围 ────────────────────────────────────────────────


async def test_get_range_returns_schedule_types(client, tmp_db):
    await events_mgr.create_event("期末考试", "exam", _d(7))
    r = await client.get("/api/events", params={"from": _d(3), "to": "2026-08-31"})
    assert r.status_code == 200
    data = r.json()
    assert data["count"] == 1
    assert data["events"][0]["title"] == "期末考试"


async def test_get_range_rejects_reversed(client):
    r = await client.get("/api/events", params={"from": "2026-09-30", "to": "2026-09-01"})
    assert r.status_code == 422


async def test_get_range_rejects_bad_format(client):
    r = await client.get("/api/events", params={"from": "x", "to": "2026-09-01"})
    assert r.status_code == 422


async def test_get_range_includes_archived_for_review(client, tmp_db):
    """过往月份可翻看：archived 行程在范围内照常返回。"""
    await tmp_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('arc0000000000001', '期中考过了', 'exam', "
        "'2026-07-10', 'normal', 'user', 'archived', '2026-07-01 10:00')"
    )
    await tmp_db.commit()
    r = await client.get("/api/events", params={"from": "2026-07-01", "to": "2026-07-31"})
    assert [e["id"] for e in r.json()["events"]] == ["arc0000000000001"]


# ── POST 创建 ─────────────────────────────────────────────────


async def test_create_happy_path(client, tmp_db):
    r = await client.post("/api/events", json={
        "title": "周末去爬山", "event_date": _d(10), "event_type": "trip",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["created"] is True
    assert data["event"]["source"] == "user"          # 日历入口的来源标记
    assert data["event"]["status"] == "active"


async def test_create_dedupes_with_llm_entry(client, tmp_db):
    """联动剧本 B 的后端半边：小汐先在聊天里记过 → 冷冷再加同件 → 一本账不重复。"""
    saved, _ = await events_mgr.create_event(
        "台北旅行", "trip", _d(12), source="llm"
    )
    r = await client.post("/api/events", json={"title": "台北旅行", "event_date": _d(12)})
    assert r.status_code == 200
    data = r.json()
    assert data["created"] is False
    assert data["event"]["id"] == saved["id"]          # 命中的是小汐那条，不是新建

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM events")
    assert (await cursor.fetchone())[0] == 1


async def test_create_similar_conflict_then_confirm(client, tmp_db):
    """措辞变体走相似提示：首提 409 带 similar，confirm=true 放行。"""
    await events_mgr.create_event("期中考试", "exam", _d(3))

    r1 = await client.post("/api/events", json={"title": "期中", "event_date": _d(3)})
    assert r1.status_code == 409
    detail = r1.json()["detail"]
    assert detail["similar"]["title"] == "期中考试"

    r2 = await client.post("/api/events", json={
        "title": "期中", "event_date": _d(3), "confirm": True,
    })
    assert r2.status_code == 200
    assert r2.json()["created"] is True


async def test_create_rejects_past_date(client):
    r = await client.post("/api/events", json={"title": "旧事", "event_date": "2020-01-01"})
    assert r.status_code == 422
    assert "过去" in r.json()["detail"]


async def test_create_rejects_bad_type_and_date(client):
    assert (await client.post("/api/events", json={
        "title": "x", "event_date": _d(3), "event_type": "生日！！",
    })).status_code == 422
    assert (await client.post("/api/events", json={
        "title": "x", "event_date": "2026-8-1",
    })).status_code == 422


async def test_create_end_before_start(client):
    r = await client.post("/api/events", json={
        "title": "旅行", "event_date": _d(12), "end_date": _d(7),
    })
    assert r.status_code == 422


async def test_create_readd_after_delete_revives(client, tmp_db):
    """墓碑复活：删掉后同标题同日重加 = 原 id 翻回 active（冷冷反悔）。"""
    r = await client.post("/api/events", json={"title": "牙医预约", "event_date": _d(14)})
    eid = r.json()["event"]["id"]
    assert (await client.delete(f"/api/events/{eid}")).status_code == 200

    r2 = await client.post("/api/events", json={"title": "牙医预约", "event_date": _d(14)})
    assert r2.status_code == 200
    assert r2.json()["created"] is True
    assert r2.json()["event"]["id"] == eid             # 原 id 复活

    cursor = await tmp_db.execute("SELECT status FROM events WHERE id = ?", (eid,))
    assert (await cursor.fetchone())[0] == "active"


# ── POST 更新 ─────────────────────────────────────────────────


async def test_update_fields(client, tmp_db):
    r = await client.post("/api/events", json={"title": "爬山", "event_date": _d(10)})
    eid = r.json()["event"]["id"]
    r2 = await client.post(f"/api/events/{eid}", json={
        "title": "改去游泳", "event_date": _d(11),
    })
    assert r2.status_code == 200
    ev = r2.json()["event"]
    assert ev["title"] == "改去游泳" and ev["event_date"] == _d(11)


async def test_update_readonly_403(client, tmp_db):
    await _seed_birthday(tmp_db)
    r = await client.post(f"/api/events/{_BDAY_ID}", json={"title": "改不动"})
    assert r.status_code == 403


async def test_update_bad_date_422_and_not_found_404(client, tmp_db):
    r = await client.post("/api/events", json={"title": "x", "event_date": _d(10)})
    eid = r.json()["event"]["id"]
    assert (await client.post(f"/api/events/{eid}", json={"event_date": "下周三"})).status_code == 422
    assert (await client.post("/api/events/ffffffffffffffff", json={"title": "x"})).status_code == 404


async def test_update_clears_end_date_with_empty_string(client, tmp_db):
    """P0-03 回归：清空结束日期的语义是空串（manager 约定 None=不改、
    空串=显式清空）。修复前 pattern 在路由前拒掉 "" → 日历「清掉结束
    日期」永远 422，清空操作到不了业务层。"""
    r = await client.post("/api/events", json={
        "title": "旅行", "event_date": _d(12), "end_date": _d(14),
    })
    eid = r.json()["event"]["id"]
    assert r.json()["event"]["end_date"] == _d(14)

    r2 = await client.post(f"/api/events/{eid}", json={"end_date": ""})
    assert r2.status_code == 200
    assert r2.json()["event"]["end_date"] is None

    cursor = await tmp_db.execute("SELECT end_date FROM events WHERE id = ?", (eid,))
    assert (await cursor.fetchone())[0] is None


async def test_update_end_date_bad_format_still_422(client, tmp_db):
    """清空语义放开后，非法日期仍然拒收（422 不回归）。"""
    r = await client.post("/api/events", json={"title": "x", "event_date": _d(10)})
    eid = r.json()["event"]["id"]
    assert (await client.post(f"/api/events/{eid}", json={"end_date": "下周三"})).status_code == 422
    assert (await client.post(f"/api/events/{eid}", json={"end_date": "2026-8-1"})).status_code == 422


# ── POST 完成态（v20：「办完了，别再惦记」）───────────────────


async def test_complete_marks_and_unmarks(client, tmp_db):
    r = await client.post("/api/events", json={"title": "期中考试", "event_date": _d(3)})
    eid = r.json()["event"]["id"]
    r2 = await client.post(f"/api/events/{eid}/complete", json={"completed": True})
    assert r2.status_code == 200
    assert r2.json()["event"]["completed_at"] is not None
    r3 = await client.post(f"/api/events/{eid}/complete", json={"completed": False})
    assert r3.json()["event"]["completed_at"] is None


async def test_complete_readonly_403(client, tmp_db):
    await _seed_birthday(tmp_db)
    r = await client.post(f"/api/events/{_BDAY_ID}/complete", json={"completed": True})
    assert r.status_code == 403


async def test_complete_weather_403(client, tmp_db):
    """天气事件是自动检测的预报，不是要办的事——拒勾。"""
    await tmp_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('c100000000000001', '有雷暴', "
        "'thunderstorm', ?, 'normal', 'weather', 'active', '2026-07-25 10:00')",
        (_d(5),),
    )
    await tmp_db.commit()
    r = await client.post("/api/events/c100000000000001/complete", json={"completed": True})
    assert r.status_code == 403


async def test_complete_not_found_404(client):
    r = await client.post("/api/events/ffffffffffffffff/complete", json={"completed": True})
    assert r.status_code == 404


async def test_get_range_includes_weather_and_completed(client, tmp_db):
    """日历面板默认全视图：天气事件与已完成事件都可见；关参数回到提醒读面。"""
    r = await client.post("/api/events", json={"title": "期中考试", "event_date": _d(3)})
    eid = r.json()["event"]["id"]
    await client.post(f"/api/events/{eid}/complete", json={"completed": True})
    await tmp_db.execute(
        "INSERT INTO events (id, title, event_type, event_date, severity, source, "
        "status, created_at) VALUES ('c200000000000002', '有雷暴', "
        "'thunderstorm', ?, 'normal', 'weather', 'active', '2026-07-25 10:00')",
        (_d(5),),
    )
    await tmp_db.commit()

    r2 = await client.get("/api/events", params={"from": _d(0), "to": _d(10)})
    titles = {e["title"] for e in r2.json()["events"]}
    assert {"期中考试", "有雷暴"} <= titles

    # include_completed=false → 已完成的事件从提醒读面消失，天气仍在（默认可见）
    r3 = await client.get("/api/events", params={
        "from": _d(0), "to": _d(10), "include_completed": "false",
    })
    titles3 = {e["title"] for e in r3.json()["events"]}
    assert "期中考试" not in titles3
    assert "有雷暴" in titles3


# ── DELETE 软删 ───────────────────────────────────────────────


async def test_delete_marks_tombstone(client, tmp_db):
    r = await client.post("/api/events", json={"title": "不去了", "event_date": _d(17)})
    eid = r.json()["event"]["id"]
    r2 = await client.delete(f"/api/events/{eid}")
    assert r2.status_code == 200 and r2.json()["status"] == "ok"

    cursor = await tmp_db.execute("SELECT status FROM events WHERE id = ?", (eid,))
    assert (await cursor.fetchone())[0] == "deleted"


async def test_delete_readonly_403(client, tmp_db):
    await _seed_birthday(tmp_db)
    assert (await client.delete(f"/api/events/{_BDAY_ID}")).status_code == 403


async def test_delete_not_found_404(client):
    assert (await client.delete("/api/events/ffffffffffffffff")).status_code == 404
    # 重复删（已是墓碑）同样 404
    r = await client.post("/api/events", json={"title": "x", "event_date": _d(10)})
    eid = r.json()["event"]["id"]
    await client.delete(f"/api/events/{eid}")
    assert (await client.delete(f"/api/events/{eid}")).status_code == 404
