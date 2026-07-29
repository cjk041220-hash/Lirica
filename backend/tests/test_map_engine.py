"""地图双引擎测试 — _pick_engine 几何判定 / 归一化 / 分发 / 双 key 语义分离。

纪律复述：「没配 key」与「查无结果」的文案必须可区分，否则 LLM 会
自信谎报「附近没有咖啡店」（_AmapKeyMissing 同款设计，现扩至谷歌侧）。
location_store.get_any 一律桩掉——绝不能触发 database.core 惰性初始化
碰到生产库。
"""
from unittest.mock import AsyncMock, patch

import pytest

from config.settings import get_settings
from tools.google_places import (
    MSG_GOOGLE_KEY_MISSING, _GoogleKeyMissing, _normalize_place, text_search,
)
from tools.map_tools import (
    MSG_AMAP_KEY_MISSING, _normalize_amap_poi, _parse_latlon, _pick_engine,
    fuzzy_search_place, get_transit_route, search_nearby,
)


# ── _pick_engine 几何判定 ─────────────────────────────────────


def test_pick_engine_mainland_amap():
    assert _pick_engine(22.54, 114.05) == "amap"    # 深圳
    assert _pick_engine(39.90, 116.40) == "amap"    # 北京
    assert _pick_engine(43.82, 87.62) == "amap"     # 乌鲁木齐（西陲框内）


def test_pick_engine_hk_overseas_google():
    assert _pick_engine(22.30, 114.17) == "google"  # 尖沙咀（港澳排除框内）
    assert _pick_engine(22.32, 114.18) == "google"  # 排除框内再取一点
    assert _pick_engine(35.68, 139.69) == "google"  # 东京（框外）
    assert _pick_engine(51.50, -0.12) == "google"   # 伦敦


def test_pick_engine_hkmo_box_boundary():
    """排除框外缘（新界北/港深边界北侧）归回高德——边界误差=换引擎，不坏。"""
    assert _pick_engine(22.60, 114.20) == "amap"    # 框北外
    assert _pick_engine(22.10, 114.20) == "amap"    # 框南外（仍在内地大框）


def test_parse_latlon():
    assert _parse_latlon("114.17,22.30") == (22.30, 114.17)  # 高德序→(lat,lon)
    assert _parse_latlon("坏的") is None
    assert _parse_latlon("") is None


# ── 归一化 ────────────────────────────────────────────────────


def test_normalize_amap_poi_defensive():
    """高德 distance 字段畸形（[] / None / ""）不抛（_to_int 精神）。"""
    base = {"name": " Cafe", "address": "街 1 号", "location": "114.1,22.3"}
    assert _normalize_amap_poi({**base, "distance": []})["distance_m"] == 0
    assert _normalize_amap_poi({**base, "distance": None})["distance_m"] is None
    assert _normalize_amap_poi({**base, "distance": ""})["distance_m"] is None
    assert _normalize_amap_poi({**base, "distance": "500"})["distance_m"] == 500


def test_normalize_google_place_flips_coords():
    """谷歌 lat/lng 分字段 → 归一化为高德「经度,纬度」序（反序全链软失败）。"""
    poi = _normalize_place({
        "displayName": {"text": "兰芳园"},
        "formattedAddress": "中环结志街 2 号",
        "location": {"latitude": 22.282, "longitude": 114.154},
        "rating": 4.3,
        "types": ["cafe"],
    })
    assert poi["location"] == "114.154,22.282"
    assert poi["name"] == "兰芳园"
    assert poi["rating"] == 4.3
    assert poi["distance_m"] is None

    # 缺字段兜底不抛
    bare = _normalize_place({})
    assert bare["name"] == "?" and bare["location"] == ""


# ── key 缺失语义（两侧严格分离）──────────────────────────────


async def test_google_key_missing_raises(monkeypatch):
    monkeypatch.setattr(get_settings(), "google_maps_api_key", "")
    with pytest.raises(_GoogleKeyMissing):
        await text_search("中环")


async def test_search_nearby_hk_no_google_key(monkeypatch):
    """港岛坐标 + 无谷歌 key → 谷歌侧归因文案（不是「没找到」）。"""
    monkeypatch.setattr(get_settings(), "google_maps_api_key", "")
    with patch("memory.location_store.get_any",
               AsyncMock(return_value=(22.28, 114.15))):
        out = await search_nearby.ainvoke({"keywords": "咖啡店"})
    assert out == MSG_GOOGLE_KEY_MISSING
    assert out != MSG_AMAP_KEY_MISSING   # 两文案不串，LLM 才能正确归因


async def test_search_nearby_mainland_no_amap_key(monkeypatch):
    """深圳坐标 + 无高德 key → 高德侧文案（双引擎回归，防改坏内地路径）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "")
    out = await search_nearby.ainvoke({
        "keywords": "咖啡店", "location": "114.05,22.54",
    })
    assert out == MSG_AMAP_KEY_MISSING


# ── 引擎分发 ──────────────────────────────────────────────────


async def test_search_nearby_google_dispatch_and_render(monkeypatch):
    """港岛坐标 + 有 key → 走谷歌，归一化后统一渲染（含评分）。"""
    monkeypatch.setattr(get_settings(), "google_maps_api_key", "fake-key")
    fake_pois = [{
        "name": "Cafe Coral", "address": "铜锣湾百德新街",
        "location": "114.18,22.28", "rating": 4.5,
        "distance_m": None, "types": ["cafe"],
    }]
    with patch("tools.map_tools._google_nearby", AsyncMock(return_value=fake_pois)) as gn, \
         patch("memory.location_store.get_any", AsyncMock(return_value=(22.28, 114.15))):
        out = await search_nearby.ainvoke({"keywords": "咖啡店"})
    assert "Cafe Coral" in out and "⭐ 4.5" in out
    gn.assert_awaited_once()
    assert gn.call_args.args[0] == (22.28, 114.15)   # latlon 原样下传


async def test_fuzzy_search_google_dispatch(monkeypatch):
    """用户位置在港 → 模糊搜索走谷歌 text_search。"""
    monkeypatch.setattr(get_settings(), "google_maps_api_key", "")
    with patch("tools.map_tools._engine_hint", AsyncMock(return_value=(22.30, 114.17))):
        out = await fuzzy_search_place.ainvoke({"keywords": "中环"})
    assert out == MSG_GOOGLE_KEY_MISSING


async def test_transit_route_google_side_declines(monkeypatch):
    """任一端点落谷歌侧 → 固定婉拒（谷歌 Directions 是将来项）。"""
    with patch("tools.map_tools._resolve_engine_for_place",
               AsyncMock(return_value=("google", "114.17,22.30"))):
        out = await get_transit_route.ainvoke({
            "origin": "尖沙咀", "destination": "中环",
        })
    assert "画不了" in out


async def test_transit_route_amap_no_key(monkeypatch):
    """两端都在高德侧但没 key → 高德归因文案（婉拒分支不误伤）。"""
    monkeypatch.setattr(get_settings(), "amap_api_key", "")
    with patch("tools.map_tools._resolve_engine_for_place",
               AsyncMock(return_value=("amap", "114.17,22.30"))):
        out = await get_transit_route.ainvoke({
            "origin": "福田", "destination": "南山",
        })
    assert out == MSG_AMAP_KEY_MISSING


async def test_google_error_does_not_leak_secret(monkeypatch):
    """回归（谷歌侧）：异常细节（含 key）绝不回给 LLM，只回固定兜底。"""
    monkeypatch.setattr(get_settings(), "google_maps_api_key", "fake-key")

    async def boom(latlon, keywords, radius):
        raise RuntimeError("request failed X-Goog-Api-Key=SUPER_SECRET")

    with patch("tools.map_tools._google_nearby", boom):
        out = await search_nearby.ainvoke({"keywords": "咖啡店", "location": "114.17,22.30"})
    assert "SUPER_SECRET" not in out
    assert "迷路" in out
