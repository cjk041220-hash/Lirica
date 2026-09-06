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
    """离线 + 已有当天开放 era → 追加，不是 opener。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 08:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    await chat_archive.record_solo_message("e-1", "早安，冷冷。今天映海的云很好看。", is_era=1, now="2026-08-09 08:00")

    from era import get_proactive_target
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("era", "e-1", False)


async def test_target_offline_cross_day_opens_new_era(era_db):
    """跨天防护：昨天的 solo 会话即便未接话，新一天（当天）的第一条主动消息必须新开会话，绝不塞进昨天的聊天。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-08 22:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    # 昨天 2026-08-08 留下了一个 solo 会话
    await chat_archive.record_solo_message("e-yesterday", "昨晚的消息", is_era=1, now="2026-08-08 22:00")

    from era import get_proactive_target
    # 今天 NOW 是 2026-08-09 14:30
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("new_era", None, True)


async def test_target_offline_follows_latest_chat_when_user_opened_new_chat(era_db):
    """最新优先：今早小汐开了碎碎念，但冷冷下午新开了对话，后续主动消息永远跟随最新的聊天。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 12:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    # 1. 今早 08:00 小汐发的碎碎念
    await chat_archive.record_solo_message("e-morning", "早安呀", is_era=1, now="2026-08-09 08:00")
    # 2. 中午 11:00 冷冷新开的对话
    await chat_archive.record_user_message("s-lengling-new", "小汐我今天有新计划")
    await era_db.execute(
        "UPDATE chat_sessions SET created_at = '2026-08-09 11:00', updated_at = '2026-08-09 11:00' WHERE id = 's-lengling-new'"
    )
    await era_db.commit()

    from era import get_proactive_target
    # 下午 14:30 冷冷离线，主动消息应该追加在冷冷最新聊天的下面，而不是回退到早上的碎碎念
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("session", "s-lengling-new", False)


async def test_target_offline_same_day_keeps_same_session(era_db):
    """同一天聚合：今天内的多条主动消息都放在当天的同一个会话下。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 06:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    # 今早 08:00 第一条消息开了 e-today
    await chat_archive.record_solo_message("e-today", "早安", is_era=1, now="2026-08-09 08:00")

    from era import get_proactive_target
    # 下午 14:30 发第二条，依然追加在 e-today
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("era", "e-today", False)


async def test_target_ancient_stale_solo_never_intercepts(era_db):
    """库中遗留的远古 solo 会话（如一个月前冷冷从未回复过的碎碎念）绝不会拦截新一天的会话路由。"""
    await era_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-01 10:00' WHERE id = 1"
    )
    await era_db.commit()
    from memory import chat_archive
    # 远古 2026-08-01 遗留的 solo 会话
    await chat_archive.record_solo_message("e-ancient", "很久以前的碎碎念", is_era=1, now="2026-08-01 10:00")

    from era import get_proactive_target
    # 2026-08-09 主动消息必须新开当天会话，绝不塞进 2026-08-01 的 e-ancient
    kind, sid, opener = await get_proactive_target(era_db, NOW, 45)
    assert (kind, sid, opener) == ("new_era", None, True)


async def test_full_day_lifecycle_and_cross_day_transition(era_db):
    """全天主动消息生命周期与次日平滑过渡端到端验证：
    1. Day 1 早 08:00 -> new_era 新开当天时间线 (opener=True)
    2. Day 1 中 12:00 -> era 追加进当天时间线 (opener=False)
    3. Day 1 下 15:00 -> 冷冷新开对话并聊天
    4. Day 1 晚 23:00 -> session 追加进冷冷最新对话 (opener=False)
    5. Day 2 早 08:00 -> new_era 跨天新开 Day 2 时间线 (opener=True)
    """
    from era import resolve_proactive_target, append_proactive_turn
    from memory import chat_archive

    # 1. Day 1 08:00
    d1_0800 = datetime.datetime(2026, 8, 9, 8, 0, tzinfo=_HKT)
    kind1, sid1, opener1 = await resolve_proactive_target(era_db, d1_0800, 45)
    assert kind1 == "new_era"
    assert opener1 is True
    await append_proactive_turn(None, sid1, "Day 1 早安", kind=kind1, now_dt=d1_0800)

    # 2. Day 1 12:00
    d1_1200 = datetime.datetime(2026, 8, 9, 12, 0, tzinfo=_HKT)
    kind2, sid2, opener2 = await resolve_proactive_target(era_db, d1_1200, 45)
    assert kind2 == "era"
    assert sid2 == sid1
    assert opener2 is False
    await append_proactive_turn(None, sid2, "Day 1 中午碎碎念", kind=kind2, now_dt=d1_1200)

    # 3. Day 1 15:00 冷冷新开对话
    sid_user = "s-d1-afternoon"
    await chat_archive.create_session(sid_user, "下午冷冷新开聊天")
    await chat_archive.record_user_message(sid_user, "小汐你在干嘛")
    await chat_archive.record_assistant_message(sid_user, "在想你呀")
    await era_db.execute(
        "UPDATE chat_sessions SET updated_at = '2026-08-09 15:05' WHERE id = ?",
        (sid_user,),
    )
    await era_db.commit()

    # 4. Day 1 23:00 晚间关怀 -> 自动跟随冷冷最新对话
    d1_2300 = datetime.datetime(2026, 8, 9, 23, 0, tzinfo=_HKT)
    kind4, sid4, opener4 = await resolve_proactive_target(era_db, d1_2300, 45)
    assert kind4 == "session"
    assert sid4 == sid_user
    assert opener4 is False
    await append_proactive_turn(None, sid4, "Day 1 晚间关怀", kind=kind4, now_dt=d1_2300)

    # 5. Day 2 08:00 跨天 -> 绝不追加到昨天的 sid_user 或 sid1，自动新开 Day 2 会话
    d2_0800 = datetime.datetime(2026, 8, 10, 8, 0, tzinfo=_HKT)
    kind5, sid5, opener5 = await resolve_proactive_target(era_db, d2_0800, 45)
    assert kind5 == "new_era"
    assert sid5 != sid1
    assert sid5 != sid_user
    assert opener5 is True


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


async def test_resolve_new_era_preinserts_session_row(tmp_db):
    """C-1：new_era 分支预插会话行——第二个竞态方命中它而非再开新 era。

    竞态场景：早安 cron 与启动对齐的心跳（或任何两条主动消息任务）同
    分钟并发 resolve，都看到「无开放 era」。修复前各自生成新 era id 且
    都允许开场问候 → 双开两条「小汐的日记」；修复后第一个 resolve 立即
    预插 chat_sessions 行（is_era=1、无消息），第二个 resolve 的
    get_open_era_id 命中它 → 降级为追加（era, sid），双开窗口关闭。
    """
    await tmp_db.execute(
        "UPDATE life_state SET last_seen_at = '2026-08-09 08:00' WHERE id = 1"
    )
    await tmp_db.commit()

    from era import resolve_proactive_target

    # 第一个竞态方：new_era → 预插行
    kind1, sid1, opener1 = await resolve_proactive_target(tmp_db, NOW, 45)
    assert (kind1, opener1) == ("new_era", True)

    # 预插行已落库：is_era=1 + kind='solo'、碎碎念标题、无消息
    # → 对第二个竞态方可见（get_open_era_id 按 kind='solo' 找）
    cursor = await tmp_db.execute(
        "SELECT id, is_era, kind, title FROM chat_sessions WHERE id = ?",
        (sid1,),
    )
    row = await cursor.fetchone()
    assert row is not None
    assert row[1] == 1
    assert row[2] == "solo"
    assert row[3] == "小汐的碎碎念"

    # 第二个竞态方：命中第一个预插的 era → 追加而非再开
    kind2, sid2, opener2 = await resolve_proactive_target(tmp_db, NOW, 45)
    assert (kind2, sid2, opener2) == ("era", sid1, False)

    # 库里仍只有一条 era 会话行（无双开）
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM chat_sessions WHERE is_era = 1"
    )
    assert (await cursor.fetchone())[0] == 1


async def test_resolve_new_era_preinsert_failure_still_returns_sid(monkeypatch, tmp_db):
    """C-1 降级：预插写失败（如并发锁异常）不得吞掉 new_era 结果——
    宁可回到修复前行为（可能双开），也不让主动消息整体失败。"""
    async def fake_target(conn, now_dt, timeout_minutes):
        return ("new_era", None, True)

    monkeypatch.setattr("era.get_proactive_target", fake_target)

    def boom():
        raise RuntimeError("锁已损坏")

    monkeypatch.setattr("database.core.get_write_lock", boom)

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
