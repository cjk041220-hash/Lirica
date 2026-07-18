"""DeepSeek Provider — OpenAI 兼容接口。"""
from langchain_openai import ChatOpenAI
from langchain_core.language_models.chat_models import BaseChatModel
from config.settings import get_settings


# ── 可用模型列表 ──────────────────────────────────────────────
# 注意：DeepSeek API 没有 "reasoning_effort" 参数
# R1 的深度推理是模型内置能力，不受外部参数控制
# temperature 控制的是输出随机性/创造性，不是思考深度
# max_tokens 是真正影响"模型能说多少话"的参数

AVAILABLE_MODELS = [
    {
        "id": "deepseek-v4-flash",
        "name": "DeepSeek V4 Flash",
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
        "reasoning_effort": True,
        "max_output_tokens": 8192,
        "context_window": "1M",
    },
]


def get_available_models() -> list[dict]:
    """返回可用模型列表。"""
    return AVAILABLE_MODELS


def create_deepseek_llm(
    model: str | None = None,
    temperature: float | None = None,
    max_tokens: int | None = None,
    reasoning_effort: float | None = None,
) -> BaseChatModel:
    """创建 DeepSeek Chat 模型实例。

    Args:
        model: 模型名称，不传则使用默认配置
        temperature: 温度参数（控制输出随机性，不是思考深度）
        max_tokens: 最大输出 token 数，拉满让模型充分表达

    参数真相：
    - temperature: 0=确定性高, 1=创造性高。不是"思考深度"
    - max_tokens: V3/R1 最大都是 8192，这是真正限制输出长度的参数
    - R1 的思维链是模型内置，不受 temperature 影响
    - DeepSeek 上下文窗口：V3 为 64K, R1 为 128K
    """
    s = get_settings()
    kwargs = {}
    if reasoning_effort is not None:
        kwargs["reasoning_effort"] = reasoning_effort

    params = dict(
        model=model or s.deepseek_model,
        api_key=s.deepseek_api_key,
        base_url=s.deepseek_base_url,
        temperature=temperature if temperature is not None else 0.7,
        max_tokens=max_tokens or 8192,
        timeout=120,
        max_retries=2,
    )
    # 只在有额外参数时才传 model_kwargs（None 会导致 langchain 崩溃）
    if kwargs:
        params["model_kwargs"] = kwargs

    return ChatOpenAI(**params)
