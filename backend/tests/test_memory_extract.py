"""记忆后台提取 + 健康检查 回归（2026-08-01 架构评审 D1/D2/D3）。

覆盖：
- 水位线语义：首次运行回填全部、成功推进、无新消息 no-op、失败保持
- 提取决策：add / update / noop dispatch、预算护栏（5 add / 3 update）
- 容错：LLM 调用异常、输出不可解析 → 水位线不推进（下次重试）
- 批次预算：50 条消息 / 4000 字上限
- 内容长度上限（manager.add / update_content 截断，D4）
- 健康检查：指标（有对话零记忆 / 记忆停滞 / 正常）+ 24h 节流告警
"""
import datetime

import pytest

from memory import extract
from memory.manager import MemoryManager, _MAX_CONTENT_LEN
from utils.timezone import HKT as _HKT

_TIME_FMT = "%Y-%m-%d %H:%M"


class _FakeLLM:
    """返回预设文本或抛出预设异常的假 LLM（monkeypatch _create_llm 注入）。"""

    def __init__(self, text=None, exc=None):
        self._text = text
        self._exc = exc

    async def ainvoke(self, messages):
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
    cursor = await tmp_db.execute(
        "SELECT id, category, content FROM memories ORDER BY created_at, rowid"
    )
    return await cursor.fetchall()


# ── 水位线语义 ────────────────────────────────────────────────


async def test_watermark_zero_when_no_state(tmp_db):
    """从未运行过（无状态行）时水位线为 0 → 首次运行回填全部历史。"""
    assert await extract.get_watermark(tmp_db) == 0


async def test_extract_backfills_all_history(tmp_db, fake_llm):
    """首次运行：全部历史消息送入提取，add 落库，水位线推进到最大 id。"""
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
    assert await extract.get_watermark(tmp_db) == 0


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
    """有足够对话量（≥10 条）却零记忆 → 写路径异常信号。"""
    await _seed_messages(tmp_db, ["在吗"] * 10)
    health = await MemoryManager(tmp_db).check_memory_health()
    assert health["active_count"] == 0
    assert health["chat_count"] == 10
    assert health["abnormal"] is True


async def test_health_abnormal_stale(tmp_db):
    """距上次写入 ≥7 天 → 记忆停滞信号。"""
    mgr = MemoryManager(tmp_db)
    old = (datetime.datetime.now(_HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        ("m-stale", "旧记忆", "其他", old),
    )
    await tmp_db.commit()
    health = await mgr.check_memory_health()
    assert health["days_since_write"] >= 7
    assert health["abnormal"] is True


async def test_health_normal_fresh_memory(tmp_db):
    """正常状态：有新记忆 → 不告警。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "刚刚记住的事")
    health = await mgr.check_memory_health()
    assert health["abnormal"] is False


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


async def test_health_report_normal_logs_info(tmp_db, monkeypatch):
    """正常状态打 INFO 遥测，不告警。"""
    mgr = MemoryManager(tmp_db)
    await mgr.add("其他", "新记忆")
    calls = []
    monkeypatch.setattr(extract.log, "info", lambda *a, **k: calls.append(a))
    result = await extract.check_and_report_memory_health(tmp_db)
    assert result["warned"] is False
    assert any("记忆健康" in str(c) for c in calls)
