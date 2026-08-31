"""对话路由 — /api/chat 和 /api/chat/stream。

使用 LangGraph astream_events (v2) 实现 token 级流式输出。
每个 SSE data 行是 JSON 对象，前端统一 JSON.parse 消费。

每次请求实时构建 Agent，确保读取最新的生命状态和运行时配置。
"""
import asyncio
import datetime
import json
import re
import uuid

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import Response, StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel, field_validator

from database.core import get_db
from heartbeat_moods import period_label
from memory import chat_archive, location_store
from memory.extract import get_watermark
from utils.logger import get_logger
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────

_MAX_MESSAGE_LENGTH = 2000
_SESSION_ID_RE = re.compile(r'^[a-z0-9-]{1,64}$')

# ReAct 工具循环深度上限。120 过高：工具失败重试 + LLM 自决策
# 容易放大成烧钱的长调用链；真实场景（查路线+查天气+记记忆）
# 极少超过 15 步。25 留有余量，异常路径由 LLM 自行降级收尾。
_RECURSION_LIMIT = 25


def _tool_end_payload(output: object) -> tuple[str, dict | None, dict | None]:
    """on_tool_end 输出 → (渲染文本, nav, tarot)。

    data.output 是 ToolMessage：content 才是渲染文本，str() 是 pydantic
    repr（含 tool_call_id 噪声）——顺带修掉折叠区显示 repr 的旧观感
    问题；artifact 分两类下发：地图工具的 nav（content_and_artifact
    契约，LLM 只见 content 不见 artifact）与塔罗的 tarot 卡片数据。
    nav 判据要求 lat+lon（与前端 isValidNav 结构一致），tarot 判据
    要求非空 cards 列表——两者互斥，防地图按钮错挂到塔罗结果上。
    普通 str 输出原样兜底。
    """
    text = getattr(output, "content", None)
    if text is None:
        text = str(output)
    artifact = getattr(output, "artifact", None)
    nav = artifact if isinstance(artifact, dict) and "lat" in artifact and "lon" in artifact else None
    tarot = (
        artifact
        if isinstance(artifact, dict) and isinstance(artifact.get("cards"), list) and artifact["cards"]
        else None
    )
    return str(text), nav, tarot


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
    # GPS 开关信号（体检「GPS 关不净」修复）：None = 旧客户端未表态
    # （行为不变）；False = 用户显式关闭 GPS → 立即清后端缓存且不注入。
    gps_enabled: bool | None = None

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


class MemoryUpdate(BaseModel):
    """PATCH /api/memories/{id} 请求体（记忆编辑，体检 R4）。"""
    content: str

    @field_validator("content")
    @classmethod
    def _validate_content(cls, v: str) -> str:
        v = (v or "").strip()
        if not v:
            raise ValueError("记忆内容不能为空")
        return v


# ── 辅助 ────────────────────────────────────────────────────

def _ensure_sid(req: ChatRequest) -> str:
    return req.session_id or str(uuid.uuid4())


_WEEKDAY_LABELS = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")


def _time_line(now_dt: datetime.datetime) -> str:
    """当前时间行（Spec B 地基）：她总知道现在几点。

    格式与设计文档逐字一致：`现在是 2026-08-05 周三 02:14，深夜。`
    时段词走 heartbeat_moods.period_label（与心跳同一口径）。
    """
    return (
        f"现在是 {now_dt.strftime('%Y-%m-%d')} "
        f"{_WEEKDAY_LABELS[now_dt.weekday()]} {now_dt.strftime('%H:%M')}，"
        f"{period_label(now_dt.hour)}。"
    )


def _gps_line(lat: float, lon: float) -> str:
    """GPS 坐标行。措辞同时服务两类工具：
    - 天气工具要 latitude/longitude 分参，故纬度/经度各自带标签可辨；
    - 地图工具的 location 参数要求「经度,纬度」整串（map_tools.py 契约），
      故直接拼好可复制的串，消除 LLM 对「谁在前」的推理负担——
      此前注入「(lat, lon)」与工具契约相反，LLM 照抄会命中高德
      INVALID_PARAMS 后软失败成「没找到附近有什么」。
    """
    return (
        f"冷冷当前 GPS 坐标：纬度 {lat}，经度 {lon}"
        f"（地图工具的 location 参数请用「经度,纬度」格式：{lon},{lat}）。"
    )


def _location_suffix(lat: float, lon: float) -> str:
    """仅 GPS 的系统信息块（兼容旧契约；请求路径现走 _system_info_block）。"""
    return "\n\n[系统信息：" + _gps_line(lat, lon) + "]"


def _system_info_block(now_dt: datetime.datetime, care_lines: list[str],
                       lat_lon: tuple[float, float] | None) -> str:
    """统一的 [系统信息：…] 块：时间行永远在场，关心提示行（Spec B
    ④ 接入）与 GPS 行按需加入。

    前缀 `\n\n[系统信息：` 与 _strip_location_suffix 的切分耦合，勿改：
    三类行同居一块，历史回显按同一前缀整块剥离。
    """
    lines = [_time_line(now_dt)]
    lines.extend(care_lines)
    if lat_lon:
        lines.append(_gps_line(*lat_lon))
    return "\n\n[系统信息：" + "\n".join(lines) + "]"


async def _resolve_user_content(user_message: str, req: ChatRequest, sid: str) -> str:
    """GPS 副作用 + 上下文后缀的**单一入口**（REST 与 SSE 两条路径共用）。

    体检回归教训：此逻辑曾在本文件两处各存一份，修复「GPS 关不净」时
    只改了 REST 辅助函数，漏了 SSE 端点的内联副本——前端实际只走 SSE，
    开关在生产路径上失效而测试全绿（测的是没人调的抽象层）。凡请求
    预处理需要「两端口共用」的逻辑，一律收敛到本函数，禁止内联副本。

    语义：gps_enabled=False → 清本会话缓存 + 不注入 GPS 行；
    带坐标 → 更新缓存；有缓存 → 追加 GPS 行。
    当前时间行永远注入（Spec B：她总知道现在几点）；时间行、关心
    提示行与 GPS 行同住一个 [系统信息：…] 块，剥离契约不变。
    """
    now_dt = datetime.datetime.now(_HKT)

    lat_lon = None
    if req.gps_enabled is False:
        # 显式关闭信号：立即清掉本会话缓存坐标（体检「GPS 关不净」：
        # 此前前端开关只写 localStorage，后端缓存按 TTL 最长 8h 持续
        # 注入旧坐标——关了还在用。清缓存 + 不注入 GPS 行，关即消失）。
        # 时间行不受影响——关 GPS 关不掉时钟
        await location_store.forget(sid)
    else:
        if req.latitude is not None and req.longitude is not None:
            await location_store.update(sid, req.latitude, req.longitude)
        lat_lon = await location_store.get(sid)

    # 异常感知关心（Spec B）：检测 + 冷却过滤 + 注入即记账。
    # collect_care_lines 内部已全兜底，这里再包一层只是纵深防御——
    # 关心提示任何失败都不得阻断对话
    try:
        from agent.care import collect_care_lines
        care_lines = await collect_care_lines(now_dt)
    except Exception:
        log.warning("异常感知关心整体失败，跳过", exc_info=True)
        care_lines = []

    # 近期主动消息感知（跨会话接话）：若 12 小时内发过主动消息且在不同会话
    try:
        conn = await get_db()
        cursor = await conn.execute(
            """SELECT content, created_at, session_id FROM proactive_messages
               WHERE status IN ('delivered', 'stored')
               ORDER BY created_at DESC, rowid DESC LIMIT 1"""
        )
        p_row = await cursor.fetchone()
        if p_row and p_row[0] and p_row[1]:
            p_content, p_created_at, p_sid = p_row[0], p_row[1], p_row[2]
            try:
                p_dt = datetime.datetime.strptime(p_created_at, "%Y-%m-%d %H:%M").replace(tzinfo=_HKT)
                elapsed_min = (now_dt - p_dt).total_seconds() / 60
                if 0 <= elapsed_min <= 720:  # 12 小时以内
                    if p_sid != sid:
                        time_desc = "刚刚" if elapsed_min < 5 else f"{int(elapsed_min)}分钟前" if elapsed_min < 60 else f"{int(elapsed_min/60)}小时前"
                        care_lines.append(
                            f"你于{time_desc}主动向冷冷发过消息：「{p_content}」。"
                            f"如果冷冷此句（如『咋了/怎么了/怎么啦/在的』等）是在回复你刚才的主动消息，请顺着你的主动消息自然承接。"
                        )
            except Exception:
                pass
    except Exception:
        log.warning("近期主动消息上下文读取失败，跳过", exc_info=True)

    return user_message + _system_info_block(now_dt, care_lines, lat_lon)


async def _build_input_with_location(user_message: str, req: ChatRequest, sid: str) -> dict:
    """构建带 GPS 上下文的图输入（REST /chat 用）。薄包装，逻辑见
    _resolve_user_content。

    v2 重构：不再注入记忆叙事。记忆由 pre_model_hook 通过
    llm_input_messages 动态注入，不进入 checkpoint。
    """
    return {"messages": [HumanMessage(content=await _resolve_user_content(user_message, req, sid))]}


def _build_config(session_id: str) -> dict:
    return {
        "configurable": {"thread_id": session_id},
        "recursion_limit": _RECURSION_LIMIT,
    }


# ── 断连看门狗（体检 R1'/R2'）────────────────────────────────
# 修复前：非流式 /chat 客户端断开后 ainvoke 照跑完整个工具循环，白烧
# token；SSE 的 is_disconnected 检测是事件驱动的，卡在单次 LLM 调用
# 内（重试链最坏数分钟无事件）发现不了断连。
_DISCONNECT_POLL_SECONDS = 1.0
# SSE 空闲超时须大于最坏单跳静默时长：providers/deepseek.py 的
# timeout=120 × (1 + max_retries=2) = 一次健康但慢的调用最坏 ~240s
# 不产出任何流事件。取值低于它会在重试链中误杀正常对话，故 300s
# （5 分钟）+ 注释留推导。代价是硬断连最坏 5 分钟后才止损——优于
# 修复前的「永远跑完」。
_SSE_IDLE_TIMEOUT = 300.0
# SSE 心跳间隔：长 LLM 调用（重试链最坏 ~240s 静默）期间连接完全
# 无字节，移动网络/反代常在 60-120s 空闲后静默掐线——前端收到
# 截断流且无从察觉。每 20s 下发一行 SSE 注释（`: ping`，EventSource
# 与自家前端解析器天然忽略）保活。注意：ping 不重置上方空闲总闸，
# 累计静默超 300s 仍按断连止损，语义与原看门狗一致。
_SSE_PING_INTERVAL = 20.0


async def _await_with_disconnect_watch(coro, request: Request, sid: str):
    """带断连看门狗地执行 awaitable（非流式 /chat 用）。

    每秒检查一次 request.is_disconnected()；断开则取消内层任务并
    返回 None（调用方跳过归档与遥测——用户什么都没看到，无文本可
    归；用户消息已在调用前归档，顺序无损）。任务正常完成返回其
    结果；任务抛异常原样上抛（走既有 500 兜底）。

    取消的是内层 task、不向调用方传播 CancelledError——调用方的
    `async with _session_lock` 正常走完 __aexit__ 释放锁，无死锁面。
    """
    task = asyncio.create_task(coro)
    while not task.done():
        if await request.is_disconnected():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            except Exception:
                log.warning("断开取消后任务收尾异常（客户端已离开，sid=%s）",
                            sid, exc_info=True)
            log.info("客户端已断开，取消非流式对话任务 (sid=%s)", sid)
            return None
        try:
            # shield 使 wait_for 超时时不取消真实任务，只回去做下轮
            # 断连检查；任务提前完成则立即唤醒
            await asyncio.wait_for(
                asyncio.shield(task), timeout=_DISCONNECT_POLL_SECONDS
            )
        except asyncio.TimeoutError:
            pass  # 仍在运行，下一轮再查断连
    return task.result()


async def ensure_checkpoint_hydrated(agent, sid: str, max_messages: int = 20) -> None:
    """确保 LangGraph Checkpoint 与 SQLite 档案表（chat_messages）对齐。

    若 checkpointer 中当前 thread 为空（如初次接话 era 独处时间线、脚本外部注入、
    checkpoint 被裁剪或重启），从 chat_archive 读取该会话已有历史消息，
    灌入 checkpoint，使 LLM 上下文与前端看到的内容完全一致。
    """
    if agent is None or not sid:
        return
    try:
        from unittest.mock import AsyncMock, MagicMock
        if isinstance(agent, (AsyncMock, MagicMock)):
            return
    except ImportError:
        pass

    try:
        aget_state = getattr(agent, "aget_state", None)
        if not callable(aget_state):
            return
        state = await aget_state({"configurable": {"thread_id": sid}})
        values = getattr(state, "values", None)
        existing_msgs = values.get("messages", []) if isinstance(values, dict) else []
        if not existing_msgs:
            history = await chat_archive.get_messages(sid, limit=max_messages)
            if history:
                nodes = {}
                get_graph = getattr(agent, "get_graph", None)
                if callable(get_graph):
                    try:
                        g = get_graph()
                        if hasattr(g, "nodes") and isinstance(g.nodes, dict):
                            nodes = g.nodes
                    except Exception:
                        pass
                attribution = next(
                    (n for n in ("model", "agent") if n in nodes),
                    next(
                        (
                            n
                            for n in nodes
                            if n not in ("__start__", "__end__", "start", "end", "input", "output")
                        ),
                        None,
                    ),
                )
                if attribution is not None and hasattr(agent, "aupdate_state"):
                    from langchain_core.messages import AIMessage, HumanMessage

                    to_inject = []
                    for m in history:
                        content = m.get("content", "")
                        if not content:
                            continue
                        if m.get("role") == "user":
                            to_inject.append(HumanMessage(content=content))
                        else:
                            to_inject.append(AIMessage(content=content))
                    if to_inject:
                        await agent.aupdate_state(
                            {"configurable": {"thread_id": sid}},
                            {"messages": to_inject},
                            as_node=attribution,
                        )
                        log.info("会话 checkpoint 自动水合完成 (sid=%s, n=%d)", sid, len(to_inject))
    except Exception as e:
        log.warning("会话 checkpoint 水合检查失败 (sid=%s): %s", sid, e)


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
            # 对话上下文水合：若当前会话在 checkpointer 里为空但档案库有历史，预先水合
            await ensure_checkpoint_hydrated(agent, sid)

            # 对话档案：用户消息落库（req.message 不含 GPS 后缀——后缀
            # 只拼进 input_data，档案与前端看到的一致）。失败只记日志
            await chat_archive.record_user_message(sid, req.message)

            result = await _await_with_disconnect_watch(
                agent.ainvoke(input_data, _build_config(sid)),
                request, sid,
            )
            if result is None:
                # 客户端已离开：跳过归档与遥测，锁正常释放
                return ChatResponse(reply="", session_id=sid)

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

    # 构建带 GPS 坐标的用户消息（记忆由 pre_model_hook 注入，不在此处）。
    # 与 REST /chat 共用 _resolve_user_content 单一入口——此前这里是一份
    # 内联副本，漏了 gps_enabled=False 清缓存分支（关了还在用回归根因）。
    user_content = await _resolve_user_content(req.message, req, sid)

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
                # 对话上下文水合：若当前会话在 checkpointer 里为空但档案库有历史，预先水合
                await ensure_checkpoint_hydrated(agent, sid)

                # 对话档案：用户消息落库（req.message 不含 GPS 后缀）。
                # 客户端在流开始迭代前就断开则不归档——对话没有发生
                await chat_archive.record_user_message(sid, req.message)
                try:
                    # 手动驱动迭代器以挂空闲看门狗（体检 R2'）：
                    # async for 的 is_disconnected 检查只在有事件产出时
                    # 运行，卡在单次 LLM 调用内（重试链最坏 ~240s 静默）
                    # 发现不了断连。wait_for(__anext__) 每事件重置空闲
                    # 计时，超时按断连处理 → break → finally 尽力归档
                    # 已累积 chunks（_SSE_IDLE_TIMEOUT 取值推导见常量注释）
                    event_iter = agent.astream_events(
                        {"messages": [HumanMessage(content=user_content)]},
                        _build_config(sid),
                        version="v2",
                    ).__aiter__()
                    # 空闲计时起点：每收到真实事件重置；ping 不重置——
                    # ping 是保活手段，不代表上游有进展，总闸仍看真实静默
                    idle_start = asyncio.get_running_loop().time()
                    # __anext__ 包成 task + shield：wait_for 超时只放弃
                    # 等待、不取消底层迭代——否则首次 ping 就把生成器关掉，
                    # 后续 __anext__ 直接 StopAsyncIteration，真实内容丢失
                    pending: asyncio.Task | None = None
                    try:
                        while True:
                            if pending is None:
                                pending = asyncio.ensure_future(
                                    event_iter.__anext__()
                                )
                            try:
                                event = await asyncio.wait_for(
                                    asyncio.shield(pending),
                                    timeout=_SSE_PING_INTERVAL,
                                )
                                pending = None
                            except StopAsyncIteration:
                                break
                            except asyncio.TimeoutError:
                                # 先查断连：客户端走了就不必再保活
                                if await request.is_disconnected():
                                    log.info("客户端已断开，停止流式生成 (sid=%s)", sid)
                                    break
                                idle_for = asyncio.get_running_loop().time() - idle_start
                                if idle_for >= _SSE_IDLE_TIMEOUT:
                                    log.warning(
                                        "流式生成 %ds 无事件，按断连语义停止 (sid=%s)",
                                        int(idle_for), sid,
                                    )
                                    break
                                yield ": ping\n\n"
                                continue

                            idle_start = asyncio.get_running_loop().time()

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
                                text, nav, tarot = _tool_end_payload(output)
                                payload = {
                                    "type": "tool_end",
                                    "name": name,
                                    "output": str(text)[:500],
                                }
                                # 地图工具附带目的地信息（GCJ-02 + 名称），
                                # 前端据此渲染「看地图/去这里」；塔罗附带卡片
                                # 数据，前端渲染翻牌网格。无 artifact → 不
                                # 下发对应字段，旧前端忽略未知字段天然兼容
                                if nav is not None:
                                    payload["nav"] = nav
                                if tarot is not None:
                                    payload["tarot"] = tarot
                                yield f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

                    finally:
                        # 断连/空闲止损 break 时 shield 保护的迭代 task 仍
                        # 在跑（LLM 调用继续烧 token）——取消它；正常消费完
                        #（pending=None）则无操作。cancel 不等待收尾：
                        # 生成器 aclose 由事件循环回收，此处不阻塞 [DONE]
                        if pending is not None and not pending.done():
                            pending.cancel()

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

@router.api_route("/health", methods=["GET", "HEAD"])
async def health(request: Request):
    """健康检查。未认证时只返回基本状态，已认证时返回完整诊断信息。

    显式放行 HEAD（体检 P2-8）：FastAPI 0.139 的 routing 会用
    ``route.methods = {…}`` 重置方法集，抹掉 Starlette 给 GET 自动追加的
    HEAD，导致 HEAD 探针（UptimeRobot 等默认姿势）打 /api/health 得 404。
    显式声明两方法即可；HEAD 的响应体由 uvicorn h11 层强制清空，协议安全。
    """
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

    # 深度诊断：心跳调度器 + 记忆提取水位线滞后量。
    # 旧版只看 checkpointer 非 None（MemorySaver 兜底后恒真），DB 挂掉、
    # 心跳停了、提取卡死全都报健康——compose 探针失去意义。每项独立
    # try 降级：诊断字段缺失不应拖垮健康检查本身。
    heartbeat_running = None
    try:
        import heartbeat
        heartbeat_running = bool(heartbeat.scheduler.running)
    except Exception as e:
        log.warning("心跳状态获取失败：%s", e)

    extract_watermark = None
    extract_lag = None
    try:
        # get_db / get_watermark 走模块级 import，测试可仿
        # chat_archive.get_db 的 patch 姿势接到 tmp_db，不碰生产库
        conn = await get_db()
        extract_watermark = await get_watermark(conn)
        # 与提取口径一致：solo 自言自语不在提取范围，不计入积压
        cursor = await conn.execute(
            "SELECT MAX(id) FROM chat_messages WHERE msg_type != 'solo'"
        )
        row = await cursor.fetchone()
        latest_id = (row[0] or 0) if row else 0
        extract_lag = max(0, latest_id - extract_watermark)
    except Exception as e:
        log.warning("提取水位线获取失败：%s", e)

    return {
        "status": "ok" if checkpointer is not None else "degraded",
        "name": "小汐",
        "mood": "开心" if checkpointer is not None else "还在睡……",
        "tools": [t.name for t in tools],
        "memory_count": memory_count,
        "heartbeat_running": heartbeat_running,
        "extract_watermark": extract_watermark,
        "extract_lag": extract_lag,
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


# ── 记忆管理端点 ────────────────────────────────────────────

@router.patch("/memories/{memory_id}")
async def update_memory_route(memory_id: str, body: MemoryUpdate, request: Request):
    """修改一条记忆的内容（冷冷的记忆主权，体检 R4）。

    此前删除/修改记忆只能「求小汐动手」，而 LLM 写路径纪律曾被实盘
    证明不可靠（31 条消息零触发）——控制权不该押在模型自觉上。
    覆盖走 manager.update_content 语义：保留 created_at、刷
    updated_at、旧内容落 superseded 历史链（v15）+ 单层 prev_content。
    """
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    ok = await mem.update_content(memory_id, body.content)
    if not ok:
        raise HTTPException(404, "未找到该记忆")
    return {"status": "ok"}


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
    include_superseded: bool = Query(False, description="是否包含已被覆盖的历史说法（superseded 历史链）"),
):
    """导出记忆为 JSON。include_archived=true 时含冷藏记忆（D8：冷库可回看）；
    include_superseded=true 时含覆盖历史链（O3：此前只存于整库快照备份）。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    data = await mem.export_json(include_archived=include_archived,
                                 include_superseded=include_superseded)
    # export_json 已产出序列化好的 JSON——直接 Response 回字节，
    # 不再 json.loads 回 dict 交 JSONResponse 双重序列化（体检杂项）
    return Response(
        content=data,
        media_type="application/json",
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

    # 清空前自动备份（含 archived 冷藏记忆，v13 修复）
    backup_path = await mem.backup_before_clear()

    count = await mem.clear()
    # 只回文件名不回完整路径：避免向前端泄漏服务器内部目录结构
    return {"status": "ok", "cleared": count, "backup": backup_path.name}


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
