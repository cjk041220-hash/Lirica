"""端到端集成测试 — 验证 HTTP 请求 → 中间件 → 路由 → 响应的完整链路。

使用 httpx.ASGITransport 直接对接 FastAPI app，无需启动真实服务器。
使用 FakeLLM 替代 DeepSeek，确保测试不依赖外部 API。
"""
import asyncio
import json
from unittest.mock import AsyncMock, patch

import httpx
import pytest
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.middleware import AuthAndRateLimitMiddleware, RejectWebSocketMiddleware
from api.routes.chat import router as chat_router
from api.routes.config import router as config_router
from api.routes.sessions import router as sessions_router

# ── Fake LLM ──────────────────────────────────────────────────

class FakeLLM:
    """模拟 LangChain ChatModel，返回预设回复。"""

    def __init__(self, reply: str = "嘿嘿，小汐在这里陪着你哦~"):
        self._reply = reply

    async def ainvoke(self, messages, **kwargs):
        from langchain_core.messages import AIMessage
        return AIMessage(content=self._reply)

    async def astream_events(self, input_data, config=None, **kwargs):
        """模拟 SSE 流式输出，逐字发送。"""
        for char in self._reply:
            yield {
                "event": "on_chat_model_stream",
                "data": {"chunk": type("Chunk", (), {"content": char})()},
            }


# ── Fixtures ──────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def _clean_rate_buckets(monkeypatch):
    """每个用例独立的限流桶——限流器是模块级全局，跨用例共享会误触 429。"""
    from api.middleware import auth as auth_mod
    monkeypatch.setattr(auth_mod, "_rate_buckets", {})


@pytest.fixture(autouse=True)
def _clean_location_cache():
    """每个用例前后清空 GPS 会话缓存——location_store 是进程级全局，
    GPS 用例留下的坐标会污染其他用例（如早安天气路径的 get_any）。"""
    from memory import location_store
    location_store._location_cache.clear()
    yield
    location_store._location_cache.clear()


@pytest.fixture
async def integration_app(tmp_db, monkeypatch):
    """创建完整的 FastAPI 测试实例（带中间件、路由、mock 组件）。

    tmp_db 是通过 monkeypatch 注入各模块的 get_db 接入的（2026-08-30 改）。
    此前用「手动保存旧值 → 夹具拆时还原」，与用例体内的 monkeypatch 组合
    会永久泄漏：finalizer 按注册的逆序执行，夹具的手动还原**先**跑（还
    原成真身），随后 monkeypatch.undo() **后**跑，把它记下的旧值——即本
    夹具的 mock_get_db——又装了回去。于是整个 session 后续所有调用
    chat_archive.get_db() 的用例都拿到本夹具那个已关闭的 tmp_db
    （ValueError: no active connection）。改用 monkeypatch 后，undo 按
    setattr 的逆序进行，最终稳定还原成真身。
    """
    from memory.manager import MemoryManager
    from memory.narrative import NarrativeBuilder

    # 组件初始化
    memory_mgr = MemoryManager(tmp_db)
    narrative_builder = NarrativeBuilder(memory_mgr)


    # 创建 app
    app = FastAPI()

    # CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:3000"],
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Content-Type", "Authorization"],
    )
    app.add_middleware(RejectWebSocketMiddleware)
    app.add_middleware(AuthAndRateLimitMiddleware, api_token="")

    # 路由
    app.include_router(chat_router, prefix="/api")
    app.include_router(config_router, prefix="/api")
    app.include_router(sessions_router, prefix="/api")

    # 注入 state
    app.state.agent = None  # 会在下面用 mock 替代
    app.state.memory_manager = memory_mgr
    app.state.narrative_builder = narrative_builder
    app.state.tools = []
    app.state.checkpointer = None

    # mock agent
    mock_agent = AsyncMock()

    from langchain_core.messages import AIMessage
    mock_agent.ainvoke = AsyncMock(return_value={
        "messages": [AIMessage(content="嘿嘿，小汐在这里陪着你哦~")]
    })

    async def fake_stream(*args, **kwargs):
        for char in "嘿嘿，小汐在这里陪着你哦~":
            yield {
                "event": "on_chat_model_stream",
                "data": {"chunk": type("Chunk", (), {"content": char})()},
            }

    mock_agent.astream_events = fake_stream
    app.state.agent = mock_agent

    # mock get_db for soul.py / heartbeat / chat_archive
    # （chat_archive 必须接到 tmp_db，否则对话路径会把测试消息
    #  写进 backend/data/userdata.db 生产库）
    async def mock_get_db():
        return tmp_db

    import agent.soul as soul_module
    import api.routes.chat as chat_route_module
    import heartbeat as hb
    import memory.chat_archive as chat_archive_module

    # Spec B：care 流水线（care_ledger / chat_messages 时间戳 / 活跃事件）
    # 同样必须接到 tmp_db，否则对话路径会读/写穿生产库
    import agent.care as care_module
    import utils.events as events_module

    # 一律走 monkeypatch：与用例体内的 monkeypatch 共享同一个 undo 栈，
    # 逆序还原，不会互相覆盖（详见本夹具 docstring）。
    for _mod in (
        soul_module,
        hb,
        chat_archive_module,
        chat_route_module,
        care_module,
        events_module,
    ):
        monkeypatch.setattr(_mod, "get_db", mock_get_db)

    yield app


@pytest.fixture
async def client(integration_app):
    """httpx 异步测试客户端。"""
    transport = httpx.ASGITransport(app=integration_app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


# ── 测试 ──────────────────────────────────────────────────────

async def test_health_check(client):
    """GET /api/health 返回 200 + status。"""
    resp = await client.get("/api/health")
    assert resp.status_code == 200
    data = resp.json()
    assert "status" in data


async def test_health_check_head_allowed(client):
    """HEAD /api/health 也返回 200（体检 P2-8 回归）。

    FastAPI 0.139 会用 route.methods 重置抹掉 Starlette 自动追加的
    HEAD，导致 HEAD 探针误报 404；health 端点显式声明 GET+HEAD。
    """
    resp = await client.head("/api/health")
    assert resp.status_code == 200


async def test_get_config(client):
    """GET /api/config 返回模型列表和思考强度。"""
    resp = await client.get("/api/config")
    assert resp.status_code == 200
    data = resp.json()
    assert "model" in data
    assert "available_models" in data
    assert "thinking_intensity" in data
    assert len(data["available_models"]) == 2  # V4 Flash + V4 Pro


async def test_chat_non_stream(client):
    """POST /api/chat 返回完整回复。"""
    resp = await client.post("/api/chat", json={
        "message": "小汐，你在吗？",
        "session_id": "test-session",
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "reply" in data
    assert "session_id" in data
    assert len(data["reply"]) > 0


async def test_chat_stream(client):
    """POST /api/chat/stream 返回 SSE 流。"""
    resp = await client.post("/api/chat/stream", json={
        "message": "小汐你好呀~",
        "session_id": "test-stream",
    })
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")

    # 读取 SSE 流
    content = ""
    async for line in resp.aiter_lines():
        if line.startswith("data: "):
            payload = line[6:].strip()
            if payload == "[DONE]":
                break
            try:
                event = json.loads(payload)
                if "content" in event:
                    content += event["content"]
            except json.JSONDecodeError:
                continue

    assert len(content) > 0


async def test_chat_stream_sse_keepalive_ping(client, integration_app, monkeypatch):
    """静默期应周期性下发 `: ping` 注释行保活（评审 P1-B2）。

    长 LLM 调用（重试链最坏 ~240s 无事件）期间连接完全静默，中间链路
    会提前掐线。把 ping 间隔缩到 0.05s、上游制造 0.3s 静默，断言流里
    出现 ping 且真实内容最终照常送达。
    """
    import api.routes.chat as chat_mod
    monkeypatch.setattr(chat_mod, "_SSE_PING_INTERVAL", 0.05)

    async def silent_then_speak(*args, **kwargs):
        await asyncio.sleep(0.3)  # 模拟 LLM 首跳静默，> 2 个 ping 周期
        yield {
            "event": "on_chat_model_stream",
            "data": {"chunk": type("Chunk", (), {"content": "来啦"})()},
        }

    integration_app.state.agent.astream_events = silent_then_speak
    resp = await client.post("/api/chat/stream", json={
        "message": "在吗", "session_id": "test-ping",
    })
    assert resp.status_code == 200
    body = resp.text
    assert ": ping" in body
    assert "来啦" in body
    assert body.rstrip().endswith("data: [DONE]")


async def test_chat_stream_idle_timeout_still_breaks(client, integration_app, monkeypatch):
    """ping 不重置空闲总闸：累计静默超 _SSE_IDLE_TIMEOUT 仍按断连止损。"""
    import time

    import api.routes.chat as chat_mod
    monkeypatch.setattr(chat_mod, "_SSE_PING_INTERVAL", 0.05)
    monkeypatch.setattr(chat_mod, "_SSE_IDLE_TIMEOUT", 0.15)

    async def never_speaks(*args, **kwargs):
        await asyncio.sleep(30)
        yield {"event": "on_chat_model_stream",
               "data": {"chunk": type("Chunk", (), {"content": "不该到达"})()}}

    integration_app.state.agent.astream_events = never_speaks
    start = time.monotonic()
    resp = await client.post("/api/chat/stream", json={
        "message": "在吗", "session_id": "test-idle-break",
    })
    elapsed = time.monotonic() - start
    assert resp.status_code == 200
    assert "不该到达" not in resp.text
    assert resp.text.rstrip().endswith("data: [DONE]")
    assert elapsed < 5  # 秒级止损，而非等满 30s


async def test_health_deep_diagnostics(client):
    """健康检查已认证分支含心跳与提取水位线诊断字段（评审 P1-B3）。"""
    resp = await client.get("/api/health")
    assert resp.status_code == 200
    data = resp.json()
    # 测试环境 api_token="" → 视为开发模式已认证，走完整诊断分支
    assert "heartbeat_running" in data
    assert "extract_watermark" in data
    assert "extract_lag" in data
    assert data["extract_watermark"] == 0   # 空库未跑过提取
    assert data["extract_lag"] == 0


async def test_memories_list(client):
    """GET /api/memories 返回空列表。"""
    resp = await client.get("/api/memories")
    assert resp.status_code == 200
    data = resp.json()
    assert "memories" in data
    assert "count" in data


async def test_memories_export(client):
    """GET /api/memories/export 返回 JSON。"""
    resp = await client.get("/api/memories/export")
    assert resp.status_code == 200


async def test_cors_headers(client):
    """OPTIONS 请求返回正确的 CORS 头。"""
    resp = await client.options(
        "/api/chat",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "POST",
        },
    )
    assert resp.status_code == 200
    assert "access-control-allow-origin" in resp.headers


async def test_chat_empty_message_rejected(client):
    """空消息被拒绝（422）。"""
    resp = await client.post("/api/chat", json={
        "message": "   ",
        "session_id": "test-empty",
    })
    assert resp.status_code == 422


async def test_chat_message_too_long(client):
    """超长消息被拒绝（422）。"""
    resp = await client.post("/api/chat", json={
        "message": "a" * 2001,
        "session_id": "test-long",
    })
    assert resp.status_code == 422


def test_session_lock_reused_per_session():
    """同 session 复用同一把锁（P1-3 并发互斥的基础）。"""
    from api.routes.chat import _session_lock

    assert _session_lock("same-id") is _session_lock("same-id")
    assert _session_lock("id-a") is not _session_lock("id-b")


# ── 中间件顺序回归（CORS 必须在最外层）──────────────────────

async def test_auth_401_carries_cors_headers(monkeypatch):
    """设 token 后跨域请求被 401 拒绝时，响应必须带 CORS 头。

    此前中间件注册顺序使 Auth 位于 CORS 外层，401/429 短路响应不带
    Access-Control-Allow-Origin → 跨域前端只看到不透明网络错误，
    401 引导流程（UNAUTHORIZED_EVENT → 设置页填令牌）整体失效。
    用真实 create_app 的中间件栈验证（不走 lifespan，不碰 DB）。
    """
    from api.server import create_app
    from config.settings import get_settings

    s = get_settings()
    monkeypatch.setattr(s, "xiaoxi_api_token", "test-token")
    monkeypatch.setattr(s, "cors_origins", "http://localhost:3000")

    app = create_app()
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/api/config", headers={"Origin": "http://localhost:3000"})

    assert r.status_code == 401
    assert r.headers.get("access-control-allow-origin") == "http://localhost:3000"


async def test_docs_closed_when_token_set(monkeypatch):
    """设了 token（= 生产意图）→ /docs 自动关闭，避免 API schema 公网裸奔。"""
    from api.server import create_app
    from config.settings import get_settings

    s = get_settings()
    monkeypatch.setattr(s, "xiaoxi_api_token", "test-token")

    app = create_app()
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/docs")
        r_openapi = await c.get("/openapi.json")

    assert r.status_code == 404
    assert r_openapi.status_code == 404


# ── GPS 注入措辞（同时服务天气分参与地图整串）────────────────

def test_location_suffix_serves_both_tools():
    """注入文本须同时含带标签的纬度/经度（天气分参）与经度在前整串（地图 location）。"""
    from api.routes.chat import _location_suffix

    s = _location_suffix(22.3, 114.1)
    assert "纬度 22.3" in s
    assert "经度 114.1" in s
    assert "114.1,22.3" in s
    # 与 _strip_location_suffix 的切分前缀耦合
    assert s.startswith("\n\n[系统信息：")


# ── Spec B：当前时间注入（请求路径地基）──────────────────────


async def test_chat_injects_time_block_with_period_word(client, integration_app):
    """请求路径注入当前时间块：日期 + 星期 + 时分 + 时段词，与 GPS 同居一块。"""
    import datetime as dt

    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)  # 周三凌晨

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "睡不着", "session_id": "s-time",
            "latitude": 22.3, "longitude": 114.17,
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 02:14，深夜。" in content
    assert "GPS 坐标" in content
    # 同一个 [系统信息：…] 块：一个前缀、一个收尾括号
    assert content.count("[系统信息：") == 1
    assert content.rstrip().endswith("]")


# ── Spec B：异常感知关心注入（深夜/冷却/降级）──────────────────


async def test_chat_late_night_injects_care_hint_and_records_ledger(
    client, integration_app, tmp_db
):
    """深夜请求：时间块 + 关心提示同居一块；注入即记账（台账落库）。"""
    import datetime as dt

    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "睡不着", "session_id": "s-care-1",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 02:14，深夜。" in content
    assert "冷冷现在还没睡。" in content
    assert content.count("[系统信息：") == 1   # 与时间行同居一块

    cursor = await tmp_db.execute(
        "SELECT last_at FROM care_ledger WHERE type = 'late_night'"
    )
    row = await cursor.fetchone()
    assert row is not None and row[0] == "2026-08-05 02:14"  # 注入即记账


async def test_chat_late_night_second_request_cooled_down(
    client, integration_app, tmp_db
):
    """冷却台账生效：72h 内第二个深夜不再注入 late_night 提示。"""
    import datetime as dt

    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        await client.post("/api/chat/stream", json={
            "message": "第一条", "session_id": "s-care-2",
        })
        resp = await client.post("/api/chat/stream", json={
            "message": "第二条", "session_id": "s-care-2",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是" in content        # 时间块永远在场（层次设计）
    assert "还没睡" not in content    # 提示被 72h 冷却滤掉


async def test_chat_daytime_only_time_block(client, integration_app, tmp_db):
    """白天请求：只注入时间块，无关心提示，不落台账。"""
    import datetime as dt

    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 15, 0, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "下午好", "session_id": "s-care-day",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 15:00，下午。" in content
    assert "还没睡" not in content

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM care_ledger")
    assert (await cursor.fetchone())[0] == 0


async def test_chat_care_pipeline_failure_degrades(client, integration_app, monkeypatch):
    """care 读库全炸 → 时间块照注入、提示省略，对话不阻断。"""
    import datetime as dt

    from agent import care
    from utils.timezone import HKT

    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(care, "get_db", broken_get_db)

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "在吗", "session_id": "s-care-broken",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是" in content
    assert "还没睡" not in content


async def test_chat_rest_path_also_injects_time_block(client, integration_app):
    """REST /api/chat 与 SSE 共用单一入口：同样带时间块。"""
    import datetime as dt

    from utils.timezone import HKT

    fake_now = dt.datetime(2026, 8, 5, 15, 0, tzinfo=HKT)
    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat", json={
            "message": "你好", "session_id": "s-rest-time",
        })
    assert resp.status_code == 200

    call = integration_app.state.agent.ainvoke.await_args
    content = call[0][0]["messages"][0].content
    assert "现在是 2026-08-05 周三 15:00，下午。" in content


# ── SSE 错误事件（失败不伪装成成功回复）──────────────────────

async def _collect_sse(resp):
    """收集 SSE 行为 (events, done) 列表。"""
    events = []
    done = False
    async for line in resp.aiter_lines():
        if not line.startswith("data: "):
            continue
        payload = line[6:].strip()
        if payload == "[DONE]":
            done = True
            break
        try:
            events.append(json.loads(payload))
        except json.JSONDecodeError:
            continue
    return events, done


async def test_chat_stream_agent_not_ready_error_event(client, integration_app):
    """agent 未就绪 → 下发 type=error 事件（而非伪装成正常 content）。"""
    integration_app.state.agent = None
    resp = await client.post("/api/chat/stream", json={
        "message": "小汐？", "session_id": "not-ready",
    })
    assert resp.status_code == 200
    events, done = await _collect_sse(resp)
    assert done
    assert any(e.get("type") == "error" and e.get("content") for e in events)
    # 错误事件不带裸 content 键（前端据此区分成功/失败）
    assert not any("content" in e and "type" not in e for e in events)
    # agent 未就绪 = 对话没有发生 → 不归档
    messages = (
        await client.get("/api/sessions/not-ready/messages")
    ).json()["messages"]
    assert messages == []


async def test_chat_stream_internal_error_event(client, integration_app):
    """流内异常 → 下发 type=error 事件 + [DONE] 收尾。"""
    async def broken_stream(*args, **kwargs):
        yield {
            "event": "on_chat_model_stream",
            "data": {"chunk": type("Chunk", (), {"content": "嘿"})()},
        }
        raise RuntimeError("boom")

    integration_app.state.agent.astream_events = broken_stream
    resp = await client.post("/api/chat/stream", json={
        "message": "小汐？", "session_id": "broken-stream",
    })
    assert resp.status_code == 200
    events, done = await _collect_sse(resp)
    assert done
    assert any(e.get("type") == "error" and "浪花" in e.get("content", "") for e in events)

    # 流中途炸了，用户看到的部分回复（「嘿」）照样归档（finally flush）
    messages = (
        await client.get("/api/sessions/broken-stream/messages")
    ).json()["messages"]
    assert [(m["role"], m["content"]) for m in messages] == [
        ("user", "小汐？"), ("assistant", "嘿"),
    ]


async def test_chat_non_stream_agent_not_ready_returns_503(client, integration_app):
    """agent 未就绪 → 非流式聊天返回 503 优雅降级（不是 500）。

    回归：此前 `agent is None` 检查放在 try 之内，HTTPException(503)
    继承自 Exception，被外层 `except Exception` 兜底改写为 500——
    启动窗口期的优雅降级永远不生效，还每次打一条误导性的堆栈日志。
    """
    integration_app.state.agent = None
    resp = await client.post("/api/chat", json={
        "message": "小汐？", "session_id": "not-ready-rest",
    })
    assert resp.status_code == 503
    assert "睡觉" in resp.json().get("detail", "")
    # 503 在归档之前抛出 → 不归档
    messages = (
        await client.get("/api/sessions/not-ready-rest/messages")
    ).json()["messages"]
    assert messages == []


# ── 对话档案（迁移 v8）───────────────────────────────────────


async def test_chat_archives_session_and_messages(client):
    """非流式对话：会话行 + 用户/助手消息落档，列表与回看可见。"""
    resp = await client.post("/api/chat", json={
        "message": "小汐，你在吗？",
        "session_id": "arch-1",
    })
    assert resp.status_code == 200

    resp = await client.get("/api/sessions")
    sessions = resp.json()["sessions"]
    assert any(
        s["id"] == "arch-1" and s["title"] == "小汐，你在吗？"
        for s in sessions
    )

    resp = await client.get("/api/sessions/arch-1/messages")
    messages = resp.json()["messages"]
    assert [m["role"] for m in messages] == ["user", "assistant"]
    assert messages[0]["content"] == "小汐，你在吗？"
    assert messages[1]["content"] == "嘿嘿，小汐在这里陪着你哦~"


async def test_chat_stream_archives_full_reply(client):
    """流式对话：finally 落库完整回复（逐字流也一字不落）。"""
    resp = await client.post("/api/chat/stream", json={
        "message": "讲个故事吧",
        "session_id": "arch-2",
    })
    # 消费到 [DONE]，确保 generate() 的 finally 跑完再断言
    events, done = await _collect_sse(resp)
    assert done

    messages = (
        await client.get("/api/sessions/arch-2/messages")
    ).json()["messages"]
    assert messages[-1]["role"] == "assistant"
    assert messages[-1]["content"] == "嘿嘿，小汐在这里陪着你哦~"


async def test_chat_archive_never_stores_gps_suffix(client):
    """GPS 系统信息只进 LLM 输入，不落档案。"""
    resp = await client.post("/api/chat", json={
        "message": "附近有什么好吃的",
        "session_id": "arch-3",
        "latitude": 22.3,
        "longitude": 114.1,
    })
    assert resp.status_code == 200

    messages = (
        await client.get("/api/sessions/arch-3/messages")
    ).json()["messages"]
    assert messages[0]["content"] == "附近有什么好吃的"
    assert "[系统信息" not in messages[0]["content"]


async def test_chat_stream_archive_never_stores_gps_suffix(client):
    """流式路径同样：GPS 后缀只进 LLM 输入，不落档案。

    流式路径的后缀拼在 user_content、归档用 req.message——两个
    独立变量，重构时最容易写混，单独钉死。
    """
    resp = await client.post("/api/chat/stream", json={
        "message": "今天天气怎么样",
        "session_id": "arch-gps",
        "latitude": 22.3,
        "longitude": 114.1,
    })
    events, done = await _collect_sse(resp)
    assert done

    messages = (
        await client.get("/api/sessions/arch-gps/messages")
    ).json()["messages"]
    assert messages[0]["content"] == "今天天气怎么样"
    assert "[系统信息" not in messages[0]["content"]


# ── GPS 流式端点回归（体检「关了还在用」：修复曾只落在 REST 辅助函数，
#    前端实际走的 SSE 端点内联副本漏了 gps_enabled 分支，测试全绿但
#    生产路径开关失效。以下用例直接打真实端点、捕获图输入，钉死闭环）──


def _install_capturing_stream(app):
    """把 app.state.agent.astream_events 换成输入捕获版，返回 captured dict。

    复用现有 fake_stream 的事件形状（on_chat_model_stream + 逐字 chunk），
    额外记录 endpoint 实际喂给图的 input_data。
    """
    captured = {}

    async def capturing_stream(input_data, config=None, **kwargs):
        captured["input"] = input_data
        for char in "好":
            yield {
                "event": "on_chat_model_stream",
                "data": {"chunk": type("Chunk", (), {"content": char})()},
            }

    app.state.agent.astream_events = capturing_stream
    return captured


async def test_chat_stream_gps_disabled_clears_cache_and_skips_injection(
    client, integration_app
):
    """SSE 端点收到 gps_enabled=false：清缓存 + 图输入不带坐标后缀。

    预置 8h TTL 旧缓存模拟「用户刚关掉 GPS」，断言三件事：
    ① 图输入 content 以原始消息开头、无 GPS 行（Spec B 起时间块永远
       在场——关 GPS 关不掉时钟）；
    ② 本会话缓存被清；
    ③ get_any() 也读不到——钉死早安问候天气的降级契约
       （heartbeat 用 get_any 读坐标，关 GPS 后应自动降级默认坐标文案）。
    """
    from memory import location_store

    await location_store.update("s-stream-off", 22.3, 114.17)
    captured = _install_capturing_stream(integration_app)

    resp = await client.post("/api/chat/stream", json={
        "message": "陪我说说话",
        "session_id": "s-stream-off",
        "gps_enabled": False,
    })
    events, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert content.startswith("陪我说说话")
    assert "GPS 坐标" not in content
    assert "现在是" in content           # 时间块永远在场（Spec B）
    assert await location_store.get("s-stream-off") is None
    assert await location_store.get_any() is None


async def test_chat_stream_gps_enabled_updates_and_injects(client, integration_app):
    """SSE 端点 gps_enabled=true 带坐标：更新缓存 + 图输入含坐标后缀。"""
    from memory import location_store

    captured = _install_capturing_stream(integration_app)

    resp = await client.post("/api/chat/stream", json={
        "message": "附近有什么好玩的",
        "session_id": "s-stream-on",
        "latitude": 22.3,
        "longitude": 114.17,
        "gps_enabled": True,
    })
    events, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "GPS 坐标" in content
    assert "114.17,22.3" in content
    assert await location_store.get("s-stream-on") == (22.3, 114.17)


async def test_chat_survives_archive_failure(client, monkeypatch):
    """归档写入失败只记日志，不拖垮对话（chat_archive 韧性契约）。"""
    import memory.chat_archive as chat_archive_mod

    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(chat_archive_mod, "get_db", broken_get_db)

    resp = await client.post("/api/chat", json={
        "message": "还在吗", "session_id": "arch-fail",
    })
    assert resp.status_code == 200
    assert resp.json()["reply"]

    resp = await client.post("/api/chat/stream", json={
        "message": "还在吗", "session_id": "arch-fail-2",
    })
    events, done = await _collect_sse(resp)
    assert done
    assert any("content" in e for e in events)


async def test_session_delete_cascade_and_404(client):
    """DELETE 级联删除会话与消息；重复删除返回 404。"""
    await client.post("/api/chat", json={
        "message": "短对话", "session_id": "arch-del",
    })

    resp = await client.delete("/api/sessions/arch-del")
    assert resp.status_code == 200
    resp = await client.get("/api/sessions/arch-del/messages")
    assert resp.json()["messages"] == []

    resp = await client.delete("/api/sessions/arch-del")
    assert resp.status_code == 404


async def test_chat_cross_session_proactive_awareness_integration(client, integration_app, tmp_db):
    """端到端集成验证：主动消息发出后，冷冷在不同 session 回复，系统信息正确注入主动消息感知。"""
    import datetime
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT).strftime("%Y-%m-%d %H:%M")
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, session_id) "
        "VALUES ('msg_int_care', '在吗在吗？冷冷，你知道 JK 怎么了吗？', ?, 'delivered', 'e-proactive-sid')",
        (now,),
    )
    await tmp_db.commit()

    captured = _install_capturing_stream(integration_app)
    resp = await client.post("/api/chat/stream", json={
        "message": "咋了",
        "session_id": "s-cross-reply",
    })
    events, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "在吗在吗？冷冷，你知道 JK 怎么了吗？" in content
    assert "主动向冷冷发过消息" in content or "主动给冷冷发过消息" in content


async def test_security_headers_present():
    """真实 create_app 中间件栈：响应带安全头（公网纵深防御）。

    用纯 ASGI 中间件而非 BaseHTTPMiddleware——后者缓冲响应体会弄坏 SSE，
    这里顺带验证 /api/health 的响应头注入正常（流式端点由 E2E 兜底验证）。
    """
    from api.server import create_app

    app = create_app()
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/api/health")
    assert r.status_code == 200
    assert r.headers["x-frame-options"] == "DENY"
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["referrer-policy"] == "strict-origin-when-cross-origin"
    csp = r.headers["content-security-policy"]
    assert "frame-ancestors 'none'" in csp
    assert "script-src 'self'" in csp
