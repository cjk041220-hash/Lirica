"""对话档案（chat_sessions / chat_messages）单元测试。

覆盖：标题生成与只设一次、空回复跳过、role CHECK 约束、
列表倒序 + 预览、消息升序、级联删除。
"""
import sqlite3

import pytest

from memory import chat_archive


@pytest.fixture
def archive_db(tmp_db, monkeypatch):
    """把 chat_archive 接到临时库，并换一个可控假时钟。

    now_str 是分钟精度，真实时钟下同一分钟内的连续写入无法断言
    updated_at 推进——假时钟每次调用 +1 分钟。
    """
    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(chat_archive, "get_db", fake_get_db)

    clock = {"t": 0}

    def fake_now():
        clock["t"] += 1
        return f"2026-07-27 10:{clock['t']:02d}"

    monkeypatch.setattr(chat_archive, "now_str", fake_now)
    return tmp_db


async def test_first_user_message_creates_session_with_title(archive_db):
    """首条用户消息创建会话行并定标题。"""
    await chat_archive.record_user_message("s-1", "小汐，你在吗？")

    sessions = await chat_archive.list_sessions()
    assert len(sessions) == 1
    assert sessions[0]["id"] == "s-1"
    assert sessions[0]["title"] == "小汐，你在吗？"
    assert sessions[0]["message_count"] == 1


async def test_title_truncated_for_long_message(archive_db):
    """超长消息标题截断为前 30 字 + …。"""
    long = "这是一条非常非常非常非常非常非常非常非常非常非常长的开场消息" + "真的很长"
    await chat_archive.record_user_message("s-long", long)

    title = (await chat_archive.list_sessions())[0]["title"]
    assert title.endswith("…")
    assert len(title) == chat_archive._TITLE_MAX + 1
    assert title.startswith(long[:10])


async def test_title_set_once_updated_at_advances(archive_db):
    """标题只由首条消息设定，后续消息只推 updated_at。"""
    await chat_archive.record_user_message("s-2", "第一条消息")
    await chat_archive.record_user_message("s-2", "第二条消息")

    cursor = await archive_db.execute(
        "SELECT title, created_at, updated_at FROM chat_sessions WHERE id = 's-2'"
    )
    title, created_at, updated_at = await cursor.fetchone()
    assert title == "第一条消息"      # 不被覆盖
    assert updated_at > created_at    # 活跃时间推进


async def test_empty_assistant_text_skipped(archive_db):
    """空/纯空白助手回复不落档。"""
    await chat_archive.record_user_message("s-3", "在吗")
    await chat_archive.record_assistant_message("s-3", "   ")
    await chat_archive.record_assistant_message("s-3", "")

    messages = await chat_archive.get_messages("s-3")
    assert [m["role"] for m in messages] == ["user"]


async def test_role_check_constraint(archive_db):
    """role 只允许 user/assistant——显示层临时物（tool_hint 等）不落档案。"""
    with pytest.raises(sqlite3.IntegrityError):
        await archive_db.execute(
            "INSERT INTO chat_messages (session_id, role, content, created_at) "
            "VALUES ('s-x', 'system', 'x', '2026-07-27 10:00')"
        )


async def test_list_sessions_desc_order_with_preview(archive_db):
    """列表按最近活跃倒序；preview 取末条消息、截断 60 字。"""
    await chat_archive.record_user_message("s-old", "旧会话")
    await chat_archive.record_user_message("s-new", "新会话")
    await chat_archive.record_assistant_message("s-new", "新会话的回复")

    sessions = await chat_archive.list_sessions()
    assert [s["id"] for s in sessions] == ["s-new", "s-old"]
    new = sessions[0]
    assert new["preview"] == "新会话的回复"
    assert new["message_count"] == 2


async def test_get_messages_asc_and_unknown_empty(archive_db):
    """消息按写入升序返回；未知 session 返回空列表。"""
    await chat_archive.record_user_message("s-4", "甲")
    await chat_archive.record_assistant_message("s-4", "乙")
    await chat_archive.record_user_message("s-4", "丙")

    messages = await chat_archive.get_messages("s-4")
    assert [(m["role"], m["content"]) for m in messages] == [
        ("user", "甲"), ("assistant", "乙"), ("user", "丙"),
    ]
    assert await chat_archive.get_messages("no-such-session") == []


async def test_delete_session_cascades(archive_db):
    """显式级联删除：消息随会话一起走，不误伤其他会话。"""
    await chat_archive.record_user_message("s-del", "删我")
    await chat_archive.record_assistant_message("s-del", "好")
    await chat_archive.record_user_message("s-keep", "留我")

    assert await chat_archive.delete_session("s-del") is True
    assert await chat_archive.get_messages("s-del") == []
    assert [s["id"] for s in await chat_archive.list_sessions()] == ["s-keep"]

    # 重复删除 / 未知会话 → False（路由层据此返回 404）
    assert await chat_archive.delete_session("s-del") is False


async def test_delete_then_write_self_heals(archive_db):
    """删掉进行中的会话后，下一条消息让会话行自愈重建。"""
    await chat_archive.record_user_message("s-zombie", "第一轮")
    await chat_archive.delete_session("s-zombie")

    await chat_archive.record_user_message("s-zombie", "第二轮")
    sessions = await chat_archive.list_sessions()
    assert [s["id"] for s in sessions] == ["s-zombie"]
    assert sessions[0]["title"] == "第二轮"   # 重建后标题取新首条


async def test_title_boundary_30_and_31(archive_db):
    """标题边界：恰 30 字不截断，31 字截断加 …。"""
    await chat_archive.record_user_message("s-t30", "字" * 30)
    await chat_archive.record_user_message("s-t31", "字" * 31)

    titles = {s["id"]: s["title"] for s in await chat_archive.list_sessions()}
    assert titles["s-t30"] == "字" * 30
    assert titles["s-t31"] == "字" * 30 + "…"


async def test_preview_truncated_to_60(archive_db):
    """列表 preview 截断为 60 字。"""
    await chat_archive.record_user_message("s-p", "开场")
    await chat_archive.record_assistant_message("s-p", "回" * 61)

    preview = (await chat_archive.list_sessions())[0]["preview"]
    assert len(preview) == chat_archive._PREVIEW_MAX
    assert preview == "回" * 60


async def test_get_messages_returns_most_recent_n(archive_db):
    """长会话回看取「最近 N 条」升序，而非最早 N 条。"""
    for i in range(5):
        await chat_archive.record_user_message("s-long", f"第{i}句")

    messages = await chat_archive.get_messages("s-long", limit=3)
    assert [m["content"] for m in messages] == ["第2句", "第3句", "第4句"]


async def test_list_sessions_pagination(archive_db):
    """limit/offset 分页：倒序稳定、翻页不重不漏。"""
    for i in range(3):
        await chat_archive.record_user_message(f"s-page-{i}", f"会话{i}")

    page1 = await chat_archive.list_sessions(limit=2, offset=0)
    page2 = await chat_archive.list_sessions(limit=2, offset=2)
    assert [s["id"] for s in page1] == ["s-page-2", "s-page-1"]
    assert [s["id"] for s in page2] == ["s-page-0"]


async def test_record_swallows_db_errors(monkeypatch):
    """归档写入失败只记日志不上抛——对话韧性契约的单元级钉死。"""
    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(chat_archive, "get_db", broken_get_db)

    # 不抛即通过
    await chat_archive.record_user_message("s-x", "任意消息")
    await chat_archive.record_assistant_message("s-x", "任意回复")


# ── Spec A：主动消息/早安历史尾巴的只读取面 ─────────────────


async def test_recent_messages_cross_session_ascending(archive_db):
    """跨会话取最近消息，按时间正序返回（最新在最后）。"""
    await chat_archive.record_user_message("s-a", "甲会话的第一句")
    await chat_archive.record_assistant_message("s-a", "甲会话的回复")
    await chat_archive.record_user_message("s-b", "乙会话的第一句")
    await chat_archive.record_assistant_message("s-b", "乙会话的回复")

    messages = await chat_archive.recent_messages()
    assert [(m["role"], m["content"]) for m in messages] == [
        ("user", "甲会话的第一句"),
        ("assistant", "甲会话的回复"),
        ("user", "乙会话的第一句"),
        ("assistant", "乙会话的回复"),
    ]
    assert all(m["created_at"] for m in messages)


async def test_recent_messages_limit_newest_n(archive_db):
    """limit 生效：只取全局最近 N 条。"""
    for i in range(5):
        await chat_archive.record_user_message(f"s-{i}", f"第{i}句")

    messages = await chat_archive.recent_messages(limit=3)
    assert [m["content"] for m in messages] == ["第2句", "第3句", "第4句"]


async def test_recent_messages_empty_table(archive_db):
    """空库返回空列表（新部署无对话历史）。"""
    assert await chat_archive.recent_messages() == []


async def test_recent_messages_truncates_long_content(archive_db):
    """单条超长按 100 字截断。"""
    await chat_archive.record_user_message("s-long", "字" * 300)

    messages = await chat_archive.recent_messages()
    assert len(messages[0]["content"]) == 100


async def test_recent_messages_total_budget(archive_db):
    """总量封顶：超出预算丢最旧、保最新。"""
    for _ in range(20):
        await chat_archive.record_user_message("s-budget", "字" * 100)

    messages = await chat_archive.recent_messages(limit=20, max_chars=1500)
    assert 0 < len(messages) < 20
    assert messages[-1]["content"] == "字" * 100   # 最新的保留
    assert sum(len(m["content"]) + 20 for m in messages) <= 1500


async def test_format_recent_messages_time_marks(archive_db):
    """格式化：[MM-DD HH:MM] 说话人：内容，最新在最下。

    archive_db 假时钟每次调用 +1 分钟（2026-07-27 10:01 起）。
    """
    await chat_archive.record_user_message("s-fmt", "你这么说，我不高兴了。")
    await chat_archive.record_assistant_message("s-fmt", "冷冷，等一下……")

    block = chat_archive.format_recent_messages(await chat_archive.recent_messages())
    assert block.split("\n") == [
        "[07-27 10:01] 冷冷：你这么说，我不高兴了。",
        "[07-27 10:02] 小汐：冷冷，等一下……",
    ]


def test_archive_isolation_from_llm_context_paths():
    """静态自检：agent/ 与记忆叙事不得 import 对话档案。

    2026-08-05（Spec A）起 heartbeat 成为唯一例外：主动消息与早安经
    recent_messages 注入「历史尾巴」是用户批准的设计（见
    docs/superpowers/specs/2026-08-05-proactive-history-awareness-design.md）。
    例外只开给 heartbeat.py 一个文件，且只准走专用读函数
    （见下方 test_heartbeat_only_uses_dedicated_archive_read_api）。
    """
    from pathlib import Path

    backend_root = Path(__file__).parent.parent
    candidates = [backend_root / "memory" / "narrative.py"]
    candidates += list((backend_root / "agent").glob("*.py"))

    for f in candidates:
        assert "chat_archive" not in f.read_text(encoding="utf-8"), (
            f"{f.relative_to(backend_root)} 引用了对话档案——"
            "档案内容会沿此路径渗入 LLM 上下文，违反设计约束"
        )


def test_heartbeat_only_uses_dedicated_archive_read_api():
    """heartbeat 的档案例外只准走专用读函数：不得出现整表读写 API
    （防止例外扩大化——历史尾巴之外的任何档案访问都是违约）。"""
    from pathlib import Path

    text = (Path(__file__).parent.parent / "heartbeat.py").read_text(encoding="utf-8")
    for forbidden in ("get_messages", "list_sessions", "record_user_message",
                      "record_assistant_message", "delete_session"):
        assert forbidden not in text, (
            f"heartbeat.py 出现了 {forbidden}——档案例外访问扩大化"
        )
