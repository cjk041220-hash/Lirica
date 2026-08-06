"""天气工具单元测试 — 坐标解析和 WMO 码映射。"""
import pytest

from tools.weather import (
    _geocode_is_trustworthy, _norm_name, _resolve_coords,
    _WMO, _RAIN_CODES, _STORM_CODES,
)


def _fake_geocode(location: str, level: str = "市", **kw) -> dict:
    """构造一条高德 geocode 返回（默认 city 级 + 广州坐标）。"""
    g = {
        "level": level,
        "location": location,
        "province": kw.get("province", "广东省"),
        "city": kw.get("city", "广州市"),
        "district": kw.get("district", ""),
        "name": kw.get("name", ""),
        "formatted_address": kw.get("formatted_address", "广东省广州市"),
    }
    return g


async def _resolve_with_fake_geocode(city, geocode, monkeypatch):
    """注入假高德响应后调用 _resolve_coords。

    _resolve_coords 内 `from config.settings import get_settings` 是函数内
    局部 import（每次调用现取），须 patch config.settings 模块本身。
    """
    import tools.weather as weather_module
    import config.settings as settings_module
    from config.settings import Settings

    fake_settings = Settings(amap_api_key="fake-key-for-test")
    monkeypatch.setattr(settings_module, "get_settings", lambda: fake_settings)

    class FakeResp:
        def json(self):
            return {"geocodes": [geocode] if geocode else []}

    class FakeClient:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url, **kw):
            return FakeResp()

    monkeypatch.setattr(weather_module.httpx, "AsyncClient", FakeClient)
    return await _resolve_coords(city)


# ── 名称归一化 ────────────────────────────────────────────────

def test_norm_name_strips_admin_suffixes():
    assert _norm_name("洛阳市") == "洛阳"
    assert _norm_name("浦东新区") == "浦东"
    assert _norm_name("香港特别行政区") == "香港"
    assert _norm_name("Guangzhou") == "guangzhou"
    assert _norm_name("") == ""


def test_norm_name_removes_stacked_suffixes():
    """尾缀剥除一次剥到底；「省+市」等中间层级剥不到但保留
    （名称一致性靠 _geocode_is_trustworthy 的包含判断兜底）。"""
    assert _norm_name("广州市") == "广州"
    assert _norm_name("广州市白云区") == "广州市白云"
    assert _norm_name("湖南省长沙市") == "湖南省长沙"


# ── 可信度校验（P2-3：高德模糊匹配兜底）──────────────────────

def test_geocode_trustworthy_matching_city():
    g = _fake_geocode("113.26,23.13", city="洛阳市", province="河南省",
                      formatted_address="河南省洛阳市")
    assert _geocode_is_trustworthy("洛阳", g) is True


def test_geocode_trustworthy_fuzzy_fallback_rejected():
    """体检 P2-3：不存在的城市被高德模糊匹配成广州 → 拒绝采信。"""
    g = _fake_geocode("113.347033,23.099564", city="广州市", province="广东省")
    assert _geocode_is_trustworthy("不存在的城市xyz", g) is False


def test_geocode_trustworthy_rejects_province_level():
    """province/country 级结果太粗（「广东省」「中国」），不能当城市定位。"""
    g = _fake_geocode("113.26,23.13", level="省", city="", province="广东省")
    assert _geocode_is_trustworthy("广东省", g) is False
    g2 = _fake_geocode("113.26,23.13", level="国家", city="", province="中国")
    assert _geocode_is_trustworthy("中国", g2) is False


def test_geocode_trustworthy_accepts_district_level():
    g = _fake_geocode("114.05,22.54", level="区县", city="深圳市",
                      district="南山区", formatted_address="广东省深圳市南山区")
    assert _geocode_is_trustworthy("南山", g) is True


def test_geocode_trustworthy_rejects_short_input():
    """单字输入无区分度，不冒险。"""
    g = _fake_geocode("113.26,23.13", city="广州市")
    assert _geocode_is_trustworthy("广", g) is False


def test_geocode_trustworthy_district_in_city_name():
    """输入带区名（「西湖区」）能对上 district 字段。"""
    g = _fake_geocode("120.15,30.27", level="区县", city="杭州市",
                      district="西湖区", formatted_address="浙江省杭州市西湖区")
    assert _geocode_is_trustworthy("西湖区", g) is True


# ── 坐标解析 ──────────────────────────────────────────────────

async def test_resolve_coords_known_city():
    """已知城市返回正确坐标。"""
    lat, lon, name = await _resolve_coords("香港")
    assert lat == 22.30
    assert lon == 114.17
    assert name == "香港"


async def test_resolve_coords_case_insensitive():
    """城市名大小写不敏感。"""
    lat, lon, name = await _resolve_coords("HONG KONG")
    assert lat == 22.30
    assert lon == 114.17


async def test_resolve_coords_unknown_city():
    """未知城市（无高德 key）返回 None。"""
    lat, lon, name = await _resolve_coords("一个不存在的城市XYZ")
    assert lat is None
    assert lon is None
    assert name == "一个不存在的城市XYZ"


async def test_resolve_coords_returns_none_not_zero():
    """解析失败返回 None 而非 (0,0)。"""
    lat, lon, _ = await _resolve_coords("不存在的城市ABC")
    # 修复后应该是 None，不是 0.0
    assert lat is None or lat != 0.0 or lon is None or lon != 0.0


async def test_resolve_coords_fuzzy_fallback_rejected(monkeypatch):
    """P2-3 回归：高德模糊匹配（不存在城市→广州）必须被拒，返回 None。"""
    g = _fake_geocode("113.347033,23.099564", city="广州市", province="广东省")
    lat, lon, name = await _resolve_with_fake_geocode("不存在的城市xyz", g, monkeypatch)
    assert lat is None and lon is None
    assert name == "不存在的城市xyz"


async def test_resolve_coords_normal_geocode_accepted(monkeypatch):
    """正常城市（不在本地字典）经高德解析 → 接受并返回坐标。"""
    g = _fake_geocode("112.453895,34.619702", city="洛阳市", province="河南省",
                      formatted_address="河南省洛阳市")
    lat, lon, name = await _resolve_with_fake_geocode("洛阳", g, monkeypatch)
    assert lat == pytest.approx(34.619702)
    assert lon == pytest.approx(112.453895)
    assert name == "洛阳市"


async def test_resolve_coords_geocode_exception_safe(monkeypatch):
    """高德调用抛异常 → 降级 None，不击穿（既有行为保持）。"""
    import tools.weather as weather_module
    import config.settings as settings_module

    fake_settings = type("S", (), {"amap_api_key": "fake"})()
    monkeypatch.setattr(settings_module, "get_settings", lambda: fake_settings)

    class Boom:
        async def __aenter__(self):
            raise RuntimeError("amap down")

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(weather_module.httpx, "AsyncClient", Boom)
    lat, lon, name = await _resolve_coords("洛阳")
    assert lat is None and lon is None
    assert name == "洛阳"


async def test_wmo_rain_codes_all_mapped():
    """所有降雨代码都在 WMO 映射表中。"""
    for code in _RAIN_CODES:
        assert code in _WMO, f"降雨代码 {code} 未在 _WMO 中定义"


async def test_wmo_storm_codes_all_mapped():
    """所有雷暴代码都在 WMO 映射表中。"""
    for code in _STORM_CODES:
        assert code in _WMO, f"雷暴代码 {code} 未在 _WMO 中定义"


async def test_zero_degrees_gets_cold_hint(monkeypatch):
    """回归：恰好 0.0°C 时旧 falsy 判断跳过防寒提示（0.0 是合法温度）。"""
    import tools.weather as weather_module
    from tools.weather import get_weather

    async def fake_fetch(client, lat, lon):
        return {
            "current": {
                "weather_code": 0,
                "temperature_2m": 0.0,
                "apparent_temperature": -2.0,
                "relative_humidity_2m": 60,
                "wind_speed_10m": 10,
            },
            "daily": {},
        }

    monkeypatch.setattr(weather_module, "_fetch_open_meteo", fake_fetch)

    result = await get_weather.ainvoke({
        "city": "北京", "latitude": 39.9, "longitude": 116.4,
    })
    assert "多穿一件" in result  # t < 15 分支必须命中，哪怕恰好 0.0°C
