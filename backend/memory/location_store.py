"""GPS 坐标会话级缓存 — 前端发来的坐标存这里，工具直接读取。"""
import time
import threading

_location_cache: dict[str, dict] = {}
_lock = threading.Lock()

# 坐标有效期：8 小时（单次使用周期内足够）
_TTL_SECONDS = 8 * 3600


def update(session_id: str, lat: float, lon: float):
    """存储/更新某会话的 GPS 坐标。"""
    if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
        return
    with _lock:
        _location_cache[session_id] = {
            "lat": lat,
            "lon": lon,
            "time": time.time(),
        }
        _cleanup()


def get(session_id: str) -> tuple[float, float] | None:
    """获取某会话的缓存坐标。过期返回 None。"""
    with _lock:
        entry = _location_cache.get(session_id)
        if entry is None:
            return None
        if time.time() - entry["time"] > _TTL_SECONDS:
            del _location_cache[session_id]
            return None
        return entry["lat"], entry["lon"]


def get_any() -> tuple[float, float] | None:
    """获取任意一个有效缓存的坐标（适用于单用户场景）。"""
    with _lock:
        now = time.time()
        for entry in _location_cache.values():
            if now - entry["time"] <= _TTL_SECONDS:
                return entry["lat"], entry["lon"]
        return None


def _cleanup():
    """清除过期条目（调用方须已持有 _lock）。"""
    now = time.time()
    expired = [
        sid for sid, v in _location_cache.items()
        if now - v["time"] > _TTL_SECONDS
    ]
    for sid in expired:
        del _location_cache[sid]
