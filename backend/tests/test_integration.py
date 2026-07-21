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

    # mock get_db for soul.py
    async def mock_get_db():
        return tmp_db

    import agent.soul as soul_module
    import heartbeat as hb
    original_soul_get_db = soul_module.get_db
    original_hb_get_db = hb.get_db
    soul_module.get_db = mock_get_db
    hb.get_db = mock_get_db

    yield app

    # 恢复
    soul_module.get_db = original_soul_get_db
    hb.get_db = original_hb_get_db


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
