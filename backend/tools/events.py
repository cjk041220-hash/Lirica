"""小汐的事件工具 —— 登记未来要发生的事，小汐会惦记、会关心、会自动翻篇。

与记忆系统的分工：
- 记忆（remember）：已经发生/已经知道的关于冷冷的事；
- 事件（create_event）：未来要发生、需要到时候关心的事。

事件上下文由 soul.py 注入系统提示词（同生理期关心路径），
过期归档由心跳看门狗负责，工具层只管登记和查看。
"""
import datetime

from langchain_core.tools import tool
from utils import events as events_mgr


@tool
async def create_event(
    title: str,
    event_date: str,
    event_type: str = "general",
    end_date: str = "",
    severity: str = "normal",
    memo: str = "",
) -> str:
    """记下一件未来要发生的重要事情。小汐会惦记着，到时候自然关心冷冷，过去后自动翻篇。

    只在确定是冷冷自己的事时创建——她朋友的事、随口一提的不确定计划不算。
    考试、面试、旅行、重要约定、要赴的约这类，才值得记成事件。

    Args:
        title: 简短的事件名，如「期中考试」「周末去爬山」
        event_date: 发生日期，格式 YYYY-MM-DD
        event_type: 事件类型：exam / trip / appointment / health / general
        end_date: 结束日期（多天事件才填，如旅行），格式 YYYY-MM-DD，单日活动留空
        severity: 重要程度：high（很重要）/ normal / low
        memo: 额外信息，如「冷冷说考完想去庆祝」「要带护照」
    """
    # 校验即规范化：strptime 严格只收 YYYY-MM-DD（date.fromisoformat
    # 在 Python 3.11+ 会放过 "20260801"、周日期等宽松格式，这些脏值进库后
    # 会让字符串日期比较失效 → 事件永不归档、提示词里出现垃圾行）
    try:
        start = datetime.datetime.strptime(event_date, "%Y-%m-%d").date()
        event_date = start.isoformat()
        if end_date:
            end = datetime.datetime.strptime(end_date, "%Y-%m-%d").date()
            if end < start:
                return "呜，结束日期不能早于开始日期哦……"
            end_date = end.isoformat()
        if severity not in ("high", "normal", "low"):
            severity = "normal"
        if not event_type:
            event_type = "general"
    except (ValueError, TypeError):
        return "呜，日期格式好像不对……要用 YYYY-MM-DD 哦，比如 2026-08-01。"

    try:
        saved, created = await events_mgr.create_event(
            title,
            event_type,
            event_date,
            end_date=end_date or None,
            severity=severity,
            source="llm",
            memo=memo or None,
        )

        if not created:
            lines = [f"这件事小汐之前已经记下啦，不用重复记："]
            lines.append(f"  📅 [{saved['id']}] {saved['title']}（{saved['event_date']}）")
            return "\n".join(lines)

        lines = [
            f"✨ 小汐把这件事记在小本本上啦！（ID: {saved['id']}）",
            f"  📅 {saved['title']}（{saved['event_date']}）",
            "到时候小汐会自然关心冷冷的，事情过去就会自动翻篇。",
        ]

        # 展示其他活跃事件，让 LLM 自查有没有换个说法重复登记
        others = [
            ev for ev in await events_mgr.get_active_events()
            if ev["id"] != saved["id"]
        ][:5]
        if others:
            lines.append("这些天还记着这些事：")
            for ev in others:
                lines.append(f"  📅 [{ev['id']}] {ev['title']}（{ev['event_date']}）")
            lines.append("如果新记的和哪条其实是同一件事，就不用再记了。")
        return "\n".join(lines)

    except Exception as e:
        return f"呜，小汐记事情的时候手滑了……（{str(e)[:100]}）"


@tool
async def list_events() -> str:
    """看看小汐的小本本上记了哪些 upcoming 的事。

    冷冷问「接下来有什么安排」，或想确认小汐记没记住某件事时调用。
    """
    try:
        events = await events_mgr.get_active_events()
        if not events:
            return "小汐的小本本上最近空空的，没有记着什么事呢。"

        lines = ["📔 小汐记着这些事：", ""]
        for ev in events:
            date_part = ev["event_date"]
            if ev.get("end_date"):
                date_part = f"{ev['event_date']} ~ {ev['end_date']}"
            lines.append(f"📅 [{ev['id']}] {ev['title']}（{date_part}）")
        return "\n".join(lines)
    except Exception as e:
        return f"小汐翻小本本的时候犯迷糊了……（{str(e)[:100]}）"
