"""人格重锚中间件单测 — 每 N 次模型调用注入 PERSONA_ANCHOR（批次①）。

注入经 awrap_model_call 用 request.override 就地替换模型输入（纯内存、
不进 checkpoint），与记忆叙事同机制；计数器是中间件实例属性，跨会话
累积——单用户设计下这是特性（活跃用户上下文长得快、锚得更勤），不是
缺陷。
"""
from langchain.agents.middleware import ModelRequest, ModelResponse
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from agent.graph import _PersonaAnchorMiddleware, _PreModelHookMiddleware


def _make_request(messages, state=None):
    """构造 ModelRequest（与 create_agent 模型节点构造的等价）。"""
    return ModelRequest(
        model=None,
        messages=list(messages),
        system_message=SystemMessage(content="你是小汐"),
        state=state if state is not None else {"messages": list(messages)},
        runtime=None,
        tools=[],
    )


def _capture_handler(captured):
    """返回记录模型实际收到的输入的 handler。

    awrap_model_call 用 override 不可变替换 request——只有到达 handler
    的 request 才是模型真实输入，断言必须在这里取。
    """
    async def handler(request):
        captured.append(list(request.messages))
        return ModelResponse(result=[AIMessage(content="测试回复")])
    return handler


def _make_state():
    return {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }


def _anchors(messages):
    return [m for m in messages
            if isinstance(m, SystemMessage) and "人格锚" in m.content]


async def test_no_anchor_before_interval():
    """第 1..interval-1 次模型调用不注入锚。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        captured = []
        await mw.awrap_model_call(
            _make_request(_make_state()["messages"]), _capture_handler(captured))
        assert not _anchors(captured[0])


async def test_anchor_injected_on_interval():
    """第 interval 次调用注入锚 SystemMessage，追加在系统段末尾。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        await mw.awrap_model_call(
            _make_request(_make_state()["messages"]), _capture_handler([]))
    captured = []
    await mw.awrap_model_call(
        _make_request(_make_state()["messages"]), _capture_handler(captured))
    model_input = captured[0]
    system_msgs = [m for m in model_input if isinstance(m, SystemMessage)]
    # 位置钉用中间件自有标记（锚首/锚尾），不绑锚文本措辞——锚内容
    # 与 soul 常量的一致性由 test_anchor_content_is_soul_constant 钉死
    assert "[锚结束]" in system_msgs[-1].content  # 锚在系统段末尾
    assert len(_anchors(model_input)) == 1  # 锚唯一


async def test_counter_resets_after_injection():
    """注入后计数归零，下一周期重复（8 的倍数次仍注入）。"""
    mw = _PersonaAnchorMiddleware(interval=4)
    for _ in range(3):
        await mw.awrap_model_call(
            _make_request(_make_state()["messages"]), _capture_handler([]))
    captured1 = []
    await mw.awrap_model_call(
        _make_request(_make_state()["messages"]), _capture_handler(captured1))
    assert len(_anchors(captured1[0])) == 1  # 第 4 次注入
    captured2 = []
    await mw.awrap_model_call(
        _make_request(_make_state()["messages"]), _capture_handler(captured2))
    assert not _anchors(captured2[0])  # 归零后第 1 次不注入
    for _ in range(2):
        await mw.awrap_model_call(
            _make_request(_make_state()["messages"]), _capture_handler([]))
    captured4 = []
    await mw.awrap_model_call(
        _make_request(_make_state()["messages"]), _capture_handler(captured4))
    assert len(_anchors(captured4[0])) == 1  # 第 8 次再次注入


async def test_anchor_content_is_soul_constant():
    """锚文本与 soul.PERSONA_ANCHOR 逐字节一致（防两处漂移）。"""
    from agent.soul import PERSONA_ANCHOR
    mw = _PersonaAnchorMiddleware(interval=1)
    captured = []
    await mw.awrap_model_call(
        _make_request(_make_state()["messages"]), _capture_handler(captured))
    system_msgs = [m for m in captured[0] if isinstance(m, SystemMessage)]
    assert PERSONA_ANCHOR in system_msgs[-1].content


async def test_anchor_preserves_prior_llm_input_messages():
    """锚注入必须保留前序中间件（记忆叙事）构造的输入。

    实测结论（批① Task 2 Step 0）：langchain 1.3.14 的 create_agent 中
    llm_input_messages 不是 state channel（abefore_model 的返回被静默
    丢弃），注入统一走 awrap_model_call 就地改写 request.messages——
    request.messages 即真实模型输入，锚在系统段末尾追加，前序输入
    （记忆叙事）必须原样保留、且锚只出现一次。
    """
    mw = _PersonaAnchorMiddleware(interval=1)
    prior = [
        SystemMessage(content="你是小汐"),
        SystemMessage(content="[小汐的记忆 —— 以下是关于冷冷的资料数据]\n爱吃芒果"),
        HumanMessage(content="你好", id="h1"),
    ]
    captured = []
    await mw.awrap_model_call(_make_request(prior), _capture_handler(captured))
    model_input = captured[0]
    sys_msgs = [m for m in model_input if isinstance(m, SystemMessage)]
    assert "人格锚" in sys_msgs[-1].content
    assert len(_anchors(model_input)) == 1  # 锚唯一
    assert "爱吃芒果" in "".join(m.content for m in sys_msgs)  # 记忆叙事必须保留


async def test_hook_middleware_delivers_llm_input_to_request():
    """回归（v2.2 迁移丢语义）：hook 的 llm_input 必须落到模型输入。

    abefore_model 返回的 llm_input_messages 在本版本被静默丢弃
    （实测钉死），改经 awrap_model_call 改写 request.messages。
    """
    async def hook(state):
        return {
            "llm_input_messages": [
                SystemMessage(content="你是小汐"),
                SystemMessage(content="[小汐的记忆 —— 以下是关于冷冷的资料数据]\n爱吃芒果"),
                HumanMessage(content="你好", id="h1"),
            ]
        }

    mw = _PreModelHookMiddleware(hook)
    captured = []
    await mw.awrap_model_call(
        _make_request([HumanMessage(content="你好", id="h1")]),
        _capture_handler(captured))
    sys_msgs = [m for m in captured[0] if isinstance(m, SystemMessage)]
    assert "爱吃芒果" in "".join(m.content for m in sys_msgs)


async def test_hook_middleware_trim_flows_as_command():
    """hook 的 RemoveMessage 裁剪经 command 应用到 state（不进 llm_input）。"""
    from langchain_core.messages import RemoveMessage

    async def hook(state):
        return {
            "llm_input_messages": [HumanMessage(content="你好", id="h1")],
            "messages": [RemoveMessage(id="old1")],
        }

    mw = _PreModelHookMiddleware(hook)
    captured = []
    result = await mw.awrap_model_call(
        _make_request([HumanMessage(content="你好", id="h1")]),
        _capture_handler(captured))
    from langchain.agents.middleware import ExtendedModelResponse

    assert isinstance(result, ExtendedModelResponse)
    assert result.command is not None
    updates = result.command.update
    assert [m.id for m in updates["messages"]] == ["old1"]
    # llm_input 已落到模型输入
    assert captured[0][0].content == "你好"


class _CapturingChatModel(BaseChatModel):
    """真实模型子类：记录每次模型调用的输入消息（链式语义实测用）。"""

    @property
    def _llm_type(self) -> str:
        return "capturing"

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise NotImplementedError("同步路径不在本测试覆盖范围")

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        from langchain_core.outputs import ChatGeneration, ChatResult

        self._last_input = list(messages)
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage(content="测试回复"))]
        )


async def test_build_agent_with_anchor_interval():
    """build_agent 传 anchor_interval 时锚就位；不传时旧行为不变。

    断言方式（实测决定）：langchain 1.3.14 的 CompiledStateGraph 不暴露
    .middleware 属性，改用行为级断言——真实 ainvoke 一轮、用捕获型
    fake model 验证模型输入是否含「人格锚」SystemMessage。
    """
    from agent.graph import build_agent

    model = _CapturingChatModel()
    agent = build_agent(model, [], "你是小汐", anchor_interval=1)
    await agent.ainvoke(
        {"messages": [HumanMessage(content="你好", id="h1")]},
        config={"configurable": {"thread_id": "wiring-on"}},
    )
    assert len(_anchors(model._last_input)) == 1  # 传参则锚就位

    model2 = _CapturingChatModel()
    agent2 = build_agent(model2, [], "你是小汐")
    await agent2.ainvoke(
        {"messages": [HumanMessage(content="你好", id="h1")]},
        config={"configurable": {"thread_id": "wiring-off"}},
    )
    assert not _anchors(model2._last_input)  # 不传则旧行为（无锚）


async def test_real_chain_anchor_round_preserves_narrative():
    """真实调用链实测：锚轮输入必须同时含记忆叙事与锚（合并语义钉死）。

    make_pre_model_hook + _PersonaAnchorMiddleware(interval=1) 同时接线，
    走 create_agent 完整链路 ainvoke 一轮，断言模型实际收到的输入
    （行为级断言，不依赖 CompiledStateGraph 内部属性）。
    """
    from agent.graph import build_agent, make_pre_model_hook

    class _StubNarrative:
        async def build(self, max_items=5, query=""):
            return "爱吃芒果"

    model = _CapturingChatModel()
    agent = build_agent(
        model,
        [],
        "你是小汐",
        pre_model_hook=make_pre_model_hook(_StubNarrative()),
        anchor_interval=1,  # 首轮即锚：同时验证 hook 与锚共存
    )
    result = await agent.ainvoke(
        {"messages": [HumanMessage(content="你好", id="h1")]},
        config={"configurable": {"thread_id": "semantics-test"}},
    )
    captured = model._last_input
    sys_msgs = [m for m in captured if isinstance(m, SystemMessage)]
    assert any("人格锚" in m.content for m in sys_msgs)
    assert len(_anchors(captured)) == 1  # 锚唯一
    assert "语气铁律" in sys_msgs[-1].content  # PHI 语气重锚在系统段最末尾（最贴近对话）
    assert "爱吃芒果" in "".join(m.content for m in sys_msgs)  # 记忆叙事必须保留
    # 不进 checkpoint：ainvoke 终态 messages 不得含锚（override 纯内存，
    # 结构性钉死——此前仅读码验证）
    assert not _anchors(result["messages"])
    assert not any("语气铁律" in getattr(m, "content", "") for m in result["messages"])


async def test_tone_anchor_middleware_injects_every_call():
    """_ToneAnchorMiddleware 每轮模型调用均在系统段末尾注入 TONE_ANCHOR。"""
    from agent.graph import _ToneAnchorMiddleware
    from agent.soul import TONE_ANCHOR

    mw = _ToneAnchorMiddleware()
    captured = []
    await mw.awrap_model_call(_make_request(_make_state()["messages"]), _capture_handler(captured))
    sys_msgs = [m for m in captured[0] if isinstance(m, SystemMessage)]
    assert len(sys_msgs) == 2
    assert sys_msgs[-1].content == TONE_ANCHOR
