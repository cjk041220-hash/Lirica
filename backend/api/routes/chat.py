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

from memory import chat_archive, location_store
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────

_MAX_MESSAGE_LENGTH = 2000
_SESSION_ID_RE = re.compile(r'^[a-z0-9-]{1,64}$')

# ReAct 工具循环深度上限。120 过高：工具失败重试 + LLM 自决策
# 容易放大成烧钱的长调用链；真实场景（查路线+查天气+记记忆）
# 极少超过 15 步。25 留有余量，异常路径由 LLM 自行降级收尾。
_RECURSION_LIMIT = 25


# ── 会话级并发互斥 ─────────────────────────────────────────
# 同一 session 的两条流并发执行会让 checkpointer 交错写入。
# 单用户场景下「串行化等待」比「拒绝 409」体验更好（连点发送
# 只是排队，不会报错）。锁表定期清理未持锁条目防止内存膨胀。

_session_locks: dict[str, asyncio.Lock] = {}


def _session_lock(sid: str) -> asyncio.Lock:
    """获取/创建 session 级锁（懒清理，单用户下开销可忽略）。"""
    if len(_session_locks) > 512:
        for key in [k for k, v in _session_locks.items() if not v.locked()]:
            _session_locks.pop(key, None)
    lock = _session_locks.get(sid)
    if lock is None:
        lock = _session_locks[sid] = asyncio.Lock()
    return lock


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


# ── 辅助 ────────────────────────────────────────────────────

def _ensure_sid(req: ChatRequest) -> str:
    return req.session_id or str(uuid.uuid4())


def _location_suffix(lat: float, lon: float) -> str:
    """GPS 上下文注入文本（REST 与 SSE 两条路径共用）。

    措辞同时服务两类工具：
    - 天气工具要 latitude/longitude 分参，故纬度/经度各自带标签可辨；
    - 地图工具的 location 参数要求「经度,纬度」整串（map_tools.py 契约），
      故直接拼好可复制的串，消除 LLM 对「谁在前」的推理负担——
      此前注入「(lat, lon)」与工具契约相反，LLM 照抄会命中高德
      INVALID_PARAMS 后软失败成「没找到附近有什么」。
    前缀 `\n\n[系统信息：` 与 _strip_location_suffix 的切分耦合，勿改。
    """
    return (
        f"\n\n[系统信息：冷冷当前 GPS 坐标：纬度 {lat}，经度 {lon}"
        f"（地图工具的 location 参数请用「经度,纬度」格式：{lon},{lat}）。]"
    )


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
        location_context = _location_suffix(lat, lon)

    return {"messages": [HumanMessage(content=user_message + location_context)]}


def _build_config(session_id: str) -> dict:
    return {
        "configurable": {"thread_id": session_id},
        "recursion_limit": _RECURSION_LIMIT,
    }


# ── 对话端点 ────────────────────────────────────────────────

@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, request: Request):
    """非流式对话。返回完整 AI 回复。"""
    sid = _ensure_sid(req)

    # agent 就绪检查必须在 try 之外：HTTPException 继承自 Exception，
    # 放在 try 里会被下方兜底 except 吞成 500，503 优雅降级永远不生效
    # （与 chat_stream 的写法对齐）
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还在睡觉……过一会儿再来找她吧")

    try:
        # 构建带 GPS 上下文的输入（记忆由 pre_model_hook 注入，不在此处）
        input_data = await _build_input_with_location(req.message, req, sid)

        # 同 session 串行化，防止 checkpointer 交错写入。
        # 档案写入也在锁内：用户消息在头、助手回复在尾，连点发送时
        # 档案顺序才与真实对话一致（u1,a1,u2,a2 而非 u1,u2,a1,a2）
        async with _session_lock(sid):
            # 对话档案：用户消息落库（req.message 不含 GPS 后缀——后缀
            # 只拼进 input_data，档案与前端看到的一致）。失败只记日志
            await chat_archive.record_user_message(sid, req.message)

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

            # 工具调用遥测（每轮打点，见 _log_tool_telemetry）
            tool_names = [
                tc.get("name", "")
                for m in messages
                if getattr(m, "type", None) == "ai" and getattr(m, "tool_calls", None)
                for tc in m.tool_calls
            ]
            _log_tool_telemetry(sid, tool_names)

            # 对话档案：助手回复落库（空回复会被 record 侧跳过）
            await chat_archive.record_assistant_message(sid, reply)

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
            # type=error 事件让前端区分「故障」与「正常回复」：
            # 失败时立绘不演开心、气泡展示错误文案（旧前端不认识
            # 该 type 会直接忽略，随后 [DONE] 正常收尾，协议向后兼容）
            yield f"data: {json.dumps({'type': 'error', 'content': '小汐还在睡觉……过一会儿再来找她吧'}, ensure_ascii=False)}\n\n"
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
        user_content += _location_suffix(lat, lon)

    # 助手回复累积器：generate 闭包共享，在会话锁内的 finally 统一落库。
    # 正常收尾 / 流内异常时归档用户实际看到的那份文本；客户端硬断开
    #（任务被取消）是尽力归档——flush 可能被第二次取消打断
    chunks: list[str] = []
    # 工具调用遥测累积器（on_tool_start 时登记，收尾统一打点）
    stream_tools: list[str] = []

    async def generate():
        # 同 session 串行化：async with 保证锁在异常 / 取消时安全释放，
        # 避免旧版手动 acquire→try→finally 中 acquire 与 try 之间的
        # CancelledError 导致锁永远不释放的死锁窗口。
        # 档案写入也在锁内（用户消息在头、助手消息在内层 finally）：
        # 连点发送时档案顺序才与真实对话一致，否则 u2 会抢在 a1 之前插入
        try:
            async with _session_lock(sid):
                # 对话档案：用户消息落库（req.message 不含 GPS 后缀）。
                # 客户端在流开始迭代前就断开则不归档——对话没有发生
                await chat_archive.record_user_message(sid, req.message)
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
                                    chunks.append(text)  # 档案累积，与下发同源
                                    yield f"data: {json.dumps({'content': text}, ensure_ascii=False)}\n\n"

                        elif kind == "on_tool_start":
                            name = event.get("name", "unknown")
                            stream_tools.append(name)
                            # 只下发工具名，不回传完整入参（含坐标/搜索词等，
                            # 缩小 SSE 信息暴露面；前端也只用 name 做状态提示）
                            yield f"data: {json.dumps({'type': 'tool_start', 'name': name}, ensure_ascii=False)}\n\n"

                        elif kind == "on_tool_end":
                            name = event.get("name", "unknown")
                            output = event.get("data", {}).get("output", "")
                            yield f"data: {json.dumps({'type': 'tool_end', 'name': name, 'output': str(output)[:500]}, ensure_ascii=False)}\n\n"

                    yield "data: [DONE]\n\n"
                finally:
                    # 对话档案：已生成的助手文本在锁内落库（顺序保证见上）。
                    # record_* 自身吞异常；唯一未覆盖的是 flush 期间任务
                    # 被再次取消（客户端硬断开的尽力归档缺口，罕见，接受）
                    await chat_archive.record_assistant_message(sid, "".join(chunks))
                    # 工具调用遥测（每轮打点，见 _log_tool_telemetry）
                    _log_tool_telemetry(sid, stream_tools)

        except asyncio.CancelledError:
            # 客户端断开连接时 ASGI 服务器会取消此任务
            log.info("流式请求被取消 (sid=%s)", sid)
            raise
        except Exception:
            log.exception("流式聊天处理失败")
            yield f"data: {json.dumps({'type': 'error', 'content': '呜，小汐被浪花打了一下……等浪退了再试一次好不好？'}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

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

    # 检查是否已认证（持有有效 Bearer Token）。
    # 复用 utils/security.bearer_token_matches（常量时间比较，防时序侧信道），
    # 与认证中间件同一实现，避免内联复刻导致的漂移。
    from config.settings import get_settings
    from utils.security import bearer_token_matches
    token = get_settings().xiaoxi_api_token
    # 未配置 token 时视为开发模式，允许完整访问
    is_authenticated = (
        bearer_token_matches(request.headers.get("authorization", ""), token)
        if token else True
    )

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


def _log_tool_telemetry(sid: str, tool_names: list[str]) -> None:
    """工具调用遥测：每轮对话打点实际调用的工具。

    2026-08-01 取证（checkpoints.db）：68 条 human 消息里 search_nearby /
    get_weather 等调用了 18 次，而 remember 0 次——模型并不懒于调用
    工具，只是不主动写记忆。有了每轮打点，prompt 改造前后可量化对比
    「写路径有效性」，也顺带监控工具链路是否健康。
    """
    names = [n for n in tool_names if n]
    if names:
        log.info("telemetry: session_tools=[%s] n=%d (sid=%s)",
                 ",".join(names), len(names), sid)


# ── 会话历史（陪伴连续性）────────────────────────────────────


def _strip_location_suffix(text: str) -> str:
    """去掉用户消息尾部注入的 GPS 系统信息（不应回显给前端）。"""
    return re.split(r"\n\n\[系统信息：", text, maxsplit=1)[0]


@router.get("/chat/history")
async def chat_history(
    request: Request,
    session_id: str = Query(..., pattern=r"^[a-z0-9-]{1,64}$"),
    limit: int = Query(50, ge=1, le=200),
):
    """从 checkpoint 读取会话历史，供前端刷新后恢复「刚才聊过什么」。

    只回 human/ai 的文本消息；工具调用中间态不回显。
    记忆注入的 SystemMessage 走 llm_input_messages，不进 checkpoint，
    因此不会出现在这里。
    """
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还在睡觉……过一会儿再来找她吧")

    try:
        state = await agent.aget_state(
            {"configurable": {"thread_id": session_id}}
        )
    except Exception:
        log.exception("读取会话历史失败 (sid=%s)", session_id)
        raise HTTPException(500, "映海里的记忆有点模糊……再试一次？")

    if state is None:
        return {"messages": [], "session_id": session_id}

    out = []
    for m in state.values.get("messages", []):
        mtype = getattr(m, "type", "")
        content = getattr(m, "content", "")
        if not isinstance(content, str) or not content.strip():
            continue  # 多模态 content 列表 / 纯工具调用的空 AIMessage
        if mtype == "human":
            out.append({"role": "user", "content": _strip_location_suffix(content)})
        elif mtype == "ai":
            out.append({"role": "assistant", "content": content})

    return {"messages": out[-limit:], "session_id": session_id}


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
async def export_memories(
    request: Request,
    include_archived: bool = Query(False, description="是否包含已归档（冷藏）记忆"),
):
    """导出记忆为 JSON。include_archived=true 时含冷藏记忆（D8：冷库可回看）。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    data = await mem.export_json(include_archived=include_archived)
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
async def list_all_memories(
    request: Request,
    include_archived: bool = Query(False, description="是否包含已归档（冷藏）记忆"),
):
    """获取记忆列表。include_archived=true 时含冷藏记忆（面板「已归档」标签页）。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    memories = await mem.all(include_archived=include_archived)
    return {"memories": memories, "count": len(memories)}
