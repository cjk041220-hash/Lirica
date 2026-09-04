"""配置路由 — /api/config。

提供前端控制台：模型选择、思考强度调节。
"""
import asyncio

from fastapi import APIRouter, HTTPException, Request
from langchain_core.language_models.chat_models import BaseChatModel
from pydantic import BaseModel, Field

from agent.graph import build_agent, make_pre_model_hook
from agent.soul import build_system_prompt
from providers.deepseek import create_deepseek_llm, get_available_models
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()

# Agent 重建锁：串行化所有重建路径（配置变更 / 心跳刷新），
# 防止两条路径并发构建并互相覆盖 app.state.agent / app.state.model
_agent_lock = asyncio.Lock()


# ── 运行时配置（内存中保存，重启后重置为默认值） ──────────────
# 注意：这份配置是 LLM 参数的唯一权威来源（single source of truth）。
# 启动时（server.py lifespan）与运行中的所有 Agent 重建都必须经由
# create_llm_from_runtime_config() / rebuild_agent_for_app()，
# 保证「GET /api/config 报告的值」「app.state.model」「app.state.agent」
# 三者永远一致。默认值必须与 create_deepseek_llm() 的默认参数
# （temperature=0.8 / max_tokens=8192 / flash 模型）保持一致。

_runtime_config = {
    "model": "deepseek-v4-pro",
    "temperature": 0.8,
    "max_tokens": 8192,
    "thinking_intensity": "medium",
}

# 思考强度 → temperature 映射
# 诚实地说：这控制的是创造性/随机性，不是真正的"思考深度"
# 对于 V4 Flash/Pro 来说，高 temperature 让回答更发散、更有想象力
# V4 Pro 支持深度推理模式，但推理深度由模型内部控制，与 temperature 无关
_THINKING_PRESETS = {
    "low":    {"temperature": 0.3, "max_tokens": 4096,  "label": "轻柔"},
    "medium": {"temperature": 0.8, "max_tokens": 8192,  "label": "适中"},
    "high":   {"temperature": 1.0, "max_tokens": 8192,  "label": "深度"},
    "max":    {"temperature": 1.2, "max_tokens": 8192,  "label": "全力"},
}


def get_runtime_config() -> dict:
    """获取当前运行时配置。"""
    return _runtime_config.copy()


def get_current_temperature() -> float:
    """获取当前 temperature。"""
    intensity = _runtime_config.get("thinking_intensity", "medium")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["temperature"]
    return _runtime_config.get("temperature", 0.7)


def get_current_max_tokens() -> int:
    """获取当前 max_tokens。"""
    intensity = _runtime_config.get("thinking_intensity", "medium")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["max_tokens"]
    return _runtime_config.get("max_tokens", 8192)


def get_current_model() -> str:
    """获取当前模型名称。"""
    return _runtime_config.get("model", "deepseek-v4-flash")


def create_llm_from_runtime_config() -> BaseChatModel:
    """按当前运行时配置创建 LLM 实例。

    所有创建 LLM 的路径（启动 / 配置变更 / 心跳刷新）都应经由此函数，
    确保 LLM 参数永远来自 _runtime_config 这一唯一权威来源。
    """
    return create_deepseek_llm(
        model=get_current_model(),
        temperature=get_current_temperature(),
        max_tokens=get_current_max_tokens(),
    )


async def _rebuild_agent_inner(app) -> None:
    """Agent 重建核心逻辑（不含锁）。调用方负责持有 _agent_lock。

    与 rebuild_agent_for_app 拆分是为了让 POST /api/config 的配置
    变更 + 重建 + 回滚在同一个锁内原子完成，消除 clear()→update() 中间
    窗口期其他协程读到空 _runtime_config 的竞态。
    """
    new_llm = create_llm_from_runtime_config()

    # 刷新 system_prompt（注入最新生命状态）
    system_prompt = await build_system_prompt()

    # 创建 pre_model_hook（记忆注入）
    narrative_builder = getattr(app.state, "narrative_builder", None)
    pre_model_hook = make_pre_model_hook(narrative_builder)

    new_agent = build_agent(
        new_llm,
        app.state.tools,
        system_prompt,
        app.state.checkpointer,
        pre_model_hook,
        anchor_interval=8,  # 人格重锚（批①）：每 8 次模型调用重申人格锚
    )

    # 三者同时更新，保持单一数据源
    app.state.model = new_llm
    app.state.system_prompt = system_prompt
    app.state.agent = new_agent


async def rebuild_agent_for_app(app) -> None:
    """统一的 Agent 重建入口 —— 用当前运行时配置重建 LLM 和 Agent。

    所有重建路径（POST /api/config 配置变更、心跳生命状态刷新）都必须
    经由本函数，以保证：
    1. app.state.model / app.state.agent / app.state.system_prompt 三者
       在锁内同时更新，永远相互一致；
    2. 心跳刷新不会用启动时的默认 LLM 覆盖用户配置的模型/思考强度
       （v0.4.2 的缺陷：心跳直接拿 app.state.model 重建，而配置变更
       从不回写 app.state.model，导致用户设置最多 2 小时被静默回滚）。

    asyncio.Lock 串行化并发重建。CPython GIL 保证引用赋值原子性：
    进行中的请求持有旧 agent 引用继续完成，新请求拿到新 agent。
    """
    async with _agent_lock:
        await _rebuild_agent_inner(app)


# ── 请求/响应模型 ──────────────────────────────────────────

class ConfigResponse(BaseModel):
    model: str
    temperature: float
    max_tokens: int
    thinking_intensity: str
    available_models: list[dict]


class UpdateConfigRequest(BaseModel):
    model: str | None = None
    temperature: float | None = Field(None, ge=0.0, le=2.0)
    max_tokens: int | None = Field(None, ge=256, le=8192)
    thinking_intensity: str | None = None  # low / medium / high / max


# ── 端点 ────────────────────────────────────────────────────

@router.get("/config", response_model=ConfigResponse)
async def get_config():
    """获取当前配置。"""
    cfg = get_runtime_config()
    return ConfigResponse(
        model=cfg["model"],
        temperature=cfg["temperature"],
        max_tokens=cfg["max_tokens"],
        thinking_intensity=cfg["thinking_intensity"],
        available_models=get_available_models(),
    )


@router.post("/config", response_model=ConfigResponse)
async def update_config(req: UpdateConfigRequest, request: Request):
    """更新运行时配置，重建 Agent 使其生效。

    原子性：全部校验先在副本上完成（400 不触碰 _runtime_config），
    发布后重建 Agent；重建失败则回滚到旧配置并返回 500——
    避免「配置已改、Agent 还是旧的」的永久性状态漂移
    （此前先改 _runtime_config 再重建，重建抛异常即漂移，
    且之后每次心跳刷新都会按这份超前配置继续失败）。
    """
    # ── 1) 纯校验阶段：在副本上合并请求，任何 400 发生在写入之前 ──
    new_cfg = _runtime_config.copy()

    if req.model is not None:
        valid_ids = [m["id"] for m in get_available_models()]
        if req.model not in valid_ids:
            raise HTTPException(400, f"不支持的模型: {req.model}")
        new_cfg["model"] = req.model

    if req.thinking_intensity is not None:
        if req.thinking_intensity not in _THINKING_PRESETS:
            raise HTTPException(400, "思考强度只能是 low / medium / high / max")
        new_cfg["thinking_intensity"] = req.thinking_intensity
        preset = _THINKING_PRESETS[req.thinking_intensity]
        new_cfg["temperature"] = preset["temperature"]
        new_cfg["max_tokens"] = preset["max_tokens"]

    if req.temperature is not None:
        new_cfg["temperature"] = req.temperature
        new_cfg["thinking_intensity"] = "custom"

    if req.max_tokens is not None:
        new_cfg["max_tokens"] = req.max_tokens

    # ── 2) 发布 + 重建（_agent_lock 内原子执行）；失败 → 回滚旧配置 + 500 ──
    # 锁内完成 mutation → rebuild → 回滚三步，消除旧版中 _runtime_config.update
    # 与 rebuild_agent_for_app 之间的窗口：运行时配置已改但 Agent 仍是旧的，
    # 之后每次心跳刷新都会按超前配置继续失败，造成永久性状态漂移。
    old_cfg = _runtime_config.copy()
    async with _agent_lock:
        _runtime_config.update(new_cfg)
        try:
            await _rebuild_agent_inner(request.app)
        except Exception:
            _runtime_config.clear()
            _runtime_config.update(old_cfg)  # clear+update 保持 dict 身份不变
            log.exception("Agent 重建失败，运行时配置已回滚")
            raise HTTPException(500, "配置生效失败，已回滚到之前的设置")

    cfg = get_runtime_config()
    return ConfigResponse(
        model=cfg["model"],
        temperature=cfg["temperature"],
        max_tokens=cfg["max_tokens"],
        thinking_intensity=cfg["thinking_intensity"],
        available_models=get_available_models(),
    )
