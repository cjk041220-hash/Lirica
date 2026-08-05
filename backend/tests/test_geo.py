"""WGS-84 → GCJ-02 坐标转换测试（utils/geo.py）。

纯算法无外部依赖：境外恒等、内地偏移量级、确定性。
"""
from utils.geo import out_of_china, wgs84_to_gcj02


def test_out_of_china_identity():
    """海外（国境框外）：原样返回。"""
    assert wgs84_to_gcj02(35.6762, 139.6503) == (35.6762, 139.6503)  # 东京（框东缘外）
    assert wgs84_to_gcj02(51.5007, -0.1246) == (51.5007, -0.1246)    # 伦敦
    assert out_of_china(35.6762, 139.6503) is True
    assert out_of_china(22.5431, 114.0579) is False  # 深圳在框内


def test_hk_inside_standard_box_gets_shifted():
    """标准算法的国境框含港澳 → 港澳坐标按框内加偏。

    单引擎（纯高德）后这正是生产语义：港澳同样走高德，
    高德港澳数据同为 GCJ-02 加偏系（见 geo.py 模块注）。
    """
    shifted = wgs84_to_gcj02(22.3193, 114.1694)  # 尖沙咀
    assert shifted != (22.3193, 114.1694)
    assert out_of_china(22.3193, 114.1694) is False


def test_mainland_offset_magnitude():
    """内地：结果 ≠ 原值，两分量偏移在 GCJ 加偏量级（约 55m–1.1km）。"""
    for lat, lon in ((22.5431, 114.0579), (39.9042, 116.4074)):  # 深圳 / 北京
        glat, glon = wgs84_to_gcj02(lat, lon)
        assert (glat, glon) != (lat, lon)
        assert 0.0005 < abs(glat - lat) < 0.01
        assert 0.0005 < abs(glon - lon) < 0.01


def test_mainland_result_in_neighborhood():
    """偏移再大也在原点左右（不会飞到别的城市）。"""
    glat, glon = wgs84_to_gcj02(22.5431, 114.0579)
    assert abs(glat - 22.5431) < 0.02
    assert abs(glon - 114.0579) < 0.02


def test_deterministic():
    a = wgs84_to_gcj02(31.2304, 121.4737)  # 上海
    b = wgs84_to_gcj02(31.2304, 121.4737)
    assert a == b
