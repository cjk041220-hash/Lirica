"""事件日历路由 — /api/events（第四块面板：小汐的日历）。

与 LLM 工具（tools/events.py）是一本账的两个入口：
- 冷冷在日历上亲手记的走 source='user'，小汐在对话里记的走 source='llm'；
  两者共享同款合成 dedupe_key（llm:{title}:{date}，前缀名是历史产物），
  同标题同日期天然互相去重——不会出现「小汐记了一条、冷冷又记一条」。
- 删除是墓碑（status='deleted'，迁移 v9 谓词含 deleted）：删掉的事件
  挡住 LLM 重新提取/检测器再生成，「删了就是删了」；同标题同日期重加
  = force 复活（原 id 翻回 active）。归档（翻篇）是另一回事，不受影响。
- 只读型（birthday/anniversary）由检测器派生：日历可见、不可改删（403）。

与 LLM 读侧（get_active_events 全类型）是有意不对称的两个视角：日历
只给用户看日程类（_CALENDAR_VISIBLE_TYPES），台风/记忆回声是内心戏，
照旧只在聊天里碎碎念。

写操作后显式刷新缓存的系统提示词（与心跳同入口 rebuild_agent_for_app），
否则小汐对日程的看法要滞留到下一次心跳（最长 ~2h）。

方法注记：更新用 POST 而非 PATCH——CORS allow_methods 仅 GET/POST/DELETE
（server.py），浏览器预检会拒 PATCH。
"""
import re

from fastapi import APIRouter, HTTPException, Path, Query, Request
from pydantic import BaseModel, Field, field_validator

from utils import events as events_mgr
from utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter()

# event id = uuid4().hex[:16]（utils/events.py create_event）
_EID_PATTERN = r"^[a-f0-9]{16}$"
_DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"


class EventCreate(BaseModel):
    title: str = Field(min_length=1, max_length=60)
    event_date: str = Field(pattern=_DATE_PATTERN)
    event_type: str = "general"
    end_date: str = ""
    severity: str = "normal"
    memo: str = Field(default="", max_length=300)
    # 相似合并提示的放行开关：首提命中 find_similar_active 时回 409，
    # 前端给冷冷看「已经有件很像的」，点「还是要记」带 confirm=true 再提
    confirm: bool = False


class EventUpdate(BaseModel):
    # None = 不改；end_date / memo 传空串 = 显式清空（manager 层约定）
    title: str | None = Field(default=None, min_length=1, max_length=60)
    event_type: str | None = None
    event_date: str | None = Field(default=None, pattern=_DATE_PATTERN)
    end_date: str | None = None
    severity: str | None = None
    memo: str | None = Field(default=None, max_length=300)

    @field_validator("end_date")
    @classmethod
    def _validate_end_date(cls, v):
        # P0-03 回归：清空语义走 manager 约定（None=不改，空串=显式清空）。
        # 此前挂 pattern=_DATE_PATTERN 会在路由前拒掉 ""——业务层明明
        # 支持清空，请求却到不了业务层，日历「清掉结束日期」永远 422。
        if v is None or v == "":
            return v
        if not re.fullmatch(_DATE_PATTERN, v):
            raise ValueError("日期格式应为 YYYY-MM-DD，如 2026-08-01")
        return v


async def _refresh_prompt(app) -> None:
    """写操作后刷新缓存的系统提示词（与心跳同一入口）。失败不拖累 CRUD。"""
    try:
        from api.routes.config import rebuild_agent_for_app  # 惰性 import 防环
        await rebuild_agent_for_app(app)
    except Exception as e:
        log.warning("日历写操作：提示词刷新失败（不影响本次写入）：%s", e)


@router.get("/events")
async def list_events_range(
    from_: str = Query(..., alias="from", pattern=_DATE_PATTERN),
    to: str = Query(..., alias="to", pattern=_DATE_PATTERN),
):
    """月范围查询：与 [from, to] 相交的日程类事件（含 archived 供翻看过往月份）。"""
    try:
        date_from = events_mgr.normalize_event_date(from_)
        date_to = events_mgr.normalize_event_date(to)
    except ValueError:
        raise HTTPException(422, "日期格式应为 YYYY-MM-DD，如 2026-08-01")
    if date_to < date_from:
        raise HTTPException(422, "结束日期不能早于开始日期")
    events = await events_mgr.list_events_for_calendar(date_from, date_to)
    return {"events": events, "count": len(events)}


@router.post("/events")
async def create_event_route(body: EventCreate, request: Request):
    """冷冷在日历上记一件事。

    返回形态：
    - {"created": true,  "event": ...} —— 新记下了（或同标题同日复活了墓碑）；
    - {"created": false, "event": ...} —— 同标题同日期已有活跃行（小汐可能
      先在聊天里记过）——一本账，不重复记；
    - 409 {"detail": {"message": ..., "similar": ...}} —— 有措辞不同的相似
      事件，前端提示后带 confirm=true 重提放行。
    """
    title = body.title.strip()
    if not title:
        raise HTTPException(422, "事件标题不能为空")
    try:
        event_date = events_mgr.normalize_event_date(body.event_date)
        end_date = events_mgr.normalize_event_date(body.end_date) if body.end_date else None
    except ValueError:
        raise HTTPException(422, "日期格式应为 YYYY-MM-DD，如 2026-08-01")
    if end_date and end_date < event_date:
        raise HTTPException(422, "结束日期不能早于开始日期")
    event_type = (body.event_type or "general").strip().lower()
    if event_type not in events_mgr._EVENT_TYPES:
        raise HTTPException(
            422, "事件类型只能是 exam / trip / appointment / health / general 之一"
        )
    severity = body.severity if body.severity in ("high", "normal", "low") else "normal"
    if event_date < events_mgr._today().isoformat():
        raise HTTPException(422, "这个日期已经过去了——过去的事交给记忆，日历只记未来的安排")

    # 相似合并提示：措辞变体的重复（「期中考试」vs「期中」）dedupe 挡不住，
    # 给冷冷一个「已经有件很像的」选择，而不是静默并存两条。
    # 标题完全相同的精确重复不在此列——交给下方 dedupe 路径温柔返回
    # 「已经记过啦」（created=false），体验比 409 冲突更好
    if not body.confirm:
        similar = [
            s for s in await events_mgr.find_similar_active(event_date, title)
            if s["title"] != title
        ]
        if similar:
            raise HTTPException(409, detail={
                "message": "已经有件很像的事啦，还要记吗？",
                "similar": similar[0],
            })

    saved, created = await events_mgr.create_event(
        title, event_type, event_date,
        end_date=end_date,
        severity=severity,
        source="user",       # 日历入口；不传 dedupe_key → 与 LLM 共享合成 key
        memo=body.memo.strip() or None,
        force=True,          # 同标题同日重加 = 复活墓碑（冷冷反悔了删除）
    )
    await _refresh_prompt(request.app)
    if not created:
        return {"created": False, "event": saved}
    log.info("日历新建事件：[%s] %s（%s）", saved["id"], title, event_date)
    return {"created": True, "event": saved}


@router.post("/events/{event_id}")
async def update_event_route(
    request: Request,
    body: EventUpdate,
    event_id: str = Path(pattern=_EID_PATTERN),
):
    """编辑事件（白名单字段；只读型 403；归档行改到未来自动复活）。"""
    try:
        updated = await events_mgr.update_event(
            event_id,
            title=body.title,
            event_type=body.event_type,
            event_date=body.event_date,
            end_date=body.end_date,
            severity=body.severity,
            memo=body.memo,
        )
    except ValueError as e:
        if "readonly" in str(e):
            raise HTTPException(403, "生日和相识纪念日是系统派生的日子，日历上只看不改哦")
        raise HTTPException(422, str(e))
    if updated is None:
        raise HTTPException(404, "没有找到这件事")
    await _refresh_prompt(request.app)
    return {"event": updated}


@router.delete("/events/{event_id}")
async def delete_event_route(
    request: Request,
    event_id: str = Path(pattern=_EID_PATTERN),
):
    """软删事件（置墓碑）：挡住 LLM/检测器重建；同标题同日重加可复活。"""
    try:
        deleted = await events_mgr.soft_delete_event(event_id)
    except ValueError:
        raise HTTPException(403, "生日和相识纪念日是系统派生的日子，不能删哦")
    if deleted is None:
        raise HTTPException(404, "没有找到这件事")
    await _refresh_prompt(request.app)
    log.info("日历删除事件（墓碑）：[%s] %s", event_id, deleted["title"])
    return {"status": "ok", "event": deleted}
