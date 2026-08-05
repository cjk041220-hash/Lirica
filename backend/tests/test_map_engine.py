"""地图单引擎测试 — 纯高德：归一化 / GCJ-02 转换 / key 语义 / nav artifact 契约。

纪律复述：「没配 key」与「查无结果」的文案必须可区分，否则 LLM 会
自信谎报「附近没有咖啡店」（_AmapKeyMissing 设计）。
location_store.get_any 一律桩掉——绝不能触发 database.core 惰性初始化
碰到生产库。

content_and_artifact 契约（已实证 langchain-core 1.4.9）：
- 直接 ainvoke({纯参数}) → tool_call_id 为 None → 只回 content 字符串
  （老测试 / verify_tools 零改动）；
- 图内 ToolNode 传 ToolCall（含 type/id）→ 构造 ToolMessage（content +
  artifact）——chat.py on_tool_end 消费的就是它。_graph_call 模拟该形态。
"""
from unittest.mock import AsyncMock, patch

import pytest

from config.settings import get_settings
from tools.map_tools import (
    MSG_AMAP_KEY_MISSING, _normalize_amap_poi, _parse_latlon, _nav,
    fuzzy_search_place, geocode_address, get_transit_route, search_nearby,
)

NAV = {"name": "尖沙咀站", "lat": 22.297, "lon": 114.172, "coord": "gcj02"}


def _graph_call(tool, args: dict):
    """以图内 ToolCall 形态调用工具 → ToolMessage（含 content/artifact）。"""
    return tool.ainvoke({
        "type": "tool_call", "name": tool.name, "id": "call_test", "args": args,
    })


# ── 解析与归一化 ──────────────────────────────────────────────


def test_parse_latlon():
    assert _parse_latlon("114.17,22.30") == (22.30, 114.17)  # 高德序→(lat,lon)
    assert _parse_latlon("坏的") is None
    assert _parse_latlon("") is None


def test_normalize_amap_poi_defensive():
    """高德 distance 字段畸形（[] / None / ""）不抛（_to_int 精神）。"""
    base = {"name": " Cafe", "address": "街 1 号", "location": "114.1,22.3"}
    assert _normalize_amap_poi({**base, "distance": []})["distance_m"] == 0
    assert _normalize_amap_poi({**base, "distance": None})["distance_m"] is None
    assert _normalize_amap_poi({**base, "distance": ""})["distance_m"] is None
    assert _normalize_amap_poi({**base, "distance": "500"})["distance_m"] == 500


# ── nav 构造器 ────────────────────────────────────────────────


def test_nav_builds_from_amap_contract_location():
    """「经度,纬度」GCJ-02 字符串 → {name, lat, lon, coord}；非法返回 None。"""
    assert _nav("尖沙咀站", "114.172,22.297") == NAV
    assert _nav("没坐标", "") is None
    assert _nav("坏坐标", "abc") is None
    assert _nav("缺一半", "114.17") is None


# ── key 缺失语义 ──────────────────────────────────────────────


async def test_search_nearby_mainland_no_amap_key(monkeypatch):
    """深圳坐标 + 无高德 key → 高德侧文案（回归，防改坏内地路径）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "")
    out = await search_nearby.ainvoke({
        "keywords": "咖啡店", "location": "114.05,22.54",
    })
    assert out == MSG_AMAP_KEY_MISSING


# ── 高德分发与渲染 ────────────────────────────────────────────


async def test_search_nearby_amap_converts_to_gcj02(monkeypatch):
    """内地坐标：实发 location 必须是 GCJ-02 转换值。

    回归：前端 GPS 报 WGS-84，曾原样喂给高德（要 GCJ-02）→ 内地
    iPhone「附近」搜索整体偏几百米（安卓系统常代转反而「侥幸正确」）。
    """
    from utils.geo import wgs84_to_gcj02

    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    amap = AsyncMock(return_value={"status": "1", "pois": []})
    with patch("tools.map_tools._amap_request", amap):
        await search_nearby.ainvoke({
            "keywords": "咖啡", "location": "114.0579,22.5431",  # 深圳·福田（WGS-84）
        })

    amap.assert_awaited_once()
    sent = amap.call_args.args[1]["location"]
    glat, glon = wgs84_to_gcj02(22.5431, 114.0579)
    assert sent == f"{glon},{glat}"
    assert sent != "114.0579,22.5431"  # 确实转了，不是原值穿透


# ── nav artifact 契约（content_and_artifact）─────────────────


async def test_direct_ainvoke_still_returns_str(monkeypatch):
    """纯参数直调 → 纯字符串（tool_call_id 为 None，无 ToolMessage 包装）。

    老测试与 verify_tools 的兼容契约：Content 即渲染文本，tuple 不外泄。
    """
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    fake = [{
        "name": "Cafe Coral", "address": "铜锣湾百德新街",
        "location": "114.18,22.28", "distance": "120",
    }]
    with patch("tools.map_tools._amap_request",
               AsyncMock(return_value={"status": "1", "pois": fake})):
        out = await search_nearby.ainvoke({"keywords": "咖啡店", "location": "114.17,22.30"})
    assert isinstance(out, str)
    assert "Cafe Coral" in out and "📏 约 120m" in out


async def test_search_nearby_nav_first_poi(monkeypatch):
    """图内形态：nav = 高德排序首位 POI（GCJ-02 原值 + coord 标记）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    fake = [
        {"name": "兰芳园", "address": "中环结志街 2 号", "location": "114.154,22.282", "distance": "80"},
        {"name": "翠华", "address": "中环", "location": "114.159,22.279", "distance": "300"},
    ]
    with patch("tools.map_tools._amap_request",
               AsyncMock(return_value={"status": "1", "pois": fake})):
        msg = await _graph_call(search_nearby, {"keywords": "茶餐厅", "location": "114.15,22.28"})
    assert "兰芳园" in msg.content
    assert msg.artifact == {
        "name": "兰芳园", "lat": 22.282, "lon": 114.154, "coord": "gcj02",
    }


async def test_search_nearby_empty_pois_nav_none(monkeypatch):
    """零结果 → 诚实文案，nav 为 None（不渲染跳转按钮）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    with patch("tools.map_tools._amap_request",
               AsyncMock(return_value={"status": "1", "pois": []})):
        msg = await _graph_call(search_nearby, {"keywords": "咖啡店", "location": "114.17,22.30"})
    assert "没有找到" in msg.content
    assert msg.artifact is None


async def test_geocode_address_nav_formatted_address(monkeypatch):
    """geocode：nav 名称取 formatted_address，坐标为 geocode location。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    fake = {"status": "1", "geocodes": [{
        "formatted_address": "香港铜锣湾时代广场",
        "location": "114.182,22.277",
    }]}
    with patch("tools.map_tools._amap_request", AsyncMock(return_value=fake)):
        msg = await _graph_call(geocode_address, {"address": "铜锣湾时代广场"})
    assert "📍" in msg.content
    assert msg.artifact == {
        "name": "香港铜锣湾时代广场", "lat": 22.277, "lon": 114.182, "coord": "gcj02",
    }


async def test_geocode_address_not_found_nav_none(monkeypatch):
    """查无此地 → 文案 + nav None。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    with patch("tools.map_tools._amap_request",
               AsyncMock(return_value={"status": "1", "geocodes": []})):
        msg = await _graph_call(geocode_address, {"address": "不存在的地方"})
    assert "没有" in msg.content
    assert msg.artifact is None


async def test_fuzzy_search_nav_first_poi(monkeypatch):
    """模糊搜索：nav = 首位 POI（city 参数直传高德）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    fake = {"status": "1", "pois": [
        {"name": "尖沙咀站", "address": "尖沙咀", "location": "114.172,22.297"},
        {"name": "尖沙咀码头", "address": "梳士巴利道", "location": "114.168,22.294"},
    ]}
    with patch("tools.map_tools._amap_request", AsyncMock(return_value=fake)):
        msg = await _graph_call(fuzzy_search_place, {"keywords": "尖沙咀"})
    assert "尖沙咀站" in msg.content
    assert msg.artifact == NAV


async def test_transit_route_nav_destination(monkeypatch):
    """公交：nav 指向 destination（起点未知时地图 App 从当前位置起步）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")
    geocodes = AsyncMock(side_effect=[
        {"location": "114.155,22.281"},          # 起点
        {"formatted_address": "中环码头", "location": "114.159,22.285"},  # 终点
    ])
    route = {"status": "1", "route": {"transits": [{
        "duration": "1800", "cost": "8", "walking_distance": "200",
        "segments": [{"bus": {"buslines": [{"name": "MTR 港岛线", "departure_stop": {"name": "香港站"}, "arrival_stop": {"name": "中环站"}}]}}],
    }]}}
    with patch("tools.map_tools._amap_geocode", geocodes), \
         patch("tools.map_tools._amap_request", AsyncMock(return_value=route)):
        msg = await _graph_call(get_transit_route, {
            "origin": "上环", "destination": "中环码头",
        })
    assert "🚇" in msg.content
    assert msg.artifact == {
        "name": "中环码头", "lat": 22.285, "lon": 114.159, "coord": "gcj02",
    }


async def test_transit_route_amap_no_key(monkeypatch):
    """无 key → 高德归因文案（geocode 先行、_amap_request 抛 key 缺失）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "")
    with patch("tools.map_tools._amap_geocode",
               AsyncMock(return_value={"location": "114.17,22.30"})):
        msg = await _graph_call(get_transit_route, {
            "origin": "福田", "destination": "南山",
        })
    assert msg.content == MSG_AMAP_KEY_MISSING
    assert msg.artifact is None


async def test_soft_error_paths_nav_none(monkeypatch):
    """软错误路径一律 (文案, None)：无坐标 / geocode 失败 / 公交空结果。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "fake-key")

    with patch("memory.location_store.get_any", AsyncMock(return_value=None)):
        msg = await _graph_call(search_nearby, {"keywords": "咖啡店"})
    assert "不知道冷冷现在在哪里" in msg.content
    assert msg.artifact is None

    with patch("tools.map_tools._amap_geocode", AsyncMock(return_value=None)):
        msg = await _graph_call(get_transit_route, {
            "origin": "不存在的地方", "destination": "中环",
        })
    assert "找不到这条路" in msg.content
    assert msg.artifact is None

    with patch("tools.map_tools._amap_geocode",
               AsyncMock(return_value={"location": "114.17,22.30"})), \
         patch("tools.map_tools._amap_request",
               AsyncMock(return_value={"status": "1", "route": {"transits": []}})):
        msg = await _graph_call(get_transit_route, {
            "origin": "福田", "destination": "南山",
        })
    assert "没找到公交路线" in msg.content
    assert msg.artifact is None
