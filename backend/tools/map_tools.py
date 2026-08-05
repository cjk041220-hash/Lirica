"""地图工具 — 纯高德单引擎。

高德 Web 服务 API 承担全部地理能力（地理编码 / POI 搜索 / 路线规划）。
内地与港澳统一收 GCJ-02 加偏坐标（高德港澳数据同样是 GCJ-02），无
第二引擎、无坐标引擎切换；港澳的路线/POI 数据弱是数据现实，空结果
按既有文案诚实告知，不搞「婉拒」概念。

面向 LLM 的工具签名与 docstring 稳定；每个工具返回 (渲染文本, nav)
二元组——nav 是「看地图 / 去这里」跳转所需的目的地信息（GCJ-02，
前端按目的地区域选地图 App 唤起），经 response_format=
"content_and_artifact" 走 ToolMessage.artifact 下发，LLM 只见 content
文本，artifact 不进模型上下文。
"""
import httpx
from langchain_core.tools import tool
from config.settings import get_settings
from memory import location_store
from utils.errors import log_tool_error
from utils.geo import wgs84_to_gcj02
from utils.retry import with_retry

# 共享 HTTP 客户端（由 server.py lifespan 注入）
_shared_client: httpx.AsyncClient | None = None


def set_http_client(client: httpx.AsyncClient):
    """注入共享 HTTP 客户端（在 lifespan 中调用）。"""
    global _shared_client
    _shared_client = client


class _AmapKeyMissing(Exception):
    """AMAP_API_KEY 未配置或 key 维度错误。

    必须与「真实零结果」语义区分：否则 LLM 无法分辨配置问题与
    查无结果，会按提示词指引反复换参数空转，最后自信地告诉用户
    「附近没有咖啡店」——而真相是地图功能根本没开启。
    """


MSG_AMAP_KEY_MISSING = (
    "小汐的地图功能现在用不了……可能是冷冷还没配置 AMAP_API_KEY，"
    "或者这个 Key 无效 / 今日调用额度用完了哦。"
)

# 高德侧 key 维度错误的 infocode：10001 key 不正确 / 10003 日调用量已耗尽
_KEY_INFOCODES = {"10001", "10003"}


def _to_int(v, default: int = 0) -> int:
    """高德字段形态不稳定（str / int / 空 list / None 都见过），统一安全转 int。

    实测 transit/integrated 的 walking_distance 在部分响应里是 [] 而非字符串，
    直接 int() 会抛 TypeError 把整个工具搞崩（被吞成软错误，看似"没联通"）。
    """
    if isinstance(v, list):
        v = v[0] if v else default
    if v is None or v == "":
        return default
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


@with_retry(max_attempts=2, base_delay=0.5)
async def _amap_request(endpoint: str, params: dict) -> dict:
    """发起高德地图 API 请求（带重试）。优先使用共享客户端，回退到临时客户端。

    Key 缺失 / key 维度错误抛 _AmapKeyMissing（不在 with_retry 的
    可重试白名单内，会立即穿透给调用方，不浪费重试次数）。
    """
    s = get_settings()
    if not s.amap_api_key:
        raise _AmapKeyMissing("AMAP_API_KEY 未配置")
    params["key"] = s.amap_api_key

    if _shared_client is not None:
        resp = await _shared_client.get(f"https://restapi.amap.com{endpoint}", params=params)
        resp.raise_for_status()
        data = resp.json()
    else:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(f"https://restapi.amap.com{endpoint}", params=params)
            resp.raise_for_status()
            data = resp.json()

    if data.get("status") != "1" and str(data.get("infocode", "")) in _KEY_INFOCODES:
        raise _AmapKeyMissing(f"高德返回 key 维度错误 infocode={data.get('infocode')}")
    return data


# ── 地理编码与导航载体 ─────────────────────────────────────────

# nav 坐标系标记：后端统一下发 GCJ-02 原值（高德 API 直出零转换），
# 由前端按目的地区域决定要不要逆向成 WGS-84（Apple/Google Maps）
NAV_COORD_GCJ02 = "gcj02"


def _parse_latlon(location: str) -> tuple[float, float] | None:
    """解析「经度,纬度」（高德契约）→ (lat, lon)；非法格式返回 None。"""
    try:
        lon_s, lat_s = location.split(",")
        return float(lat_s), float(lon_s)
    except (ValueError, AttributeError):
        return None


def _nav(name: str, location: str | None) -> dict | None:
    """目的地名称 + GCJ-02 坐标（高德契约「经度,纬度」）；非法返回 None。"""
    ll = _parse_latlon(location or "")
    if ll is None:
        return None
    lat, lon = ll
    return {"name": name, "lat": lat, "lon": lon, "coord": NAV_COORD_GCJ02}


async def _amap_geocode(place: str, city: str = "") -> dict | None:
    """地名 → 高德 geocode 对象（含 location「经度,纬度」GCJ-02）；查不到返回 None。"""
    data = await _amap_request("/v3/geocode/geo", {"address": place, "city": city})
    return (data.get("geocodes") or [None])[0]


def _normalize_amap_poi(poi: dict) -> dict:
    """高德 POI → 归一化字典（统一渲染与 nav 构造）。"""
    d = poi.get("distance")
    return {
        "name": poi.get("name", "?"),
        "address": poi.get("address") or "?",
        "location": poi.get("location", ""),
        "rating": None,                        # 高德 around 不给评分
        "distance_m": _to_int(d) if d not in (None, "", "?") else None,
        "types": [],
    }


def _render_pois(keywords: str, pois: list[dict]) -> str:
    """统一 POI 渲染（口语化：无编号无加粗，转述起来才像人话）。"""
    if not pois:
        return f"小汐在附近没有找到「{keywords}」……要不要换个词试试？"
    result = f"🐋 小汐帮你找到附近的「{keywords}」啦：\n\n"
    for poi in pois[:8]:
        result += f"· {poi['name']}（{poi['address']}）"
        if poi.get("distance_m"):
            result += f"，约 {poi['distance_m']}m"
        if poi.get("rating") is not None:
            result += f"，评分 {poi['rating']}"
        result += "\n"
    return result


@tool(response_format="content_and_artifact")
async def search_nearby(keywords: str = "", radius: int = 1000, location: str = "") -> tuple[str, dict | None]:
    """搜索附近的兴趣点。冷冷可以问「附近有什么咖啡店呀？」
    如果有 GPS 坐标，location 可以不传，会自动使用冷冷的当前位置。

    Args:
        keywords: 搜索关键词，如「咖啡店」「餐厅」
        radius: 搜索半径（米），默认 1000m
        location: 中心点坐标（WGS-84，移动端 GPS 原值），格式「经度,纬度」，
            如「114.17,22.30」。可以不传，自动使用 GPS。入高德时内部转 GCJ-02。
    """
    try:
        # 坐标全程以 WGS-84（浏览器 GPS 原值）持有，仅在入高德 API
        # 的最后一步转 GCJ-02（高德契约）
        latlon = _parse_latlon(location) if location else None
        if latlon is None:
            # 没有可用坐标 → 尝试会话缓存的 GPS
            cached = await location_store.get_any()
            if cached:
                latlon = cached

        if latlon is None:
            return ("小汐不知道冷冷现在在哪里呢……告诉小汐你的位置，或者开启 GPS 定位好不好？", None)

        # 高德用 GCJ-02 加偏坐标系：WGS-84 原值直传会偏出几百米
        #（内地 iPhone 必现；安卓系统常代转 GCJ-02 反而「侥幸正确」。
        #  港澳同样收 GCJ-02 —— out_of_china 国境框含港澳，一并转换）
        glat, glon = wgs84_to_gcj02(latlon[0], latlon[1])
        data = await _amap_request("/v3/place/around", {
            "location": f"{glon},{glat}", "keywords": keywords,
            "radius": radius, "offset": 10,
        })
        if data.get("status") != "1":
            return ("小汐在海里游了一圈，没找到附近有什么……", None)
        pois = [_normalize_amap_poi(p) for p in data.get("pois", [])]
        # 高德排序首位即最佳候选，nav 只带一个目的地（避免 UI 跳转爆炸）
        nav = _nav(pois[0]["name"], pois[0]["location"]) if pois else None
        return (_render_pois(keywords, pois), nav)
    except _AmapKeyMissing:
        return (MSG_AMAP_KEY_MISSING, None)
    except Exception as e:
        log_tool_error("search_nearby", e)
        return ("呜，小汐去找的时候迷路了……过一会儿再试试好不好？", None)


@tool(response_format="content_and_artifact")
async def geocode_address(address: str) -> tuple[str, dict | None]:
    """把中文地址转成经纬度坐标。冷冷可以直接问「铜锣湾在哪里？」

    Args:
        address: 详细地址，如「香港铜锣湾时代广场」
    """
    try:
        gc = await _amap_geocode(address, "香港")
        if gc is None or not gc.get("location"):
            return (f"小汐在香港找了找，好像没有「{address}」这个地方……", None)
        text = f"📍 {gc.get('formatted_address', address)}：{gc.get('location', '?')}"
        return (text, _nav(gc.get("formatted_address") or address, gc.get("location")))
    except _AmapKeyMissing:
        return (MSG_AMAP_KEY_MISSING, None)
    except Exception as e:
        log_tool_error("geocode_address", e)
        return ("小汐定位的时候漂走了……过一会儿再试试好不好？", None)


@tool(response_format="content_and_artifact")
async def fuzzy_search_place(keywords: str, city: str = "香港") -> tuple[str, dict | None]:
    """按关键词模糊搜索香港的地点。冷冷可以直接问「尖沙咀在哪里？」

    Args:
        keywords: 搜索关键词，如「尖沙咀」「铜锣湾」
        city: 城市，默认「香港」
    """
    try:
        data = await _amap_request("/v3/place/text", {
            "keywords": keywords, "city": city, "offset": 5,
        })
        if data.get("status") != "1" or not data.get("pois"):
            return (f"小汐在香港找了找，没有找到「{keywords}」……", None)
        pois = data["pois"][:5]
        result = f"🐋 小汐找到了这些「{keywords}」：\n\n"
        for poi in pois:
            result += f"· {poi.get('name', '?')}（{poi.get('address', '?')}）"
            if poi.get("location"):
                result += f"  🗺️ {poi['location']}"
            result += "\n"
        nav = _nav(pois[0].get("name") or keywords, pois[0].get("location"))
        return (result, nav)
    except _AmapKeyMissing:
        return (MSG_AMAP_KEY_MISSING, None)
    except Exception as e:
        log_tool_error("fuzzy_search_place", e)
        return ("小汐被水母绊了一下……过一会儿再试试好不好？", None)


@tool(response_format="content_and_artifact")
async def get_transit_route(origin: str, destination: str, city: str = "香港") -> tuple[str, dict | None]:
    """查询公交/地铁路线。冷冷可以问「从铜锣湾怎么去中环？」

    Args:
        origin: 起点，如「铜锣湾」
        destination: 终点，如「中环」
        city: 城市，默认「香港」
    """
    try:
        og_gc = await _amap_geocode(origin, city)
        dg_gc = await _amap_geocode(destination, city)
        og_loc = (og_gc or {}).get("location", "")
        dg_loc = (dg_gc or {}).get("location", "")
        if not og_loc or not dg_loc:
            return ("小汐找不到这条路……地址是不是写错了呀？", None)

        route = await _amap_request("/v3/direction/transit/integrated", {
            "origin": og_loc, "destination": dg_loc, "city": city,
        })
        transits = (route.get("route") or {}).get("transits", [])
        if not transits:
            return ("小汐没找到公交路线……试试打车？", None)

        t = transits[0]
        dur = _to_int(t.get("duration"))
        walk = _to_int(t.get("walking_distance"))
        result = f"🚇 小汐帮你规划好啦！\n"
        result += f"💰 约 {t.get('cost','?')} 元  ⏱️ 约 {dur // 60} 分钟"
        if walk:
            result += f"  🚶 {walk}m"
        result += "\n\n"

        for seg in t.get("segments", [])[:8]:
            bus = seg.get("bus", {})
            if bus and bus.get("buslines"):
                bl = bus["buslines"][0]
                dep = (bl.get("departure_stop") or {}).get("name", "?")
                arr = (bl.get("arrival_stop") or {}).get("name", "?")
                result += f"  🚌 {bl.get('name','?')}：{dep} → {arr}\n"
            walk_s = seg.get("walking", {})
            if walk_s:
                d = _to_int(walk_s.get("distance"))
                if d > 100:
                    result += f"  🚶 步行 {d}m\n"
        # nav 指向目的地（起点未知时地图 App 从当前位置起步）
        return (result, _nav(destination, dg_loc))
    except _AmapKeyMissing:
        return (MSG_AMAP_KEY_MISSING, None)
    except Exception as e:
        log_tool_error("get_transit_route", e)
        return ("小汐被红绿灯搞晕了……过一会儿再试试好不好？", None)


@tool(response_format="content_and_artifact")
async def get_cycling_route(origin: str, destination: str, city: str = "香港") -> tuple[str, dict | None]:
    """查询骑行路线。

    Args:
        origin: 起点
        destination: 终点
        city: 城市，默认「香港」
    """
    try:
        og_gc = await _amap_geocode(origin, city)
        dg_gc = await _amap_geocode(destination, city)
        og_loc = (og_gc or {}).get("location", "")
        dg_loc = (dg_gc or {}).get("location", "")
        if not og_loc or not dg_loc:
            return ("小汐找不到这条路……", None)

        data = await _amap_request("/v4/direction/bicycling", {
            "origin": og_loc, "destination": dg_loc,
        })
        paths = (data.get("data") or {}).get("paths") or []
        if not paths:
            return ("小汐没找到骑行路线……这段路也许不适合骑行，或在映海的骑行地图之外，试试公交好不好？", None)
        p = paths[0]
        d = _to_int(p.get("distance"))
        t = _to_int(p.get("duration"))
        if d <= 0:
            return ("小汐没找到骑行路线……这段路也许不适合骑行，或在映海的骑行地图之外，试试公交好不好？", None)
        return (f"🚲 {d}m，约 {t // 60} 分钟", _nav(destination, dg_loc))
    except _AmapKeyMissing:
        return (MSG_AMAP_KEY_MISSING, None)
    except Exception as e:
        log_tool_error("get_cycling_route", e)
        return ("小汐骑着骑着漂走了……过一会儿再试试好不好？", None)
