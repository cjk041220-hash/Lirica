"""记忆后台提取 + 健康检查 回归（2026-08-01 架构评审 D1/D2/D3）。

覆盖：
- 水位线语义：首次运行消费待处理批（有界、不回看）、成功推进、
  无新消息 no-op、失败保持、失败时既有水位线不被触碰
- 提取决策：add / update / noop dispatch、预算护栏（5 add / 3 update）
- 容错：LLM 调用异常、输出不可解析 → 水位线不推进（下次重试）
- 卡死死信（v13）：连续解析失败 ≥3 次弃批推进 / 成功归零 / 异常不计次
- 响应形态归一化（T1-2）：多段 list content 回收 text 块、无 text 计失败
- 批次预算：50 条消息 / 4000 字上限
- 内容长度上限（manager.add / update_content 截断，D4）
- 健康检查：指标（有对话零记忆 / 记忆停滞 / 正常）+ 24h 节流告警
"""
import datetime

import pytest

from memory import extract
from memory.manager import _MAX_CONTENT_LEN, MemoryManager
from utils.timezone import HKT as _HKT

_TIME_FMT = "%Y-%m-%d %H:%M"


class _FakeLLM:
    """返回预设文本或抛出预设异常的假 LLM（monkeypatch _create_llm 注入）。

    captured 保存最近一次 ainvoke 收到的 messages，供测试断言送入
    LLM 的提示词内容（如冲突上下文是否含旧话题记忆）。
    """

    def __init__(self, text=None, exc=None):
        self._text = text
        self._exc = exc
        self.captured = None

    async def ainvoke(self, messages):
        self.captured = messages
        if self._exc:
            raise self._exc
        return type("R", (), {"content": self._text})()


@pytest.fixture
def fake_llm(monkeypatch):
    """把 extract._create_llm 换成返回假 LLM 的工厂。"""

    def _set(text=None, exc=None):
        fake = _FakeLLM(text=text, exc=exc)
        monkeypatch.setattr(extract, "_create_llm", lambda: fake)
        return fake

    return _set


async def _seed_messages(tmp_db, texts):
    """灌入对话消息（无需会话行——schema 无 FK 约束）。"""
    now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)
    for i, text in enumerate(texts):
        await tmp_db.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at) "
            "VALUES (?, 'user', ?, ?)",
            (f"s-{i}", text, now),
        )
    await tmp_db.commit()


async def _fetch_memories(tmp_db):
    # 只取活跃读面：superseded 历史行（v15 覆盖留痕）是内部账本，
    # 提取语义断言不该把它算进「记忆条数」
    cursor = await tmp_db.execute(
        "SELECT id, category, content FROM memories "
        "WHERE status IN ('active', 'core') ORDER BY created_at, rowid"
    )
    return await cursor.fetchall()


# ── 水位线语义 ────────────────────────────────────────────────


async def test_watermark_zero_when_no_state(tmp_db):
    """从未运行过（无状态行）时水位线为 0 → 首次运行回填全部历史。"""
    assert await extract.get_watermark(tmp_db) == 0


async def test_first_run_consumes_pending_batch(tmp_db, fake_llm):
    """首次运行（水位线 0）：待处理批次送入提取，add 落库，水位线推进
    到批内最大 id。（命名诚实化：原 test_extract_backfills_all_history
    暗示「回填全部历史」，实际 >50 条的历史会被水位线跳过，见模块
    docstring 的有界设计说明。）"""
    await _seed_messages(tmp_db, ["冷冷说她喜欢喝椰子水", "今天下雨了"])
    fake_llm(text='[{"action": "add", "category": "冷冷喜欢的", "content": "冷冷喜欢喝椰子水"}]')

    result = await extract.extract_new_memories(tmp_db)

    assert result["processed"] == 2
    assert result["added"] == 1
    assert result["llm_error"] is False
    mems = await _fetch_memories(tmp_db)
    assert len(mems) == 1
    assert mems[0][1] == "冷冷喜欢的"
    assert mems[0][2] == "冷冷喜欢喝椰子水"
    # 来源追踪（v12）：新记忆标注提取自哪个会话
    cursor = await tmp_db.execute(
        "SELECT source FROM memories WHERE id = ?", (mems[0][0],)
    )
    assert (await cursor.fetchone())[0] == "chat:s-1"
    # 水位线 = 批内最大 chat_messages.id（第二条）
    assert await extract.get_watermark(tmp_db) == 2


async def test_extract_no_new_messages_skips_llm(tmp_db, fake_llm):
    """无新消息：纯读检查直接返回，不调用 LLM、不推进任何东西。"""
    fake_llm(exc=AssertionError("不应调用 LLM"))
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 0
    assert result["llm_error"] is False
    assert await extract.get_watermark(tmp_db) == 0


async def test_extract_second_run_only_new_messages(tmp_db, fake_llm):
    """第二批只处理水位线之后的新消息（旧消息不回看）。"""
    await _seed_messages(tmp_db, ["第一条旧消息"])
    fake_llm(text='[{"action": "add", "category": "其他", "content": "第一条事实"}]')
    await extract.extract_new_memories(tmp_db)

    await _seed_messages(tmp_db, ["第二条新消息"])
    fake_llm(text='[{"action": "add", "category": "其他", "content": "第二条事实"}]')
    result = await extract.extract_new_memories(tmp_db)

    assert result["processed"] == 1  # 只处理第二条
    mems = await _fetch_memories(tmp_db)
    assert {m[2] for m in mems} == {"第一条事实", "第二条事实"}


# ── 提取决策 ──────────────────────────────────────────────────


async def test_extract_dispatch_update(tmp_db, fake_llm):
    """update：LLM 指出旧记忆 id → 内容被覆盖。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("冷冷喜欢的", "冷冷爱喝奶茶不加糖")
    await _seed_messages(tmp_db, ["最近在控糖，奶茶喝得少了"])
    fake_llm(text=f'[{{"action": "update", "memory_id": "{saved["id"]}", '
                  '"content": "冷冷以前爱喝奶茶不加糖，最近控糖喝得少了"}]')

    result = await extract.extract_new_memories(tmp_db)

    assert result["updated"] == 1
    mems = await _fetch_memories(tmp_db)
    assert len(mems) == 1  # 覆盖不是新增
    assert mems[0][2] == "冷冷以前爱喝奶茶不加糖，最近控糖喝得少了"


async def test_extract_empty_json_advances(tmp_db, fake_llm):
    """LLM 判定无话可记（[]）：合法，推进水位线，零记忆。"""
    await _seed_messages(tmp_db, ["晚安"])
    fake_llm(text="[]")
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 1
    assert result["added"] == 0 and result["skipped"] == 0
    assert await extract.get_watermark(tmp_db) == 1


async def test_extract_caps_adds_at_five(tmp_db, fake_llm):
    """预算护栏：单次最多 5 条新增，超出的记入 skipped。"""
    await _seed_messages(tmp_db, ["事实多到爆炸"])
    actions = [
        {"action": "add", "category": "其他", "content": f"事实 {i}"}
        for i in range(7)
    ]
    fake_llm(text=str(actions).replace("'", '"'))

    result = await extract.extract_new_memories(tmp_db)

    assert result["added"] == 5
    assert result["skipped"] == 2
    mems = await _fetch_memories(tmp_db)
    assert len(mems) == 5


async def test_extract_update_nonexistent_id_skipped(tmp_db, fake_llm):
    """update 指向不存在的记忆 id → 跳过（可能已被删），不报错。"""
    await _seed_messages(tmp_db, ["随便聊聊"])
    fake_llm(text='[{"action": "update", "memory_id": "deadbeef00000000", '
                  '"content": "这条没有对象"}]')
    result = await extract.extract_new_memories(tmp_db)
    assert result["updated"] == 0
    assert result["skipped"] == 1
    assert result["llm_error"] is False


async def test_extract_unknown_action_skipped(tmp_db, fake_llm):
    """未知 action：跳过，批处理仍完成、水位线照常推进。"""
    await _seed_messages(tmp_db, ["随便聊聊"])
    fake_llm(text='[{"action": "delete_everything"}]')
    result = await extract.extract_new_memories(tmp_db)
    assert result["skipped"] == 1
    assert result["llm_error"] is False
    assert await extract.get_watermark(tmp_db) == 1


# ── 容错：失败不推进水位线 ────────────────────────────────────


async def test_extract_llm_exception_keeps_watermark(tmp_db, fake_llm):
    """LLM 调用异常：水位线保持，下次重试同一批。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(exc=RuntimeError("网络断了"))

    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is True
    assert await extract.get_watermark(tmp_db) == 0  # 未推进
    mems = await _fetch_memories(tmp_db)
    assert len(mems) == 0


async def test_extract_unparseable_output_keeps_watermark(tmp_db, fake_llm):
    """输出不可解析（非 JSON）：同样不推进（None ≠ [] 语义）。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text="抱歉，我实在记不下来……")

    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is True
    assert result["dead_letter"] is False
    assert await extract.get_watermark(tmp_db) == 0


# ── 卡死死信（v13：连续解析失败 ≥3 次放弃该批，防永久空转）──


async def _get_fail_count(tmp_db):
    cursor = await tmp_db.execute(
        "SELECT fail_count FROM memory_extract_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    return (row[0] or 0) if row else 0


async def test_extract_parse_failures_accumulate(tmp_db, fake_llm):
    """阈值内：失败累计、水位线保持（给瞬态故障留重试窗口）。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text="抱歉，我不会输出 JSON……")

    r1 = await extract.extract_new_memories(tmp_db)
    r2 = await extract.extract_new_memories(tmp_db)

    assert r1["dead_letter"] is False
    assert r2["dead_letter"] is False
    assert r2["llm_error"] is True
    assert await extract.get_watermark(tmp_db) == 0
    assert await _get_fail_count(tmp_db) == 2


async def test_extract_parse_failure_dead_letter_after_three(tmp_db, fake_llm):
    """第 3 次连续解析失败 → 死信：放弃该批、推进水位线、归零计数。

    修复前（v13）：输出确定性损坏的批次会让水位线永不推进，提取通道
    每 2h 空转重试同一批，永久卡死。
    """
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text="不是 JSON")

    await extract.extract_new_memories(tmp_db)
    await extract.extract_new_memories(tmp_db)
    r3 = await extract.extract_new_memories(tmp_db)

    assert r3["dead_letter"] is True
    assert await extract.get_watermark(tmp_db) == 1   # 该批被弃，通道恢复
    assert await _get_fail_count(tmp_db) == 0

    # 死信后的新消息照常处理——通道没有整体死掉
    await _seed_messages(tmp_db, ["新的事实"])
    fake_llm(text='[{"action": "add", "category": "其他", "content": "新的事实"}]')
    r4 = await extract.extract_new_memories(tmp_db)
    assert r4["added"] == 1
    assert r4["dead_letter"] is False


async def test_extract_success_resets_fail_count(tmp_db, fake_llm):
    """失败后成功消费一批：连续失败计数归零（死信只针对同一批持续损坏）。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text="不是 JSON")
    await extract.extract_new_memories(tmp_db)
    await extract.extract_new_memories(tmp_db)
    assert await _get_fail_count(tmp_db) == 2

    fake_llm(text="[]")  # 合法空批也是成功消费
    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is False
    assert await _get_fail_count(tmp_db) == 0
    assert await extract.get_watermark(tmp_db) == 1


# ── 响应形态归一化（T1-2：堵死信机制的解析后门）──────────────
#
# 修复前：response.content 为非 str（OpenAI 兼容多段 list）时，
# _parse_actions 首行 .strip() 抛 AttributeError——调用在 try 块外，
# 异常不计次不推进 → 每 2h 原样重放同一批，绕过死信永久卡死。


async def test_extract_list_content_text_blocks_recovered(tmp_db, fake_llm):
    """多段 list content 里的 text 块被归一化回收——有效 JSON 不该被
    送进死信永久丢失。"""
    await _seed_messages(tmp_db, ["冷冷说她最近在学画画"])
    fake_llm(text=[
        {"type": "text", "text": '[{"action": "add", "category": "冷冷的信息", '
                                  '"content": "冷冷最近在学画画"}]'},
    ])

    result = await extract.extract_new_memories(tmp_db)

    assert result["added"] == 1           # 归一化生效，不是死信
    assert result["dead_letter"] is False
    assert await extract.get_watermark(tmp_db) == 1


async def test_extract_list_content_no_text_counts_failure(tmp_db, fake_llm):
    """list 里抽不出 text 块（如纯 image_url 段）→ 归一化为空 → 计解析
    失败（走正常失败计数，不是异常后门）。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text=[{"type": "image_url", "image_url": {"url": "..."}}])

    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is True
    assert result["dead_letter"] is False
    assert await extract.get_watermark(tmp_db) == 0
    assert await _get_fail_count(tmp_db) == 1


async def test_extract_none_content_counts_failure(tmp_db, fake_llm):
    """content=None → 空串 → 计解析失败（修复前 None 也会走 (text or "")
    兜住，本条钉死该行为不被归一化改动破坏）。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(text=None)

    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is True
    assert await extract.get_watermark(tmp_db) == 0
    assert await _get_fail_count(tmp_db) == 1


async def test_extract_parse_failure_preserves_watermark(tmp_db, fake_llm):
    """水位线已在 42 时解析失败 → fail_count 计数但水位线保持 42
    （_set_fail_count 的 ON CONFLICT 只更新 fail_count，不碰水位线）。"""
    # 先正常消费一批把水位线推到 1，再造 41 条空档到 42 不现实——
    # 直接改状态行模拟「水位线 42」的既成状态
    await _seed_messages(tmp_db, ["第一条"])
    fake_llm(text="[]")
    await extract.extract_new_memories(tmp_db)
    await tmp_db.execute(
        "UPDATE memory_extract_state SET last_message_id = 42 WHERE id = 1"
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["第四十三条"])  # id=2 > 水位线？不——id=2 < 42，不会被取
    # 种一条 id > 42 的消息才能被 _fetch_pending 取到
    await tmp_db.execute(
        "INSERT INTO chat_messages (id, session_id, role, content, created_at) "
        "VALUES (43, 's-x', 'user', '新消息', '2026-08-01 10:00')"
    )
    await tmp_db.commit()

    fake_llm(text="不是 JSON")
    result = await extract.extract_new_memories(tmp_db)

    assert result["llm_error"] is True
    assert await extract.get_watermark(tmp_db) == 42   # 未被 _set_fail_count 触碰
    assert await _get_fail_count(tmp_db) == 1


async def test_extract_llm_exception_not_counted_as_parse_failure(tmp_db, fake_llm):
    """LLM 调用异常是瞬态故障：不计入解析失败（永不触发死信），
    长期失灵由健康检查「记忆停滞」告警兜底。"""
    await _seed_messages(tmp_db, ["重要的话"])
    fake_llm(exc=RuntimeError("网络断了"))

    for _ in range(5):  # 超过死信阈值的次数也不死信
        result = await extract.extract_new_memories(tmp_db)

    assert result["dead_letter"] is False
    assert await _get_fail_count(tmp_db) == 0
    assert await extract.get_watermark(tmp_db) == 0  # 依然保持待重试


# ── 冲突上下文话题召回（D1）──────────────────────────────────
#
# 修复前冲突上下文只有 mgr.recent(10)——与更旧记忆的矛盾 LLM 看不见，
# 只能发 add → 矛盾记忆共存（「爱吃火锅」三个月后遇上「不爱吃了」）。
# 修复后按批次话题 recall(query=) 全库召回候选。


async def test_extract_conflict_context_includes_old_topic_memory(tmp_db, fake_llm):
    """旧话题记忆被 12 条更新记忆挤出 recent-10，仍经话题召回进入
    提取 LLM 的冲突上下文——矛盾才有机会被判成 update 而非 add。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("冷冷喜欢的", "冷冷爱吃火锅")
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=30)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ? WHERE content = '冷冷爱吃火锅'", (old,)
    )
    for i in range(12):  # 12 条更新的无关记忆挤掉 recent-10 的全部席位
        await mgr.add("日常", f"无关的闲谈 {i}")
    await tmp_db.commit()

    # 措辞避开与新记忆的共享 bigram（如「最近」），让话题召回而非
    # 时近排序决定候选——这正是本测试要钉的机制
    await _seed_messages(tmp_db, ["火锅不想吃了"])
    fake = fake_llm(text="[]")

    await extract.extract_new_memories(tmp_db)

    assert fake.captured is not None
    prompt_text = fake.captured[1].content  # HumanMessage = 提取提示词
    assert "冷冷爱吃火锅" in prompt_text, "旧话题记忆未进入冲突上下文"


# ── 批次预算 ──────────────────────────────────────────────────


async def test_extract_batch_caps_at_50_messages(tmp_db, fake_llm):
    """单批最多 50 条消息（旧端丢弃，最新保留）。"""
    await _seed_messages(tmp_db, [f"消息 {i} 内容" for i in range(60)])
    fake_llm(text="[]")
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 50
    assert await extract.get_watermark(tmp_db) == 60


async def test_extract_char_budget_trims_old_messages(tmp_db, fake_llm):
    """4000 字预算：超长从旧端丢弃。"""
    # 每条 ~105 字：50 条 × 105 ≈ 5250 字 > 4000 预算，必然触发从旧端裁剪
    await _seed_messages(tmp_db, [f"第{i}条消息，内容补到一百字左右" + "的流水账" * 30 for i in range(60)])
    fake_llm(text="[]")
    result = await extract.extract_new_memories(tmp_db)
    assert 0 < result["processed"] < 50  # 字数预算先于条数预算生效
    assert await extract.get_watermark(tmp_db) == 60


# ── 稳态游标（体检 S2：超量消息不再被水位线永久跳过）────────────


async def test_extract_steady_state_drains_oldest_first(tmp_db, fake_llm):
    """稳态超量（水位线 > 0）：从最旧端顺序消化，中间消息零跳过。"""
    await _seed_messages(tmp_db, [f"消息 {i}" for i in range(60)])
    fake_llm(text="[]")
    # 首跑：跳尾语义不变——消费最新 50 条，水位线推到批尾
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 50
    assert await extract.get_watermark(tmp_db) == 60

    # 稳态再来 60 条（id 61-120）：应消化最旧的 50 条（61-110），
    # 水位线只推进到 110——修复前的 DESC 跳尾会推到 120，永久跳过 61-110
    await _seed_messages(tmp_db, [f"新消息 {i}" for i in range(60)])
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 50
    assert await extract.get_watermark(tmp_db) == 110

    # 再跑：剩余 10 条全部消化，最终追上零跳过
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 10
    assert await extract.get_watermark(tmp_db) == 120


async def test_extract_backlog_safety_valve_jumps_tail(tmp_db, fake_llm):
    """积压安全阀：稳态 pending > _MAX_BACKLOG 时按首跑跳尾，防追账烧账单。"""
    await _seed_messages(tmp_db, [f"消息 {i}" for i in range(60)])
    fake_llm(text="[]")
    await extract.extract_new_memories(tmp_db)  # 首跑，水位线 60
    assert await extract.get_watermark(tmp_db) == 60

    # 稳态积压 501 条（> _MAX_BACKLOG=500）→ 跳尾：只消费最新 50 条，
    # 水位线直接推到 561，中间的旧账不再逐批追
    await _seed_messages(tmp_db, [f"积压 {i}" for i in range(501)])
    result = await extract.extract_new_memories(tmp_db)
    assert result["processed"] == 50
    assert await extract.get_watermark(tmp_db) == 561


# ── 内容长度上限（D4）─────────────────────────────────────────


async def test_add_caps_overlong_content(tmp_db):
    """add 超 500 字截断（不拒绝，截断带省略号）。"""
    mgr = MemoryManager(tmp_db)
    long_text = "很长的事实。" * 100  # 600 字
    saved = await mgr.add("其他", long_text)
    assert len(saved["content"]) <= _MAX_CONTENT_LEN + 2  # 500 + 省略号
    assert saved["content"].startswith(long_text[:_MAX_CONTENT_LEN])


async def test_update_content_caps_overlong(tmp_db):
    """update_content 同样截断。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("其他", "旧内容")
    long_text = "很长的更新。" * 100
    assert await mgr.update_content(saved["id"], long_text)
    mems = await _fetch_memories(tmp_db)
    assert len(mems[0][2]) <= _MAX_CONTENT_LEN + 2
    assert mems[0][2].startswith(long_text[:_MAX_CONTENT_LEN])


async def test_update_content_empty_returns_false(tmp_db):
    """空内容覆盖是无效操作，返回 False（避免写入空记忆）。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("其他", "旧内容")
    assert await mgr.update_content(saved["id"], "") is False
    assert await mgr.update_content(saved["id"], "   ") is False
    mems = await _fetch_memories(tmp_db)
    assert mems[0][2] == "旧内容"  # 未被清空


# ── 健康检查（D3）─────────────────────────────────────────────


async def test_health_abnormal_no_memories_with_chats(tmp_db):
    """有足够对话量（≥10 条）却零记忆 → 写路径异常信号（从未写入）。"""
    await _seed_messages(tmp_db, ["在吗"] * 10)
    health = await MemoryManager(tmp_db).check_memory_health()
    assert health["active_count"] == 0
    assert health["chat_count"] == 10
    assert health["abnormal"] is True
    assert health["chats_7d"] == 10
    assert health["added_7d"] == 0


async def test_health_abnormal_stale_with_chats(tmp_db):
    """停滞：距上次写入 ≥7 天且近 7 天仍有对话 → 异常。"""
    mgr = MemoryManager(tmp_db)
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        ("m-stale", "旧记忆", "其他", old),
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["在吗"])  # 窗口内有对话

    health = await mgr.check_memory_health()
    assert health["days_since_write"] >= 7
    assert health["abnormal"] is True


async def test_health_idle_no_chats_is_normal(tmp_db):
    """误报修复：7 天未写入但也没有对话（正常闲置）→ 不告警。"""
    mgr = MemoryManager(tmp_db)
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        ("m-stale", "旧记忆", "其他", old),
    )
    await tmp_db.commit()

    health = await mgr.check_memory_health()
    assert health["days_since_write"] >= 7  # 指标照报
    assert health["abnormal"] is False       # 只是不告警


async def test_health_normal_fresh_memory(tmp_db):
    """正常状态：有新记忆 → 不告警。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "刚刚记住的事")
    health = await mgr.check_memory_health()
    assert health["abnormal"] is False


async def test_health_abnormal_stopped_after_prior_memories(tmp_db):
    """新检测能力：曾有记忆，但近 7 天对话活跃却零新增 → 写路径停摆。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "旧记忆")
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, updated_at = ?, last_mention_ts = ?",
        (old, old, old),
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["在吗"] * 10)  # 今天 10 条对话

    health = await mgr.check_memory_health()
    assert health["active_count"] == 1
    assert health["added_7d"] == 0
    assert health["chats_7d"] == 10
    assert health["abnormal"] is True


async def test_health_window_metrics_and_rates(tmp_db):
    """多指标：近 7 天窗口统计 + 提取率 + 归档占比。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "最近记住的事")          # 今天新增（v17 created_at 不可变）
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=30)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        ("m-old", "30 天前的旧记忆", "其他", old),
    )
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'archived')",
        ("m-arch", "已归档记忆", "其他", old),
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["在吗"] * 4)   # 今天 4 条对话

    health = await mgr.check_memory_health()
    assert health["chats_7d"] == 4
    assert health["added_7d"] == 1               # 30 天前的行不进窗口
    assert health["extract_rate_7d"] == pytest.approx(0.25)
    assert health["archived_count"] == 1
    assert health["archive_ratio"] == pytest.approx(1 / 3)  # 2 active+core : 1 archived


async def test_health_extract_rate_none_without_chats(tmp_db):
    """无对话 → 提取率为 None（不除零、不误造 0%）。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "一条记忆")
    health = await mgr.check_memory_health()
    assert health["chats_7d"] == 0
    assert health["added_7d"] == 1
    assert health["extract_rate_7d"] is None


async def test_health_report_warns_then_throttles(tmp_db, monkeypatch):
    """告警走 24h 节流：首次 WARNING，24h 内不重复告警。"""
    await _seed_messages(tmp_db, ["在吗"] * 10)
    calls = []
    monkeypatch.setattr(extract.log, "warning", lambda *a, **k: calls.append(a))

    r1 = await extract.check_and_report_memory_health(tmp_db)
    r2 = await extract.check_and_report_memory_health(tmp_db)

    assert r1["warned"] is True
    assert r2["warned"] is False  # 节流生效
    assert len(calls) == 1


async def test_health_report_warns_never_written(tmp_db, monkeypatch):
    """告警三态-从未写入：0 记忆 + 窗口对话量 → 文本含「从未写入」。"""
    await _seed_messages(tmp_db, ["在吗"] * 10)
    calls = []
    monkeypatch.setattr(extract.log, "warning", lambda *a, **k: calls.append(a))
    await extract.check_and_report_memory_health(tmp_db)
    assert any("从未写入" in str(c) for c in calls)


async def test_health_report_warns_stalled_write_path(tmp_db, monkeypatch):
    """告警三态-最近停摆：有旧记忆但窗口对话活跃零新增 → 文本含「停摆」。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "旧记忆")
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, updated_at = ?",
        (old, old),
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["在吗"] * 10)
    calls = []
    monkeypatch.setattr(extract.log, "warning", lambda *a, **k: calls.append(a))
    await extract.check_and_report_memory_health(tmp_db)
    assert any("停摆" in str(c) for c in calls)


async def test_health_report_warns_stale_with_chats(tmp_db, monkeypatch):
    """告警三态-停滞：超期未写但窗口有对话 → 文本含「停滞」。"""
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        ("m-stale", "旧记忆", "其他", old),
    )
    await tmp_db.commit()
    await _seed_messages(tmp_db, ["在吗"])
    calls = []
    monkeypatch.setattr(extract.log, "warning", lambda *a, **k: calls.append(a))
    await extract.check_and_report_memory_health(tmp_db)
    assert any("停滞" in str(c) for c in calls)


async def test_health_report_normal_logs_info(tmp_db, monkeypatch):
    """正常状态打 INFO 遥测（含多指标时间序列——基线积累数据源），不告警。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "新记忆")
    await _seed_messages(tmp_db, ["在吗", "嗯嗯"])
    calls = []
    monkeypatch.setattr(extract.log, "info", lambda *a, **k: calls.append(a))
    result = await extract.check_and_report_memory_health(tmp_db)
    assert result["warned"] is False
    text = " ".join(str(c) for c in calls)
    assert "记忆健康" in text
    assert "提取率" in text and "归档占比" in text


# ── I1 importance 打分（schema v17）────────────────────────────


async def test_extract_passes_importance(tmp_db, fake_llm):
    """I1：提取 LLM 的 add 动作带 importance → 落库。"""
    await _seed_messages(tmp_db, ["冷冷说她最喜欢的是夏天"])
    fake_llm(text='[{"action": "add", "category": "冷冷喜欢的", '
                  '"content": "冷冷最喜欢夏天", "importance": 9}]')

    result = await extract.extract_new_memories(tmp_db)

    assert result["added"] == 1
    cursor = await tmp_db.execute(
        "SELECT importance FROM memories WHERE status IN ('active', 'core')"
    )
    assert (await cursor.fetchone())[0] == 9


async def test_extract_importance_defaults_five(tmp_db, fake_llm):
    """I1：LLM 没打分 → 默认 5（中性）。"""
    await _seed_messages(tmp_db, ["冷冷说她喜欢下雨天"])
    fake_llm(text='[{"action": "add", "category": "冷冷喜欢的", '
                  '"content": "冷冷喜欢下雨天"}]')

    await extract.extract_new_memories(tmp_db)

    cursor = await tmp_db.execute(
        "SELECT importance FROM memories WHERE status IN ('active', 'core')"
    )
    assert (await cursor.fetchone())[0] == 5


# ── 情绪标签打分（schema v18 情绪联动）────────────────────────


async def test_extract_passes_valence_arousal(tmp_db, fake_llm):
    """v18：提取 LLM 的 add 动作带 valence/arousal → 落库。"""
    await _seed_messages(tmp_db, ["冷冷说今天考试考砸了，很难过"])
    fake_llm(text='[{"action": "add", "category": "冷冷的信息", '
                  '"content": "冷冷今天考试考砸了很难过", "importance": 6, '
                  '"valence": -0.7, "arousal": 0.3}]')

    result = await extract.extract_new_memories(tmp_db)

    assert result["added"] == 1
    cursor = await tmp_db.execute(
        "SELECT valence, arousal FROM memories WHERE status IN ('active', 'core')"
    )
    valence, arousal = await cursor.fetchone()
    assert valence == pytest.approx(-0.7)
    assert arousal == pytest.approx(0.3)


async def test_extract_valence_defaults_zero(tmp_db, fake_llm):
    """v18：LLM 没打情绪分 → 0 中性（与既有行为兼容）。"""
    await _seed_messages(tmp_db, ["冷冷说她周三有课"])
    fake_llm(text='[{"action": "add", "category": "冷冷的信息", "content": "冷冷周三有课"}]')

    await extract.extract_new_memories(tmp_db)

    cursor = await tmp_db.execute(
        "SELECT valence, arousal FROM memories WHERE status IN ('active', 'core')"
    )
    assert tuple(await cursor.fetchone()) == (0.0, 0.0)


# ── 提取提示词时间约束（批次 H：2026-08-05 用户反馈防回归）──────


def test_extract_system_forbids_relative_time_words():
    """提取提示词规则 9：content 只写绝对日期，禁止相对时间词。"""
    from memory.extract import _EXTRACT_SYSTEM

    assert "一律写绝对日期" in _EXTRACT_SYSTEM
    assert "今天/明天/后天/下周" in _EXTRACT_SYSTEM
    assert "换算成绝对日期" in _EXTRACT_SYSTEM
