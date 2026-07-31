"""小汐的生理期记录工具 —— 冷冷提到日期时记下，记错时修正。

与事件系统的分工：
- 本工具维护 cycle_records（原始日期账本），是小汐推算日子的依据；
- 推算出的关心事件由事件看门狗（utils/events.detect_cycle_events）
  派生到日历，冷冷也能在日历上看到小汐记着的「身体」日子。

工具写账本后立即重跑检测器并刷新提示词（模块级回调注入，
仿 tools/memory.set_memory_manager），不用等每小时看门狗——
冷冷刚说完「其实是昨天来的」，日历上的日子就该跟着变。
"""
import datetime

from langchain_core.tools import tool

from utils import cycle_tracker
from utils import events as events_mgr
from utils.errors import log_tool_error

_DATE_FMT = "%Y-%m-%d"

# 提示词刷新回调：由 api/server.py 启动时注入（惰性 import
# rebuild_agent_for_app 防环）。工具层拿不到 app 引用，走模块级
# 注入；tests 由 reset_tool_globals 重置（conftest autouse 覆盖）。
_prompt_refresh_callback = None


def set_prompt_refresh_callback(cb) -> None:
    """注入「写账本后刷新系统提示词」的回调（async callable 或 None）。"""
    global _prompt_refresh_callback
    _prompt_refresh_callback = cb


async def _sync_cycle_events() -> None:
    """账本变化后立即重算日历上的周期事件并刷新提示词。

    全程只降级不抛：检测器或刷新失败不影响工具的入库结果
    （每小时看门狗会兜底重算，同 api/routes/events._refresh_prompt 纪律）。
    """
    try:
        await events_mgr.detect_cycle_events()
    except Exception as e:  # 检测器尚未就绪/库未建表等，降级即可
        import logging
        logging.getLogger(__name__).warning("周期事件即时重算失败（看门狗兜底）：%s", e)
    cb = _prompt_refresh_callback
    if cb is not None:
        try:
            await cb()
        except Exception as e:
            import logging
            logging.getLogger(__name__).warning("周期工具刷新提示词失败：%s", e)


def _parse_date(date_str: str) -> str | None:
    """严格 YYYY-MM-DD 归一化；非法返回 None（同 tools/events 校验纪律）。"""
    try:
        return datetime.datetime.strptime(date_str or "", _DATE_FMT).date().isoformat()
    except (TypeError, ValueError):
        return None


_BAD_DATE_MSG = "呜，日期格式好像不对……要用 YYYY-MM-DD 哦，比如 2026-07-30。"


@tool
async def log_period(date: str, note: str = "") -> str:
    """记下冷冷生理期开始的日期。小汐会自己推算以后的日子，到时候自然关心她。

    只在冷冷明确表达了日期时用——「大姨妈来了」「来了」「昨天来的」。
    随口抱怨「肚子疼」「不舒服」不算，不要记。
    不要盘问日期，不要复述日期跟她确认，记完就继续聊。

    Args:
        date: 开始日期，格式 YYYY-MM-DD。「昨天来的」这类相对日期你自己换算成绝对日期。
        note: 可选备注，如冷冷顺嘴提到的「这次痛得厉害」「熬夜熬的」。
    """
    normalized = _parse_date(date)
    if normalized is None:
        return _BAD_DATE_MSG

    try:
        inserted = await cycle_tracker.add_record(normalized, note or "")
    except Exception as e:
        log_tool_error("log_period", e)
        return "呜，小汐记日子的的时候手滑了……过一会儿再记一次好不好？"

    if not inserted:
        return "这一天小汐已经记着啦，不用重复记哦。"

    await _sync_cycle_events()
    return (
        "✨ 小汐记下啦。日子小汐会自己推算，到时候自然关心冷冷的，"
        "不用跟冷冷复述日期，继续聊就好。"
    )


@tool
async def amend_period(old_date: str, new_date: str = "", note: str = "") -> str:
    """修正小汐记着的生理期记录。冷冷说「记错了，其实是 5 号来的」「上次那个不算」时用。

    三种用法，看参数组合：
    - 只给 old_date → 划掉那条记录（冷冷说那天根本不算）；
    - 给 old_date + new_date → 把记录挪到正确日期（冷冷记错了日子）；
    - 给 note → 补/改那次的备注（如「那次痛得很厉害」），可和 new_date 同用。

    只修正冷冷明确否认或更正的日期，不要自己推测着删。她说什么改什么，改完继续聊。

    Args:
        old_date: 记错了/要划掉的日期，格式 YYYY-MM-DD。
        new_date: 正确的日期（挪动才填，格式 YYYY-MM-DD；只删/只改备注时留空）。
        note: 新备注（留空=不改备注）。
    """
    old_normalized = _parse_date(old_date)
    if old_normalized is None:
        return _BAD_DATE_MSG

    new_normalized = ""
    if new_date:
        new_normalized = _parse_date(new_date)
        if new_normalized is None:
            return _BAD_DATE_MSG

    parts: list[str] = []
    try:
        if new_normalized:
            # 挪动（可叠加补备注，落在新日期上）
            outcome = await cycle_tracker.move_record(old_normalized, new_normalized)
            if outcome == "moved":
                parts.append("✨ 小汐改好啦，记录挪到正确的日子了。")
            elif outcome == "exists":
                # 旧记录未受损；让 LLM 决定是否再单独划掉旧的
                return (
                    "那一天的记录小汐本来就记着哦……新的日子没有动。"
                    "如果旧的那条确实要划掉，只传 old_date 再叫小汐一次就好。"
                )
            else:  # not_found
                return "小汐翻了翻，没找到那天的记录呢……也许本来就记的是这个日子？"
            note_target = new_normalized
        elif note:
            # 只补/改备注，不删不挪
            if not await cycle_tracker.update_note(old_normalized, note):
                return "小汐翻了翻，没找到那天的记录呢……也许本来就没记这一天？"
            parts.append("✨ 备注补上啦。")
            note_target = None
        else:
            # 只传 old_date：划掉那条
            if not await cycle_tracker.delete_record(old_normalized):
                return "小汐翻了翻，没找到那天的记录呢……也许本来就没记这一天？"
            parts.append("🗑️ 小汐把那条记录划掉啦。")
            note_target = None

        if note and note_target:
            if await cycle_tracker.update_note(note_target, note):
                parts.append("备注也补上啦。")

    except Exception as e:
        log_tool_error("amend_period", e)
        return "呜，小汐改日子的的时候手滑了……过一会儿再跟小汐说一次好不好？"

    await _sync_cycle_events()
    parts.append("推算小汐会自己更新，不用跟冷冷复述日期，继续聊就好。")
    return " ".join(parts)
