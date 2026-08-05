"""DeepSeek Provider — OpenAI 兼容接口。"""
from langchain_openai import ChatOpenAI
from langchain_core.language_models.chat_models import BaseChatModel
from config.settings import get_settings


# ── 可用模型列表 ──────────────────────────────────────────────

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
) -> BaseChatModel:
    """创建 DeepSeek Chat 模型实例。

    Args:
        model: 模型名称，不传则使用默认配置
        temperature: 温度参数（控制输出随机性）
        max_tokens: 最大输出 token 数
    """
    s = get_settings()

    return ChatOpenAI(
        model=model or s.deepseek_model,
        api_key=s.deepseek_api_key,
        base_url=s.deepseek_base_url,
        temperature=temperature if temperature is not None else 0.8,
        max_tokens=max_tokens or 8192,
        timeout=120,
        max_retries=2,
    )
