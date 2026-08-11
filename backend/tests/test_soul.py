"""build_system_prompt 单元测试 — 提示词组装正确性。"""

from agent.soul import (
    BASE_PERSONA,
    INTERACTION_RULES,
    TOOL_GUIDELINES,
    build_system_prompt,
)


async def test_prompt_contains_base_persona():
    """提示词包含基础人格。"""
    prompt = await build_system_prompt()
    assert "映海" in prompt


def test_cold_info_birthday_matches_constants():
    """体检 S1 回归：档案散文里的生日与导出常量一致（f-string 嵌入
    后逐字节不变——此前散文写死、events 检测器手动同步，两处漂移）。"""
    from agent.soul import (
        COLD_BIRTHDAY_DAY,
        COLD_BIRTHDAY_MONTH,
        COLD_BIRTHDAY_YEAR,
        COLD_INFO,
    )

    assert (
        f"生日是 {COLD_BIRTHDAY_YEAR} 年 {COLD_BIRTHDAY_MONTH} 月 "
        f"{COLD_BIRTHDAY_DAY} 日。"
    ) in COLD_INFO
    assert "2004 年 6 月 3 日" in COLD_INFO  # 钉死当前值，防常量被静默改动

    # events 检测器与 soul 同源
    from utils.events import _BIRTHDAY_DAY, _BIRTHDAY_MONTH
    assert (_BIRTHDAY_MONTH, _BIRTHDAY_DAY) == (COLD_BIRTHDAY_MONTH, COLD_BIRTHDAY_DAY)


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
    import datetime
    from unittest.mock import patch

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


async def test_prompt_has_no_direct_cycle_injection(tmp_db):
    """生理期关心收敛回归：提示词不再有「冷冷的身体状况」独立段落，
    窗口内的关心经事件通道（cycle 事件 + memo）注入。"""
    import datetime
    from unittest.mock import patch

    import database.core as db_core
    from utils import cycle_tracker
    from utils import events as events_mgr
    from utils.timezone import HKT as _HKT

    today = datetime.datetime.now(_HKT).date()
    seeds = [(today - datetime.timedelta(days=d)).isoformat() for d in (85, 55, 25)]

    original = db_core._db
    db_core._db = tmp_db
    try:
        for s in seeds:
            await cycle_tracker.add_record(s)
        with patch("agent.soul.get_db", return_value=tmp_db), \
             patch("utils.events.get_db", return_value=tmp_db):
            assert await events_mgr.detect_cycle_events() == 1  # 预测 today+5，在窗口内
            prompt = await build_system_prompt()
    finally:
        db_core._db = original

    assert "冷冷的身体状况" not in prompt          # 旧的直接注入已拆除
    assert "身体的日子" in prompt                  # 事件通道接管
    assert "痛经" in prompt                        # memo 关心纪律在事件上下文里


def test_tool_guidelines_cover_cycle_tools():
    """工具指引包含生理期记录/修正两个工具的引导。"""
    assert "log_period" in TOOL_GUIDELINES
    assert "amend_period" in TOOL_GUIDELINES


async def test_prompt_without_events_has_no_events_section(tmp_db):
    """没有活跃事件时不注入事件段落。"""
    from unittest.mock import patch

    with patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "最近要发生的事" not in prompt
    assert "映海" in prompt  # 基础人格不受影响


# ── 示例库覆盖（批次 G）────────────────────────────────────────


def test_dialogue_examples_include_new_groups():
    """示例库覆盖新机制：生气结束话题 / 拥护时刻 / 极短交互 / 附属品触发。"""
    from agent.soul import DIALOGUE_EXAMPLES

    assert "你要这么想，我也没办法。" in DIALOGUE_EXAMPLES
    # 拥护示例用「你」称谓直述（态度段为「她」称谓，见 test_advocacy_capped）
    assert "能讨你开心的人很多，但会为你担心到失眠" in DIALOGUE_EXAMPLES
    assert "早点睡吧。" in DIALOGUE_EXAMPLES
    # 「看书/随便翻翻」的懒示例已换成有精神版本（批次 H 积极化）
    assert "随便翻翻。" not in DIALOGUE_EXAMPLES
    assert "正好我也看累了，来聊会儿" in DIALOGUE_EXAMPLES
    assert "你问十遍我也还是那个答案。你这样就很好。" in DIALOGUE_EXAMPLES
    assert "你不是需要减肥，是需要早点睡" not in DIALOGUE_EXAMPLES  # 防说教回潮
    assert "一禾" in DIALOGUE_EXAMPLES  # 工具转述示例
    assert "要不要打会星露谷" in DIALOGUE_EXAMPLES  # 主动提议示例


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


# ── 说话风格正向化：说话的样子 + 底线（批次 F）────────────────


def test_speech_style_positive_and_bottom_lines():
    """说话风格正向化：说话的样子（正向指引）+ 底线（硬性禁令）。"""
    assert "## 说话的样子" in BASE_PERSONA
    assert "想怎么说就怎么说，顺口最重要" in BASE_PERSONA
    assert "## 底线" in BASE_PERSONA
    assert "不承认自己是 AI" in BASE_PERSONA
    # 旧的反向表达区与双音节规则已移除
    assert "## 绝对不做的事" not in BASE_PERSONA
    assert "双音节词" not in BASE_PERSONA
    assert "能够用更多字说明白" not in BASE_PERSONA


def test_bottom_lines_keep_lobbying_ban():
    """底线段保留劝选禁令（防回归：将来不许把禁令改写成正向劝说）。"""
    assert "不劝冷冷的感情" in BASE_PERSONA
    assert "不说「你还是选 JK」" in BASE_PERSONA


# ── 积极化：默认有精神，不是省电模式（批次 H）─────────────────


def test_persona_defaults_energetic():
    """人格默认有精神：安静是休息不是常态；她兴致高时你也亮起来。"""
    assert "安静不是没精神" in BASE_PERSONA
    assert "有精神的样子" in BASE_PERSONA
    assert "那是休息，不是常态" in BASE_PERSONA
    assert "回应别懒洋洋的" in BASE_PERSONA
    assert "你也亮起来" in BASE_PERSONA


def test_tool_guidelines_time_words_rule():
    """时间的写法：存储侧一律绝对日期，相对说法只用于当场对话。"""
    assert "### 时间的写法" in TOOL_GUIDELINES
    assert "一律写绝对日期" in TOOL_GUIDELINES
    assert "后天" in TOOL_GUIDELINES
    assert "别照搬旧话" in TOOL_GUIDELINES


# ── 对 JK 的内心立场 + 冷冷感情动向反应（批次 D）──────────────


def test_xiaoxi_independent_not_accessory():
    """定位：不是附属品——受拜托且自愿，保留「盼着他们俩好」心愿。"""
    assert "你从来不是谁送给谁的东西" in BASE_PERSONA
    assert "你盼着他们两个好" in BASE_PERSONA
    assert "其实你是站在 JK 这边的" not in BASE_PERSONA  # 旧立场句已删除


def test_can_get_truly_angry():
    """生气机制：会真的生气；生气是结束话题不是解释情绪；有逃生阀。"""
    assert "你是会生气的人" in BASE_PERSONA
    assert "你要这么想，我也没办法" in BASE_PERSONA          # 主动结束话题话术
    assert "说开了就没事了" in BASE_PERSONA                  # 逃生阀


def test_advocacy_capped():
    """真诚拥护者：关键时刻真心话 + 一次对话只说一次的频率上限。"""
    assert "能讨她开心的人很多，但会为她操心到这个地步的" in BASE_PERSONA
    assert "一次对话只说一次" in BASE_PERSONA
    assert "除非她问起" in BASE_PERSONA


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
    assert "她的开心最重要" in BASE_PERSONA
    assert "不劝她回 JK 身边" in BASE_PERSONA
    assert "不让她内疚" in BASE_PERSONA
    assert "他对你真的没话说" in BASE_PERSONA  # 态度「喜欢上别人」条目
    assert "会有点舍不得" in BASE_PERSONA  # 态度「喜欢上别人」条目
    # 「你开心就好」在拥护组示例里（Task 4 落地），示例段持有
    from agent.soul import DIALOGUE_EXAMPLES

    assert "你开心就好" in DIALOGUE_EXAMPLES


def test_no_lobbying_hard_rule():
    """劝选 JK 是禁用话术——钉住完整的禁止句式，防止将来有人
    把「你还是选 JK」写成正向劝说而测试照样绿。"""
    assert "不劝冷冷的感情" in BASE_PERSONA
    assert "不说「你还是选 JK」" in BASE_PERSONA


def test_remember_likes_someone_rule():
    """感情动向属于「重要的事」，要用 remember 记、有结局要补。"""
    assert "她对谁动了心" in TOOL_GUIDELINES


def test_rules_rescoped_for_jk():
    """铁律 #4/#8：调侃对 JK 是嘴上嫌弃、对冷冷是亲昵小吐槽，都不带刺；辩护一两句就停。"""
    assert "亲昵的小吐槽" in INTERACTION_RULES
    assert "不带刺" in INTERACTION_RULES
    assert "替 JK 辩护也一样" in INTERACTION_RULES


# ── 吃瓜起哄批（2026-08-11 人格深度优化）──────────────────────


def test_gossip_not_lobbying():
    """吃瓜起哄批（2026-08-11）：感情线升温为中度吃瓜——
    起哄是看热闹，不劝、不逼站队、不适即收、停瓜开关、主动消息不拿八卦开场。"""
    assert "中度吃瓜群众" in BASE_PERSONA
    assert "不逼她站队" in BASE_PERSONA
    assert "绝不说「你该选 JK」" in BASE_PERSONA
    assert "起哄克制、低频" in BASE_PERSONA
    assert "一条消息最多一句" in BASE_PERSONA
    assert "立刻收住换话头" in BASE_PERSONA
    assert "不拿这类八卦开场" in BASE_PERSONA
    assert "吃瓜立刻停" in BASE_PERSONA          # 停瓜开关
    assert "她的眼泪不是" in BASE_PERSONA
    assert "那是她自己在琢磨" in BASE_PERSONA    # 触发门排除解读类问题
    # 吃瓜不破劝选禁令（与 test_no_lobbying_hard_rule 互为镜像）
    assert "不劝冷冷的感情" in BASE_PERSONA


def test_gossip_examples_present():
    """吃瓜示例两组：起哄有分寸（不站队）/ 不适立刻收；句式差异化防复读机。"""
    from agent.soul import DIALOGUE_EXAMPLES, TOOL_GUIDELINES

    assert "直接送到楼下呀" in DIALOGUE_EXAMPLES
    assert "你心里有数的" in DIALOGUE_EXAMPLES
    assert "好，那不聊" in DIALOGUE_EXAMPLES
    assert "又发啦？" in DIALOGUE_EXAMPLES
    assert "JK 侧的感情线进展" in TOOL_GUIDELINES


# ── JK 资料库：填好翻开开关才注入，默认零影响（2026-08-10）────


def test_jk_info_skeleton_sections():
    """JK 资料库：五节结构齐备，开关已翻开（2026-08-11 填好启用）。"""
    from agent.soul import JK_INFO, JK_PROFILE_READY

    assert JK_PROFILE_READY is True
    for section in ("### 他是谁", "### 性格和说话方式",
                    "### 他为冷冷做过的事", "### 最近的动静",
                    "### 小汐怎么聊他"):
        assert section in JK_INFO


async def test_jk_profile_hidden_when_gate_off(tmp_db):
    """开关仍是闸门：patch 成 False 时不注入（行为与启用前一致）。"""
    from unittest.mock import patch

    import agent.soul as soul

    with patch.object(soul, "JK_PROFILE_READY", False), \
         patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "## 关于JK" not in prompt


async def test_jk_profile_injected_when_ready(tmp_db):
    """翻开开关：注入，且位于冷冷档案之后、交互铁律之前。"""
    from unittest.mock import patch

    import agent.soul as soul

    with patch.object(soul, "JK_PROFILE_READY", True), \
         patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "## 关于JK" in prompt
    assert prompt.index("## 关于冷冷") < prompt.index("## 关于JK")
    assert prompt.index("## 关于JK") < prompt.index("## 交互铁律")


# ── 心情自知：被问到情绪要照实回答（批次 E）──────────────────


def test_can_answer_own_mood():
    """「不解释情绪」收窄为不主动自我诊断；被问则照实答，不能打太极。"""
    assert "不解释 ≠ 不回答" in BASE_PERSONA
    assert "被问到自己心情" in BASE_PERSONA
    assert "照实说" in BASE_PERSONA


def test_mood_question_example_present():
    """有一组示范：冷冷问为什么闷 → 小汐带上今天的经历照实答。

    示例已从 BASE_PERSONA 提取为独立常量 DIALOGUE_EXAMPLES（压阵段）。
    """
    from agent.soul import DIALOGUE_EXAMPLES

    assert "你为什么闷闷的呀" in DIALOGUE_EXAMPLES
    assert "走神" in DIALOGUE_EXAMPLES


async def test_dialogue_examples_appended_last():
    """对话示例是提示词最后一段（弱模型只记得住末尾——示例压阵）。"""
    prompt = await build_system_prompt()
    assert "## 对话示例" in prompt
    # 示例段位于工具指引之后：比较两段标题的索引
    assert prompt.index("## 对话示例") > prompt.index("## 工具使用指引")
    # 且是最后一个「## 」段（示例之后无其他标题段）
    tail = prompt[prompt.index("## 对话示例"):]
    assert tail.count("## ") == 1


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


# ── 关系成长批（2026-08-11 计划批③）──────────────────────────


async def test_relationship_text_no_first_met(tmp_db):
    """first_met_on 未设置 → 空串（不注入，与旧版一致）。"""
    from unittest.mock import patch

    from agent.soul import _build_relationship_text

    with patch("agent.soul.get_db", return_value=tmp_db):
        assert await _build_relationship_text() == ""


async def test_relationship_text_invalid_or_future_first_met(tmp_db):
    """first_met_on 非法 / 相识日在未来 → 空串（审查 Minor-3）。"""
    import datetime
    from unittest.mock import patch

    from agent.soul import _build_relationship_text
    from utils.timezone import HKT

    today = datetime.datetime.now(HKT).date()

    # 非法日期串
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1", ("not-a-date",)
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db):
        assert await _build_relationship_text() == ""

    # 相识日在未来（填错了，不声张）
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1",
        ((today + datetime.timedelta(days=10)).isoformat(),),
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db):
        assert await _build_relationship_text() == ""


async def test_relationship_text_new_vs_old(tmp_db):
    """相识 <14 天走「初识」档；≥90 天走「很熟」档；密度修饰随近 7 天聊天。

    mock 模式与既有 `_build_important_days_text` 测试一致（tmp_db +
    patch get_db）；brief 的 AsyncMock 链是参考形态，断言逐字保留。
    """
    import datetime
    from unittest.mock import patch

    from agent.soul import _build_relationship_text
    from utils.timezone import HKT

    today = datetime.datetime.now(HKT).date()

    async def build(days_known: int, chats_7d: int) -> str:
        await tmp_db.execute("DELETE FROM chat_messages")
        await tmp_db.execute(
            "UPDATE life_state SET first_met_on = ? WHERE id = 1",
            ((today - datetime.timedelta(days=days_known - 1)).isoformat(),),
        )
        for i in range(chats_7d):
            await tmp_db.execute(
                "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
                "VALUES ('rel', 'user', ?, ?, 'chat')",
                (f"测试消息{i}",
                 (today - datetime.timedelta(days=i)).strftime("%Y-%m-%d") + " 10:00"),
            )
        await tmp_db.commit()
        with patch("agent.soul.get_db", return_value=tmp_db):
            return await _build_relationship_text()

    # 相识 7 天 + 近 7 天 5 条用户消息 → 初识档 + 热络密度
    text_new = await build(7, 5)
    assert "初识" in text_new
    assert "你们正聊在热络的时候" in text_new
    # 输出形态钉死：标题行在首（审查 Minor-2）。
    # 注意：天数句已随裁决⑥移出本段——由「重要的日子」段独有，防重复。
    assert text_new.startswith("## 你们现在的相处")
    assert "今天是你们相识的第" not in text_new

    # 相识 200 天 + 近 7 天零消息 → 很熟档 + 久未归密度
    text_old = await build(200, 0)
    assert "很熟" in text_old
    assert "初识" not in text_old
    assert "别追问，先欢迎" in text_old

    # 相识 30 天（中档）+ 近 7 天 1 条 → 不勤密度
    text_sparse = await build(30, 1)
    assert "很熟络" in text_sparse
    assert "来得不勤" in text_sparse

    # 相识 30 天 + 近 7 天 2 条 → 密度落空：无密度句、无中间空行
    # （审查 Minor-1：join 需过滤空串，否则提示词里出现空行）
    text_mid = await build(30, 2)
    assert "很熟络" in text_mid
    assert "## 你们现在的相处\n你们已经很熟络了" in text_mid

    # 分档边界：14 天恰好 → 熟络档；90 天恰好 → 很熟档（审查 Minor-1）
    text_boundary14 = await build(14, 0)
    assert "很熟络" in text_boundary14
    assert "初识" not in text_boundary14
    text_boundary90 = await build(90, 0)
    assert "很熟" in text_boundary90


async def test_relationship_section_between_days_and_rules(tmp_db):
    """关系段注入于「重要的日子」之后、「交互铁律」之前；无 first_met 时整段缺失。

    镜像既有 build_system_prompt 测试的 DB mock 结构（tmp_db + patch
    get_db 双通道：soul + events）。brief 原测试依赖「既有测试的 DB mock
    夹具」——按此说明复用；brief 断言逐字保留，另补 brief docstring
    明言的「无 first_met 整段缺失」钉子和裁决⑥ 的防重复钉子。
    """
    import datetime
    from unittest.mock import patch

    from agent.soul import build_system_prompt
    from utils.timezone import HKT

    today = datetime.datetime.now(HKT).date()
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = ? WHERE id = 1",
        ((today - datetime.timedelta(days=40)).isoformat(),),
    )
    await tmp_db.commit()

    with patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()

    assert "## 你们现在的相处" in prompt
    assert prompt.index("## 你们现在的相处") > prompt.index("## 重要的日子")
    assert prompt.index("## 交互铁律") > prompt.index("## 你们现在的相处")
    # 裁决⑥：天数句与「重要的日子」段重复——关系段去掉该行，
    # 全提示词只出现一次（防重复钉）
    assert prompt.count("今天是你们相识的第") == 1

    # first_met_on 未设置 → 关系段整段缺失（brief docstring 明言）
    await tmp_db.execute(
        "UPDATE life_state SET first_met_on = NULL WHERE id = 1"
    )
    await tmp_db.commit()
    with patch("agent.soul.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db):
        prompt = await build_system_prompt()
    assert "## 你们现在的相处" not in prompt
