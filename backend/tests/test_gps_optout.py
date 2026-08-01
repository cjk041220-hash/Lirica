"""GPS 显式关闭闭环回归（体检「GPS 关不净」）。

修复前：前端关 GPS 只写 localStorage 开关，后端 location_store 按 TTL
最长 8h 保留旧坐标并持续向每条对话注入「系统信息：冷冷当前 GPS 坐标」
——关了还在用。修复：请求体带 gps_enabled=false → 后端清缓存 + 不注入。
"""
from memory import location_store


async def test_forget_removes_cached_coords():
    """location_store.forget：删掉会话缓存并如实回报。"""
    await location_store.update("s-optout", 22.3, 114.17)
    assert await location_store.get("s-optout") is not None

    assert await location_store.forget("s-optout") is True
    assert await location_store.get("s-optout") is None
    # 再删不存在的会话：False，不报错
    assert await location_store.forget("s-optout") is False


async def test_gps_disabled_clears_cache_and_skips_injection():
    """gps_enabled=False：缓存被清 + 构建的消息不带坐标后缀。"""
    from api.routes.chat import ChatRequest, _build_input_with_location

    await location_store.update("s-off", 22.3, 114.17)
    req = ChatRequest(message="你好", session_id="s-off", gps_enabled=False)

    result = await _build_input_with_location("你好", req, "s-off")

    assert result["messages"][0].content == "你好"  # 无 GPS 后缀
    assert await location_store.get("s-off") is None  # 缓存已清


async def test_gps_enabled_still_updates_and_injects():
    """gps_enabled=True（或未表态）：行为不变，更新缓存并注入后缀。"""
    from api.routes.chat import ChatRequest, _build_input_with_location

    req = ChatRequest(
        message="天气", session_id="s-on",
        latitude=22.3, longitude=114.17, gps_enabled=True,
    )

    result = await _build_input_with_location("天气", req, "s-on")

    assert "GPS 坐标" in result["messages"][0].content
    assert await location_store.get("s-on") == (22.3, 114.17)


async def test_gps_legacy_client_no_signal_unchanged():
    """gps_enabled 缺省（None，旧客户端）：行为与修复前完全一致。"""
    from api.routes.chat import ChatRequest, _build_input_with_location

    req = ChatRequest(message="在吗", session_id="s-legacy")  # 无 gps 字段

    result = await _build_input_with_location("在吗", req, "s-legacy")

    assert result["messages"][0].content == "在吗"  # 无坐标可注入
    assert await location_store.get("s-legacy") is None
