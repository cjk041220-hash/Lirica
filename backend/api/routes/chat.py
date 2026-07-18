"""对话路由 — /api/chat 和 /api/chat/stream。

使用 LangGraph astream_events (v2) 实现 token 级流式输出。
每个 SSE data 行是 JSON 对象，前端统一 JSON.parse 消费。
"""
import json
import uuid

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────

class ChatRequest(BaseModel):
    message: str
    session_id: str = ""


class ChatResponse(BaseModel):
    reply: str
    session_id: str


# ── 辅助 ────────────────────────────────────────────────────

def _ensure_sid(req: ChatRequest) -> str:
    return req.session_id or str(uuid.uuid4())[:8]


def _build_input(user_message: str) -> dict:
    return {"messages": [HumanMessage(content=user_message)]}


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
            _build_input(req.message),
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
                _build_input(req.message),
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
