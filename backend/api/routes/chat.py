"""对话路由 — /api/chat 和 /api/chat/stream。

使用 LangGraph astream_events (v2) 实现 token 级流式输出。
每个 SSE data 行是 JSON 对象，前端统一 JSON.parse 消费。
"""
import json
import re
import uuid

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel, field_validator

from memory import location_store

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────

_MAX_MESSAGE_LENGTH = 2000
_SESSION_ID_RE = re.compile(r'^[a-z0-9-]{1,64}$')


class ChatRequest(BaseModel):
    message: str
    session_id: str = ""
    latitude: float | None = None
    longitude: float | None = None

    @field_validator("message")
    @classmethod
    def _validate_message(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("消息不能为空")
        if len(v) > _MAX_MESSAGE_LENGTH:
            raise ValueError(f"消息最长 {_MAX_MESSAGE_LENGTH} 字")
        return v

    @field_validator("session_id")
    @classmethod
    def _validate_session_id(cls, v: str) -> str:
        v = v.strip()
        if v and not _SESSION_ID_RE.match(v):
            raise ValueError("会话 ID 只能包含小写字母、数字和连字符")
        return v

    @field_validator("latitude")
    @classmethod
    def _validate_lat(cls, v):
        if v is not None and not (-90 <= v <= 90):
            raise ValueError("纬度范围 -90 ~ 90")
        return v

    @field_validator("longitude")
    @classmethod
    def _validate_lon(cls, v):
        if v is not None and not (-180 <= v <= 180):
            raise ValueError("经度范围 -180 ~ 180")
        return v


class ChatResponse(BaseModel):
    reply: str
    session_id: str


class DeleteMemoryRequest(BaseModel):
    memory_id: str


# ── 辅助 ────────────────────────────────────────────────────

def _ensure_sid(req: ChatRequest) -> str:
    return req.session_id or str(uuid.uuid4())[:8]


def _build_input(user_message: str) -> dict:
    return {"messages": [HumanMessage(content=user_message)]}


def _build_input_with_location(user_message: str, req: ChatRequest, sid: str) -> dict:
    """构建带 GPS 上下文的用户消息，并将坐标存入缓存。"""
    # 存储前端发来的 GPS 坐标
    if req.latitude is not None and req.longitude is not None:
        location_store.update(sid, req.latitude, req.longitude)

    # 在消息末尾追加位置上下文（给 LLM 看，不显示给冷冷）
    location_context = ""
    cached = location_store.get(sid)
    if cached:
        lat, lon = cached
        location_context = f"\n\n[系统信息：冷冷当前 GPS 坐标为 ({lat}, {lon})，使用地图工具时可直接用这个坐标作为 location 参数。]"

    return {"messages": [HumanMessage(content=user_message + location_context)]}


def _build_config(session_id: str) -> dict:
    return {
        "configurable": {"thread_id": session_id},
        "recursion_limit": 120,
    }


# ── 端点 ────────────────────────────────────────────────────

@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, request: Request):
    """非流式对话。返回完整 AI 回复。"""
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还没睡醒……")

    sid = _ensure_sid(req)

    try:
        result = await agent.ainvoke(
            _build_input_with_location(req.message, req, sid),
            _build_config(sid),
        )

        messages = result.get("messages", [])
        reply = ""
        for msg in reversed(messages):
            if hasattr(msg, "content") and getattr(msg, "type", None) == "ai":
                content = msg.content
                if isinstance(content, str) and content.strip():
                    reply = content
                    break

        return ChatResponse(reply=reply.strip(), session_id=sid)

    except Exception:
        import traceback, sys
        traceback.print_exc(file=sys.stderr)
        raise HTTPException(500, "小汐被浪花打了一下……等浪退了再试一次好不好？")


@router.post("/chat/stream")
async def chat_stream(req: ChatRequest, request: Request):
    """流式对话（SSE）。每行 data 为 JSON 对象：{"content": "..."} 或工具事件。"""
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还没睡醒……")

    sid = _ensure_sid(req)

    async def generate():
        try:
            async for event in agent.astream_events(
                _build_input_with_location(req.message, req, sid),
                _build_config(sid),
                version="v2",
            ):
                kind = event.get("event", "")

                # Token 级文本
                if kind == "on_chat_model_stream":
                    chunk = event.get("data", {}).get("chunk")
                    if chunk and hasattr(chunk, "content") and chunk.content:
                        text = chunk.content
                        if isinstance(text, str) and text:
                            yield f"data: {json.dumps({'content': text}, ensure_ascii=False)}\n\n"

                # 工具开始
                elif kind == "on_tool_start":
                    name = event.get("name", "unknown")
                    tool_input = event.get("data", {}).get("input", {})
                    try:
                        safe_input = json.loads(json.dumps(tool_input, default=str))
                    except Exception:
                        safe_input = str(tool_input)
                    yield f"data: {json.dumps({'type': 'tool_start', 'name': name, 'input': safe_input}, ensure_ascii=False)}\n\n"

                # 工具结束
                elif kind == "on_tool_end":
                    name = event.get("name", "unknown")
                    output = event.get("data", {}).get("output", "")
                    yield f"data: {json.dumps({'type': 'tool_end', 'name': name, 'output': str(output)[:500]}, ensure_ascii=False)}\n\n"

            yield "data: [DONE]\n\n"

        except Exception:
            import traceback, sys
            traceback.print_exc(file=sys.stderr)
            yield f"data: {json.dumps({'content': '呜，小汐被浪花打了一下……等浪退了再试一次好不好？'}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/health")
async def health(request: Request):
    """健康检查。"""
    agent = request.app.state.agent
    tools = getattr(request.app.state, "tools", [])
    mem = getattr(request.app.state, "memory_manager", None)

    return {
        "status": "ok" if agent is not None else "degraded",
        "name": "小汐",
        "mood": "开心" if agent is not None else "还在睡……",
        "tools": [t.name for t in tools],
        "memory_count": mem.count if mem else 0,
    }


# ── 记忆管理端点 ────────────────────────────────────────────

@router.delete("/memories/{memory_id}")
async def delete_memory(memory_id: str, request: Request):
    """删除一条记忆。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    ok = mem.delete(memory_id)
    if not ok:
        raise HTTPException(404, "未找到该记忆")
    return {"status": "ok", "remaining": mem.count}


@router.get("/memories/export")
async def export_memories(request: Request):
    """导出所有记忆为 JSON。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    data = mem.export_json()
    return JSONResponse(
        content=json.loads(data),
        headers={"Content-Disposition": "attachment; filename=xiaoxi_memories.json"},
    )


@router.post("/memories/clear")
async def clear_memories(request: Request):
    """清空所有记忆。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    count = mem.clear()
    return {"status": "ok", "cleared": count}


@router.get("/memories")
async def list_all_memories(request: Request):
    """获取所有记忆列表。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    memories = mem.all()
    return {"memories": memories, "count": len(memories)}
