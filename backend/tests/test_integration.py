"""端到端集成测试 — 验证 HTTP 请求 → 中间件 → 路由 → 响应的完整链路。

使用 httpx.ASGITransport 直接对接 FastAPI app，无需启动真实服务器。
使用 FakeLLM 替代 DeepSeek，确保测试不依赖外部 API。
"""
import json

import pytest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

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


@pytest.fixture
async def integration_app(tmp_db):
    """创建完整的 FastAPI 测试实例（带中间件、路由、mock 组件）。"""
    from memory.manager import MemoryManager
    from memory.narrative import NarrativeBuilder

    # 组件初始化
    memory_mgr = MemoryManager(tmp_db)
    narrative_builder = NarrativeBuilder(memory_mgr)

    fake_llm = FakeLLM()

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
    import heartbeat as hb
    import memory.chat_archive as chat_archive_module
    original_soul_get_db = soul_module.get_db
    original_hb_get_db = hb.get_db
    original_archive_get_db = chat_archive_module.get_db
    soul_module.get_db = mock_get_db
    hb.get_db = mock_get_db
    chat_archive_module.get_db = mock_get_db

    yield app

    # 恢复
    soul_module.get_db = original_soul_get_db
    hb.get_db = original_hb_get_db
    chat_archive_module.get_db = original_archive_get_db


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


async def test_chat_history_returns_messages(client, integration_app):
    """GET /api/chat/history 从 checkpoint 恢复对话（P1-1）。

    GPS 系统信息后缀应被剥离，纯工具调用的空 AIMessage 应被跳过。
    """
    from types import SimpleNamespace
    from langchain_core.messages import AIMessage, HumanMessage

    gps_suffix = (
        "\n\n[系统信息：冷冷当前 GPS 坐标：纬度 22.3，经度 114.1"
        "（地图工具的 location 参数请用「经度,纬度」格式：114.1,22.3）。]"
    )
    integration_app.state.agent.aget_state = AsyncMock(return_value=SimpleNamespace(values={
        "messages": [
            HumanMessage(content="你好呀" + gps_suffix),
            AIMessage(content="你好！小汐在哦~"),
            AIMessage(content=""),  # 纯工具调用，无文本 → 跳过
        ]
    }))

    resp = await client.get("/api/chat/history?session_id=hist-1")
    assert resp.status_code == 200
    data = resp.json()
    assert data["messages"] == [
        {"role": "user", "content": "你好呀"},        # GPS 后缀已剥离
        {"role": "assistant", "content": "你好！小汐在哦~"},
    ]


async def test_chat_history_empty_thread(client, integration_app):
    """不存在的会话返回空列表（刷新一个全新 session 不报错）。"""
    integration_app.state.agent.aget_state = AsyncMock(return_value=None)
    resp = await client.get("/api/chat/history?session_id=brand-new")
    assert resp.status_code == 200
    assert resp.json()["messages"] == []


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
    from config.settings import get_settings
    from api.server import create_app

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
    from config.settings import get_settings
    from api.server import create_app

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
