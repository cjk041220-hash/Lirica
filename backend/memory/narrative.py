"""叙事构建器 — 将离散记忆编织为连贯叙事，注入系统提示词。

v2 重构：
- 适配新 schema 字段名（created_at 替代 time）
- 删除无用的 set_manager() 方法
- 简化输出格式
"""
from memory.manager import MemoryManager


class NarrativeBuilder:
    """将记忆列表构建为适合注入系统提示词的叙述文本。

    不做 LLM 二次总结（保持简洁），而是按类别分组 + 时间排序，
    形成自然的人物记忆画像。

    注意：构建结果由 pre_model_hook 注入 llm_input_messages，
    不会进入 LangGraph checkpoint，避免上下文污染。
    """

    def __init__(self, manager: MemoryManager | None = None):
        self._manager = manager

    async def build(self, max_items: int = 30) -> str:
        """从 MemoryManager 读取记忆，构建叙事文本。"""
        if self._manager is None:
            return ""
        total = await self._manager.count()
        if total == 0:
            return ""

        # SQL 层面 LIMIT，避免全表扫描
        memories = await self._manager.recent(max_items)

        if not memories:
            return ""

        # 按类别分组（memories 已由 recent() 按时间正序排列）
        grouped: dict[str, list[str]] = {}
        for m in memories:
            cat = m.get("category", "其他")
            content = m.get("content", "")
            if cat not in grouped:
                grouped[cat] = []
            grouped[cat].append(content)

        lines = ["以下是小汐记住的关于冷冷的事情：", ""]
        for cat, items in grouped.items():
            lines.append(f"### {cat}")
            for item in items:
                lines.append(f"- {item}")
            lines.append("")

        return "\n".join(lines)
