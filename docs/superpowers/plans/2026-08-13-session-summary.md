# Session Summary 层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对"已结束且够长"的普通会话用 LLM 生成一句话脉络摘要，叙事注入时呈现，让小汐在每轮对话前想起最近几场深聊的走向（补 ZifaMem L2）。

**Architecture:** 心跳维护周期 `_periodic_maintenance` 新增独立段 → `memory/summarize.py` 合格判定 + LLM 一句话摘要 → 存新表 `session_summaries`（迁移 v23）→ `narrative.py` 在画像之后、常规记忆之前注入"最近的谈话"块。直接 SQL 读 `chat_messages`（extract.py 先例），不碰 `chat_archive`，隔离自检零改动。

**Tech Stack:** Python 3.13 + aiosqlite + langchain_core（LLM 走 `memory.extract._create_llm`，DeepSeek flash）。

## Global Constraints

- 隔离纪律：`memory/summarize.py` **不得** import `chat_archive`（隔离自检 `test_archive_isolation_from_llm_context_paths` 只禁 `narrative.py` + `agent/*`，`memory/` 处理模块读 `chat_messages` 走 extract.py 先例）。
- 成本护栏：单轮 ≤3 会话；会话需 ≥15 条消息 + 空闲 ≥6h + 非当前活跃会话（`life_state.active_session_id`，离线即清空）+ 无既有摘要。
- LLM 输出纪律：一句话 ≤80 字、过去时、**禁止编造**对话中未出现的内容、**不写日期**（时间锚由代码从 `ended_at` 渲染）。任何异常/空输出 → `log.warning` + 跳过不写库（下轮重试）。
- 时间一律 HKT（`utils.timezone.HKT`）；时间格式 `_TIME_FMT = "%Y-%m-%d %H:%M"`。
- 叙事注入"数据非指令"包裹（纵深第一层，graph.py 现成）不变。
- 验证纪律：每任务 `pytest` 绿 + 收尾全量 + :8001 副本冒烟 + 生产库 sha256 零污染。测试命令：`cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_summarize.py -v`（单文件）。后台 agent 不可靠，**直接手做**。

---

### Task 1: 迁移 v23 — session_summaries 表 + conftest 接表

**Files:**
- Modify: `backend/database/migrations.py`（SCHEMA_VERSION 22→23 + `SESSION_SUMMARIES_SCHEMA_SQL` 常量 + `_migrate_v23` + 注册）
- Modify: `backend/tests/conftest.py`（`tmp_db` 与 `_profile_conn` 两处夹具接入新表常量）
- Create: `backend/tests/test_migrations_v23.py`

**Interfaces:**
- Consumes: 无（本任务奠基，下游任务依赖 `session_summaries` 表存在）。
- Produces: `SESSION_SUMMARIES_SCHEMA_SQL`（conftest 与 `_migrate_v23` 共用）；`_migrate_v23(conn)`；`SCHEMA_VERSION = 23`。

- [ ] **Step 1: 写失败测试**

创建 `backend/tests/test_migrations_v23.py`：

```python
"""迁移 v23 回归 — session_summaries 表。

自建 v22 时代裸库（镜像生产 schema），测迁移 SQL 本身：
- 表存在（sqlite_master）
- 幂等：重跑不报错，仍唯一一张表
- 列齐：session_id / summary / msg_count / ended_at / created_at
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v23


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v23.db"))
    await conn.executescript("""
        CREATE TABLE chat_sessions (
            id TEXT PRIMARY KEY, title TEXT, created_at TEXT,
            updated_at TEXT, is_era INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL, created_at TEXT NOT NULL,
            msg_type TEXT
        );
    """)
    await conn.commit()
    yield conn
    await conn.close()


async def test_v23_creates_table(bare_db):
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name='session_summaries'"
    )
    assert await cursor.fetchone() is not None


async def test_v23_idempotent(bare_db):
    await _migrate_v23(bare_db)
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name='session_summaries'"
    )
    assert len(await cursor.fetchall()) == 1


async def test_v23_schema_columns(bare_db):
    await _migrate_v23(bare_db)
    cursor = await bare_db.execute("PRAGMA table_info(session_summaries)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert {"session_id", "summary", "msg_count", "ended_at", "created_at"} <= cols
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v23.py -v`
Expected: FAIL — `ImportError: cannot import name '_migrate_v23'`

- [ ] **Step 3: 实现迁移**

`backend/database/migrations.py`：
- 把 `SCHEMA_VERSION = 22` 改为 `SCHEMA_VERSION = 23`（第 91 行）。
- 在 `_migrate_v22` 之后、`_migrations[23]` 注册处新增：

```python
# 会话脉络摘要（P1-a，2026-08-13）：长对话的一句话语义压缩。
# session_id 对应 chat_sessions.id；ended_at 是会话最后消息时间快照
# （叙事按谈话新鲜度排序，非摘要生成时间）；独立表 → 天然不落
# memories 任何读面（面板/recall/健康检查），零 _READ_EXCLUSIONS 改动。
SESSION_SUMMARIES_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS session_summaries (
    session_id  TEXT PRIMARY KEY,
    summary     TEXT NOT NULL,
    msg_count   INTEGER NOT NULL,
    ended_at    TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
"""


async def _migrate_v23(conn: aiosqlite.Connection) -> None:
    """v23: session_summaries 表 — session summary 层（P1-a）的存储。"""
    await conn.execute(SESSION_SUMMARIES_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v23 完成：session_summaries 表已建立")


_migrations[23] = _migrate_v23
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v23.py -v`
Expected: PASS（3 passed）

- [ ] **Step 5: 接 conftest 夹具（防下游"no such table"）**

`backend/tests/conftest.py`：
- `tmp_db` fixture：在 `from database.migrations import (...)` 清单里加 `SESSION_SUMMARIES_SCHEMA_SQL`，并在 `CARE_LEDGER_SCHEMA_SQL` 那行之后加 `await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)`。
- `_profile_conn` fixture：`from database.migrations import MEMORY_EXTRACT_STATE_SQL` 改为同时 import `SESSION_SUMMARIES_SCHEMA_SQL`，并在 `await conn.executescript(MEMORY_EXTRACT_STATE_SQL)` 之后加 `await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)`。
- **为什么必须两处**：`narrative.build()` 将在 Task 4 无条件查 `session_summaries`；`_profile_conn`（test_profile.py 的叙事测试用）没有这张表会崩——与 2026-08-11 给 `_profile_conn` 补 `memory_extract_state` 是同款裁决（consolidate 无条件查那张表）。

- [ ] **Step 6: 全量迁移测试回归**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v22.py tests/test_migrations_v21.py tests/test_migrations_v23.py -q`
Expected: PASS（现有迁移测试不破 + 新增 3 个过）

- [ ] **Step 7: Commit**

```bash
git add backend/database/migrations.py backend/tests/conftest.py backend/tests/test_migrations_v23.py
git commit -m "feat(db): 迁移 v23——session_summaries 表（P1-a session summary 存储）"
```

---

### Task 2: memory/summarize.py — 合格判定 + 一句话摘要生成

**Files:**
- Create: `backend/memory/summarize.py`
- Create: `backend/tests/test_summarize.py`

**Interfaces:**
- Consumes: `memory.extract._create_llm`（同 consolidate）、`memory.extract._coerce_content`（LLM 输出归一化，防 list 型 content）、`database.core.get_db / get_write_lock`、`utils.timezone.HKT as _HKT / _TIME_FMT`。
- Produces: `async def summarize_closed_sessions(conn=None) -> dict`，返回 `{"summarized": int, "skipped": int}`。模块常量 `_MIN_MESSAGES=15`、`_IDLE_HOURS=6`、`_MAX_PER_RUN=3`、`_SUMMARY_MESSAGE_CAP=80`、`_SUMMARY_CHARS=4000`、`_SUMMARY_MAX_LEN=120`。

- [ ] **Step 1: 写失败测试**

创建 `backend/tests/test_summarize.py`：

```python
"""会话脉络摘要（P1-a）回归 — 合格判定、生成、失败降级、预算护栏。

mock 纪律：monkeypatch `summarize._create_llm` 注入假 LLM（同
test_consolidate 的 _FakeLLM 模式），不走真实网络。
"""
import datetime

import pytest

from memory import summarize
from memory.summarize import summarize_closed_sessions


class _FakeLLM:
    """返回预设文本 / 抛异常的假 LLM。"""

    def __init__(self, text=None, *, error=None):
        self._text = text
        self._error = error
        self.calls = 0

    async def ainvoke(self, messages):
        self.calls += 1
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_summarize.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'memory.summarize'`

- [ ] **Step 3: 实现 memory/summarize.py**

创建 `backend/memory/summarize.py`：

```python
"""会话脉络摘要 — session summary 层（P1-a，2026-08-13）。

补 ZifaMem L2：长对话只有硬裁剪 + recent 记忆，缺"这段聊了什么"的
语义压缩。本模块在心跳维护周期里，对"已结束且够长"的普通会话用 LLM
生成一句话脉络摘要，存 session_summaries 表，由 narrative.py 注入。

读面先例：直接 SQL 读 chat_messages（与 memory/extract.py 同款）。
隔离自检 test_archive_isolation_from_llm_context_paths 只禁
narrative.py 与 agent/* 引用 chat_archive；本模块是 memory/ 处理
模块，走 extract.py 先例，不 import chat_archive。输出是经 LLM 蒸馏
的脉络，注入时走叙事"数据非指令"包裹——与后台提取同类，不是新增
档案→上下文注入路径。

成本护栏：单轮 ≤3 会话；会话需 ≥15 条消息 + 空闲 ≥6h + 非当前活跃
会话 + 无既有摘要。失败（异常/空输出）跳过不写库，下轮重试。
"""
import datetime

from langchain_core.messages import HumanMessage, SystemMessage

from database.core import get_db, get_write_lock
from memory.extract import _coerce_content, _create_llm
from utils.logger import get_logger
from utils.timezone import _TIME_FMT
from utils.timezone import HKT as _HKT

log = get_logger(__name__)

_MIN_MESSAGES = 15           # 会话至少这么多条消息才值得摘
_IDLE_HOURS = 6              # 空闲（最后消息距今）至少这么久才算"已结束"
_MAX_PER_RUN = 3             # 每轮最多摘几个（排旧账防一次性烧爆）
_SUMMARY_MESSAGE_CAP = 80    # 喂 LLM 的会话尾部消息条数上限（控 token）
_SUMMARY_CHARS = 4000        # 喂 LLM 的对话文本总字符上限
_SUMMARY_MAX_LEN = 120       # 摘要长度上限（超长截断，防 LLM 话痨）

_SUMMARIZE_SYSTEM = """你是小汐的记忆管家。读一段对话，用一句话总结这段对话的脉络与情绪走向。

规则：
1. 只总结对话里实际出现的内容；**禁止编造**对话里没提过的话题或事实；
2. 侧重冷冷的状态与情绪（如"从考试焦虑聊到家庭压力""她拿到了 offer 很高兴"），不要流水账罗列话题；
3. 一句话写完，不超过 80 字，用过去时；
4. 不写日期、时间（由系统补）。只输出摘要本身，不要任何解释、引号或前缀。"""


def _format_messages(rows) -> str:
    """[(role, content)] → 带说话人标记的文本（时间升序传入）。

    与 extract.py 同款说话人标记（冷冷/小汐）；总量超 _SUMMARY_CHARS
    时从最旧开始丢（保留最新段——摘要反映的是这场对话的走向）。
    """
    lines = []
    for role, content in rows:
        text = (content or "").strip()
        if not text:
            continue
        lines.append(f"冷冷：{text}" if role == "user" else f"小汐：{text}")
    total = sum(len(line) for line in lines)
    while total > _SUMMARY_CHARS and len(lines) > 1:
        dropped = lines.pop(0)
        total -= len(dropped)
    return "\n".join(lines)


async def _find_eligible(conn, now: datetime.datetime) -> list[tuple[str, str, int]]:
    """返回 [(session_id, updated_at, msg_count)]：合格待摘会话，最旧优先。

    合格：is_era=0 + 消息数 ≥ _MIN_MESSAGES + 无既有摘要 + 非当前活跃
    会话（life_state.active_session_id 离线即清空，:active 判参数自身）+
    空闲 ≥ _IDLE_HOURS（updated_at ≤ now − _IDLE_HOURS）。LIMIT 上限。
    """
    active = None
    cursor = await conn.execute(
        "SELECT active_session_id FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    if row:
        active = row[0]
    cutoff = (now - datetime.timedelta(hours=_IDLE_HOURS)).strftime(_TIME_FMT)
    cursor = await conn.execute(
        """SELECT s.id, s.updated_at, COUNT(m.id) AS n
           FROM chat_sessions s
           JOIN chat_messages m ON m.session_id = s.id
           WHERE s.is_era = 0
             AND (:active IS NULL OR s.id != :active)
             AND NOT EXISTS (
                 SELECT 1 FROM session_summaries ss
                 WHERE ss.session_id = s.id
             )
           GROUP BY s.id
           HAVING COUNT(m.id) >= :min_msgs AND s.updated_at <= :cutoff
           ORDER BY s.updated_at ASC
           LIMIT :max_run""",
        {"active": active, "min_msgs": _MIN_MESSAGES,
         "cutoff": cutoff, "max_run": _MAX_PER_RUN},
    )
    return [(r[0], r[1], r[2]) for r in await cursor.fetchall()]


async def _fetch_session_messages(conn, session_id: str) -> list[tuple[str, str]]:
    """读会话尾部 ≤ _SUMMARY_MESSAGE_CAP 条消息（时间升序）。"""
    cursor = await conn.execute(
        "SELECT role, content FROM chat_messages "
        "WHERE session_id = ? ORDER BY id DESC LIMIT ?",
        (session_id, _SUMMARY_MESSAGE_CAP),
    )
    return list(reversed(await cursor.fetchall()))


async def _summarize_one(conn, session_id: str) -> str | None:
    """单个会话摘一条摘要；失败返回 None（调用方不写库、下轮重试）。"""
    rows = await _fetch_session_messages(conn, session_id)
    if not rows:
        return None
    llm = _create_llm()
    try:
        response = await llm.ainvoke([
            SystemMessage(content=_SUMMARIZE_SYSTEM),
            HumanMessage(content=_format_messages(rows)),
        ])
    except Exception as e:
        log.warning("会话摘要：LLM 调用失败（sid=%s），跳过：%s", session_id, e)
        return None
    summary = _coerce_content(
        response.content if hasattr(response, "content") else str(response)
    ).strip()
    if not summary:
        return None
    return summary[:_SUMMARY_MAX_LEN]


async def summarize_closed_sessions(conn=None) -> dict:
    """主入口（心跳维护新段）。返回 {"summarized": n, "skipped": n}。"""
    if conn is None:
        conn = await get_db()
    now = datetime.datetime.now(_HKT)

    eligible = await _find_eligible(conn, now)
    summarized = 0
    skipped = 0
    for session_id, ended_at, msg_count in eligible:
        summary = await _summarize_one(conn, session_id)
        if summary is None:
            skipped += 1
            continue
        async with get_write_lock():
            await conn.execute(
                """INSERT INTO session_summaries
                   (session_id, summary, msg_count, ended_at, created_at)
                   VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(session_id) DO UPDATE SET
                       summary = excluded.summary,
                       msg_count = excluded.msg_count,
                       ended_at = excluded.ended_at,
                       created_at = excluded.created_at""",
                (session_id, summary, msg_count, ended_at,
                 now.strftime(_TIME_FMT)),
            )
            await conn.commit()
        summarized += 1
    if summarized or skipped:
        log.info("会话摘要：生成 %d 条（跳过 %d 个）", summarized, skipped)
    return {"summarized": summarized, "skipped": skipped}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_summarize.py -v`
Expected: PASS（10 passed）

- [ ] **Step 5: Commit**

```bash
git add backend/memory/summarize.py backend/tests/test_summarize.py
git commit -m "feat(memory): 会话脉络摘要生成——summarize_closed_sessions（P1-a）"
```

---

### Task 3: heartbeat 接线 — _periodic_maintenance 新增段

**Files:**
- Modify: `backend/heartbeat.py`（`_periodic_maintenance` 末尾，consolidate 段之后）
- Modify: `backend/tests/test_heartbeat.py`（新增接线测试）

**Interfaces:**
- Consumes: `memory.summarize.summarize_closed_sessions`（Task 2 产出，签名 `async def (conn=None) -> dict`）。
- Produces: 无新对外接口（心跳侧效果）。

- [ ] **Step 1: 写失败测试**

在 `backend/tests/test_heartbeat.py` 末尾追加：

```python
# ── session summary 段接线（P1-a）────────────────────────────


async def test_periodic_maintenance_runs_summarize_segment(tmp_db, monkeypatch):
    """机制钉死：_periodic_maintenance 触发 summarize_closed_sessions。

    其余重段全部 no-op（隔离本用例只管接线），summarize 用 spy 记录
    调用；lazy import 使 monkeypatch `memory.summarize` 模块属性即可。
    """
    import heartbeat as hb
    from database import core as _core
    from memory import consolidate as _mc
    from memory import extract as _extract
    from memory import manager as _mm
    from memory import summarize as _msum

    async def _noop(*a, **kw):
        pass

    monkeypatch.setattr(_core, "backup_database", _noop)
    monkeypatch.setattr(_core, "prune_checkpoints", _noop)
    monkeypatch.setattr(_extract, "extract_new_memories", _noop)
    monkeypatch.setattr(_extract, "check_and_report_memory_health", _noop)
    monkeypatch.setattr(_mm, "run_status_decay", _noop)
    monkeypatch.setattr(_mc, "consolidate_memories", _noop)

    calls = []
    async def fake_summarize(conn=None):
        calls.append(conn)
        return {"summarized": 0, "skipped": 0}
    monkeypatch.setattr(_msum, "summarize_closed_sessions", fake_summarize)

    await hb._periodic_maintenance()

    assert len(calls) == 1
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat.py -k periodic_maintenance_runs_summarize -v`
Expected: FAIL — `assert 0 == 1`（未接线，spy 没被调用）

- [ ] **Step 3: 实现接线**

`backend/heartbeat.py` 的 `_periodic_maintenance`，在 consolidate 段之后（第 290 行 `log.error("记忆周期整合失败...")` 块之后）新增：

```python
    # 会话脉络摘要（P1-a）：对"已结束且够长"的普通会话生成一句话
    # 摘要，供叙事注入（ZifaMem L2 补齐）。独立段，失败隔离
    try:
        from memory.summarize import summarize_closed_sessions
        await summarize_closed_sessions()
    except Exception as e:
        log.error("会话摘要生成失败：%s", e)
```

同时，在 `backend/tests/test_heartbeat.py` 的 `_run_heartbeat_at` 助手（约第 1145 行，`extract_new_memories` noop 之后）追加 `summarize` noop——**本任务的关键防污染**：`_run_heartbeat_at` 是几乎所有心跳测试的共用路径，它触发 `_periodic_maintenance`（heartbeat.py:207），而 `summarize_closed_sessions(conn=None)` 走 `database.core.get_db()` **真实全局库、且无节流**——真实生产库一旦含合格会话（≥15 条 + 空闲 6h）会对真实会话调真实 LLM 并写 `session_summaries`（测试污染，违背 sha256 零污染纪律）。noop 对齐 `extract_new_memories` 的既有处理（由 test_summarize.py 带 mock 覆盖）：

```python
    import memory.summarize as _msum
    monkeypatch.setattr(_msum, "summarize_closed_sessions", _noop)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat.py -k periodic_maintenance_runs_summarize -v`
Expected: PASS

**注意**：`test_heartbeat_triggers_global_db_connection`（test_heartbeat.py:1387）同样走 `_run_heartbeat_at` 助手 → 摘要段已被上述 noop 盖住，不碰真实库（其自身对 consolidate/profile 的 `_create_llm` 打桩保持不变）。若漏加助手 noop，该测试会因真实库存在合格会话而触发真实 LLM 调用 + 写库，污染生产数据。

- [ ] **Step 5: Commit**

```bash
git add backend/heartbeat.py backend/tests/test_heartbeat.py
git commit -m "feat(heartbeat): 维护周期接入会话摘要段（P1-a）"
```

---

### Task 4: 叙事注入 — narrative.py 加"最近的谈话"块

**Files:**
- Modify: `backend/memory/narrative.py`
- Modify: `backend/tests/test_narrative.py`

**Interfaces:**
- Consumes: `session_summaries` 表（Task 1）、`NarrativeBuilder._manager._db`（既有）。
- Produces: `NarrativeBuilder._summaries_block(limit=3) -> str`；模块私有 `_time_anchor(ended_at) -> str`、`_join_blocks(a, b) -> str`；`build()` 在画像后、常规记忆前插入摘要块，且早退路径（total==0 / memories 空）含摘要块。

- [ ] **Step 1: 写失败测试**

在 `backend/tests/test_narrative.py` 末尾追加：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_narrative.py -k summaries -v`
Expected: FAIL — `AttributeError: 'NarrativeBuilder' object has no attribute '_summaries_block'`（首个断言处）

- [ ] **Step 3: 实现 narrative.py**

`backend/memory/narrative.py` 改动：

1. 在 `_MAX_ITEM_LEN = 200` 之后加：

```python
# 摘要单行截断上限（P1-a）：摘要块每行独立截断，防伪造结构与超长。
_SUMMARY_LINE_MAX = 160


def _time_anchor(ended_at: str) -> str:
    """'2026-08-11 22:00' → '8月11日晚上'。时段按 HKT 小时粗分。"""
    if len(ended_at) < 13:
        return ""
    m, d, h = int(ended_at[5:7]), int(ended_at[8:10]), int(ended_at[11:13])
    if h < 5:
        period = "凌晨"
    elif h < 12:
        period = "早上"
    elif h < 14:
        period = "中午"
    elif h < 18:
        period = "下午"
    elif h < 22:
        period = "晚上"
    else:
        period = "深夜"
    return f"{m}月{d}日{period}"


def _join_blocks(a: str, b: str) -> str:
    """空块省略：两个叙事块拼一块，全空返回空串。"""
    return "\n\n".join(p for p in (a, b) if p)
```

2. 在 `NarrativeBuilder` 类内（`build` 方法前）加：

```python
    async def _summaries_block(self, limit: int = 3) -> str:
        """最近 limit 条会话摘要（按谈话新鲜度倒序），渲染为叙事块。

        P1-a：session summary 层。独立块、不占 max_items 记忆席位。
        无摘要返回 ""（原语义零影响）。每行压平换行 + 截断（同
        profile 纪律）；时间锚由代码从 ended_at 渲染，LLM 不写日期。
        """
        if self._manager is None:
            return ""
        cursor = await self._manager._db.execute(
            "SELECT session_id, summary, ended_at FROM session_summaries "
            "ORDER BY ended_at DESC LIMIT ?",
            (limit,),
        )
        rows = await cursor.fetchall()
        if not rows:
            return ""
        lines = ["## 最近的谈话", ""]
        for _sid, summary, ended_at in rows:
            content = " ".join(summary.split())
            if len(content) > _SUMMARY_LINE_MAX:
                content = content[:_SUMMARY_LINE_MAX] + "……"
            lines.append(f"- {_time_anchor(ended_at)}：{content}")
        return "\n".join(lines)
```

3. 改 `build()` 的三处：
   - `profile_text` 之后、`total = await self._manager.count()` 之前，加 `summaries_text = await self._summaries_block()`；
   - `if total == 0:` 的 `return profile_text` 改为 `return _join_blocks(profile_text, summaries_text)`；
   - `if not memories:` 的 `return profile_text` 改为 `return _join_blocks(profile_text, summaries_text)`；
   - 主渲染段，在 `if profile_text:` 块之后加：

```python
        if summaries_text:
            lines.append(summaries_text)
            lines.append("")
```

（现有 `if profile_text:` 块在 `lines.append("")` 之后紧接 `lines.append("以下是小汐记住的关于冷冷的事情：")`，摘要块插在两者之间，即"画像 > 摘要 > 常规记忆"。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_narrative.py tests/test_profile.py -q`
Expected: PASS（新增 4 个 + 既有叙事/画像测试不破）

- [ ] **Step 5: Commit**

```bash
git add backend/memory/narrative.py backend/tests/test_narrative.py
git commit -m "feat(memory): 叙事注入'最近的谈话'摘要块（P1-a）"
```

---

### Task 5: delete_session 级联清理 session_summaries

**Files:**
- Modify: `backend/memory/chat_archive.py`（`delete_session`）
- Modify: `backend/tests/test_chat_archive.py`

**Interfaces:**
- Consumes: `session_summaries` 表（Task 1）。
- Produces: `delete_session(session_id) -> bool` 同时删除该会话的摘要行。

- [ ] **Step 1: 写失败测试**

在 `backend/tests/test_chat_archive.py` 的级联删除测试区追加：

```python
async def test_delete_session_removes_summary(archive_db):
    """级联：删除会话同时删除其 session_summaries 行（P1-a）。"""
    await chat_archive.record_user_message("s-casc", "第一条")
    await chat_archive.record_assistant_message("s-casc", "回你~")
    await archive_db.execute(
        "INSERT INTO session_summaries "
        "(session_id, summary, msg_count, ended_at, created_at) "
        "VALUES ('s-casc', '摘要', 2, '2026-08-11 22:00', '2026-08-11 22:00')"
    )
    await archive_db.commit()

    deleted = await chat_archive.delete_session("s-casc")

    assert deleted is True
    cursor = await archive_db.execute(
        "SELECT COUNT(*) FROM session_summaries WHERE session_id = 's-casc'"
    )
    assert (await cursor.fetchone())[0] == 0
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_chat_archive.py -k delete_session_removes_summary -v`
Expected: FAIL — 断言 `0 == 0` 处实际 `1 == 0`（摘要行残留）

- [ ] **Step 3: 实现级联**

`backend/memory/chat_archive.py` 的 `delete_session`，在 `DELETE FROM proactive_messages ...` 之后加：

```python
        await conn.execute(
            "DELETE FROM session_summaries WHERE session_id = ?", (session_id,)
        )
```

（与 proactive_messages 同款手动级联——本库无 FK CASCADE。级联范围注释同步更新为含 session_summaries。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_chat_archive.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/memory/chat_archive.py backend/tests/test_chat_archive.py
git commit -m "fix(memory): delete_session 级联清理 session_summaries（P1-a）"
```

---

### Task 6: 文档同步 — 路线图勾选 + CHANGELOG 条目

**Files:**
- Modify: `docs/OPTIMIZATION_ROADMAP_2026-08-12.md`
- Modify: `docs/CHANGELOG.md`

- [ ] **Step 1: 更新路线图**

在 `docs/OPTIMIZATION_ROADMAP_2026-08-12.md` 的 P1 段落（`P1-4` 之后）新增并勾选：

```markdown
- [x] **P1-a. session summary 层** ✅（2026-08-13，设计 `docs/superpowers/specs/2026-08-13-session-summary-design.md`）：
      - `session_summaries` 表（迁移 v23）+ `memory/summarize.py`（心跳维护段，
        合格判定：is_era=0 + ≥15 条 + 空闲 ≥6h + 非活跃 + 无既有摘要，单轮 ≤3）
      - 叙事注入「最近的谈话」块（画像后、常规记忆前，近 3 段，代码补时间锚）
      - 隔离零改动：直接 SQL 读 chat_messages（extract.py 先例），不 import chat_archive
```

- [ ] **Step 2: 更新 CHANGELOG**

`docs/CHANGELOG.md` 顶部加一条（遵循既有过渡说明纪律）：

```markdown
## 2026-08-13 — session summary 层（P1-a）
- 新增 `session_summaries` 表（迁移 v23）：长对话的一句话脉络摘要。
- 心跳维护周期新段 `summarize_closed_sessions`：合格会话（≥15 条、空闲 ≥6h、非活跃、无摘要）由 DeepSeek 摘一句话，单轮 ≤3。
- 叙事注入「最近的谈话」块（画像后、常规记忆前），时间锚由代码补。
- 设计文档：`docs/superpowers/specs/2026-08-13-session-summary-design.md`。
- 过渡说明：摘要注入改变每轮 LLM 上下文，轻微扰动吃瓜/初识档观察基线（0a-0c 收口评估时注意）。
```

- [ ] **Step 3: Commit**

```bash
git add docs/OPTIMIZATION_ROADMAP_2026-08-12.md docs/CHANGELOG.md
git commit -m "docs: session summary 路线图勾选 + CHANGELOG 条目（P1-a）"
```

---

### Task 7: 全量验证 + 冒烟（收尾门禁）

**Files:**
- 无代码改动（除非发现缺陷）。

- [ ] **Step 1: 全量后端测试**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q`
Expected: 基线 897 + 新增（迁移 3 + summarize 10 + narrative 4 + heartbeat 1 + cascade 1 = 19）≈ **916 passed / 1 skip**，exit 0。任何失败先定位修复再继续。

- [ ] **Step 2: 静态检查**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m ruff check memory/summarize.py memory/narrative.py heartbeat.py memory/chat_archive.py database/migrations.py`
Expected: exit 0，无告警。

- [ ] **Step 3: 生产库零污染核验**

Run（记录改动前后双库 sha256，改动必须为零）：
```bash
cd /e/小汐sonetto && sha256sum backend/data/userdata.db backend/data/checkpoints.db > /tmp/sha.before
cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_summarize.py -q
cd /e/小汐sonetto && sha256sum backend/data/userdata.db backend/data/checkpoints.db > /tmp/sha.after
diff /tmp/sha.before /tmp/sha.after && echo "ZERO POLLUTION"
```
Expected: `ZERO POLLUTION`（测试走 tmp_db，不碰生产库）。

- [ ] **Step 4: :8001 副本冒烟（可选，含迁移链）**

起 `:8001` 临时副本（副本库 + 假 key，见 `docs/PERSONA_DEEP_DIVE_2026-08-10.md` §8 纪律），用副本库种一个 ≥15 条消息、6h 前结束的会话 → 跑 `_periodic_maintenance` 的摘要段 → 断言 `session_summaries` 落行 + 下一次叙事注入含「最近的谈话」块。生产库 sha256 前后一致。

- [ ] **Step 5: 汇报 + 交接**

汇报：测试基线、零污染、冒烟结论、观察基线扰动提醒（0a-0c 收口注意摘要注入变量）。未 push（等用户拍板部署）。
