"""动态画像蒸馏单测 — 「小汐眼里的冷冷」画像块（批次②）。

范式：Letta human 块（模型自维护 + 字符上限逼蒸馏）+ Graphiti 失效
不删除（旧画像走 superseded 链，v15）。固定 id 的 core 行，首次
懒创建，此后 update_content 覆盖——与 mood_current（v18）同一模式。
"""
from unittest.mock import AsyncMock, patch

import pytest

from memory.profile import (
    PROFILE_CATEGORY,
    PROFILE_MEMORY_ID,
    distill_cold_profile,
)


class _FakeLLM:
    """假 LLM：返回固定画像文本（单测零真实 API 调用）。"""

    def __init__(self, content: str):
        self._content = content

    async def ainvoke(self, messages):
        class _Resp:
            content = self._content
        return _Resp()


@pytest.fixture
def profile_db():
    """内存库：建 memories + chat_messages 表的最小夹具。"""
    import aiosqlite

    db = None
    # 由调用方注入 conn 的方式测试（distill_cold_profile(conn=...)）
    return None


async def test_distill_creates_core_row(_profile_conn):
    """首次蒸馏：懒创建 core 行，类别与 id 固定。"""
    result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is True
    row = await _profile_conn.execute(
        "SELECT content, category, status FROM memories WHERE id = ?",
        (PROFILE_MEMORY_ID,),
    )
    got = await row.fetchone()
    assert got is not None
    assert got[1] == PROFILE_CATEGORY
    assert got[2] == "core"


async def test_distill_updates_via_superseded_chain(_profile_conn):
    """二次蒸馏：update_content 语义——旧画像落 superseded 行可回放。"""
    with patch("memory.profile._create_llm",
               return_value=_FakeLLM("第一版画像")):
        await distill_cold_profile(conn=_profile_conn)
    with patch("memory.profile._create_llm",
               return_value=_FakeLLM("第二版画像：冷冷最近在忙论文")):
        await distill_cold_profile(conn=_profile_conn)

    cur = await _profile_conn.execute(
        "SELECT content, status, superseded_by FROM memories "
        "WHERE id = ?", (PROFILE_MEMORY_ID,))
    row = await cur.fetchone()
    assert row[0] == "第二版画像：冷冷最近在忙论文"

    cur = await _profile_conn.execute(
        "SELECT content, status, superseded_by FROM memories "
        "WHERE status = 'superseded' AND superseded_by = ?",
        (PROFILE_MEMORY_ID,))
    old = await cur.fetchone()
    assert old is not None
    assert old[0] == "第一版画像"


async def test_distill_enforces_char_cap(_profile_conn):
    """LLM 输出超长被截到 PROFILE_MAX_CHARS（蒸馏压力兜底）。"""
    long_text = "冷" * 2000
    with patch("memory.profile._create_llm", return_value=_FakeLLM(long_text)):
        await distill_cold_profile(conn=_profile_conn)
    cur = await _profile_conn.execute(
        "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,))
    row = await cur.fetchone()
    assert len(row[0]) <= 500


async def test_distill_skips_on_llm_failure(_profile_conn):
    """LLM 失败：跳过本轮，画像行保持原样（绝不写入半截）。"""
    class _Boom:
        async def ainvoke(self, messages):
            raise RuntimeError("llm down")
    await distill_cold_profile(conn=_profile_conn)  # 先建行
    with patch("memory.profile._create_llm", return_value=_Boom()):
        result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is True
    assert result["updated"] is False


async def test_distill_no_chat_no_run(_profile_conn):
    """近 24h 无对话：跳过（ran=False），不调 LLM 不落节流。"""
    # 夹具已 seed 对话（计划缺陷裁决 2026-08-11），本用例自建无对话场景
    await _profile_conn.execute("DELETE FROM chat_messages")
    await _profile_conn.commit()
    with patch("memory.profile._create_llm") as mock_llm:
        result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is False
    mock_llm.assert_not_called()
