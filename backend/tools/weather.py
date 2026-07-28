"""天气查询工具 — Open-Meteo 免费 API。"""
import httpx
from langchain_core.tools import tool
from utils.errors import log_tool_error, sanitize_sensitive_text
from utils.logger import get_logger
from utils.retry import with_retry

log = get_logger(__name__)

# 城市坐标映射（覆盖更多港澳台及大陆城市）
_CITY_COORDS = {
    # 香港各区
    "香港": (22.30, 114.17), "hong kong": (22.30, 114.17), "hk": (22.30, 114.17),
    "九龙": (22.32, 114.19), "kowloon": (22.32, 114.19),
    "尖沙咀": (22.30, 114.17), "tst": (22.30, 114.17),
    "铜锣湾": (22.28, 114.18), "causeway bay": (22.28, 114.18),
    "中环": (22.28, 114.16), "central": (22.28, 114.16),
    "旺角": (22.32, 114.17), "mong kok": (22.32, 114.17),
    "元朗": (22.44, 114.03), "沙田": (22.38, 114.19),
    "荃湾": (22.37, 114.11), "屯门": (22.39, 113.98),
    "大埔": (22.45, 114.17), "西贡": (22.38, 114.27),
    "观塘": (22.31, 114.22), "深水埗": (22.33, 114.16),
    "湾仔": (22.28, 114.17), "跑马地": (22.27, 114.18),
    "太古城": (22.29, 114.22), "天水围": (22.44, 114.00),
    "将军澳": (22.32, 114.26), "东涌": (22.29, 113.95),
    "赤柱": (22.22, 114.21), "兰桂坊": (22.28, 114.16),
    # 澳门
    "澳门": (22.20, 113.55), "macau": (22.20, 113.55),
    # 大陆主要城市
    "北京": (39.91, 116.40), "上海": (31.23, 121.47),
    "广州": (23.13, 113.26), "深圳": (22.54, 114.06),
    "杭州": (30.27, 120.15), "成都": (30.57, 104.07),
    "重庆": (29.56, 106.55), "武汉": (30.59, 114.31),
    "南京": (32.06, 118.80), "西安": (34.26, 108.94),
    "长沙": (28.23, 112.94), "厦门": (24.48, 118.09),
    "珠海": (22.27, 113.58), "佛山": (23.02, 113.12),
    "东莞": (23.04, 113.75), "天津": (39.13, 117.20),
    "青岛": (36.07, 120.38), "大连": (38.91, 121.60),
    "苏州": (31.30, 120.59), "台北": (25.03, 121.57),
    "高雄": (22.63, 120.30),
}

# WMO 天气代码 → 中文 + emoji
_WMO: dict[int, str] = {
    0: "☀️ 晴天", 1: "🌤️ 大部晴朗", 2: "⛅ 多云", 3: "☁️ 阴天",
    45: "🌫️ 雾", 48: "🌫️ 霜雾",
    51: "🌧️ 毛毛雨", 53: "🌧️ 毛毛雨", 55: "🌧️ 毛毛雨",
    61: "🌧️ 小雨", 63: "🌧️ 中雨", 65: "🌧️ 大雨",
    71: "❄️ 小雪", 73: "❄️ 中雪", 75: "❄️ 大雪",
    80: "🌦️ 阵雨", 81: "🌦️ 阵雨", 82: "🌦️ 阵雨",
    95: "⛈️ 雷暴", 96: "⛈️ 雷暴", 99: "⛈️ 雷暴",
}

_RAIN_CODES = {51, 53, 55, 61, 63, 65, 80, 81, 82}
_STORM_CODES = {95, 96, 99}


@with_retry(max_attempts=3, base_delay=1.0)
async def _fetch_open_meteo(
    client: httpx.AsyncClient,
    lat: float,
    lon: float,
    *,
    forecast_days: int = 2,
) -> dict:
    """带重试的 Open-Meteo API 调用（仅对网络瞬时错误重试）。

    Args:
        forecast_days: 预报天数（1-16）。默认 2 天（今天+明天），
            保持 get_weather / 早安问候两个既有调用方行为不变；
            事件检测（utils.events.detect_weather_events）传 7 天，
            用于提前发现台风/暴雨。
    """
    resp = await client.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": lat, "longitude": lon,
            "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
            # wind_speed_10m_max：台风/大风检测的必要信号（WMO 无台风 code，
            # 只能靠天气 code + 逐日最大风速双信号合成）
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max",
            "timezone": "Asia/Hong_Kong",
            "forecast_days": max(1, min(16, forecast_days)),
        },
    )
    resp.raise_for_status()
    return resp.json()


async def _resolve_coords(city: str) -> tuple[float | None, float | None, str]:
    """解析城市名到坐标。优先查本地字典，回退到高德地理编码。

    Returns:
        (lat, lon, display_name) — lat/lon 为 None 表示解析失败。
    """
    coords = _CITY_COORDS.get(city) or _CITY_COORDS.get(city.lower())
    if coords is not None:
        return coords[0], coords[1], city

    # 回退：通过高德地理编码 API 获取坐标
    try:
        from config.settings import get_settings
        from utils.logger import get_logger
        s = get_settings()
        if s.amap_api_key:
            async with httpx.AsyncClient(timeout=8) as client:
                resp = await client.get(
                    "https://restapi.amap.com/v3/geocode/geo",
                    params={"key": s.amap_api_key, "address": city},
                )
                data = resp.json()
                geocodes = data.get("geocodes", [])
                if geocodes:
                    location = geocodes[0].get("location", "")
                    resolved_name = geocodes[0].get("city", city) or geocodes[0].get("district", city)
                    if "," in location:
                        lon_s, lat_s = location.split(",")
                        return float(lat_s), float(lon_s), resolved_name
    except Exception as e:
        # 异常文本含带 key= 参数的完整 URL，必须脱敏后再落日志
        log.warning(
            "高德地理编码失败（%s）：%s", city, sanitize_sensitive_text(str(e))
        )

    # 最终回退：无法定位，返回 None
    return None, None, city


@tool
async def get_weather(
    city: str = "香港",
    latitude: float | None = None,
    longitude: float | None = None,
) -> str:
    """查询指定城市或坐标的实时天气和明天预报。当冷冷问天气、提到出门、或说冷/热时主动调用。

    Args:
        city: 城市名称，默认「香港」。支持：香港、北京、上海、广州、深圳等。
        latitude: 纬度（可选）。如果有 GPS 坐标可直接传入，优先级高于 city。
        longitude: 经度（可选）。如果有 GPS 坐标可直接传入，优先级高于 city。
    """
    try:
        # 坐标优先级：参数直传 > 会话缓存 > 城市名查表
        # 使用 is not None 判空：赤道(0.0)和本初子午线(0.0)是合法坐标
        if latitude is not None and longitude is not None:
            lat, lon = latitude, longitude
            display_city = city
        else:
            lat, lon, display_city = await _resolve_coords(city)

        # 地理编码失败（不在本地字典且高德 API 不可用）
        if lat is None or lon is None:
            return (
                f"小汐在映海里找了找，没有找到「{city}」的位置……\n"
                f"冷冷可以试试输入更具体的城市名，比如「香港」「北京」「上海」~"
            )

        async with httpx.AsyncClient(timeout=10) as client:
            data = await _fetch_open_meteo(client, lat, lon)

        current = data.get("current", {})
        daily = data.get("daily", {})
        code = current.get("weather_code", 0)

        result = f"🌤️ {display_city}实时天气：\n"
        result += f"☁️ {_WMO.get(code, f'未知({code})')}\n"
        result += f"🌡️ 气温 {current.get('temperature_2m','?')}°C（体感 {current.get('apparent_temperature','?')}°C）\n"
        result += f"💧 湿度 {current.get('relative_humidity_2m','?')}%\n"
        result += f"💨 风速 {current.get('wind_speed_10m','?')} km/h"

        # 温馨提示
        if code in _RAIN_CODES:
            result += "\n\n☔ 可能要下雨哦，冷冷记得带伞！"
        elif code in _STORM_CODES:
            result += "\n\n⛈️ 有雷暴！冷冷要注意安全呀！"
        elif current.get("temperature_2m") is not None:
            # is not None：恰好 0.0°C 时旧写法的 falsy 判断会跳过防寒提示
            # （:145 判坐标用 is not None、注释明说 0.0 合法，两处须一致）
            t = float(current["temperature_2m"])
            if t > 30:
                result += "\n\n☀️ 好热呀，冷冷记得多喝水！"
            elif t < 15:
                result += "\n\n🧣 有点冷，冷冷多穿一件呀！"

        # 明天预报
        if daily:
            tm = daily.get("temperature_2m_max", ["?"])
            tn = daily.get("temperature_2m_min", ["?"])
            wc = daily.get("weather_code", [0, 0])
            rp = daily.get("precipitation_probability_max", [0, 0])
            # 各数组分别守卫长度：Open-Meteo 偶尔返回不等长字段，
            # 只查 tm 会让 wc[1]/tn[1]/rp[1] 抛 IndexError，把本可正常
            # 展示的天气整体降级成「被浪花卷了一下」
            if len(tm) > 1 and len(tn) > 1 and len(wc) > 1:
                result += f"\n\n📅 明天：{_WMO.get(wc[1], '?')}，{tn[1]}°C ~ {tm[1]}°C"
                if len(rp) > 1 and rp[1] and int(rp[1]) > 50:
                    result += f"（降水概率 {rp[1]}%）"

        return result

    except httpx.TimeoutException:
        return (
            f"呜，天气服务响应太慢了，小汐等了好久都没等到……\n"
            f"冷冷稍等一下再问小汐好不好？"
        )
    except httpx.HTTPStatusError as e:
        log_tool_error("get_weather", e)
        return (
            f"天气服务暂时出了点问题（状态码 {e.response.status_code}），"
            f"小汐再去映海里看看，冷冷等一下再试试~"
        )
    except (KeyError, ValueError, TypeError) as e:
        log_tool_error("get_weather", e)
        return (
            f"小汐拿到了天气数据但是看不懂……映海里的信号好像有点乱，"
            f"冷冷过一会儿再问问小汐吧~"
        )
    except Exception as e:
        log_tool_error("get_weather", e)
        return f"呜，小汐去查「{city}」天气的时候被浪花卷了一下……等浪退了再试试？"
