"""错误脱敏工具测试 — 防止 API Key 随异常文本泄漏（#22 回归）。

高德等服务的 Key 以 URL 查询参数传递，httpx 异常的 str(e) 含完整 URL。
旧版工具把 str(e)[:80] 直接返回给 LLM → 可能经复述泄漏到前端/checkpoint。
"""
import logging

from utils.errors import log_tool_error, sanitize_sensitive_text


def test_sanitize_masks_amap_key_in_url():
    """高德异常 URL 中的 key 参数被遮罩，其余上下文保留。"""
    text = (
        "Server error '500 Internal Server Error' for url "
        "'https://restapi.amap.com/v3/place/around?key=SECRET_AMAP_KEY_123&keywords=coffee&offset=10'"
    )
    result = sanitize_sensitive_text(text)
    assert "SECRET_AMAP_KEY_123" not in result
    assert "key=***" in result
    assert "keywords=coffee" in result  # 非敏感参数保留，可排障
    assert "restapi.amap.com" in result


def test_sanitize_masks_various_sensitive_params():
    """key / apikey / api_key / token / signature / secret 都被遮罩（大小写不敏感）。"""
    text = "a?token=t0k3n&APIKEY=abc&signature=xyz&secret=s3cr3t&api_key=ak&safe=1"
    result = sanitize_sensitive_text(text)
    for leaked in ("t0k3n", "abc", "xyz", "s3cr3t", "ak"):
        assert leaked not in result
    assert "safe=1" in result


def test_sanitize_leaves_clean_text_untouched():
    """无敏感参数的文本原样返回。"""
    text = "Connection timed out after 10 seconds"
    assert sanitize_sensitive_text(text) == text


def test_log_tool_error_masks_key_in_log(caplog):
    """log_tool_error 落日志前遮罩敏感参数。"""
    exc = Exception(
        "error for url 'https://restapi.amap.com/v3/geocode/geo?key=REAL_KEY&address=x'"
    )
    with caplog.at_level(logging.WARNING):
        log_tool_error("geocode_address", exc)

    log_text = caplog.text
    assert "REAL_KEY" not in log_text
    assert "key=***" in log_text
    assert "geocode_address" in log_text


async def test_map_tool_returns_generic_message_without_exception_details(tmp_db, monkeypatch):
    """回归：地图工具失败时返回固定文案，不含异常细节（即不含 URL/Key）。"""
    import importlib

    map_tools = importlib.import_module("tools.map_tools")

    async def boom(endpoint, params):
        raise RuntimeError(
            "error for url 'https://restapi.amap.com/v3/place/around?key=SUPER_SECRET&keywords=x'"
        )

    monkeypatch.setattr(map_tools, "_amap_request", boom)

    # 深圳坐标走高德 around 路径（单引擎后港澳同样走高德，
    # 此用例钉的是 around 请求的异常脱敏，深圳坐标保持原意图）
    result = await map_tools.search_nearby.ainvoke({"keywords": "咖啡", "location": "114.05,22.54"})
    assert "SUPER_SECRET" not in result
    assert "key=" not in result
    assert "迷路" in result  # 友好文案
