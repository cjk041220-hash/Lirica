"""地图工具 — 高德地图 API。"""
import httpx
from langchain_core.tools import tool
from config.settings import get_settings
from memory import location_store
from utils.errors import log_tool_error
from utils.retry import with_retry

# 共享 HTTP 客户端（由 server.py lifespan 注入）
_shared_client: httpx.AsyncClient | None = None


def set_http_client(client: httpx.AsyncClient):
    """注入共享 HTTP 客户端（在 lifespan 中调用）。"""
    global _shared_client
    _shared_client = client


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
    """发起高德地图 API 请求（带重试）。优先使用共享客户端，回退到临时客户端。"""
    s = get_settings()
    params["key"] = s.amap_api_key

    if _shared_client is not None:
        resp = await _shared_client.get(f"https://restapi.amap.com{endpoint}", params=params)
        resp.raise_for_status()
        return resp.json()
    else:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(f"https://restapi.amap.com{endpoint}", params=params)
            resp.raise_for_status()
            return resp.json()


@tool
async def search_nearby(keywords: str = "", radius: int = 1000, location: str = "") -> str:
    """搜索附近的兴趣点。冷冷可以问「附近有什么咖啡店呀？」
    如果有 GPS 坐标，location 可以不传，会自动使用冷冷的当前位置。

    Args:
        keywords: 搜索关键词，如「咖啡店」「餐厅」
        radius: 搜索半径（米），默认 1000m
        location: 中心点坐标，格式「经度,纬度」，如「114.17,22.30」。可以不传，自动使用 GPS。
    """
    try:
        # 如果没有传 location，尝试从会话缓存中获取 GPS 坐标
        if not location:
            cached = await location_store.get_any()
            if cached:
                lat, lon = cached
                location = f"{lon},{lat}"

        if not location:
            return "小汐不知道冷冷现在在哪里呢……告诉小汐你的位置，或者开启 GPS 定位好不好？"

        data = await _amap_request("/v3/place/around", {
            "location": location, "keywords": keywords,
            "radius": radius, "offset": 10,
        })
        if data.get("status") != "1":
            return "小汐在海里游了一圈，没找到附近有什么……"

        pois = data.get("pois", [])
        if not pois:
            return f"小汐在附近没有找到「{keywords}」……要不要换个词试试？"

        result = f"🐋 小汐帮你找到附近的「{keywords}」啦：\n\n"
        for i, poi in enumerate(pois[:8], 1):
            name = poi.get("name", "?")
            address = poi.get("address", "?")
            distance = poi.get("distance", "?")
            result += f"{i}. **{name}**\n   📍 {address}"
            if distance and distance != "?":
                result += f"  📏 约 {int(distance)}m"
            result += "\n\n"
        return result
    except Exception as e:
        log_tool_error("search_nearby", e)
        return "呜，小汐去找的时候迷路了……过一会儿再试试好不好？"


@tool
async def geocode_address(address: str) -> str:
    """把中文地址转成经纬度坐标。冷冷可以直接问「铜锣湾在哪里？」

    Args:
        address: 详细地址，如「香港铜锣湾时代广场」
    """
    try:
        data = await _amap_request("/v3/geocode/geo", {
            "address": address, "city": "香港",
        })
        if data.get("status") != "1" or not data.get("geocodes"):
            return f"小汐在香港找了找，好像没有「{address}」这个地方……"
        gc = data["geocodes"][0]
        return f"📍 {gc.get('formatted_address', address)}：{gc.get('location', '?')}"
    except Exception as e:
        log_tool_error("geocode_address", e)
        return "小汐定位的时候漂走了……过一会儿再试试好不好？"


@tool
async def fuzzy_search_place(keywords: str, city: str = "香港") -> str:
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
            return f"小汐在香港找了找，没有找到「{keywords}」……"
        result = f"🐋 小汐找到了这些「{keywords}」：\n\n"
        for i, poi in enumerate(data["pois"][:5], 1):
            result += f"{i}. **{poi.get('name','?')}**\n"
            result += f"   📍 {poi.get('address','?')}"
            if poi.get("location"):
                result += f"  🗺️ {poi['location']}"
            result += "\n\n"
        return result
    except Exception as e:
        log_tool_error("fuzzy_search_place", e)
        return "小汐被水母绊了一下……过一会儿再试试好不好？"


@tool
async def get_transit_route(origin: str, destination: str, city: str = "香港") -> str:
    """查询公交/地铁路线。冷冷可以问「从铜锣湾怎么去中环？」

    Args:
        origin: 起点，如「铜锣湾」
        destination: 终点，如「中环」
        city: 城市，默认「香港」
    """
    try:
        og = await _amap_request("/v3/geocode/geo", {"address": origin, "city": city})
        dg = await _amap_request("/v3/geocode/geo", {"address": destination, "city": city})
        og_loc = (og.get("geocodes") or [{}])[0].get("location", "")
        dg_loc = (dg.get("geocodes") or [{}])[0].get("location", "")
        if not og_loc or not dg_loc:
            return "小汐找不到这条路……地址是不是写错了呀？"

        route = await _amap_request("/v3/direction/transit/integrated", {
            "origin": og_loc, "destination": dg_loc, "city": city,
        })
        transits = (route.get("route") or {}).get("transits", [])
        if not transits:
            return "小汐没找到公交路线……试试打车？"

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
        return result
    except Exception as e:
        log_tool_error("get_transit_route", e)
        return "小汐被红绿灯搞晕了……过一会儿再试试好不好？"


@tool
async def get_cycling_route(origin: str, destination: str, city: str = "香港") -> str:
    """查询骑行路线。

    Args:
        origin: 起点
        destination: 终点
        city: 城市，默认「香港」
    """
    try:
        og = await _amap_request("/v3/geocode/geo", {"address": origin, "city": city})
        dg = await _amap_request("/v3/geocode/geo", {"address": destination, "city": city})
        og_loc = (og.get("geocodes") or [{}])[0].get("location", "")
        dg_loc = (dg.get("geocodes") or [{}])[0].get("location", "")
        if not og_loc or not dg_loc:
            return "小汐找不到这条路……"

        data = await _amap_request("/v4/direction/bicycling", {
            "origin": og_loc, "destination": dg_loc,
        })
        paths = (data.get("data") or {}).get("paths") or []
        if not paths:
            return "小汐没找到骑行路线……这段路也许不适合骑行，或在映海的骑行地图之外，试试公交好不好？"
        p = paths[0]
        d = _to_int(p.get("distance"))
        t = _to_int(p.get("duration"))
        if d <= 0:
            return "小汐没找到骑行路线……这段路也许不适合骑行，或在映海的骑行地图之外，试试公交好不好？"
        return f"🚲 {d}m，约 {t // 60} 分钟"
    except Exception as e:
        log_tool_error("get_cycling_route", e)
        return "小汐骑着骑着漂走了……过一会儿再试试好不好？"
