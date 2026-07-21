"""配置路由 — /api/config。

提供前端控制台：模型选择、思考强度调节。
"""
import asyncio

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from providers.deepseek import get_available_models, create_deepseek_llm
from agent.graph import build_agent, make_pre_model_hook
from agent.soul import build_system_prompt

router = APIRouter()

# Agent 重建锁，防止并发 config 变更与 chat 请求之间的竞态
_agent_lock = asyncio.Lock()


# ── 运行时配置（内存中保存，重启后重置为默认值） ──────────────

_runtime_config = {
    "model": "deepseek-v4-flash",
    "temperature": 0.7,
    "max_tokens": 8192,
    "thinking_intensity": "max",
}

# 思考强度 → temperature 映射
# 诚实地说：这控制的是创造性/随机性，不是真正的"思考深度"
# 对于 V4 Flash/Pro 来说，高 temperature 让回答更发散、更有想象力
# V4 Pro 支持深度推理模式，但推理深度由模型内部控制，与 temperature 无关
_THINKING_PRESETS = {
    "low":    {"temperature": 0.3, "max_tokens": 4096,  "label": "轻柔"},
    "medium": {"temperature": 0.7, "max_tokens": 8192,  "label": "适中"},
    "high":   {"temperature": 1.0, "max_tokens": 8192,  "label": "深度"},
    "max":    {"temperature": 1.2, "max_tokens": 8192,  "label": "全力"},
}


def get_runtime_config() -> dict:
    """获取当前运行时配置。"""
    return _runtime_config.copy()


def get_current_temperature() -> float:
    """获取当前 temperature。"""
    intensity = _runtime_config.get("thinking_intensity", "max")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["temperature"]
    return _runtime_config.get("temperature", 0.7)


def get_current_max_tokens() -> int:
    """获取当前 max_tokens。"""
    intensity = _runtime_config.get("thinking_intensity", "max")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["max_tokens"]
    return _runtime_config.get("max_tokens", 8192)


def get_current_model() -> str:
    """获取当前模型名称。"""
    return _runtime_config.get("model", "deepseek-v4-flash")


async def rebuild_agent(request: Request):
    """用当前配置重建 LLM 和 Agent（同时刷新生命状态注入的 system_prompt）。

    使用 asyncio.Lock 防止与并发 chat 请求之间的竞态条件。
    """
    async with _agent_lock:
        temperature = get_current_temperature()
        max_tokens = get_current_max_tokens()
        model = get_current_model()

        new_llm = create_deepseek_llm(
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
        )

        tools = request.app.state.tools
        checkpointer = request.app.state.checkpointer

        # 刷新 system_prompt（注入最新生命状态）
        system_prompt = await build_system_prompt()
        request.app.state.system_prompt = system_prompt

        # 创建 pre_model_hook（记忆注入）
        narrative_builder = getattr(request.app.state, "narrative_builder", None)
        pre_model_hook = make_pre_model_hook(narrative_builder)

        new_agent = build_agent(new_llm, tools, system_prompt, checkpointer, pre_model_hook)
        request.app.state.agent = new_agent


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
    """更新运行时配置，重建 Agent 使其生效。"""
    old_model = _runtime_config["model"]

    if req.model is not None:
        valid_ids = [m["id"] for m in get_available_models()]
        if req.model not in valid_ids:
            raise HTTPException(400, f"不支持的模型: {req.model}")
        _runtime_config["model"] = req.model

    if req.thinking_intensity is not None:
        if req.thinking_intensity not in _THINKING_PRESETS:
            raise HTTPException(400, "思考强度只能是 low / medium / high / max")
        _runtime_config["thinking_intensity"] = req.thinking_intensity
        preset = _THINKING_PRESETS[req.thinking_intensity]
        _runtime_config["temperature"] = preset["temperature"]
        _runtime_config["max_tokens"] = preset["max_tokens"]

    if req.temperature is not None:
        _runtime_config["temperature"] = req.temperature
        _runtime_config["thinking_intensity"] = "custom"

    if req.max_tokens is not None:
        _runtime_config["max_tokens"] = req.max_tokens

    # 重建 Agent 使新配置生效
    await rebuild_agent(request)

    cfg = get_runtime_config()
    return ConfigResponse(
        model=cfg["model"],
        temperature=cfg["temperature"],
        max_tokens=cfg["max_tokens"],
        thinking_intensity=cfg["thinking_intensity"],
        available_models=get_available_models(),
    )
