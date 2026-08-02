"""地图工具 — 高德/谷歌双引擎。

引擎选择（_pick_engine）：坐标几何判定——中国内地 → 高德（免费、
内地数据全），内地之外（港澳/海外）→ 谷歌 Places（香港 POI 质量
最好）。面向 LLM 的工具签名与 docstring 完全不变，切换对 LLM 无感；
两引擎输出归一化为同构 POI 字典后统一渲染。

路线规划（公交/骑行）仅高德有实现：任一端点落在谷歌侧 → 固定婉拒
文案（谷歌 Directions 记为将来项）。
"""
import httpx
from langchain_core.tools import tool
from config.settings import get_settings
from memory import location_store
from tools.google_places import (
    _GoogleKeyMissing, MSG_GOOGLE_KEY_MISSING,
    nearby_search as _google_nearby, text_search as _google_text,
)
from utils.errors import log_tool_error
from utils.geo import wgs84_to_gcj02
from utils.retry import with_retry
from utils.timezone import DEFAULT_LAT, DEFAULT_LON

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


# ── 双引擎选择与归一化 ─────────────────────────────────────────

# 中国内地粗框（覆盖内地全境）：经度 73–135，纬度 18–54
_MAINLAND_BBOX = (73.0, 18.0, 135.0, 54.0)
# 港澳排除框：几何上落在内地框内，但高德港澳 POI 数据弱 → 强制谷歌。
# 纬度上界取 22.50 ≈ 香港北界（深圳河一线）——深圳南侧（福田 22.54）
# 留在高德，港深交界少量区域（新界北 22.49-22.50 之间）的归属误差
# = 引擎换用，两侧都能给出正确坐标，体验差只在 POI 丰度
_HKMO_BBOX = (113.82, 22.15, 114.44, 22.50)


def _pick_engine(lat: float, lon: float) -> str:
    """按坐标选地图引擎：中国内地（不含港澳）→ amap，其余 → google。

    深圳 → 高德；港岛/九龙/新界 → 谷歌；海外 → 谷歌。
    """
    w, s, e, n = _MAINLAND_BBOX
    in_mainland = w <= lon <= e and s <= lat <= n
    w2, s2, e2, n2 = _HKMO_BBOX
    in_hkmo = w2 <= lon <= e2 and s2 <= lat <= n2
    return "amap" if (in_mainland and not in_hkmo) else "google"


def _parse_latlon(location: str) -> tuple[float, float] | None:
    """解析「经度,纬度」（高德契约）→ (lat, lon)；非法格式返回 None。"""
    try:
        lon_s, lat_s = location.split(",")
        return float(lat_s), float(lon_s)
    except (ValueError, AttributeError):
        return None


async def _engine_hint() -> tuple[float, float]:
    """引擎判定的参考坐标：用户最后位置，无缓存回退默认香港坐标。

    文本查询（地理编码/模糊搜索）自身没有坐标，只能按用户最近一次
    定位推断她大概率在问哪边的地方。
    """
    try:
        cached = await location_store.get_any()
        if cached:
            return cached
    except Exception:
        pass
    return DEFAULT_LAT, DEFAULT_LON


def _normalize_amap_poi(poi: dict) -> dict:
    """高德 POI → 与谷歌同构的归一化字典（两侧统一渲染）。"""
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
    """两引擎共用的 POI 渲染（归一化字典就是为了这一刻）。"""
    if not pois:
        return f"小汐在附近没有找到「{keywords}」……要不要换个词试试？"
    result = f"🐋 小汐帮你找到附近的「{keywords}」啦：\n\n"
    for i, poi in enumerate(pois[:8], 1):
        result += f"{i}. **{poi['name']}**\n   📍 {poi['address']}"
        if poi.get("distance_m"):
            result += f"  📏 约 {poi['distance_m']}m"
        if poi.get("rating") is not None:
            result += f"  ⭐ {poi['rating']}"
        result += "\n\n"
    return result


async def _resolve_engine_for_place(place: str, city: str) -> tuple[str, str]:
    """地名 → (engine, "经度,纬度")。引擎按用户最后位置判定。"""
    hint = await _engine_hint()
    if _pick_engine(*hint) == "google":
        hits = await _google_text(place)
        loc = hits[0]["location"] if hits else ""
        return "google", loc
    data = await _amap_request("/v3/geocode/geo", {"address": place, "city": city})
    loc = (data.get("geocodes") or [{}])[0].get("location", "")
    return "amap", loc


@tool
async def search_nearby(keywords: str = "", radius: int = 1000, location: str = "") -> str:
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
        # 的最后一步转 GCJ-02——引擎判定 _pick_engine 必须用真坐标
        latlon = _parse_latlon(location) if location else None
        if latlon is None:
            # 没有可用坐标 → 尝试会话缓存的 GPS
            cached = await location_store.get_any()
            if cached:
                latlon = cached

        if latlon is None:
            return "小汐不知道冷冷现在在哪里呢……告诉小汐你的位置，或者开启 GPS 定位好不好？"

        if _pick_engine(*latlon) == "google":
            pois = await _google_nearby(latlon, keywords, radius)
        else:
            # 高德用 GCJ-02 加偏坐标系：WGS-84 原值直传会偏出几百米
            #（内地 iPhone 必现；安卓系统常代转 GCJ-02 反而「侥幸正确」）
            glat, glon = wgs84_to_gcj02(latlon[0], latlon[1])
            data = await _amap_request("/v3/place/around", {
                "location": f"{glon},{glat}", "keywords": keywords,
                "radius": radius, "offset": 10,
            })
            if data.get("status") != "1":
                return "小汐在海里游了一圈，没找到附近有什么……"
            pois = [_normalize_amap_poi(p) for p in data.get("pois", [])]

        return _render_pois(keywords, pois)
    except _AmapKeyMissing:
        return MSG_AMAP_KEY_MISSING
    except _GoogleKeyMissing:
        return MSG_GOOGLE_KEY_MISSING
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
        if _pick_engine(*(await _engine_hint())) == "google":
            hits = await _google_text(address)
            if not hits or not hits[0]["location"]:
                return f"小汐找了找，好像没有「{address}」这个地方……"
            return f"📍 {hits[0]['address']}：{hits[0]['location']}"

        data = await _amap_request("/v3/geocode/geo", {
            "address": address, "city": "香港",
        })
        if data.get("status") != "1" or not data.get("geocodes"):
            return f"小汐在香港找了找，好像没有「{address}」这个地方……"
        gc = data["geocodes"][0]
        return f"📍 {gc.get('formatted_address', address)}：{gc.get('location', '?')}"
    except _AmapKeyMissing:
        return MSG_AMAP_KEY_MISSING
    except _GoogleKeyMissing:
        return MSG_GOOGLE_KEY_MISSING
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
        if _pick_engine(*(await _engine_hint())) == "google":
            hits = await _google_text(keywords)
            if not hits:
                return f"小汐找了找，没有找到「{keywords}」……"
            result = f"🐋 小汐找到了这些「{keywords}」：\n\n"
            for i, poi in enumerate(hits[:5], 1):
                result += f"{i}. **{poi['name']}**\n   📍 {poi['address']}"
                if poi.get("location"):
                    result += f"  🗺️ {poi['location']}"
                result += "\n\n"
            return result

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
    except _AmapKeyMissing:
        return MSG_AMAP_KEY_MISSING
    except _GoogleKeyMissing:
        return MSG_GOOGLE_KEY_MISSING
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
        og_engine, og_loc = await _resolve_engine_for_place(origin, city)
        dg_engine, dg_loc = await _resolve_engine_for_place(destination, city)
        if og_engine == "google" or dg_engine == "google":
            # 谷歌 Directions 是将来项；港澳/海外的路线规划先婉拒
            return "这条线小汐暂时还画不了公交路线……查附近的地方倒是可以的！"
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
    except _AmapKeyMissing:
        return MSG_AMAP_KEY_MISSING
    except _GoogleKeyMissing:
        return MSG_GOOGLE_KEY_MISSING
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
        og_engine, og_loc = await _resolve_engine_for_place(origin, city)
        dg_engine, dg_loc = await _resolve_engine_for_place(destination, city)
        if og_engine == "google" or dg_engine == "google":
            return "这条线小汐暂时还画不了骑行路线……查附近的地方倒是可以的！"
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
    except _AmapKeyMissing:
        return MSG_AMAP_KEY_MISSING
    except _GoogleKeyMissing:
        return MSG_GOOGLE_KEY_MISSING
    except Exception as e:
        log_tool_error("get_cycling_route", e)
        return "小汐骑着骑着漂走了……过一会儿再试试好不好？"
