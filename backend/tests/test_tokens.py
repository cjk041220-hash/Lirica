"""tiktoken 遥测测试（B3）：计数确定性 + 两处埋点出数。"""
import logging

from langchain_core.messages import HumanMessage


def test_count_tokens_deterministic():
    """同入同出；空串 0；中文 > 0。"""
    from utils.tokens import count_tokens

    text = "小汐陪冷冷聊天"
    assert count_tokens(text) == count_tokens(text)
    assert count_tokens(text) > 0
    assert count_tokens("") == 0


def test_count_tokens_encoding_cached():
    """编码对象惰性单例：多次调用共享同一实例。"""
    import utils.tokens as tokens

    e1 = tokens._get_encoding()
    e2 = tokens._get_encoding()
    assert e1 is e2


def test_count_tokens_failure_returns_zero():
    """计数异常降级为 0，绝不外抛（遥测不得拖累主链路）。"""
    from unittest.mock import patch
    from utils.tokens import count_tokens

    with patch("utils.tokens._get_encoding", side_effect=RuntimeError("boom")):
        assert count_tokens("任何文本") == 0


async def test_build_system_prompt_logs_telemetry(caplog):
    """soul 埋点：build_system_prompt 打出 system_prompt approx_tokens。"""
    from agent.soul import build_system_prompt

    with caplog.at_level(logging.INFO, logger="agent.soul"):
        await build_system_prompt()

    assert any("telemetry: system_prompt approx_tokens=" in r.message
               for r in caplog.records)


async def test_pre_model_hook_logs_telemetry(caplog):
    """graph 埋点：pre_model_hook 打出 narrative 与 total_input 计数。

    DEBUG 级（体检杂项：钩子每个模型调用前跑一次，工具循环一轮可达
    ~25 跳，INFO 刷屏；调参时临时开 DEBUG。每轮遥测仍在 chat.py INFO）。
    """
    from agent.graph import make_pre_model_hook

    class StubBuilder:
        async def build(self, max_items=30, query=""):
            return "以下是小汐记住的关于冷冷的事情：\n\n### 喜好\n- 喜欢火锅\n"

    hook = make_pre_model_hook(StubBuilder())
    with caplog.at_level(logging.DEBUG, logger="agent.graph"):
        await hook({"messages": [HumanMessage(content="冷冷想吃火锅")]})

    joined = "\n".join(r.message for r in caplog.records)
    assert "narrative approx_tokens=" in joined
    assert "total_input approx_tokens=" in joined
