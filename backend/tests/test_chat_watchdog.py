"""chat 断连看门狗回归（体检 R1'/R2'）。

修复前：非流式 /chat 客户端断开后 ainvoke 照跑完整个工具循环白烧
token；SSE 的 is_disconnected 检测是事件驱动的，卡在单次 LLM 调用内
发现不了断连。
"""
import asyncio
import time

import pytest

from api.routes.chat import (
    _await_with_disconnect_watch,
    _SSE_IDLE_TIMEOUT,
)


class _FakeRequest:
    """is_disconnected 在指定检查次数后开始返回 True。"""

    def __init__(self, disconnect_after: int = 999):
        self._calls = 0
        self._disconnect_after = disconnect_after

    async def is_disconnected(self) -> bool:
        self._calls += 1
        return self._calls > self._disconnect_after


async def test_watch_returns_result_when_connected():
    """客户端在线：原样返回任务结果。"""

    async def work():
        await asyncio.sleep(0.05)
        return {"messages": ["hi"]}

    result = await _await_with_disconnect_watch(
        work(), _FakeRequest(disconnect_after=999), "s-ok"
    )
    assert result == {"messages": ["hi"]}


async def test_watch_cancels_on_disconnect():
    """客户端断开：取消内层任务、返回 None，且确实没有跑完。"""
    finished = []

    async def slow():
        await asyncio.sleep(30)
        finished.append(True)
        return "不该到达"

    start = time.monotonic()
    result = await _await_with_disconnect_watch(
        slow(), _FakeRequest(disconnect_after=0), "s-off"
    )
    elapsed = time.monotonic() - start

    assert result is None
    assert elapsed < 5          # 秒级止损，不是跑完 30s
    assert not finished         # 任务体被取消，没跑到底


async def test_watch_propagates_task_exception():
    """任务自身抛异常：原样上抛（走既有 500 兜底），不被看门狗吞掉。"""

    async def boom():
        raise ValueError("LLM 炸了")

    with pytest.raises(ValueError, match="LLM 炸了"):
        await _await_with_disconnect_watch(
            boom(), _FakeRequest(disconnect_after=999), "s-err"
        )


def test_sse_idle_timeout_exceeds_worst_single_hop():
    """SSE 空闲超时须大于最坏单跳静默时长（LLM timeout 120s ×
    (1 + 2 次重试) = 240s）——低于它会在健康但慢的重试链中误杀
    正常对话。钉死推导下限，改小会红。"""
    assert _SSE_IDLE_TIMEOUT > 240
