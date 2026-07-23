"""小汐的记忆工具 — 通过 MemoryManager 读写。

v2 重构：
- remember: 存储前检测同类别旧记忆，引导 LLM 处理矛盾
- update_memory: 新增工具，用于覆盖矛盾旧记忆
- recall: 使用相关性评分排序（由 manager 层实现）
- 所有工具适配新 schema 字段名

v2.1 修复：
- remember: 改为「先存储，再展示同类别旧记忆供矛盾检测」。
  旧版在同类别已有记忆时拒绝写入并提示"再次调用确认"，但再次调用仍会
  命中同一分支，形成死循环——同类别第二条起的记忆永远存不进去。
  新版始终写入（manager 层对完全相同的内容自动去重），并把旧记忆作为
  附加上下文返回；矛盾处理交给 update_memory / delete_memory。
- recall: mems[-10:] → mems[:10]。manager.recall 按相关性降序排列
  （最相关在前），旧版截尾部导致展示的恰是最不相关的 10 条。
"""
from langchain_core.tools import tool
from memory.manager import MemoryManager

# 由 server.py 在 lifespan 中注入
_memory_manager: MemoryManager | None = None


def set_memory_manager(mgr: MemoryManager):
    """注入 MemoryManager 实例（在 lifespan 中调用）。"""
    global _memory_manager
    _memory_manager = mgr


@tool
async def remember(category: str, content: str) -> str:
    """让小汐记住一件事。冷冷说了关于自己的重要信息时主动调用。

    新记忆会被直接保存。如果同类别下已有相关记忆，会一并展示给你：
    若发现新旧矛盾（比如以前说爱吃火锅，现在说不爱了），
    请调用 update_memory(memory_id, new_content) 覆盖旧的那条，
    或用 delete_memory(memory_id) 删掉过时的记忆。

    Args:
        category: 记忆类别，如「冷冷喜欢的」「去过的地方」「重要的事」
        content: 要记住的具体内容
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        # 先保存新记忆（manager 层对完全相同的 category+content 自动去重，
        # 因此重复调用不会产生重复行，只会刷新时间戳）
        saved = await _memory_manager.add(category, content)

        # 再查同类别的其他记忆（按创建时间倒序，刚存的会在其中，按 id 排除），
        # 作为矛盾检测的上下文返回给 LLM
        others = [
            m for m in await _memory_manager.recall_by_category(category, limit=6)
            if m.get("id") != saved["id"]
        ][:5]

        if not others:
            return f"✨ 小汐帮你收好啦！放在映海的「{category}」里。（ID: {saved['id']}）"

        lines = [f"✨ 小汐把新记忆收好啦！放在映海的「{category}」里。（ID: {saved['id']}）"]
        lines.append("这个类别下之前还记着这些：")
        for m in others:
            lines.append(f"  📌 [{m['id']}] {m['content']}")
        lines.append(
            "如果新记忆和哪条旧记忆矛盾（比如偏好变了），"
            "请调用 update_memory(memory_id, new_content) 覆盖旧的那条，"
            "或用 delete_memory(memory_id) 删掉过时的。不矛盾就不用管。"
        )
        return "\n".join(lines)

    except Exception as e:
        return f"呜，小汐没接住这段记忆……（{str(e)[:100]}）"


@tool
async def update_memory(memory_id: str, new_content: str) -> str:
    """覆盖一条旧记忆的内容。当冷冷说了和之前矛盾的信息时使用。

    比如之前记住「爱吃火锅」，现在冷冷说不爱吃了，就用这个工具更新。

    Args:
        memory_id: 要更新的记忆 ID（从 remember 或 recall 返回的 ID）
        new_content: 新的记忆内容
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        if await _memory_manager.update_content(memory_id, new_content):
            return f"✨ 小汐已经把旧记忆更新成新的啦！"
        return "小汐翻了翻，没有找到这段记忆呢……ID 是不是写错了？"
    except Exception as e:
        return f"呜，小汐更新记忆的时候手滑了……（{str(e)[:100]}）"


@tool
async def recall(category: str = "") -> str:
    """让小汐回忆之前记住的事情。冷冷问"我之前……"或聊天中提到过去的事时主动调用。

    按相关性排序：越近、越常被回忆的记忆排在前面。

    Args:
        category: 按类别筛选记忆，不传则返回全部
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        mems = await _memory_manager.recall(category)
        if category:
            if not mems:
                return f"小汐在映海里翻了翻，没有找到关于「{category}」的记忆……"
            header = f"🐋 小汐记得关于「{category}」的这些：\n\n"
        else:
            if not mems:
                return "小汐还没有记住什么事情呢……映海里空空的。"
            header = "🐋 小汐记得这些事：\n\n"

        # manager.recall 按相关性降序返回（最相关在前），取前 10 条
        result = header
        for m in mems[:10]:
            result += f"📌 [{m.get('id', '?')}] **{m.get('category', '')}** ({m.get('created_at', '')})\n"
            result += f"   {m.get('content', '')}\n\n"

        return result
    except Exception as e:
        return f"小汐去映海里找记忆的时候走散了……（{str(e)[:100]}）"


@tool
async def list_memories() -> str:
    """列出小汐记住的所有记忆类别和数量。冷冷想看记忆总览时调用。"""
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        cats = await _memory_manager.list_categories()
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
async def delete_memory(memory_id: str) -> str:
    """删除一条指定的记忆。冷冷说「忘掉这个」「不要记这个」时调用。

    Args:
        memory_id: 要删除的记忆 ID
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        if await _memory_manager.delete(memory_id):
            return "✨ 小汐已经把这段记忆放回海里了~"
        return "小汐翻了翻，没有找到这段记忆呢……"
    except Exception as e:
        return f"小汐放记忆的时候手滑了……（{str(e)[:100]}）"


@tool
async def export_memories() -> str:
    """导出冷冷所有记忆的摘要。冷冷想看自己全部记忆时调用。"""
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        mems = await _memory_manager.all()
        if not mems:
            return "映海里还空空的，没有记忆可以导出呢。"

        lines = [f"🐋 小汐一共记住了 {len(mems)} 件事：\n"]
        for m in mems:
            lines.append(
                f"📌 [{m.get('id', '?')}] **{m.get('category', '')}** "
                f"({m.get('created_at', '')})\n   {m.get('content', '')}"
            )
        lines.append("\n💡 冷冷可以通过控制台删除不想要的记忆哦~")
        return "\n".join(lines)
    except Exception as e:
        return f"小汐整理记忆的时候犯迷糊了……（{str(e)[:100]}）"
