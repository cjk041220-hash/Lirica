"""会话脉络摘要（P1-a）回归 — 合格判定、生成、失败降级、预算护栏。

mock 纪律：monkeypatch `summarize._create_llm` 注入假 LLM（同
test_consolidate 的 _FakeLLM 模式），不走真实网络。
"""
import datetime

import pytest

from memory import summarize
from memory.summarize import summarize_closed_sessions


class _FakeLLM:
    """返回预设文本 / 抛异常的假 LLM。captured 保存最近一次 ainvoke
    收到的 messages，供断言送入 LLM 的提示词内容。"""

    def __init__(self, text=None, *, error=None):
        self._text = text
        self._error = error
        self.calls = 0
        self.captured = None

    async def ainvoke(self, messages):
        self.calls += 1
        self.captured = messages
        if self._error:
            raise self._error
        return type("R", (), {"content": self._text})()


@pytest.fixture
def fake_llm(monkeypatch):
    def _set(text=None, *, error=None):
        fake = _FakeLLM(text, error=error)
        monkeypatch.setattr(summarize, "_create_llm", lambda: fake)
        return fake
    return _set


def _ts(base: datetime.datetime, minutes: int) -> str:
    return (base + datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%d %H:%M")


async def _seed_session(conn, session_id, n=15, *, is_era=0, idle_hours=24):
    """造一个会话：n 条消息，最后一条消息距今 idle_hours 小时。"""
    now = datetime.datetime.now(summarize._HKT)
    base = now - datetime.timedelta(hours=idle_hours)
    await conn.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era) "
        "VALUES (?, ?, ?, ?, ?)",
        (session_id, f"会话{session_id}", _ts(base, 0), _ts(base, n - 1), is_era),
    )
    for i in range(n):
        role = "user" if i % 2 == 0 else "assistant"
        await conn.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
            "VALUES (?, ?, ?, ?, 'chat')",
            (session_id, role, f"第{i}条消息", _ts(base, i)),
        )
    await conn.commit()


async def test_summarizes_eligible_session(tmp_db, fake_llm):
    """合格会话被摘一条摘要，快照 msg_count 与 ended_at。"""
    await _seed_session(tmp_db, "s-1")
    fake_llm(text="从考试焦虑聊到家庭压力，她哭了一场，你一直陪着。")

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 1, "skipped": 0}
    cursor = await tmp_db.execute(
        "SELECT session_id, summary, msg_count, ended_at FROM session_summaries "
        "WHERE session_id = 's-1'"
    )
    row = await cursor.fetchone()
    assert row is not None
    assert "从考试焦虑" in row[1]
    assert row[2] == 15
    cursor = await tmp_db.execute(
        "SELECT updated_at FROM chat_sessions WHERE id = 's-1'"
    )
    assert row[3] == (await cursor.fetchone())[0]  # ended_at = 最后消息时间


async def test_skips_short_session(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-short", n=5)
    fake = fake_llm(text="摘要")

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 0, "skipped": 0}
    assert fake.calls == 0


async def test_skips_era_session(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-era", is_era=1)
    fake = fake_llm(text="摘要")

    await summarize_closed_sessions(tmp_db)

    assert fake.calls == 0


async def test_skips_active_session(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-active")
    await tmp_db.execute(
        "UPDATE life_state SET active_session_id = 's-active' WHERE id = 1"
    )
    await tmp_db.commit()
    fake = fake_llm(text="摘要")

    await summarize_closed_sessions(tmp_db)

    assert fake.calls == 0


async def test_skips_not_idle_session(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-recent", idle_hours=1)
    fake = fake_llm(text="摘要")

    await summarize_closed_sessions(tmp_db)

    assert fake.calls == 0


async def test_skips_already_summarized(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-done")
    await tmp_db.execute(
        "INSERT INTO session_summaries "
        "(session_id, summary, msg_count, ended_at, created_at) "
        "VALUES ('s-done', '已有摘要', 15, '2026-08-01 10:00', '2026-08-01 10:00')"
    )
    await tmp_db.commit()
    fake = fake_llm(text="新摘要")

    await summarize_closed_sessions(tmp_db)

    assert fake.calls == 0
    cursor = await tmp_db.execute(
        "SELECT summary FROM session_summaries WHERE session_id = 's-done'"
    )
    assert (await cursor.fetchone())[0] == "已有摘要"  # 不覆盖


async def test_caps_three_per_run(tmp_db, fake_llm):
    for i in range(5):
        await _seed_session(tmp_db, f"s-{i}")
    fake = fake_llm(text="摘要")

    result = await summarize_closed_sessions(tmp_db)

    assert result["summarized"] == 3
    assert fake.calls == 3
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM session_summaries")
    assert (await cursor.fetchone())[0] == 3


async def test_llm_failure_skips_and_retries(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-err")
    fake_llm(error=RuntimeError("网络抖动"))

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 0, "skipped": 1}
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM session_summaries")
    assert (await cursor.fetchone())[0] == 0  # 不写库 → 下轮重试


async def test_empty_llm_output_skips(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-empty")
    fake_llm(text="  ")

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 0, "skipped": 1}


async def test_overlong_summary_truncated(tmp_db, fake_llm):
    await _seed_session(tmp_db, "s-long")
    fake_llm(text="长" * 500)

    await summarize_closed_sessions(tmp_db)

    cursor = await tmp_db.execute(
        "SELECT summary FROM session_summaries WHERE session_id = 's-long'"
    )
    assert len((await cursor.fetchone())[0]) == summarize._SUMMARY_MAX_LEN


# ── solo 自言自语排除（2026-08-17：与后台提取同纪律）──────────
#
# solo（在线主动搭话）是小汐自己的话，不是冷冷的话——不进摘要提示词，
# 也不得把会话顶上合格线（否则自言自语污染叙事注入）。


async def _seed_session_with_solo(conn, session_id, n_chat, n_solo, *,
                                  idle_hours=24):
    """造一个会话：n_chat 条 chat 消息 + n_solo 条 solo 自言自语。"""
    now = datetime.datetime.now(summarize._HKT)
    base = now - datetime.timedelta(hours=idle_hours)
    total = n_chat + n_solo
    await conn.execute(
        "INSERT INTO chat_sessions (id, title, created_at, updated_at, is_era) "
        "VALUES (?, ?, ?, ?, 0)",
        (session_id, f"会话{session_id}", _ts(base, 0), _ts(base, total - 1)),
    )
    for i in range(n_chat):
        role = "user" if i % 2 == 0 else "assistant"
        await conn.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
            "VALUES (?, ?, ?, ?, 'chat')",
            (session_id, role, f"对话消息{i}", _ts(base, i)),
        )
    for i in range(n_solo):
        await conn.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
            "VALUES (?, 'assistant', ?, ?, 'solo')",
            (session_id, f"自言自语内容{i}", _ts(base, n_chat + i)),
        )
    await conn.commit()


async def test_summarize_excludes_solo_from_prompt(tmp_db, fake_llm):
    """solo 自言自语不进摘要提示词；msg_count 快照只算真实对话。"""
    await _seed_session_with_solo(tmp_db, "s-mix", n_chat=15, n_solo=3)
    fake = fake_llm(text="从诗词聊到人生理想。")

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 1, "skipped": 0}
    prompt_text = fake.captured[1].content
    assert "自言自语内容" not in prompt_text
    assert "对话消息14" in prompt_text
    cursor = await tmp_db.execute(
        "SELECT msg_count FROM session_summaries WHERE session_id = 's-mix'"
    )
    assert (await cursor.fetchone())[0] == 15  # 快照不含 solo


async def test_summarize_solo_does_not_inflate_eligibility(tmp_db, fake_llm):
    """13 条真实对话 + 3 条 solo 不够格：solo 不得顶上 ≥15 条合格线。"""
    await _seed_session_with_solo(tmp_db, "s-thin", n_chat=13, n_solo=3)
    fake = fake_llm(text="不该被调用")

    result = await summarize_closed_sessions(tmp_db)

    assert result == {"summarized": 0, "skipped": 0}
    assert fake.calls == 0
