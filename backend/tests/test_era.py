"""era 会话路由测试 — 主动消息的会话归属判定与「三写一推」。

覆盖（2026-08-09 用户需求，v21）：
- is_online / decide_proactive_target / new_era_id 纯函数全矩阵
- get_open_era_id：开放（只有独处消息）/ 被接话关闭 / 被删
- get_proactive_target：读 life_state 路由
- append_proactive_turn：档案行 + checkpoint 追加 + proactive 行 + 推送
  四步，独立降级（checkpoint 失败不影响其余两写与推送）
"""
import datetime

import pytest

from utils.timezone import HKT as _HKT

NOW = datetime.datetime(2026, 8, 9, 14, 30, tzinfo=_HKT)


# ── 纯函数：is_online ─────────────────────────────────────────

def test_is_online_none_means_offline():
    """last_seen 为 NULL（从未上报/旧库）→ 离线（安全默认）。"""
    assert not era_is_online(None)


def test_is_online_within_timeout():
    assert era_is_online("2026-08-09 14:00")   # 30 分钟前，在 45 内
    assert era_is_online("2026-08-09 13:45")   # 恰好 45 分钟边界 → 在线


def test_is_online_beyond_timeout():
    assert not era_is_online("2026-08-09 13:44")  # 46 分钟前 → 离线


def test_is_online_unparseable_falls_back_offline():
    """无法解析的时间戳 → 离线（安全默认，不抛错）。"""
    assert not era_is_online("这不是时间")


# 便捷包装：固定 45 分钟超时
def era_is_online(last_seen):
    from era import is_online
    return is_online(NOW, last_seen, timeout_minutes=45)


# ── 纯函数：decide_proactive_target ───────────────────────────

def test_decide_online_with_active_session():
    from era import decide_proactive_target
    assert decide_proactive_target(True, "s-x", None) == ("session", "s-x")


def test_decide_online_without_active_session_goes_era():
    """在线但没报活跃会话（前端从未上报）→ 保守进 era，不硬塞。"""
    from era import decide_proactive_target
    assert decide_proactive_target(True, None, "e-1") == ("era", "e-1")
    assert decide_proactive_target(True, None, None) == ("new_era", None)


def test_decide_offline_appends_open_era():
    """离线 + 有开放 era → 追加进独处时间线（活跃会话陈旧忽略）。"""
    from era import decide_proactive_target
    assert decide_proactive_target(False, "s-stale", "e-1") == ("era", "e-1")


def test_decide_offline_without_era_opens_new():
    from era import decide_proactive_target
    assert decide_proactive_target(False, "s-stale", None) == ("new_era", None)


# ── new_era_id ────────────────────────────────────────────────

def test_new_era_id_format_and_uniqueness():
    from era import new_era_id
    ids = {new_era_id() for _ in range(50)}
    assert len(ids) == 50                       # 同毫秒也不撞
    assert all(x.startswith("e-") for x in ids)
    import re
    assert all(re.fullmatch(r"e-[a-z0-9-]{1,64}", x) for x in ids)


# ── get_open_era_id ───────────────────────────────────────────

@pytest.fixture
def era_db(monkeypatch, tmp_db):
    """把 chat_archive 的 get_db 指到临时库（record_* 系列直连依赖）。"""
    from memory import chat_archive

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(chat_archive, "get_db", fake_get_db)
    return tmp_db


async def test_open_era_none_when_no_era(tmp_db):
    from era import get_open_era_id
    assert await get_open_era_id(tmp_db) is None


async def test_open_era_found_when_only_solo(era_db):
    """era 只有独处消息（冷冷还没接话）→ 仍开放。"""
    from memory import chat_archive
    await chat_archive.record_solo_message("e-open", "我在看云", is_era=1)

    from era import get_open_era_id
    assert await get_open_era_id(era_db) == "e-open"


async def test_open_era_closed_after_user_reply(era_db):
    """冷冷回来在 era 里接话（出现 user 消息）→ era 关闭。"""
    from memory import chat_archive
    await chat_archive.record_solo_message("e-1", "独处一", is_era=1)
    await chat_archive.record_user_message("e-1", "我回来了")

    from era import get_open_era_id
    assert await get_open_era_id(era_db) is None


async def test_open_era_picks_newest(era_db):
    from memory import chat_archive
    await chat_archive.record_solo_message("e-old", "旧的", is_era=1, now="2026-08-08 08:00")
    await chat_archive.record_solo_message("e-new", "新的", is_era=1, now="2026-08-09 08:00")

    from era import get_open_era_id
    assert await get_open_era_id(era_db) == "e-new"


async def test_open_era_ignores_normal_sessions(era_db):
    """普通会话（is_era=0）不进候选。"""
    from memory import chat_archive
    await chat_archive.record_user_message("s-n", "普通会话")
    await chat_archive.record_assistant_message("s-n", "普通回复")

    from era import get_open_era_id
    assert await get_open_era_id(era_db) is None


# ── get_proactive_target ──────────────────────────────────────

async def test_target_online_session(tmp_db):
    await tmp_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 14:00', active_session_id = 's-act' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    from era import get_proactive_target
    kind, sid, opener = await get_proactive_target(tmp_db, NOW, 45)
    assert (kind, sid, opener) == ("session", "s-act", False)


async def test_target_offline_opens_new_era(tmp_db):
    await tmp_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 08:00', active_session_id = 's-stale' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    from era import get_proactive_target
    kind, sid, opener = await get_proactive_target(tmp_db, NOW, 45)
    assert (kind, sid, opener) == ("new_era", None, True)  # opener=True


async def test_target_offline_appends_open_era(era_db):
    """离线 + 已有开放 era → 追加，不是 opener。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 08:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    await chat_archive.record_solo_message("e-1", "早安，冷冷。今天映海的云很好看。", is_era=1)

    from era import get_proactive_target
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("era", "e-1", False)


# ── resolve_proactive_target：失败放行 ────────────────────────

async def test_resolve_falls_back_to_new_era_on_error(monkeypatch, tmp_db):
    """路由读取失败 → 回退离线开新 era（绝不塞进未知会话）。"""
    async def boom(conn, now_dt, timeout_minutes):
        raise RuntimeError("db down")

    monkeypatch.setattr("era.get_proactive_target", boom)

    from era import resolve_proactive_target
    kind, sid, opener = await resolve_proactive_target(tmp_db, NOW, 45)
    assert kind == "new_era"
    assert sid.startswith("e-")
    assert opener is True


async def test_resolve_returns_sid_for_new_era(monkeypatch, tmp_db):
    """new_era 分支在此处生成 id（返回给调用方的 sid 恒非空）。"""
    async def fake_target(conn, now_dt, timeout_minutes):
        return ("new_era", None, True)

    monkeypatch.setattr("era.get_proactive_target", fake_target)

    from era import resolve_proactive_target
    kind, sid, opener = await resolve_proactive_target(tmp_db, NOW, 45)
    assert (kind, opener) == ("new_era", True)
    assert sid.startswith("e-")


# ── append_proactive_turn：三写一推 ───────────────────────────

class _FakeAgent:
    """模拟真实 agent：get_graph 暴露节点（era 的 as_node 归属发现），
    aupdate_state 记录调用参数。"""

    def __init__(self):
        self.updates = []

    def get_graph(self):
        return type("G", (), {"nodes": {"model": object(), "tools": object()}})()

    async def aupdate_state(self, config, values, as_node=None):
        self.updates.append((config, values, as_node))


@pytest.fixture
def fake_app(monkeypatch, tmp_db):
    """假 app + 假 agent；monkeypatch chat_archive 与 heartbeat 的依赖。"""
    from memory import chat_archive

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(chat_archive, "get_db", fake_get_db)

    agent = _FakeAgent()

    class _App:
        state = type("State", (), {"agent": agent})()

    return _App(), agent, tmp_db


async def test_append_era_writes_all_three(fake_app, monkeypatch):
    """era 路由：档案 is_era=1 + checkpoint AI turn + proactive 行 + 推送。"""
    app, agent, tmp_db = fake_app
    pushed = []

    import heartbeat

    async def fake_push(content, *, session_id=None, label="主动消息"):
        # 模拟 _store_and_push_proactive 的落库部分（推送本体不真发）
        import uuid
        now = "2026-08-09 14:31"
        await tmp_db.execute(
            "INSERT INTO proactive_messages (id, content, created_at, status, session_id) "
            "VALUES (?, ?, ?, 'delivered', ?)",
            (uuid.uuid4().hex[:16], content, now, session_id),
        )
        await tmp_db.commit()
        pushed.append((content, session_id))

    monkeypatch.setattr(heartbeat, "_store_and_push_proactive", fake_push)

    from era import append_proactive_turn
    await append_proactive_turn(app, "e-20260809", "我在看云，想起你上周说想喝汤。", kind="era")

    # 1) 档案行
    from memory import chat_archive
    msgs = await chat_archive.get_messages("e-20260809")
    assert msgs[0]["role"] == "assistant"
    assert msgs[0]["msg_type"] == "solo"
    sessions = await chat_archive.list_sessions()
    assert sessions[0]["is_era"] is True

    # 2) checkpoint 追加（as_node 归属节点——langgraph 1.x 外部更新强校验）
    assert len(agent.updates) == 1
    config, values, as_node = agent.updates[0]
    assert config == {"configurable": {"thread_id": "e-20260809"}}
    assert values["messages"][0].content == "我在看云，想起你上周说想喝汤。"
    assert as_node == "model"  # 运行时从图里发现的归属节点

    # 3) proactive 行 + 推送
    assert pushed == [("我在看云，想起你上周说想喝汤。", "e-20260809")]
    cursor = await tmp_db.execute(
        "SELECT session_id, status FROM proactive_messages ORDER BY id DESC LIMIT 1"
    )
    row = await cursor.fetchone()
    assert row[0] == "e-20260809"


async def test_append_session_route_not_era(fake_app, monkeypatch):
    """在线路由：档案不标 is_era，checkpoint 追加进活跃会话。"""
    app, agent, tmp_db = fake_app

    import heartbeat
    async def fake_push(content, *, session_id=None, label="主动消息"):
        pass

    monkeypatch.setattr(heartbeat, "_store_and_push_proactive", fake_push)

    from era import append_proactive_turn
    await append_proactive_turn(app, "s-active", "我在看云。", kind="session")

    from memory import chat_archive
    sessions = await chat_archive.list_sessions()
    assert sessions[0]["is_era"] is False
    assert agent.updates[0][0] == {"configurable": {"thread_id": "s-active"}}


async def test_append_no_discoverable_node_skips_checkpoint(fake_app, monkeypatch):
    """agent 无 get_graph（异常形态）→ 归属节点发现失败 → 只跳 checkpoint，
    档案与推送照常（失败放行）。"""
    app, agent, tmp_db = fake_app
    agent.get_graph = None  # 实例属性遮蔽类方法 → 调用抛 TypeError → 降级

    import heartbeat

    pushed = []

    async def fake_push(content, *, session_id=None, label="主动消息"):
        pushed.append(session_id)

    monkeypatch.setattr(heartbeat, "_store_and_push_proactive", fake_push)

    from era import append_proactive_turn
    await append_proactive_turn(app, "e-x", "内容照常", kind="new_era")

    from memory import chat_archive
    assert len(await chat_archive.get_messages("e-x")) == 1  # 档案没丢
    assert pushed == ["e-x"]                                  # 推送没丢
    assert agent.updates == []                                # checkpoint 被跳过


async def test_append_checkpoint_failure_degrades(fake_app, monkeypatch):
    """checkpoint 追加失败 → 档案与推送照常（失败放行）。"""
    app, agent, tmp_db = fake_app

    async def boom(config, values, as_node=None):
        raise RuntimeError("图状态写入失败")

    agent.aupdate_state = boom

    import heartbeat
    pushed = []
    async def fake_push(content, *, session_id=None, label="主动消息"):
        pushed.append(session_id)

    monkeypatch.setattr(heartbeat, "_store_and_push_proactive", fake_push)

    from era import append_proactive_turn
    await append_proactive_turn(app, "e-x", "内容照常", kind="new_era")

    from memory import chat_archive
    assert len(await chat_archive.get_messages("e-x")) == 1  # 档案没丢
    assert pushed == ["e-x"]                                  # 推送没丢
