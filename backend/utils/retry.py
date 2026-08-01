"""轻量异步重试装饰器 — 用于工具中的 HTTP 调用。

只对瞬时网络错误（超时、连接失败、传输异常）重试，
不对 HTTP 状态码错误（4xx/5xx）重试——那表示服务端已明确回复。
"""
import asyncio
import functools

import httpx

from utils.logger import get_logger

log = get_logger(__name__)

# 可重试的异常类型
_RETRYABLE = (
    httpx.TimeoutException,
    httpx.ConnectError,
    httpx.ReadError,
    httpx.WriteError,
    httpx.PoolTimeout,
    asyncio.TimeoutError,
)


def with_retry(max_attempts: int = 3, base_delay: float = 1.0):
    """异步重试装饰器，指数退避。

    Args:
        max_attempts: 最大尝试次数（含首次），默认 3
        base_delay: 首次重试的等待秒数，之后每次翻倍
    """
    def decorator(func):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            last_exc: Exception | None = None
            for attempt in range(1, max_attempts + 1):
                try:
                    return await func(*args, **kwargs)
                except _RETRYABLE as e:
                    last_exc = e
                    if attempt < max_attempts:
                        delay = base_delay * (2 ** (attempt - 1))
                        log.warning(
                            "重试 %s (尝试 %d/%d): %s",
                            func.__name__, attempt, max_attempts, e,
                        )
                        await asyncio.sleep(delay)
            if last_exc is None:
                # max_attempts<=0 误配（现网调用方均 ≥2，纯防 None 裸抛：
                # raise None 会是莫名的 TypeError，盖住配置错误本身）
                raise RuntimeError(
                    f"with_retry({func.__name__}): 一次都没尝试（max_attempts<=0?）"
                )
            raise last_exc
        return wrapper
    return decorator
