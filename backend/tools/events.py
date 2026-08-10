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
from utils.errors import log_tool_error
from utils.timezone import HKT as _HKT

# docstring 向 LLM 声明的事件类型枚举，入库前强制归一，
# 防止自由文本（「生日」「考试！！」）渲染进系统提示词。
# 与日历面板（api/routes/events.py）共用 manager 层的同一份白名单
_EVENT_TYPES = set(events_mgr._EVENT_TYPES)


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
        event_type = (event_type or "").strip().lower() or "general"
        if event_type not in _EVENT_TYPES:
            return "呜，事件类型只能是 exam / trip / appointment / health / general 其中之一哦。"
        # 事件是「未来要惦记的事」：过去日期入库后 list_events 会把它
        # 当接下来的安排报给冷冷（读侧 get_events_context 虽有过滤，
        # 但下次心跳归档前仍会被 list_events 看到）
        if start < datetime.datetime.now(_HKT).date():
            return "这件事已经过去了呢……小汐没办法回到过去惦记它。已经发生的事，用记忆功能记下来好不好？"
    except (ValueError, TypeError):
        return "呜，日期格式好像不对……要用 YYYY-MM-DD 哦，比如 2026-08-01。"

    # 存储侧时间词归一化（2026-08-05 用户反馈）：LLM 写的 title/memo
    # 里常带「后天/明天/今天」等相对说法，日子一过就读不懂、会答错。
    # 落库前把日级相对词改写为绝对日期（日历面板 source='user' 不走
    # 工具层，用户的原始措辞不受影响）。
    from utils.time_words import normalize_time_words

    title = normalize_time_words(title)
    memo = normalize_time_words(memo or "") if memo else memo

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
            if saved is None:
                # 撞墓碑（v9）：冷冷删过同标题同日期的事件。尊重删除，
                # 不偷偷记回来——想恢复请冷冷在日历面板上重新加
                return (
                    "咦，这件事冷冷之前让小汐删掉过呢。小汐不偷偷记回来——"
                    "冷冷想重新记的话，在日历上加一下就好啦。"
                )
            lines = ["这件事小汐之前已经记下啦，不用重复记："]
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
        log_tool_error("create_event", e)
        return "呜，小汐记事情的时候手滑了……过一会儿再记一次好不好？"


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
        log_tool_error("list_events", e)
        return "小汐翻小本本的时候犯迷糊了……过一会儿再看看好不好？"


@tool
async def update_event(
    event_id: str,
    title: str = "",
    event_date: str = "",
    event_type: str = "",
    severity: str = "",
    memo: str = "",
) -> str:
    """修改小本本上记错/变卦的一件事。冷冷说「日期记错了」「改到下周」「不是那天了」时用。

    先用 list_events 确认事件 ID，只传要改的字段，不改的留空。
    生日 / 相识纪念日这类系统派生的日子改不了（会吃闭门羹）。

    Args:
        event_id: list_events 输出里方括号中的事件 ID
        title: 新标题（留空=不改）
        event_date: 新日期，格式 YYYY-MM-DD（留空=不改）
        event_type: exam / trip / appointment / health / general（留空=不改）
        severity: high / normal / low（留空=不改）
        memo: 新备注（留空=不改）
    """
    # 同 create_event：title/memo 落库前做相对时间词归一化
    from utils.time_words import normalize_time_words

    title = normalize_time_words(title) if title else title
    memo = normalize_time_words(memo) if memo else memo

    try:
        updated = await events_mgr.update_event(
            event_id,
            title=title or None,
            event_date=event_date or None,
            event_type=event_type or None,
            severity=severity or None,
            memo=memo or None,
        )
    except ValueError as e:
        msg = str(e)
        if "readonly" in msg:
            return "生日和相识纪念日是从冷冷档案里派生的日子，小汐改不了这个哦……"
        if ("title 不能为空" in msg or "event_type 只能是" in msg
                or "end_date 不能早于" in msg):
            # 自写校验文案（utils/events.py:365,370,377），可安全转述给 LLM 修正参数
            return f"呜，这样改不了呢：{msg}"
        # 日期格式不对（strptime 英文原文）或未知校验 → 角色化兜底
        return "呜，这个日子小汐没看懂……要写成 2026-08-05 这个样子才行哦。"
    except Exception as e:
        log_tool_error("update_event", e)
        return "呜，小汐改小本本的时候手滑了……过一会儿再跟小汐说一次好不好？"
    if updated is None:
        return "这个 ID 的记事没找到呢……先用 list_events 看看最新的小本本好不好？"
    date_part = updated["event_date"]
    if updated.get("end_date"):
        date_part = f"{updated['event_date']} ~ {updated['end_date']}"
    return f"✏️ 小汐改好啦：[{updated['id']}] {updated['title']}（{date_part}）"


@tool
async def delete_event(event_id: str) -> str:
    """把小本本上的一条记事划掉。冷冷说「取消了」「不去了」「记错了删掉吧」时用。

    删掉之后小汐不会偷偷记回来——同一件事（同标题同日期）再记会算作恢复。
    先用 list_events 确认 ID 再删。生日 / 相识纪念日划不掉（系统派生的日子）。

    Args:
        event_id: list_events 输出里方括号中的事件 ID
    """
    try:
        deleted = await events_mgr.soft_delete_event(event_id)
    except ValueError:
        return "生日和相识纪念日划不掉哦——那是从冷冷档案里派生的日子。"
    except Exception as e:
        log_tool_error("delete_event", e)
        return "呜，小汐擦小本本的时候擦花了……过一会儿再跟小汐说一次好不好？"
    if deleted is None:
        return "这个 ID 的记事没找到呢……先用 list_events 看看最新的小本本好不好？"
    return f"🗑️ 小汐把这条划掉啦：{deleted['title']}（{deleted['event_date']}）。不会再记回来的。"
