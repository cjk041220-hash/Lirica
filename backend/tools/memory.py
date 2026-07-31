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
from utils.errors import log_tool_error

# 由 server.py 在 lifespan 中注入
_memory_manager: MemoryManager | None = None


def set_memory_manager(mgr: MemoryManager):
    """注入 MemoryManager 实例（在 lifespan 中调用）。"""
    global _memory_manager
    _memory_manager = mgr


@tool
async def remember(category: str, content: str) -> str:
    """让小汐记住一件事。冷冷每次说出关于自己的新信息，都要主动调用本工具记下来。

    这是你的默认习惯，不是可选动作：经历、变化、想法、决定、偏好、
    身体情况、人际关系、情绪低谷——只要是她身上新发生或新变化的事，
    哪怕只是顺口提了一句，都值得记。门槛很低：拿不准就记，宁多勿少，
    记多了可以删，没记下来就是忘了。

    唯一不记的是「关于冷冷」档案里已经写好的内容（那是你一开始就
    知道的事，不要抄进记忆）。

    新记忆会被直接保存。如果同类别下已有相关记忆，会一并展示给你：
    - 完全矛盾（比如以前说爱吃火锅，现在说不爱了）
      → 调用 update_memory(memory_id, 新内容) 覆盖旧的那条
    - 只是变化（比如以前爱喝奶茶，最近在控糖喝得少了）
      → 调用 update_memory(memory_id, "以前……最近……") 合并成一句话
    - 不矛盾 → 不用管
    也可以用 delete_memory(memory_id) 删掉过时的记忆。

    Args:
        category: 记忆类别。优先从下面几个里选，复用已有类别，
            不要为同类内容起新名字：
            「冷冷喜欢的」（偏好品味）/「冷冷的信息」（身份、生活、健康的变化）/
            「去过的地方」（地点经历）/「重要的事」（大事件、约定、待办）/
            「其他」（都不合适时兜底）
        content: 要记住的具体内容
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        # 先保存新记忆（manager 层对完全相同的 category+content 自动去重，
        # 因此重复调用不会产生重复行，只会刷新时间戳）
        saved = await _memory_manager.add(category, content)

        lines = [f"✨ 小汐把新记忆收好啦！放在映海的「{category}」里。（ID: {saved['id']}）"]

        # 动态展示已有类别及条数，引导 LLM 复用现有类别，
        # 防止类别名漂移（「美食偏好」「冷冷喜欢吃什么」各立山头，去重失效）
        cats = await _memory_manager.list_categories()
        if cats:
            existing = "、".join(f"「{c}」（{n}条）" for c, n in cats.items())
            lines.append(f"映海里现有的类别：{existing}。以后尽量放进已有类别。")

        # 再查同类别的其他记忆（按创建时间倒序，刚存的会在其中，按 id 排除），
        # 作为矛盾检测的上下文返回给 LLM
        others = [
            m for m in await _memory_manager.recall_by_category(category, limit=6)
            if m.get("id") != saved["id"]
        ][:5]

        if others:
            lines.append("这个类别下之前还记着这些：")
            for m in others:
                lines.append(f"  📌 [{m['id']}] {m['content']}")
            lines.append(
                "新旧有出入时：完全矛盾就用 update_memory(memory_id, 新内容) 覆盖旧的；"
                "只是变化（以前喜欢、最近少了）就合并成一句「以前……最近……」再 update_memory；"
                "不矛盾就不用管。彻底过时的用 delete_memory 删掉。"
            )
        return "\n".join(lines)

    except ValueError as e:
        # manager.add 的参数校验（category/content 为空）——原文是我们
        # 自己写的校验文案，可安全展示给 LLM 供其修正参数
        return f"呜，这段记忆没能收好：{e}。category 和 content 都要有内容哦。"
    except Exception as e:
        log_tool_error("remember", e)
        return "呜，小汐没接住这段记忆……过一会儿再记一次好不好？"


@tool
async def update_memory(memory_id: str, new_content: str) -> str:
    """覆盖一条旧记忆的内容。两种情况用它：

    1. 矛盾：之前记住「爱吃火锅」，现在冷冷说不爱吃了，用新内容覆盖。
    2. 变化合并：之前记住「爱喝奶茶不加糖」，最近在控糖，就合并成
       「以前爱喝奶茶不加糖，最近在控糖喝得少了」再覆盖——别直接丢掉旧事实。

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
        log_tool_error("update_memory", e)
        return "呜，小汐更新记忆的时候手滑了……过一会儿再试试好不好？"


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
        log_tool_error("recall", e)
        return "小汐去映海里找记忆的时候走散了……过一会儿再找找好不好？"


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
        log_tool_error("list_memories", e)
        return "小汐数记忆的时候犯迷糊了……过一会儿再数数看好不好？"


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
        log_tool_error("delete_memory", e)
        return "小汐放记忆的时候手滑了……过一会儿再试试好不好？"


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
        log_tool_error("export_memories", e)
        return "小汐整理记忆的时候犯迷糊了……过一会儿再整理好不好？"
