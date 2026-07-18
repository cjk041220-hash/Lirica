"""天气查询工具 — Open-Meteo 免费 API。"""
import httpx
from langchain_core.tools import tool

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


async def _resolve_coords(city: str) -> tuple[float, float, str]:
    """解析城市名到坐标。优先查本地字典，回退到高德地理编码。"""
    lat, lon = _CITY_COORDS.get(city, _CITY_COORDS.get(city.lower(), (0, 0)))
    if lat:
        return lat, lon, city

    # 回退：通过高德地理编码 API 获取坐标
    try:
        from config.settings import get_settings
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
    except Exception:
        pass

    # 最终回退：香港
    return 22.30, 114.17, city


@tool
async def get_weather(city: str = "香港") -> str:
    """查询指定城市的实时天气和明天预报。当冷冷问天气、提到出门、或说冷/热时主动调用。

    Args:
        city: 城市名称，默认「香港」。支持：香港、北京、上海、广州、深圳等。
    """
    try:
        lat, lon, resolved_city = await _resolve_coords(city)
        display_city = resolved_city if resolved_city != city else city

        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                "https://api.open-meteo.com/v1/forecast",
                params={
                    "latitude": lat, "longitude": lon,
                    "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
                    "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                    "timezone": "Asia/Hong_Kong",
                    "forecast_days": 2,
                },
            )
            data = resp.json()

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
        elif current.get("temperature_2m"):
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
            if len(tm) > 1:
                result += f"\n\n📅 明天：{_WMO.get(wc[1], '?')}，{tn[1]}°C ~ {tm[1]}°C"
                if rp[1] and int(rp[1]) > 50:
                    result += f"（降水概率 {rp[1]}%）"

        return result
    except Exception as e:
        return f"呜，小汐去查天气的时候被浪花卷了一下……等浪退了再试试？"
