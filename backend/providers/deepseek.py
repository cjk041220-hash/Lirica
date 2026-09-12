"""DeepSeek Provider — OpenAI 兼容接口。"""
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

from config.settings import get_settings

# ── 可用模型列表 ──────────────────────────────────────────────

AVAILABLE_MODELS = [
    {
        "id": "deepseek-v4.1-flash",
        "name": "DeepSeek V4.1 Flash",
        "description": "284B MoE (13B active)，轻量快速，1M 上下文",
        "supports_thinking": False,
        "max_output_tokens": 8192,
        "context_window": "1M",
    },
    {
        "id": "deepseek-v4-pro",
        "name": "DeepSeek V4 Pro",
        "description": "1.6T MoE (49B active)，深度推理旗舰，1M 上下文",
        "supports_thinking": True,
        "max_output_tokens": 8192,
        "context_window": "1M",
    },
]

# 模型名别名兼容表（兼容旧客户端配置、历史版本与简写）
_MODEL_ALIASES = {
    "deepseek-v4-flash": "deepseek-v4.1-flash",
    "deepseek-flash": "deepseek-v4.1-flash",
    "deepseek-chat": "deepseek-v4.1-flash",
}

# 官方 DeepSeek API (api.deepseek.com) 统一使用 deepseek-flash 作为轻量 Flash 模型的标准 API 名。
# 业务层使用版本化模型名（如 deepseek-v4.1-flash），此处向上游 API 透明映射至受支持的官方底座名。
_UPSTREAM_MODEL_MAP = {
    "deepseek-v4.1-flash": "deepseek-flash",
    "deepseek-v4-flash": "deepseek-flash",
}

# 模型 → 元数据索引（防每次调用线性扫描；AVAILABLE_MODELS 增删模型
# 时此索引自动跟随——字典推导式，无独立维护面）
_MODEL_META = {m["id"]: m for m in AVAILABLE_MODELS}

# 请求超时 / 重试（与历史值保持一致，零行为变化）
_REQUEST_TIMEOUT = 120
_MAX_RETRIES = 2


def get_available_models() -> list[dict]:
    """返回可用模型列表。"""
    return AVAILABLE_MODELS


def get_model_info(model: str | None) -> dict | None:
    """查询模型元数据（能力/限额），未知模型返回 None。

    为「关键场景升级 Pro」铺路（评估报告 P2-3）：调用方可用
    ``supports_thinking``（是否支持深度推理）/ ``max_output_tokens``
    （输出上限）/ ``context_window`` 做场景决策，不再各自硬编码。
    """
    if not model:
        return None
    resolved = _MODEL_ALIASES.get(model, model)
    return _MODEL_META.get(resolved)


def create_deepseek_llm(
    model: str | None = None,
    temperature: float | None = None,
    max_tokens: int | None = None,
    presence_penalty: float | None = None,
    frequency_penalty: float | None = None,
) -> BaseChatModel:
    """创建 DeepSeek Chat 模型实例。

    Args:
        model: 模型名称（须在 AVAILABLE_MODELS 内；未传/未知 → 默认配置）。
        temperature: 温度参数（控制输出随机性）
        max_tokens: 最大输出 token 数；超过模型上限（max_output_tokens）
                    时钳制到上限（防配错越界，当前两模型均为 8192，
                    钳制为恒等——行为零变化，为将来上限不同的模型兜底）
        presence_penalty: 主题存在惩罚（>0 鼓励涉及新话题，抑制复读）
        frequency_penalty: 词频惩罚（>0 抑制重复词汇）
    """
    s = get_settings()
    raw_model = model or s.deepseek_model
    resolved = _MODEL_ALIASES.get(raw_model, raw_model)
    meta = _MODEL_META.get(resolved)
    if meta is None:
        # 未知模型名（配置漂移/手误）：回退默认模型，不静默透传——
        # 透传会让 OpenAI 兼容层报「未知模型」且无本地日志可查
        resolved = _MODEL_ALIASES.get(s.deepseek_model, s.deepseek_model)
        meta = _MODEL_META.get(resolved) or {}

    if max_tokens is not None:
        cap = meta.get("max_output_tokens")
        if cap and max_tokens > cap:
            max_tokens = cap

    upstream_model = _UPSTREAM_MODEL_MAP.get(resolved, resolved)

    kwargs = {
        "model": upstream_model,
        "api_key": s.deepseek_api_key,
        "base_url": s.deepseek_base_url,
        "temperature": temperature if temperature is not None else 0.8,
        "max_tokens": max_tokens or 8192,
        "timeout": _REQUEST_TIMEOUT,
        "max_retries": _MAX_RETRIES,
    }
    if presence_penalty is not None:
        kwargs["presence_penalty"] = presence_penalty
    if frequency_penalty is not None:
        kwargs["frequency_penalty"] = frequency_penalty

    return ChatOpenAI(**kwargs)
