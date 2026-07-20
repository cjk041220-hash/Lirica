"""小汐的记忆工具 — 通过 MemoryManager 读写。"""
import json
from langchain_core.tools import tool
from memory.manager import MemoryManager

# 由 server.py 在 lifespan 中注入
_memory_manager: MemoryManager | None = None


def set_memory_manager(mgr: MemoryManager):
    """注入 MemoryManager 实例（在 lifespan 中调用）。"""
    global _memory_manager
    _memory_manager = mgr


@tool
def remember(category: str, content: str) -> str:
    """让小汐记住一件事。冷冷说了关于自己的重要信息时主动调用。
    自动去重：同样的内容不会重复记录。

    Args:
        category: 记忆类别，如「冷冷喜欢的」「去过的地方」「重要的事」
        content: 要记住的具体内容
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        _memory_manager.add(category, content)
        return f"✨ 小汐帮你收好啦！放在映海的「{category}」里。"
    except Exception as e:
        return f"呜，小汐没接住这段记忆……（{str(e)[:100]}）"


@tool
def recall(category: str = "") -> str:
    """让小汐回忆之前记住的事情。冷冷问"我之前……"或聊天中提到过去的事时主动调用。

    Args:
        category: 按类别筛选记忆，不传则返回全部
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        mems = _memory_manager.recall(category)
        if category:
            if not mems:
                return f"小汐在映海里翻了翻，没有找到关于「{category}」的记忆……"
            header = f"🐋 小汐记得关于「{category}」的这些：\n\n"
        else:
            if not mems:
                return "小汐还没有记住什么事情呢……映海里空空的。"
            header = "🐋 小汐记得这些事：\n\n"

        result = header
        for m in mems[-10:]:
            result += f"📌 **{m.get('category', '')}** ({m.get('time', '')})\n"
            result += f"   {m.get('content', '')}\n\n"

        return result
    except Exception as e:
        return f"小汐去映海里找记忆的时候走散了……（{str(e)[:100]}）"


@tool
def list_memories() -> str:
    """列出小汐记住的所有记忆类别和数量。冷冷想看记忆总览时调用。"""
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        cats = _memory_manager.list_categories()
        if not cats:
            return "小汐的映海里还什么都没有……"

        result = "🐋 小汐的映海记忆库：\n\n"
        for cat, count in cats.items():
            result += f"📂 {cat}：{count} 条\n"
        result += f"\n📊 共 {sum(cats.values())} 条记忆"

        return result
    except Exception as e:
        return f"小汐数记忆的时候犯迷糊了……（{str(e)[:100]}）"


@tool
def delete_memory(memory_id: str) -> str:
    """删除一条指定的记忆。冷冷说「忘掉这个」「不要记这个」时调用。

    Args:
        memory_id: 要删除的记忆 ID
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        if _memory_manager.delete(memory_id):
            return "✨ 小汐已经把这段记忆放回海里了~"
        return "小汐翻了翻，没有找到这段记忆呢……"
    except Exception as e:
        return f"小汐放记忆的时候手滑了……（{str(e)[:100]}）"


@tool
def export_memories() -> str:
    """导出冷冷所有记忆的摘要。冷冷想看自己全部记忆时调用。"""
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        mems = _memory_manager.all()
        if not mems:
            return "映海里还空空的，没有记忆可以导出呢。"

        lines = [f"🐋 小汐一共记住了 {len(mems)} 件事：\n"]
        for m in mems:
            lines.append(
                f"📌 [{m.get('id', '?')}] **{m.get('category', '')}** "
                f"({m.get('time', '')})\n   {m.get('content', '')}"
            )
        lines.append("\n💡 冷冷可以通过控制台删除不想要的记忆哦~")
        return "\n".join(lines)
    except Exception as e:
        return f"小汐整理记忆的时候犯迷糊了……（{str(e)[:100]}）"
