"""对话路由 — /api/chat 和 /api/chat/stream。

使用 LangGraph astream_events (v2) 实现 token 级流式输出。
每个 SSE data 行是 JSON 对象，前端统一 JSON.parse 消费。

每次请求实时构建 Agent，确保读取最新的生命状态和运行时配置。
"""
import asyncio
import json
import re
import uuid

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel, field_validator

from memory import location_store
from utils.logger import get_logger

log = get_logger(__name__)

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
    return req.session_id or str(uuid.uuid4())


async def _build_input_with_location(user_message: str, req: ChatRequest, sid: str) -> dict:
    """构建带 GPS 上下文的用户消息，并将坐标存入缓存。

    v2 重构：不再注入记忆叙事。记忆由 pre_model_hook 通过
    llm_input_messages 动态注入，不进入 checkpoint。
    """
    if req.latitude is not None and req.longitude is not None:
        await location_store.update(sid, req.latitude, req.longitude)

    location_context = ""
    cached = await location_store.get(sid)
    if cached:
        lat, lon = cached
        location_context = f"\n\n[系统信息：冷冷当前 GPS 坐标为 ({lat}, {lon})，使用地图工具时可直接用这个坐标作为 location 参数。]"

    return {"messages": [HumanMessage(content=user_message + location_context)]}


def _build_config(session_id: str) -> dict:
    return {
        "configurable": {"thread_id": session_id},
        "recursion_limit": 120,
    }


# ── 对话端点 ────────────────────────────────────────────────

@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, request: Request):
    """非流式对话。返回完整 AI 回复。"""
    sid = _ensure_sid(req)

    try:
        agent = request.app.state.agent
        if agent is None:
            raise HTTPException(503, "小汐还在睡觉……过一会儿再来找她吧")

        # 构建带 GPS 上下文的输入（记忆由 pre_model_hook 注入，不在此处）
        input_data = await _build_input_with_location(req.message, req, sid)

        result = await agent.ainvoke(
            input_data,
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
        log.exception("聊天请求处理失败")
        raise HTTPException(500, "小汐被浪花打了一下……等浪退了再试一次好不好？")


@router.post("/chat/stream")
async def chat_stream(req: ChatRequest, request: Request):
    """流式对话（SSE）。每行 data 为 JSON 对象：{"content": "..."} 或工具事件。"""
    sid = _ensure_sid(req)

    agent = request.app.state.agent
    if agent is None:
        async def error_gen():
            yield f"data: {json.dumps({'content': '小汐还在睡觉……过一会儿再来找她吧'}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"
        return StreamingResponse(error_gen(), media_type="text/event-stream")

    # 构建带 GPS 坐标的用户消息（记忆由 pre_model_hook 注入，不在此处）
    user_content = req.message

    # 合并 GPS 坐标
    if req.latitude is not None and req.longitude is not None:
        await location_store.update(sid, req.latitude, req.longitude)
    cached = await location_store.get(sid)
    if cached:
        lat, lon = cached
        user_content += f"\n\n[系统信息：冷冷当前 GPS 坐标为 ({lat}, {lon})，使用地图工具时可直接用这个坐标作为 location 参数。]"

    async def generate():
        try:
            async for event in agent.astream_events(
                {"messages": [HumanMessage(content=user_content)]},
                _build_config(sid),
                version="v2",
            ):
                # 检测客户端是否已断开连接，避免白白消耗 API Token
                if await request.is_disconnected():
                    log.info("客户端已断开，停止流式生成 (sid=%s)", sid)
                    break

                kind = event.get("event", "")

                if kind == "on_chat_model_stream":
                    chunk = event.get("data", {}).get("chunk")
                    if chunk and hasattr(chunk, "content") and chunk.content:
                        text = chunk.content
                        if isinstance(text, str) and text:
                            yield f"data: {json.dumps({'content': text}, ensure_ascii=False)}\n\n"

                elif kind == "on_tool_start":
                    name = event.get("name", "unknown")
                    tool_input = event.get("data", {}).get("input", {})
                    try:
                        safe_input = json.loads(json.dumps(tool_input, default=str))
                    except (TypeError, ValueError):
                        safe_input = str(tool_input)
                    yield f"data: {json.dumps({'type': 'tool_start', 'name': name, 'input': safe_input}, ensure_ascii=False)}\n\n"

                elif kind == "on_tool_end":
                    name = event.get("name", "unknown")
                    output = event.get("data", {}).get("output", "")
                    yield f"data: {json.dumps({'type': 'tool_end', 'name': name, 'output': str(output)[:500]}, ensure_ascii=False)}\n\n"

            yield "data: [DONE]\n\n"

        except asyncio.CancelledError:
            # 客户端断开连接时 ASGI 服务器会取消此任务
            log.info("流式请求被取消 (sid=%s)", sid)
            raise
        except Exception:
            log.exception("流式聊天处理失败")
            yield f"data: {json.dumps({'content': '呜，小汐被浪花打了一下……等浪退了再试一次好不好？'}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"
        finally:
            log.debug("流式生成器退出 (sid=%s)", sid)

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


# ── 健康检查 ────────────────────────────────────────────────

@router.get("/health")
async def health(request: Request):
    """健康检查。未认证时只返回基本状态，已认证时返回完整诊断信息。"""
    checkpointer = getattr(request.app.state, "checkpointer", None)

    # 检查是否已认证（持有有效 Bearer Token）
    from config.settings import get_settings
    token = get_settings().xiaoxi_api_token
    is_authenticated = False
    if token:
        auth_header = request.headers.get("authorization", "")
        if auth_header == f"Bearer {token}":
            is_authenticated = True
    else:
        # 未配置 token 时视为开发模式，允许完整访问
        is_authenticated = True

    # 未认证：只返回基本状态
    if not is_authenticated:
        return {"status": "ok" if checkpointer is not None else "degraded"}

    # 已认证：返回完整诊断信息
    tools = getattr(request.app.state, "tools", [])
    mem = getattr(request.app.state, "memory_manager", None)

    memory_count = 0
    if mem is not None:
        try:
            memory_count = await mem.count()
        except Exception as e:
            log.warning("记忆计数获取失败：%s", e)

    return {
        "status": "ok" if checkpointer is not None else "degraded",
        "name": "小汐",
        "mood": "开心" if checkpointer is not None else "还在睡……",
        "tools": [t.name for t in tools],
        "memory_count": memory_count,
    }


# ── 记忆管理端点 ────────────────────────────────────────────

@router.delete("/memories/{memory_id}")
async def delete_memory(memory_id: str, request: Request):
    """删除一条记忆。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    ok = await mem.delete(memory_id)
    if not ok:
        raise HTTPException(404, "未找到该记忆")
    remaining = await mem.count()
    return {"status": "ok", "remaining": remaining}


@router.get("/memories/export")
async def export_memories(request: Request):
    """导出所有记忆为 JSON。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    data = await mem.export_json()
    return JSONResponse(
        content=json.loads(data),
        headers={"Content-Disposition": "attachment; filename=xiaoxi_memories.json"},
    )


@router.post("/memories/clear")
async def clear_memories(
    request: Request,
    confirm: bool = Query(False, description="必须设为 true 才能执行清空操作"),
):
    """清空所有记忆。清空前自动备份到 data/backups/。"""
    if not confirm:
        raise HTTPException(
            400,
            "请添加 ?confirm=true 参数来确认清空操作。"
            "所有记忆将被备份后删除。",
        )

    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")

    # 清空前自动备份
    backup_path = await mem.backup_before_clear()

    count = await mem.clear()
    return {"status": "ok", "cleared": count, "backup": str(backup_path)}


@router.get("/memories")
async def list_all_memories(request: Request):
    """获取所有记忆列表。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    memories = await mem.all()
    return {"memories": memories, "count": len(memories)}
