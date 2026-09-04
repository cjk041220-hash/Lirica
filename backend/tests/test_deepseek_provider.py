"""Provider 抽象层单测（P2-6，2026-08-13）。

deepseek.py 从「57 行单模型」升级为「工厂消费模型元数据」：
- get_model_info：查询能力/限额（supports_thinking / max_output_tokens /
  context_window），为「关键场景升级 Pro」铺路（评估报告 P2-3）；
- create_deepseek_llm：未知模型名回退默认 + max_tokens 越界钳制。

纪律：工厂行为零变化（默认 flash / temperature 0.8 / max_tokens 8192 /
timeout 120 / retries 2 一律不动）；本测试只钉「元数据可查 + 钳制/回退
边界」，不发起真实网络。
"""

from providers.deepseek import (
    _MODEL_META,
    AVAILABLE_MODELS,
    create_deepseek_llm,
    get_model_info,
)


def test_model_meta_index_consistent():
    """索引自动跟随 AVAILABLE_MODELS（键集一致，防线性扫描漂移）。"""
    assert set(_MODEL_META) == {m["id"] for m in AVAILABLE_MODELS}
    # 元数据字段完整（能力/限额/上下文——消费方依赖）
    for m in AVAILABLE_MODELS:
        assert {"id", "supports_thinking", "max_output_tokens", "context_window"} <= set(m)


def test_get_model_info_known_and_unknown():
    """已知模型返回能力元数据；未知/空 → None。"""
    flash = get_model_info("deepseek-v4-flash")
    assert flash is not None
    assert flash["supports_thinking"] is False
    assert flash["max_output_tokens"] == 8192

    pro = get_model_info("deepseek-v4-pro")
    assert pro is not None
    assert pro["supports_thinking"] is True  # pro 支持深度推理（flash 不支持）

    assert get_model_info("not-a-model") is None
    assert get_model_info(None) is None
    assert get_model_info("") is None


def test_create_llm_unknown_model_falls_back_to_default(monkeypatch):
    """未知模型名 → 回退默认（不静默透传给 API 层）。"""
    captured = {}

    class _FakeLLM:
        def __init__(self, **kw):
            captured.update(kw)

    import providers.deepseek as ds
    monkeypatch.setattr(ds, "ChatOpenAI", _FakeLLM)

    create_deepseek_llm(model="typo-model")
    assert captured["model"] == "deepseek-v4-pro"  # settings 默认


def test_create_llm_max_tokens_clamped_to_model_cap(monkeypatch):
    """max_tokens 超过模型上限 → 钳制到 max_output_tokens（防配错越界）。"""
    captured = {}

    class _FakeLLM:
        def __init__(self, **kw):
            captured.update(kw)

    import providers.deepseek as ds
    monkeypatch.setattr(ds, "ChatOpenAI", _FakeLLM)

    create_deepseek_llm(model="deepseek-v4-flash", max_tokens=16384)
    assert captured["max_tokens"] == 8192  # 上限

    # 未越界 → 原样透传
    create_deepseek_llm(model="deepseek-v4-flash", max_tokens=4096)
    assert captured["max_tokens"] == 4096


def test_create_llm_defaults_unchanged(monkeypatch):
    """默认参数零变化（钉死既有行为，防重构漂移）。"""
    captured = {}

    class _FakeLLM:
        def __init__(self, **kw):
            captured.update(kw)

    import providers.deepseek as ds
    monkeypatch.setattr(ds, "ChatOpenAI", _FakeLLM)

    create_deepseek_llm()
    assert captured["model"] == "deepseek-v4-pro"   # settings 默认
    assert captured["temperature"] == 0.8
    assert captured["max_tokens"] == 8192
    assert captured["timeout"] == 120
    assert captured["max_retries"] == 2


def test_create_llm_passes_presence_and_frequency_penalty(monkeypatch):
    """支持透传 presence_penalty 与 frequency_penalty。"""
    captured = {}

    class _FakeLLM:
        def __init__(self, **kw):
            captured.update(kw)

    import providers.deepseek as ds
    monkeypatch.setattr(ds, "ChatOpenAI", _FakeLLM)

    create_deepseek_llm(presence_penalty=0.2, frequency_penalty=0.1)
    assert captured["presence_penalty"] == 0.2
    assert captured["frequency_penalty"] == 0.1
