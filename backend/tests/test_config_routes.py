"""运行时配置单元测试 — 默认值、思考强度映射。"""
import pytest


async def test_get_runtime_config_defaults():
    """默认配置值正确。"""
    from api.routes.config import get_runtime_config
    cfg = get_runtime_config()
    assert cfg["model"] == "deepseek-v4-flash"
    assert cfg["temperature"] == 0.7
    assert cfg["max_tokens"] == 8192
    assert cfg["thinking_intensity"] == "max"


async def test_get_current_temperature_per_intensity():
    """每种思考强度对应正确的 temperature。"""
    from api.routes.config import _THINKING_PRESETS

    assert _THINKING_PRESETS["low"]["temperature"] == 0.3
    assert _THINKING_PRESETS["medium"]["temperature"] == 0.7
    assert _THINKING_PRESETS["high"]["temperature"] == 1.0
    assert _THINKING_PRESETS["max"]["temperature"] == 1.2


async def test_thinking_presets_complete():
    """四种预设都存在且包含必要字段。"""
    from api.routes.config import _THINKING_PRESETS

    for key in ("low", "medium", "high", "max"):
        assert key in _THINKING_PRESETS
        preset = _THINKING_PRESETS[key]
        assert "temperature" in preset
        assert "max_tokens" in preset
        assert "label" in preset


async def test_get_current_model_default():
    """默认模型名称与 AVAILABLE_MODELS 一致。"""
    from api.routes.config import get_current_model
    from providers.deepseek import AVAILABLE_MODELS

    model = get_current_model()
    valid_ids = [m["id"] for m in AVAILABLE_MODELS]
    assert model in valid_ids
