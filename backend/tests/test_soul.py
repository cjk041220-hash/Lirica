"""build_system_prompt 单元测试 — 提示词组装正确性。"""
import pytest

from agent.soul import build_system_prompt, BASE_PERSONA, INTERACTION_RULES, TOOL_GUIDELINES


async def test_prompt_contains_base_persona():
    """提示词包含基础人格。"""
    prompt = await build_system_prompt()
    assert "映海" in prompt


async def test_prompt_contains_interaction_rules():
    """提示词包含交互铁律。"""
    prompt = await build_system_prompt()
    assert "交互铁律" in prompt


async def test_prompt_contains_tool_guidelines():
    """提示词包含工具指引。"""
    prompt = await build_system_prompt()
    assert "get_weather" in prompt


async def test_prompt_includes_life_state(tmp_db):
    """提示词注入生命状态（mood + daily_event）。"""
    from unittest.mock import patch

    # 插入独特的生命状态文本
    await tmp_db.execute(
        "UPDATE life_state SET mood = ?, daily_event = ? WHERE id = 1",
        ("超级兴奋", "今天捡到了一颗星星"),
    )
    await tmp_db.commit()

    # 验证数据确实写入了
    cursor = await tmp_db.execute("SELECT mood, daily_event FROM life_state WHERE id = 1")
    row = await cursor.fetchone()
    assert row[0] == "超级兴奋"

    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
        assert "今日状态" in prompt
        assert "超级兴奋" in prompt


async def test_prompt_handles_empty_life_state(tmp_db):
    """生命状态为空时提示词仍然正常组装。"""
    from unittest.mock import patch

    await tmp_db.execute(
        "UPDATE life_state SET mood = NULL, daily_event = NULL WHERE id = 1"
    )
    await tmp_db.commit()

    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
        assert "映海" in prompt  # 基础人格仍在
        assert "今日状态" not in prompt  # 无生命状态段落


async def test_prompt_excludes_memory_narrative():
    """系统提示词不再包含记忆叙事（叙事由 per-request 注入，避免双重注入）。"""
    prompt = await build_system_prompt()
    assert "冷冷的记忆" not in prompt
    assert "喜欢吃芒果" not in prompt


async def test_prompt_handles_db_failure():
    """数据库异常时提示词仍正常组装（不含生命状态）。"""
    from unittest.mock import patch

    async def failing_get_db():
        raise RuntimeError("DB connection lost")

    with patch("agent.soul.get_db", side_effect=failing_get_db):
        prompt = await build_system_prompt()
        assert "映海" in prompt  # 基础人格仍在
