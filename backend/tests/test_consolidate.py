"""记忆周期整合（dreaming-lite）回归（体检 R1）。

覆盖：合并执行语义（保留最早行 / 被并行落 superseded / 历史链完整）、
预算护栏（≤3 合并）、校验（id 不存在/跨类别/单条 → 跳过）、24h 节流、
类别门槛、坏提案/坏输出容错。
"""
import datetime

import pytest

from memory import consolidate
from memory.consolidate import consolidate_memories
from memory.manager import MemoryManager
from utils.timezone import HKT as _HKT


class _FakeLLM:
    """返回预设文本的假 LLM（monkeypatch _create_llm 注入）。"""

    def __init__(self, text):
        self._text = text
        self.calls = 0

    async def ainvoke(self, messages):
        self.calls += 1
        return type("R", (), {"content": self._text})()


@pytest.fixture
def fake_llm(monkeypatch):
    def _set(text):
        fake = _FakeLLM(text)
        monkeypatch.setattr(consolidate, "_create_llm", lambda: fake)
        return fake
    return _set


async def _seed_category(tmp_db, category, contents):
    """批量造同类别记忆，返回 id 列表。"""
    mgr = MemoryManager(tmp_db)
    ids = []
    for c in contents:
        saved = await mgr.add(category, c)
        ids.append(saved["id"])
    return ids


# ── 合并执行语义 ──────────────────────────────────────────────


async def test_merge_combines_duplicates(tmp_db, fake_llm):
    """近义两条合并为一：保留最早行 + 合并句，被并行落 superseded。"""
    ids = await _seed_category(tmp_db, "冷冷喜欢的", [
        "冷冷喜欢喝椰子水", "冷冷爱喝椰子水", "冷冷喜欢看电影",
        "冷冷爱看电影", "冷冷喜欢散步", "冷冷爱散步",
        "冷冷喜欢听歌", "冷冷爱听歌",
    ])
    fake_llm(text=f'[{{"merge": ["{ids[0]}", "{ids[1]}"], '
                  '"content": "冷冷喜欢喝椰子水（几乎天天喝）"}]')

    result = await consolidate_memories(tmp_db)

    assert result == {"ran": True, "merged": 1, "invalid": 0, "profile": False}
    mgr = MemoryManager(tmp_db)
    assert await mgr.count() == 7  # 8 条 → 合并掉 1 条
    active = {m["content"] for m in await mgr.all()}
    assert "冷冷喜欢喝椰子水（几乎天天喝）" in active
    # 被合并行的归宿：superseded，指向 survivor
    cursor = await tmp_db.execute(
        "SELECT status, superseded_by FROM memories WHERE id = ?", (ids[1],)
    )
    status, superseded_by = await cursor.fetchone()
    assert status == "superseded"
    assert superseded_by == ids[0]
    # survivor 的旧内容也在历史链（update_content 记的）
    hist = await mgr.history(ids[0])
    assert "冷冷喜欢喝椰子水" in {h["content"] for h in hist}


async def test_merge_survivor_is_earliest(tmp_db, fake_llm):
    """survivor 取最早创建的行（合并句延续它的生命起点，时近不重置）。"""
    ids = await _seed_category(tmp_db, "其他", [
        "旧事实", "新说法的事实", "凑数甲", "凑数乙",
        "凑数丙", "凑数丁", "凑数戊", "凑数己",
    ])
    # 把 ids[1] 拨到最旧
    await tmp_db.execute(
        "UPDATE memories SET created_at = '2026-01-01 10:00' WHERE id = ?", (ids[1],)
    )
    await tmp_db.commit()
    fake_llm(text=f'[{{"merge": ["{ids[0]}", "{ids[1]}"], "content": "合并句"}}]')

    await consolidate_memories(tmp_db)

    cursor = await tmp_db.execute(
        "SELECT status, content FROM memories WHERE id = ?", (ids[1],)
    )
    status, content = await cursor.fetchone()
    assert status == "active" and content == "合并句"  # 最旧的存活
    cursor = await tmp_db.execute("SELECT status FROM memories WHERE id = ?", (ids[0],))
    assert (await cursor.fetchone())[0] == "superseded"


# ── 预算与校验 ────────────────────────────────────────────────


async def test_merge_budget_caps_at_three(tmp_db, fake_llm):
    """单次最多 3 个合并，超出的提案不执行。"""
    ids = await _seed_category(tmp_db, "日常", [f"事实{i}" for i in range(10)])
    proposals = [
        {"merge": [ids[i], ids[i + 1]], "content": f"合并{i}"}
        for i in range(0, 8, 2)  # 4 个提案
    ]
    import json
    fake_llm(text=json.dumps(proposals, ensure_ascii=False))

    result = await consolidate_memories(tmp_db)

    assert result["merged"] == 3
    # 每个合并落 2 行 superseded：survivor 的旧内容（update_content 记的）
    # + 被合并行本身——历史链双份账本
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE status = 'superseded'"
    )
    assert (await cursor.fetchone())[0] == 6


async def test_invalid_proposals_skipped(tmp_db, fake_llm):
    """坏提案（id 不存在 / 单条 / 跨类别）静默跳过，不炸整轮。"""
    ids = await _seed_category(tmp_db, "日常", [f"事实{i}" for i in range(8)])
    other_ids = await _seed_category(tmp_db, "喜好", ["偏好0", "偏好1"])
    import json
    fake_llm(text=json.dumps([
        {"merge": ["deadbeef", ids[0]], "content": "幻觉 id"},
        {"merge": [ids[1]], "content": "只有一条"},
        {"merge": [ids[2], other_ids[0]], "content": "跨类别"},
    ], ensure_ascii=False))

    result = await consolidate_memories(tmp_db)

    assert result["merged"] == 0
    assert result["invalid"] == 3
    assert await MemoryManager(tmp_db).count() == 10  # 一条没动


async def test_category_below_threshold_not_scanned(tmp_db, fake_llm):
    """类别条数 < 8 不进整合视野，不调 LLM。"""
    await _seed_category(tmp_db, "日常", ["事实0", "事实1"])
    fake = fake_llm(text="[]")

    result = await consolidate_memories(tmp_db)

    assert result["ran"] is False
    assert fake.calls == 0  # LLM 没被调用


async def test_unparseable_output_skips_category(tmp_db, fake_llm):
    """LLM 输出不可解析：跳过该类别，不报错，仍记节流时间。"""
    await _seed_category(tmp_db, "日常", [f"事实{i}" for i in range(8)])
    fake_llm(text="抱歉我不会输出 JSON")

    result = await consolidate_memories(tmp_db)

    assert result == {"ran": True, "merged": 0, "invalid": 0, "profile": False}


# ── 节流 ──────────────────────────────────────────────────────


async def test_throttle_blocks_second_run_within_24h(tmp_db, fake_llm):
    """24h 内第二次运行被节流（心跳 2h 一次，不节流一天 12 遍空耗）。"""
    await _seed_category(tmp_db, "日常", [f"事实{i}" for i in range(8)])
    fake = fake_llm(text="[]")

    r1 = await consolidate_memories(tmp_db)
    r2 = await consolidate_memories(tmp_db)

    assert r1["ran"] is True
    assert r2["ran"] is False
    assert fake.calls == 1  # 第二次没调 LLM


async def test_throttle_expires_after_24h(tmp_db, fake_llm):
    """consolidate_ran_at 回拨 25h → 节流解除。"""
    await _seed_category(tmp_db, "日常", [f"事实{i}" for i in range(8)])
    fake = fake_llm(text="[]")

    await consolidate_memories(tmp_db)
    yesterday = (
        datetime.datetime.now(_HKT) - datetime.timedelta(hours=25)
    ).strftime("%Y-%m-%d %H:%M")
    await tmp_db.execute(
        "UPDATE memory_extract_state SET consolidate_ran_at = ? WHERE id = 1",
        (yesterday,),
    )
    await tmp_db.commit()

    r2 = await consolidate_memories(tmp_db)

    assert r2["ran"] is True
    assert fake.calls == 2


# ── 画像蒸馏并入（Task 5 批②：consolidate 顺带跑 distill） ────


async def test_consolidate_runs_profile_distill(_profile_conn):
    """consolidate_memories 顺带跑画像蒸馏（同一 24h 节流窗口）。"""
    from memory.consolidate import consolidate_memories
    from unittest.mock import patch

    # 种一条用户消息 + 一个合格类别（≥8 条）让 consolidate 进入主流程
    await _profile_conn.execute(
        """INSERT INTO chat_messages (session_id, role, content, created_at)
           VALUES ('s1', 'user', '我最近在赶论文', '2026-08-11 10:00')""")
    for i in range(8):
        await _profile_conn.execute(
            """INSERT INTO memories (id, content, category, created_at, status,
               last_used_at, access_count, updated_at)
               VALUES (?, ?, '喜好', '2026-08-01 10:00', 'active',
                       '2026-08-01 10:00', 0, '2026-08-01 10:00')""",
            (f"c{i}", f"喜欢的东西 {i}"))
    await _profile_conn.commit()

    with patch("memory.consolidate._create_llm", autospec=True) as clm, \
         patch("memory.profile._create_llm", autospec=True) as plm:
        clm.return_value = _FakeLLM("[]")   # consolidate：无合并提案
        plm.return_value = _FakeLLM("她最近在赶论文，常熬夜")
        result = await consolidate_memories(conn=_profile_conn)

    assert result["profile"] is True
    cur = await _profile_conn.execute(
        "SELECT content FROM memories WHERE id = 'profile_cold'")
    row = await cur.fetchone()
    assert row is not None and "赶论文" in row[0]
