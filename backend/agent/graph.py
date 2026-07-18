"""小汐 Agent 图 — 基于 LangChain create_agent。"""
from langchain.agents import create_agent
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph.state import CompiledStateGraph
from langchain_core.language_models import BaseChatModel
from langchain_core.tools import BaseTool


def build_agent(
    model: BaseChatModel,
    tools: list[BaseTool],
    system_prompt: str,
    checkpointer=None,
) -> CompiledStateGraph:
    """构建小汐的 ReAct Agent 图。

    Args:
        model: LLM 实例（ChatOpenAI 包装的 DeepSeek）
        tools: LangChain Tool 列表
        system_prompt: 完整系统提示词
        checkpointer: 可复用的 checkpointer（用于跨轮次状态持久化）。
                      不传则自动创建 MemorySaver。

    Returns:
        编译后的 LangGraph StateGraph，可直接 ainvoke / astream_events。
    """
    if checkpointer is None:
        checkpointer = MemorySaver()

    return create_agent(
        model=model,
        tools=tools,
        system_prompt=system_prompt,
        checkpointer=checkpointer,
    )
