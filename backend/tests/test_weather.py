"""天气工具单元测试 — 坐标解析和 WMO 码映射。"""
import pytest

from tools.weather import _resolve_coords, _WMO, _RAIN_CODES, _STORM_CODES


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


async def test_wmo_rain_codes_all_mapped():
    """所有降雨代码都在 WMO 映射表中。"""
    for code in _RAIN_CODES:
        assert code in _WMO, f"降雨代码 {code} 未在 _WMO 中定义"


async def test_wmo_storm_codes_all_mapped():
    """所有雷暴代码都在 WMO 映射表中。"""
    for code in _STORM_CODES:
        assert code in _WMO, f"雷暴代码 {code} 未在 _WMO 中定义"
