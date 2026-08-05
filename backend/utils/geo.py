"""WGS-84 ↔ GCJ-02 坐标转换（高德坐标系）。

国内地图服务（高德）使用 GCJ-02 加偏坐标，浏览器 Geolocation API
返回真 WGS-84——直接喂给高德会偏出几百米（内地 iPhone 尤甚，
安卓端系统常代转 GCJ-02 反而「侥幸正确」）。标准公开算法，
国境框外（海外）恒等返回。

注：标准算法的国境框含港澳——港澳坐标按框内加偏。这是**正确**的：
单引擎后港澳同样走高德（高德港澳数据同为 GCJ-02 加偏系），
内地/港澳坐标都应加偏后入高德，仅国境框外（海外）恒等返回。
"""
import math

_A = 6378245.0                # Krasovsky 椭球长半轴
_EE = 0.00669342162296594323  # 第一偏心率平方


def out_of_china(lat: float, lon: float) -> bool:
    """国境框粗判：框外不做加偏（高德框外本就无数据）。"""
    return not (72.004 <= lon <= 137.8347 and 0.8293 <= lat <= 55.8271)


def _transform_lat(x: float, y: float) -> float:
    ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lon(x: float, y: float) -> float:
    ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lat: float, lon: float) -> tuple[float, float]:
    """WGS-84 → GCJ-02。国境框外（海外）原样返回。"""
    if out_of_china(lat, lon):
        return lat, lon
    dlat = _transform_lat(lon - 105.0, lat - 35.0)
    dlon = _transform_lon(lon - 105.0, lat - 35.0)
    rad = lat / 180.0 * math.pi
    magic = 1 - _EE * math.sin(rad) ** 2
    sqrt_magic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_A * (1 - _EE)) / (magic * sqrt_magic) * math.pi)
    dlon = (dlon * 180.0) / (_A / sqrt_magic * math.cos(rad) * math.pi)
    return lat + dlat, lon + dlon
