"""运行时配置单元测试 — 默认值、思考强度映射、统一重建入口。"""
import pytest


async def test_get_runtime_config_defaults():
    """默认配置值正确。

    默认值必须与启动时 create_deepseek_llm() 的实际参数一致
    （temperature=0.8 / max_tokens=8192 / flash），即 medium 档；
    否则 GET /api/config 在用户首次配置前会报告与实际不符的状态。
    """
    from api.routes.config import get_runtime_config

    cfg = get_runtime_config()
    assert cfg["model"] == "deepseek-v4.1-flash"
    assert cfg["temperature"] == 0.8
    assert cfg["max_tokens"] == 8192
    assert cfg["thinking_intensity"] == "medium"

    # medium 预设与实际默认参数一致（单一数据源）
    from api.routes.config import _THINKING_PRESETS
    medium = _THINKING_PRESETS["medium"]
    assert medium["temperature"] == 0.8
    assert medium["max_tokens"] == 8192


async def test_get_current_temperature_per_intensity():
    """每种思考强度对应正确的 temperature。"""
    from api.routes.config import _THINKING_PRESETS

    assert _THINKING_PRESETS["low"]["temperature"] == 0.3
    assert _THINKING_PRESETS["medium"]["temperature"] == 0.8
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


async def test_create_llm_from_runtime_config_reflects_updates(monkeypatch):
    """create_llm_from_runtime_config 忠实反映运行时配置变更。"""
    from api.routes import config as cfg

    captured = {}

    def fake_create(model=None, temperature=None, max_tokens=None):
        captured.update(model=model, temperature=temperature, max_tokens=max_tokens)
        return object()

    monkeypatch.setattr(cfg, "create_deepseek_llm", fake_create)

    original = cfg._runtime_config.copy()
    try:
        cfg._runtime_config.update({
            "model": "deepseek-v4-pro",
            "thinking_intensity": "max",
            "temperature": 1.2,
            "max_tokens": 8192,
        })
        cfg.create_llm_from_runtime_config()
        assert captured == {
            "model": "deepseek-v4-pro",
            "temperature": 1.2,
            "max_tokens": 8192,
        }
    finally:
        cfg._runtime_config.clear()
        cfg._runtime_config.update(original)


class _FakeApp:
    """最小化的 app.state 替身，用于重建入口的接线测试。"""

    def __init__(self):
        self.state = type("State", (), {})()
        self.state.tools = []
        self.state.checkpointer = None
        self.state.narrative_builder = None
        self.state.model = "STALE_STARTUP_MODEL"
        self.state.agent = None
        self.state.system_prompt = None


async def test_rebuild_agent_for_app_syncs_model_prompt_agent(monkeypatch):
    """rebuild_agent_for_app 同时更新 model / system_prompt / agent 三者。"""
    from api.routes import config as cfg

    sentinel_llm = object()
    sentinel_agent = object()
    build_calls = {}

    monkeypatch.setattr(cfg, "create_llm_from_runtime_config", lambda: sentinel_llm)

    async def fake_prompt():
        return "PROMPT"

    monkeypatch.setattr(cfg, "build_system_prompt", fake_prompt)

    def fake_build_agent(model, tools, system_prompt, checkpointer,
                         pre_model_hook, anchor_interval=None):
        build_calls["model"] = model
        build_calls["prompt"] = system_prompt
        build_calls["anchor_interval"] = anchor_interval
        return sentinel_agent

    monkeypatch.setattr(cfg, "build_agent", fake_build_agent)

    app = _FakeApp()
    await cfg.rebuild_agent_for_app(app)

    # 构建用的是运行时配置的新 LLM，而不是启动时的旧引用
    assert build_calls["model"] is sentinel_llm
    assert build_calls["prompt"] == "PROMPT"
    # 人格重锚（批①）：Agent 重建必须带 anchor_interval=8
    assert build_calls["anchor_interval"] == 8

    # 三者同时更新，保持单一数据源
    assert app.state.model is sentinel_llm
    assert app.state.agent is sentinel_agent
    assert app.state.system_prompt == "PROMPT"


async def test_heartbeat_refresh_delegates_to_unified_rebuild(monkeypatch):
    """回归：心跳刷新走统一重建入口，不再用启动默认 LLM 回滚用户配置。

    v0.4.2 缺陷：heartbeat._refresh_agent 直接用 app.state.model（启动默认值）
    重建 agent，而配置变更从不回写 app.state.model → 用户切换模型/思考强度后
    最多 2 小时被心跳静默回滚。
    """
    import heartbeat
    from api.routes import config as cfg

    delegated = {}

    async def fake_rebuild(app):
        delegated["called"] = True
        delegated["app"] = app

    monkeypatch.setattr(cfg, "rebuild_agent_for_app", fake_rebuild)

    app = _FakeApp()
    await heartbeat._refresh_agent(app)

    assert delegated.get("called") is True
    assert delegated["app"] is app


# ── update_config 原子性 ──────────────────────────────────────

async def test_update_config_rolls_back_on_rebuild_failure(monkeypatch):
    """重建失败 → 500 + _runtime_config 回滚旧值（不产生永久性状态漂移）。"""
    from types import SimpleNamespace

    from fastapi import HTTPException

    from api.routes import config as cfg

    original = cfg._runtime_config.copy()

    async def broken_rebuild(app):
        raise RuntimeError("boom")

    monkeypatch.setattr(cfg, "_rebuild_agent_inner", broken_rebuild)

    fake_request = SimpleNamespace(app=_FakeApp())
    req = cfg.UpdateConfigRequest(model="deepseek-v4-pro")
    try:
        with pytest.raises(HTTPException) as exc_info:
            await cfg.update_config(req, fake_request)
        assert exc_info.value.status_code == 500
        # 配置完整回滚到提交前（model 不得停留在 pro）
        assert cfg._runtime_config == original
    finally:
        cfg._runtime_config.clear()
        cfg._runtime_config.update(original)


async def test_update_config_validation_failure_does_not_touch_config(monkeypatch):
    """校验失败（400）不得修改 _runtime_config，也不得触发重建。"""
    from types import SimpleNamespace

    from fastapi import HTTPException

    from api.routes import config as cfg

    original = cfg._runtime_config.copy()

    async def should_not_be_called(app):
        raise AssertionError("校验失败不应走到重建")

    monkeypatch.setattr(cfg, "rebuild_agent_for_app", should_not_be_called)

    fake_request = SimpleNamespace(app=_FakeApp())
    # model 非法（400），同请求里还带了一个合法的 temperature
    req = cfg.UpdateConfigRequest(model="no-such-model", temperature=0.5)
    try:
        with pytest.raises(HTTPException) as exc_info:
            await cfg.update_config(req, fake_request)
        assert exc_info.value.status_code == 400
        assert cfg._runtime_config == original  # temperature 不得被写入
    finally:
        cfg._runtime_config.clear()
        cfg._runtime_config.update(original)
