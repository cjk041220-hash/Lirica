"""叙事构建器 — 将离散记忆编织为连贯叙事，注入系统提示词。"""
from memory.manager import MemoryManager


class NarrativeBuilder:
    """将记忆列表构建为适合注入系统提示词的叙述文本。

    不做 LLM 二次总结（保持简洁），而是按类别分组 + 时间排序，
    形成自然的人物记忆画像。
    """

    def __init__(self, manager: MemoryManager | None = None):
        self._manager = manager

    def set_manager(self, manager: MemoryManager):
        self._manager = manager

    def build(self, max_items: int = 30) -> str:
        """从 MemoryManager 读取记忆，构建叙事文本。"""
        if self._manager is None or self._manager.count == 0:
            return ""

        memories = self._manager.all()

        # 按时间倒序取最近 N 条
        recent = sorted(memories, key=lambda m: m.get("id", ""), reverse=True)[:max_items]
        # 按时间正序展示（最早的在前）
        recent.reverse()

        if not recent:
            return ""

        # 按类别分组
        grouped: dict[str, list[str]] = {}
        for m in recent:
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
