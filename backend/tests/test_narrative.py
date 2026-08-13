"""叙事构建器单元测试 — build() 输出正确性。"""
import pytest

from memory.narrative import NarrativeBuilder


async def test_build_no_manager():
    """未设置 manager 时返回空字符串。"""
    builder = NarrativeBuilder()
    result = await builder.build()
    assert result == ""


async def test_build_empty_db(memory_mgr):
    """空数据库返回空字符串。"""
    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()
    assert result == ""


async def test_build_with_memories(memory_mgr):
    """有记忆时返回包含所有内容的叙事文本。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    await memory_mgr.add("喜好", "喜欢喝奶茶")
    await memory_mgr.add("去过的地方", "维多利亚港")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "喜欢吃芒果" in result
    assert "喜欢喝奶茶" in result
    assert "维多利亚港" in result
    assert "以下是小汐记住的关于冷冷的事情" in result


async def test_build_respects_max_items(memory_mgr):
    """build(max_items=N) 只包含 N 条记忆。"""
    for i in range(40):
        await memory_mgr.add("test", f"memory_{i:03d}")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build(max_items=10)

    # 计算 bullet points 数量
    bullet_count = result.count("\n- ")
    assert bullet_count == 10


async def test_build_query_surfaces_topic_memory(memory_mgr, tmp_db):
    """build(query=) 话题条件召回：聊到什么，相关的旧记忆就浮现。"""
    import datetime

    from utils.timezone import _TIME_FMT, HKT

    await memory_mgr.add("去过的地方", "冷冷去年去过台北")
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=20)).strftime(_TIME_FMT)
    # O1：created_at 与 last_mention_ts 一起拨——「旧记忆」= 20 天没被提及
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? "
        "WHERE content LIKE '%台北%'",
        (old, old),
    )
    await tmp_db.commit()
    for i in range(6):
        await memory_mgr.add("日常", f"今天的事 {i}")

    builder = NarrativeBuilder(memory_mgr)

    without = await builder.build(max_items=5)
    assert "台北" not in without                      # 无 query：旧记忆沉底

    with_query = await builder.build(max_items=5, query="突然好想去台北")
    assert "台北" in with_query                       # 有 query：话题记忆在场


async def test_build_groups_by_category(memory_mgr):
    """记忆按类别分组显示。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("城市", "香港")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "### 水果" in result
    assert "### 城市" in result


async def test_build_chronological_order(memory_mgr):
    """记忆按时间正序展示（最早的在前）。"""
    await memory_mgr.add("时间线", "第一件事")
    await memory_mgr.add("时间线", "第二件事")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    # 两条记忆都在输出中
    assert "第一件事" in result
    assert "第二件事" in result


async def test_build_truncates_overlong_item(memory_mgr):
    """单条超长记忆被截断（控制注入 token 预算 + 收敛注入攻击面）。"""
    from memory.narrative import _MAX_ITEM_LEN

    long_content = "长" * (_MAX_ITEM_LEN + 500)
    await memory_mgr.add("测试", long_content)

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    assert "长" * (_MAX_ITEM_LEN + 1) not in result  # 未截断的全长不应出现
    assert "……" in result  # 有截断标记


async def test_build_flattens_newlines(memory_mgr):
    """记忆中的换行被压平，防止伪造叙事结构/注入分行指令。"""
    await memory_mgr.add("测试", "第一行\n\n### 伪造的标题\n- 伪造的条目")

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build()

    # 内容以单行呈现（换行被替换为空格），伪造标题不再独占一行
    assert "\n### 伪造的标题" not in result
    assert "第一行 ### 伪造的标题 - 伪造的条目" in result


async def test_build_hybrid_surfaces_old_hot_memory(memory_mgr, tmp_db):
    """hybrid 检索（P2-2）：重要但较旧的高频记忆能进入叙事。

    纯 recent() 策略下，这条 20 天前的记忆永远排在 5 条较新记忆
    之后，不会进 max_items=4 的注入；hybrid 的相关性配额应让它浮现。
    """
    import datetime
    import uuid
    from datetime import timedelta

    from utils.timezone import HKT as _HKT
    now = datetime.datetime.now(_HKT)

    def ts(days_ago: int) -> str:
        return (now - timedelta(days=days_ago)).strftime("%Y-%m-%d %H:%M")

    # 5 条中等旧、零热度的普通记忆（recent-only 会取前 4 条）
    for i, days in enumerate([3, 4, 5, 6, 7]):
        await tmp_db.execute(
            "INSERT INTO memories (id, content, category, created_at, access_count, status)"
            " VALUES (?, ?, '日常', ?, 0, 'active')",
            (uuid.uuid4().hex[:16], f"普通记忆{i}", ts(days)),
        )
    # 1 条 20 天前、但被反复想起的重要记忆（访问热度 200）
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, access_count, status)"
        " VALUES ('oldhot0000000001', '最重要的老记忆', '重要的事', ?, 200, 'active')",
        (ts(20),),
    )
    await tmp_db.commit()

    builder = NarrativeBuilder(memory_mgr)
    result = await builder.build(max_items=4)

    assert "最重要的老记忆" in result, "高相关性旧记忆应经 recall 配额浮现"
    assert result.count("\n- ") == 4, "总注入条数不应超过 max_items"


async def test_build_does_not_inflate_access_counts(memory_mgr):
    """回归：build() 不得污染热度信号（正反馈闭环缺陷）。

    旧版叙事注入走主动 recall()，每次 LLM 调用都给 top 记忆刷新
    last_used_at / access_count——越被注入越热门、越热门越被注入，
    同样的记忆永久霸榜，新记忆挤不进相关性配额。注入必须是只读的。
    """
    await memory_mgr.add("喜好", "喜欢吃芒果")
    await memory_mgr.add("喜好", "喜欢喝奶茶")
    await memory_mgr.add("去过的地方", "维多利亚港")

    before = {
        m["id"]: (m["access_count"], m["last_used_at"])
        for m in await memory_mgr.all()
    }

    builder = NarrativeBuilder(memory_mgr)
    for _ in range(5):  # 模拟多次 LLM 调用前的注入
        await builder.build()

    after = {
        m["id"]: (m["access_count"], m["last_used_at"])
        for m in await memory_mgr.all()
    }
    assert before == after, "被动注入不应修改 access_count / last_used_at"


# ── 情绪联动：build 传 mood_valence 给 recall（schema v18）──────


async def test_build_passes_mood_valence_to_recall(memory_mgr, tmp_db, monkeypatch):
    """v18：build 每次查一次 life_state，把 mood_valence 传给 recall 相关路。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    calls = []
    orig_recall = memory_mgr.recall

    async def spy_recall(*args, **kwargs):
        calls.append(kwargs)
        return await orig_recall(*args, **kwargs)

    monkeypatch.setattr(memory_mgr, "recall", spy_recall)
    await tmp_db.execute("UPDATE life_state SET mood = '担心' WHERE id = 1")
    await tmp_db.commit()

    builder = NarrativeBuilder(memory_mgr)
    await builder.build(max_items=5)

    assert len(calls) == 1
    assert calls[0]["mood_valence"] == pytest.approx(-0.55)


async def test_build_unknown_mood_degrades_gracefully(memory_mgr, tmp_db, monkeypatch):
    """v18：life_state 是未知 mood 词 → mood_valence=None，浮现退回 v17。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    calls = []
    orig_recall = memory_mgr.recall

    async def spy_recall(*args, **kwargs):
        calls.append(kwargs)
        return await orig_recall(*args, **kwargs)

    monkeypatch.setattr(memory_mgr, "recall", spy_recall)
    await tmp_db.execute("UPDATE life_state SET mood = '不存在的词' WHERE id = 1")
    await tmp_db.commit()

    await NarrativeBuilder(memory_mgr).build(max_items=5)

    assert calls and calls[0]["mood_valence"] is None


# ── session summary 注入（P1-a）───────────────────────────────


async def _seed_summary(conn, session_id, summary, ended_at):
    await conn.execute(
        "INSERT INTO session_summaries "
        "(session_id, summary, msg_count, ended_at, created_at) "
        "VALUES (?, ?, 15, ?, ?)",
        (session_id, summary, ended_at, ended_at),
    )


async def test_build_injects_summaries_after_profile_before_memory(tmp_db, memory_mgr):
    """摘要块位于画像之后、常规记忆之前；按谈话新鲜度倒序。"""
    from memory.profile import PROFILE_MEMORY_ID
    await tmp_db.execute(
        """INSERT INTO memories (id, content, category, created_at, status,
           last_used_at, access_count, updated_at)
           VALUES (?, '她最近在赶论文', '小汐眼里的冷冷', '2026-08-11 10:00',
                   'core', '2026-08-11 10:00', 0, '2026-08-11 10:00')""",
        (PROFILE_MEMORY_ID,),
    )
    await memory_mgr.add("喜好", "爱吃芒果")
    await _seed_summary(tmp_db, "s-1", "从考试焦虑聊到家庭压力，她哭了一场。",
                        "2026-08-11 22:00")
    await _seed_summary(tmp_db, "s-2", "她拿到了 offer，高兴得连夜分享。",
                        "2026-08-09 20:00")
    await tmp_db.commit()

    text = await NarrativeBuilder(memory_mgr).build(max_items=5)

    assert "## 最近的谈话" in text
    assert text.index("## 小汐眼里的冷冷") < text.index("## 最近的谈话")
    assert text.index("## 最近的谈话") < text.index("以下是小汐记住的")
    assert "从考试焦虑聊到家庭压力" in text
    assert "8月11日晚上" in text                      # 2026-08-11 22:00 → 晚上
    assert text.index("从考试焦虑") < text.index("拿到了 offer")  # 新在前


async def test_build_no_summaries_omits_block(tmp_db, memory_mgr):
    """无摘要时不渲染"最近的谈话"块，原语义不变。"""
    await memory_mgr.add("喜好", "爱吃芒果")
    text = await NarrativeBuilder(memory_mgr).build(max_items=5)
    assert "## 最近的谈话" not in text
    assert "以下是小汐记住的" in text


async def test_build_summary_only_db_survives_gate(tmp_db, memory_mgr):
    """无画像无常规记忆但只有摘要：早退路径不丢摘要块。"""
    await _seed_summary(tmp_db, "s-1", "只聊了摘要。", "2026-08-11 22:00")
    await tmp_db.commit()
    text = await NarrativeBuilder(memory_mgr).build(max_items=5)
    assert "## 最近的谈话" in text
    assert "只聊了摘要" in text


async def test_build_truncates_overlong_summary(tmp_db, memory_mgr):
    """超长摘要截断（控制注入 token + 防伪造结构）。"""
    from memory.narrative import _SUMMARY_LINE_MAX
    await _seed_summary(tmp_db, "s-1", "长" * 500, "2026-08-11 22:00")
    await tmp_db.commit()
    text = await NarrativeBuilder(memory_mgr).build(max_items=5)
    assert "长" * (_SUMMARY_LINE_MAX + 1) not in text
    assert "……" in text
