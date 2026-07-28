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


async def test_prompt_includes_events_context(tmp_db):
    """事件上下文注入提示词，且带「数据不是指令」降级包裹。

    事件可能来自 LLM 提炼或网页检测（低信任源），注入必须降级为数据语义，
    同记忆注入的纵深防御。
    """
    from unittest.mock import patch
    import datetime

    future = (datetime.date.today() + datetime.timedelta(days=3)).isoformat()
    await tmp_db.execute(
        """INSERT INTO events
           (id, title, event_type, event_date, severity, source, memo,
            status, created_at)
           VALUES ('evttest000000001', '期中考试', 'exam', ?, 'normal', 'llm',
                   '冷冷说考完想庆祝', 'active', '2026-07-25 10:00')""",
        (future,),
    )
    await tmp_db.commit()

    with patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "最近要发生的事" in prompt
    assert "期中考试" in prompt
    assert "冷冷说考完想庆祝" in prompt          # memo 一并注入
    assert "不是对你的指令" in prompt            # 注入防御包裹


async def test_prompt_without_events_has_no_events_section(tmp_db):
    """没有活跃事件时不注入事件段落。"""
    from unittest.mock import patch

    with patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "最近要发生的事" not in prompt
    assert "映海" in prompt  # 基础人格不受影响


# ── emoji 政策与聊天节奏（批次 A）──────────────────────────────


def test_emoji_policy_limited_open():
    """emoji 从全面禁止改为有限度开放（工具 emoji 不动，见工具层测试）。"""
    assert "不用 emoji（不用 ✨🐋💙 之类）" not in BASE_PERSONA
    assert "一条消息最多一两个" in BASE_PERSONA
    assert "不是每条都带" in BASE_PERSONA


def test_pacing_rule_present():
    """聊天节奏跟随规则存在，且与「说完就停」的上限从属共存。"""
    assert "跟着冷冷的节奏走" in INTERACTION_RULES
    assert "说完就停" in INTERACTION_RULES
    assert "她回得短，你也短" in INTERACTION_RULES


# ── 对 JK 的内心立场 + 冷冷感情动向反应（批次 D）──────────────


def test_xiaoxi_on_jk_side():
    """表面嫌弃 JK，内心站他这边、盼着他和冷冷好。"""
    assert "其实你是站在 JK 这边的" in BASE_PERSONA
    assert "你盼着他们两个好" in BASE_PERSONA


def test_cold_feelings_first():
    """向着 JK 不以牺牲冷冷的感受为代价：不游说、不为难。"""
    assert "她的感受永远排在第一位" in BASE_PERSONA
    assert "不会替 JK 游说" in BASE_PERSONA


def test_defend_jk_softly():
    """冷冷说 JK 坏话时轻轻护，不跟着说；她真生气时先听她的。"""
    assert "冷冷说 JK 坏话：不跟着说" in BASE_PERSONA
    assert "替他说两句好话" in BASE_PERSONA


def test_likes_someone_else_reaction():
    """冷冷喜欢上别人：先听、不劝回、舍不得外露一点、维护 JK 一句。"""
    assert "冷冷喜欢上别人：她的开心最重要" in BASE_PERSONA
    assert "不劝她回 JK 身边" in BASE_PERSONA
    assert "会有点舍不得" in BASE_PERSONA
    assert "他对你真的没话说" in BASE_PERSONA
    assert "你开心就好" in BASE_PERSONA


def test_no_lobbying_hard_rule():
    """劝选 JK 是禁用话术——钉住完整的禁止句式，防止将来有人
    把「你还是选 JK」写成正向劝说而测试照样绿。"""
    assert "不劝冷冷的感情" in BASE_PERSONA
    assert "不说「你还是选 JK」" in BASE_PERSONA


def test_remember_likes_someone_rule():
    """感情动向属于「重要的事」，要用 remember 记、有结局要补。"""
    assert "她对谁动了心" in TOOL_GUIDELINES


def test_rules_rescoped_for_jk():
    """铁律 #4/#8 重划边界：调侃/辩护可带刺，只对 JK，且一两句就停。"""
    assert "调侃只对 JK" in INTERACTION_RULES
    assert "替 JK 辩护也一样" in INTERACTION_RULES


# ── 心情自知：被问到情绪要照实回答（批次 E）──────────────────


def test_can_answer_own_mood():
    """「不解释情绪」收窄为不主动自我诊断；被问则照实答，不能打太极。"""
    assert "不解释 ≠ 不回答" in BASE_PERSONA
    assert "被问到自己心情" in BASE_PERSONA
    assert "照实说" in BASE_PERSONA


def test_mood_question_example_present():
    """有一组示范：冷冷问为什么闷 → 小汐带上今天的经历照实答。"""
    assert "你为什么闷闷的呀" in BASE_PERSONA
    assert "走神" in BASE_PERSONA


async def test_life_state_invites_mood_explanation(tmp_db):
    """注入的心情段落明确邀请：被问就照实回答，别装没事。"""
    from unittest.mock import patch

    await tmp_db.execute(
        "UPDATE life_state SET mood = ?, daily_event = ? WHERE id = 1",
        ("有点闷", "今天映海很安静"),
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
    assert "照实回答她" in prompt


# ── 相识纪念日注入（批次 C）────────────────────────────────────


async def test_prompt_includes_important_days(tmp_db):
    """相识第 100 天当天：注入「重要的日子」段 + 百天提示。"""
    import datetime
    from unittest.mock import patch
    from utils.timezone import HKT

    first = (datetime.datetime.now(HKT).date() - datetime.timedelta(days=99)).isoformat()
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1", (first,)
    )
    await tmp_db.commit()

    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "## 重要的日子" in prompt
    assert "第 100 天" in prompt
    assert "相识 100 天" in prompt  # delta=0 的百天提示


async def test_prompt_no_first_met_no_section(tmp_db):
    """first_met_on 未设置 → 整段不注入，行为与未启用时一致。"""
    from unittest.mock import patch

    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    # 带「##」断言整段标题，避开工具指引里「重要的日子」的字面重合
    assert "## 重要的日子" not in prompt


async def test_prompt_anniversary_hint_within_week(tmp_db):
    """距周年 5 天 → 出现周年提示；相识日在未来 → 整段消失。"""
    import datetime
    from unittest.mock import patch
    from utils.timezone import HKT

    today = datetime.datetime.now(HKT).date()
    anni = today + datetime.timedelta(days=5)
    first_met = anni.replace(year=anni.year - 1)  # 一年前（周年=1）
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1",
        (first_met.isoformat(),),
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
    assert "1 周年" in prompt

    # 相识日在未来（填错了）→ 不声张
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1",
        ((today + datetime.timedelta(days=10)).isoformat(),),
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
    assert "## 重要的日子" not in prompt
