"""谷歌地图（Places API New）—— 港澳/海外 POI 搜索。

与高德（map_tools._amap_request）组成双引擎：内地高德、港澳/海外谷歌，
引擎选择是坐标几何判定（map_tools._pick_engine）。本模块只做谷歌侧
请求 + 归一化，引擎分发与面向 LLM 的文案渲染都在 map_tools。

设计纪律（与 map_tools._AmapKeyMissing 同构，逐条复刻）：
- key 缺失必须与「真实零结果」语义分离——否则 LLM 分辨不出配置问题
  与查无结果，会反复换参数空转，最后自信地告诉用户「附近没有咖啡店」，
  而真相是海外地图功能根本没开启；
- key 走 X-Goog-Api-Key 请求头（不进 URL query），异常路径经
  utils/errors.py 脱敏零负担；
- 共享客户端请求期动态读 map_tools._shared_client（函数内 import
  防循环导入；本模块无模块级全局 → 免登记 reset_tool_globals），
  None 时回退临时客户端 timeout=10；
- 关键词近距搜索用 searchText + 圆形 locationBias：Places (New) 的
  searchNearby 端点不支持关键词文本（只能按类型过滤），textSearch
  + bias 是关键词「附近搜索」的官方姿势。
"""
import httpx

from config.settings import get_settings
from utils.logger import get_logger

log = get_logger(__name__)

_PLACES_TEXT_SEARCH = "https://places.googleapis.com/v1/places:searchText"
_FIELD_MASK = (
    "places.displayName,places.formattedAddress,places.location,"
    "places.rating,places.types"
)
# key 维度错误状态码：401 key 无效 / 403 未启用 API / 429 额度耗尽
# ——统一归一为 _GoogleKeyMissing（与高德 infocode 10001/10003 同理）
_KEY_STATUS_CODES = {401, 403, 429}


class _GoogleKeyMissing(Exception):
    """GOOGLE_MAPS_API_KEY 未配置 / 无效 / 额度耗尽。

    必须与「真实零结果」语义区分（同 _AmapKeyMissing 的设计理由）：
    否则 LLM 无法分辨配置问题与查无结果，会按零结果话术骗用户。
    """


MSG_GOOGLE_KEY_MISSING = (
    "小汐的港澳/海外地图功能现在用不了……可能是冷冷还没配置 GOOGLE_MAPS_API_KEY，"
    "或者这个 Key 无效 / 今日调用额度用完了哦。内地的地方小汐还是能帮你查的。"
)


def _normalize_place(place: dict) -> dict:
    """Google place → 与高德同构的归一化 POI。

    location 统一为高德契约的「经度,纬度」字符串（谷歌给的是
    latitude/longitude 分开字段，注意顺序翻转，反序会让下游
    按「lon,lat」解析的工具全部软失败）。缺字段一律兜底不抛。
    """
    loc = place.get("location") or {}
    lat, lon = loc.get("latitude"), loc.get("longitude")
    location = f"{lon},{lat}" if lat is not None and lon is not None else ""
    return {
        "name": (place.get("displayName") or {}).get("text") or "?",
        "address": place.get("formattedAddress") or "?",
        "location": location,
        "rating": place.get("rating"),     # 无评分 → None（渲染侧按需显示）
        "distance_m": None,                # textSearch 不直接给距离，恒 None
        "types": place.get("types") or [],
    }


async def _text_search(text_query: str,
                       latlon: tuple[float, float] | None = None,
                       radius: int | None = None) -> list[dict]:
    """Places (New) searchText → 归一化 POI 列表。

    传 latlon/radius 时加圆形 locationBias（关键词近距搜索）；
    不传则是纯文本搜索（地理编码 / 模糊搜索场景）。
    """
    s = get_settings()
    if not s.google_maps_api_key:
        raise _GoogleKeyMissing("GOOGLE_MAPS_API_KEY 未配置")

    body: dict = {"textQuery": text_query}
    if latlon is not None:
        lat, lon = latlon
        body["locationBias"] = {
            "circle": {
                "center": {"latitude": lat, "longitude": lon},
                "radius": radius or 1000,
            }
        }

    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": s.google_maps_api_key,
        "X-Goog-FieldMask": _FIELD_MASK,
    }
    # 共享客户端优先、临时客户端回退（同 map_tools._amap_request 姿势）。
    # 函数内 import 防循环（map_tools 模块级 import 本模块）
    from tools import map_tools as _map
    if _map._shared_client is not None:
        resp = await _map._shared_client.post(_PLACES_TEXT_SEARCH, json=body, headers=headers)
    else:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(_PLACES_TEXT_SEARCH, json=body, headers=headers)

    if resp.status_code in _KEY_STATUS_CODES:
        raise _GoogleKeyMissing(f"Google 返回状态 {resp.status_code}（key 维度错误）")
    resp.raise_for_status()
    return [_normalize_place(p) for p in (resp.json().get("places") or [])]


async def nearby_search(latlon: tuple[float, float], keywords: str,
                        radius: int = 1000) -> list[dict]:
    """关键词近距搜索（latlon = (lat, lon)，带圆形 locationBias）。"""
    query = (keywords or "").strip() or "餐厅 咖啡店 便利店"
    return await _text_search(query, latlon=latlon, radius=radius)


async def text_search(query: str) -> list[dict]:
    """纯文本搜索（地名 → 候选 POI，供地理编码 / 模糊搜索用）。"""
    return await _text_search(query or " ")
