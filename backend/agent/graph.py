"""小汐 Agent 图 — 基于 LangGraph create_react_agent。

v2 重构：
- 使用 create_react_agent 替代 create_agent
- 新增 pre_model_hook：每次 LLM 调用前动态注入记忆上下文
  - 记忆通过 llm_input_messages 注入，不进入 checkpoint（解决上下文污染）
  - 可选的消息裁剪通过 RemoveMessage 执行（解决状态膨胀）
"""
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import (
    HumanMessage,
    RemoveMessage,
    SystemMessage,
)
from langgraph.graph.state import CompiledStateGraph
from langgraph.prebuilt import create_react_agent
from langchain_core.tools import BaseTool

from utils.logger import get_logger

log = get_logger(__name__)

# ── pre_model_hook ──────────────────────────────────────────────
# 这是解决两个核心问题的关键机制：
# 1. 上下文污染：记忆叙事通过 llm_input_messages 注入，不持久化到 checkpoint
# 2. 状态膨胀：超过阈值时用 RemoveMessage 裁剪旧对话消息


def make_pre_model_hook(narrative_builder, max_conversation_msgs: int = 30):
    """创建 pre_model_hook 闭包。

    Args:
        narrative_builder: NarrativeBuilder 实例，用于获取记忆叙事文本
        max_conversation_msgs: checkpoint 中保留的最大对话消息数。
                              超过此数量时，从最早的 HumanMessage 边界开始裁剪。
    """

    async def pre_model_hook(state: dict) -> dict:
        """每次 LLM 调用前执行。

        返回 dict 可包含：
        - llm_input_messages: LLM 实际看到的消息（不进 checkpoint）
        - messages: 状态更新（RemoveMessage 会修改 checkpoint）
        """
        messages = state.get("messages", [])

        # ── ① 消息裁剪 ────────────────────────────────────────
        # 分离系统消息和对话消息
        system_msgs = [m for m in messages if getattr(m, "type", "") == "system"]
        conversation = [m for m in messages if getattr(m, "type", "") != "system"]

        to_remove = []
        if len(conversation) > max_conversation_msgs:
            # 找到安全的裁剪边界（HumanMessage 开头），避免切断工具调用链
            excess = len(conversation) - max_conversation_msgs
            trim_end = excess
            for i in range(excess, len(conversation)):
                if getattr(conversation[i], "type", "") == "human":
                    trim_end = i
                    break
            if trim_end > 0:
                to_remove = conversation[:trim_end]
                conversation = conversation[trim_end:]

        # ── ② 记忆检索 ────────────────────────────────────────
        memory_text = ""
        if narrative_builder is not None:
            try:
                memory_text = await narrative_builder.build(max_items=5)
            except Exception as e:
                log.warning("记忆叙事构建失败：%s", e)

        # ── ③ 组装 llm_input_messages（不进 checkpoint）────────
        # 保留原始系统提示（由 create_react_agent 的 prompt 参数注入）
        base_system_msgs = system_msgs

        # 注入记忆上下文为额外的 SystemMessage
        if memory_text:
            memory_msg = SystemMessage(
                content=f"[小汐的记忆]\n{memory_text}\n[记忆结束]"
            )
            base_system_msgs = list(system_msgs) + [memory_msg]

        llm_input = base_system_msgs + conversation

        result = {"llm_input_messages": llm_input}
        if to_remove:
            result["messages"] = [RemoveMessage(id=m.id) for m in to_remove]
        return result

    return pre_model_hook


# ── Agent 构建 ──────────────────────────────────────────────────


def build_agent(
    model: BaseChatModel,
    tools: list[BaseTool],
    system_prompt: str,
    checkpointer=None,
    pre_model_hook=None,
) -> CompiledStateGraph:
    """构建小汐的 ReAct Agent 图。

    Args:
        model: LLM 实例（ChatOpenAI 包装的 DeepSeek）
        tools: LangChain Tool 列表
        system_prompt: 完整系统提示词
        checkpointer: 可复用的 checkpointer（用于跨轮次状态持久化）。
                      不传则自动创建 MemorySaver。
        pre_model_hook: 每次 LLM 调用前执行的钩子，用于注入记忆和裁剪消息。

    Returns:
        编译后的 LangGraph StateGraph，可直接 ainvoke / astream_events。
    """
    if checkpointer is None:
        from langgraph.checkpoint.memory import MemorySaver
        checkpointer = MemorySaver()

    kwargs = dict(
        model=model,
        tools=tools,
        prompt=system_prompt,
        checkpointer=checkpointer,
    )

    if pre_model_hook is not None:
        kwargs["pre_model_hook"] = pre_model_hook

    return create_react_agent(**kwargs)
