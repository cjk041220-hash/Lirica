# 记忆库 schema v17 实施计划（O1 / O2 / I1 / O3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 2026-08-03 记忆库诊断（`docs/MEMORY_GAPS_2026-08-03.md`）的四个工作项，作为 schema v17：
- **O1** 双时钟拆分：`created_at` 严格不可变 + 新增 `last_mention_ts`（add/update/hit 统一刷新），衰减与 90 天归档统一基于 `last_mention_ts`；
- **I1** importance 因子：提取时 LLM 顺带打 1-10 分，存 `importance` 列，进 `_relevance_score`（权重 0.3 档）；
- **O2** `_choose_mood` 的 `talked` 信号改读 `chat_messages`（真实对话时间），不再读 `recent(10)` 记忆（纯闲聊不触发）；
- **O3** superseded 历史链上限（每记忆保留 5 代，更旧合并为一行 digest）+ `export_json` 加 `include_superseded` 开关。

**Architecture:** 全部改动在 `backend`（Python）。一个 v17 迁移（加 `last_mention_ts` + `importance` 两列，同步 init_db / conftest 的 DDL）；`manager.py` 是改动主体（add / update_content / _count_hits / recall / _relevance_score / apply_status_decay / old_memories / recent / check_memory_health / export_json / history / 新增 compress_history、has_recent_chat）；`extract.py` 走 I1（prompt + dispatch）；`heartbeat_moods.py` 走 O2（talked 信号源）；`consolidate.py` 走 O3（合并后压缩链）；`api/routes/chat.py` 导出路由加开关。不动前端（新列经 `SELECT *` 下发，前端忽略未知字段）、不动 SSE 协议、不动 LLM 主对话。

**Tech Stack:** Python 3.13 + aiosqlite + pytest（`backend/tests/`，conftest 的 `tmp_db` / `memory_mgr` fixture 共享 init_db 的 DDL 常量）。

## Global Constraints

- 改完必须全量 pytest 绿：`cd backend && .venv/Scripts/python.exe -m pytest -q`（现有 40+ 个测试文件不能被破坏）。
- 迁移 v17 幂等（原地重跑不报错、结果不变）；`SCHEMA_VERSION` 16→17。
- 三处 DDL 永不漂移：`database/core.py` init_db / `tests/conftest.py` / 迁移（v17 用 ALTER 补齐，新库由 init_db 的 CREATE TABLE 直接含新列）。
- **不引入新依赖**；不改 `remember` 工具签名（I1 只走提取路径，agentic 写路径 importance 默认 5——文档明示「提取时 LLM 顺带打分」）。
- 本仓库后台 agent 不可靠（见项目记忆，2026-08-02/03 四批全部失联）——**本计划用内联执行，不派后台 agent**。
- O1 核心晋升年龄闸（`created_at <= core_cutoff`，存在 ≥30 天）**保持 created_at**——「爆发一周即晋升」的年龄闸语义是「存在多久」，不是「多久没提」；只有归档/衰减/久远翻旧改 `last_mention_ts`。
- hit 刷新 `last_mention_ts` 是文档明确的设计（add/update/hit 统一刷新）：被浮现的记忆衰减钟保持新鲜，自限（停止浮现即衰减），与 hit_count「衰减弱信号」语义一致；不破坏 access_count / last_used_at 的 passive 纪律（那两个计数器仍不被动刷新）。

---

### Task 1: O1 双时钟拆分 — created_at 不可变 + last_mention_ts（schema v17）

**Files:**
- Modify: `backend/database/migrations.py`（SCHEMA_VERSION 17 + `_migrate_v17`）
- Modify: `backend/database/core.py`（init_db 的 memories DDL 加新列）
- Modify: `backend/tests/conftest.py`（tmp_db 的 memories DDL 加新列）
- Modify: `backend/memory/manager.py`（add / update_content / _count_hits / recall / _relevance_score / apply_status_decay / old_memories / recent / check_memory_health）
- Test: `backend/tests/test_migrations_v17.py`（新建）
- Test: `backend/tests/test_memory_manager.py` / `backend/tests/test_memory_decay.py` / `backend/tests/test_init_db_upgrade.py`

**Interfaces:**
- Consumes: 现有 `created_at` / `last_used_at` / `updated_at` / `hit_count` / `last_hit_date`。
- Produces: `memories.last_mention_ts`（TEXT，可空，缺失回退 created_at）、`memories.importance`（INTEGER DEFAULT 5，Task 2 用）。

**背景：** 诊断 O1：`add` 去重刷新 `created_at`（`manager.py:214-225` 的 ON CONFLICT），`update_content` 保留——同一衰减体系两套矛盾时钟。被反复 add 的记忆续命、被反复 update 但未重复 add 的记忆老化。mem0 范式是 `created_at` 不可变 + 访问刷新。修复：`created_at` 从 add 的 ON CONFLICT SET 移除（严格不可变），新增 `last_mention_ts` 由 add/update/active-recall/hit 统一刷新，衰减与归档改看它。

**判定条件推演（为什么现有测试不受影响）：**
- `test_add_dedup_same_category_content`：只断言 id 相同，不检查 created_at ✓
- `test_update_content_refreshes_timestamp`：created_at 保留 + updated_at 刷新——新语义下 created_at 仍保留 ✓
- `test_recall_passive_does_not_update_counters`：只查 access_count / last_used_at，不查 last_mention_ts ✓
- `test_archive_uses_last_mention_ts`（新）：旧代码用 created_at 归档 → 新测试失败（m1 该留下的被归档）✓

- [ ] **Step 1: 写失败测试**

**A. 迁移测试** `backend/tests/test_migrations_v17.py`（新建，自建 v16 时代裸库，测迁移 SQL 本身）：

```python
"""迁移 v17 回归 — memories.last_mention_ts（O1 双时钟）+ importance（I1 重要性）。

自建 v16 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v17

_V16_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT,
    superseded_by TEXT
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v17.db"))
    await conn.executescript(_V16_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v17_adds_columns(bare_db):
    await _migrate_v17(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "last_mention_ts" in cols
    assert "importance" in cols


async def test_v17_backfills_last_mention_ts_from_latest_clock(bare_db):
    """存量回填：last_mention_ts = MAX(created_at, updated_at, last_used_at)。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, last_used_at, updated_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', '2026-06-01 10:00', '2026-07-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT last_mention_ts FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == "2026-07-01 10:00"  # 取最新（updated_at）
    # 无 updated_at / last_used_at 的行回退 created_at
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m2', '无辅助时间', '其他', '2026-02-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT last_mention_ts FROM memories WHERE id = 'm2'")
    assert (await cursor.fetchone())[0] == "2026-02-01 10:00"


async def test_v17_sets_default_importance(bare_db):
    """存量行 importance 默认 5（LLM 打分前的存量记忆按中性处理）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v17(bare_db)
    cursor = await bare_db.execute("SELECT importance FROM memories WHERE id = 'm1'")
    assert (await cursor.fetchone())[0] == 5


async def test_v17_idempotent(bare_db):
    await _migrate_v17(bare_db)
    await _migrate_v17(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "last_mention_ts" in cols and "importance" in cols
```

**B. manager 行为测试** —— 追加到 `backend/tests/test_memory_manager.py`：

```python
# ── O1 双时钟：created_at 不可变 + last_mention_ts（schema v17）────────


async def test_add_dedup_does_not_refresh_created_at(memory_mgr, tmp_db):
    """O1 核心回归：add 去重刷新 last_mention_ts，绝不刷新 created_at。

    修复前 add 去重把 created_at 刷成现在——被反复 add 的记忆衰减时钟
    被重置，而反复 update 的记忆老化，同一衰减体系两套矛盾时钟。
    """
    first = await memory_mgr.add("喜好", "喜欢吃芒果")
    await tmp_db.execute(
        "UPDATE memories SET created_at = '2026-01-01 10:00', "
        "last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (first["id"],),
    )
    await tmp_db.commit()

    second = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert second["id"] == first["id"]
    assert second["created_at"] == "2026-01-01 10:00"  # 不可变
    assert second["last_mention_ts"] != "2026-01-01 10:00"  # 提及时钟照常刷新


async def test_update_content_refreshes_last_mention_ts(memory_mgr, tmp_db):
    """O1：update 也刷新 last_mention_ts——被反复 update 的记忆不再老化。"""
    saved = await memory_mgr.add("喜好", "爱吃火锅")
    await tmp_db.execute(
        "UPDATE memories SET last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (saved["id"],),
    )
    await tmp_db.commit()

    await memory_mgr.update_content(saved["id"], "不爱吃火锅了")

    cursor = await tmp_db.execute(
        "SELECT created_at, last_mention_ts FROM memories WHERE id = ?",
        (saved["id"],),
    )
    created, last_mention = await cursor.fetchone()
    assert created != "2026-01-01 10:00"  # created_at 是首次创建（今天）
    assert last_mention != "2026-01-01 10:00"  # 覆盖后提及时钟已刷新


def test_relevance_recency_uses_last_mention_ts():
    """O1：衰减时钟是 last_mention_ts 而非 created_at（公式级单测）。"""
    import datetime
    from memory.manager import _relevance_score
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT)
    old = (now - datetime.timedelta(days=100)).strftime("%Y-%m-%d %H:%M")
    today = now.strftime("%Y-%m-%d %H:%M")

    # 100 天前创建、今天提及 → 新鲜（修复前只看 created_at 会认为老透）
    fresh = _relevance_score(
        {"created_at": old, "last_mention_ts": today, "access_count": 0, "importance": 5},
        now, "",
    )
    # 今天创建、100 天前提及 → 老化（修复前会被误判为新鲜）
    stale = _relevance_score(
        {"created_at": today, "last_mention_ts": old, "access_count": 0, "importance": 5},
        now, "",
    )
    assert fresh > stale
```

**C. 衰减/浮现行为测试** —— 追加到 `backend/tests/test_memory_decay.py`：

```python
# ── O1：衰减与归档统一基于 last_mention_ts（schema v17）──────────────


async def test_archive_uses_last_mention_ts_not_created_at(memory_mgr, tmp_db):
    """O1 归档回归：90 天冷藏看「多久没被提及」，不是「多久前创建」。

    修复前看 created_at——创建于 200 天前、但 50 天前还被 update 过的
    记忆会被误归档（它明明还活着）。
    """
    await _insert_raw(tmp_db, "m1", "老但最近提过", "日常", _days_ago(200))
    await tmp_db.execute(
        "UPDATE memories SET last_mention_ts = ? WHERE id = 'm1'", (_days_ago(50),)
    )
    await _insert_raw(tmp_db, "m2", "老且久不提", "日常", _days_ago(200))
    await tmp_db.execute(
        "UPDATE memories SET last_mention_ts = ? WHERE id = 'm2'", (_days_ago(100),)
    )
    await tmp_db.commit()

    result = await memory_mgr.apply_status_decay()

    assert result["archived"] == 1
    cursor = await tmp_db.execute(
        "SELECT id FROM memories WHERE status = 'archived'"
    )
    assert (await cursor.fetchone())[0] == "m2"


async def test_recent_orders_by_last_mention(memory_mgr, tmp_db):
    """O1 一致：recent() 的「最近」是最近被提及（last_mention_ts），
    不是最近被创建。"""
    await memory_mgr.add("日常", "旧但最近活跃")
    await memory_mgr.add("日常", "新但从未再提")
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=100)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? "
        "WHERE content = '新但从未再提'",
        (old, old),
    )
    await tmp_db.commit()

    result = await memory_mgr.recent(limit=5)
    # ASC 正序（最旧在前）：按 last_mention，「新但从未再提」100 天前 → 最前
    assert result[0]["content"] == "新但从未再提"

    # 主动想起一次 → 刷新 last_mention_ts → 浮到 recent 末尾（最新）
    await memory_mgr.recall(limit=1, passive=False)
    result = await memory_mgr.recent(limit=5)
    assert result[-1]["content"] == "新但从未再提"


async def test_health_uses_updated_at_as_write_clock(memory_mgr, tmp_db):
    """O1 连带：created_at 不可变后，健康检查的「距上次写入」改看
    updated_at（add/update 刷新）——只 update 不新增的记忆系统不该误报停滞。"""
    saved = await memory_mgr.add("日常", "一条记忆")
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, updated_at = ? WHERE id = ?",
        (old, old, saved["id"]),
    )
    await tmp_db.commit()

    await memory_mgr.update_content(saved["id"], "新内容")  # updated_at 刷到今天

    health = await memory_mgr.check_memory_health()
    assert health["days_since_write"] is not None
    assert health["days_since_write"] < 7  # 不报停滞
```

**D. 升级链路** —— `backend/tests/test_init_db_upgrade.py` 的 `test_init_db_upgrades_legacy_v1_database` 追加断言：

```python
        assert "last_mention_ts" in columns  # v17 O1 统一提及时钟
        assert "importance" in columns       # v17 I1 重要性打分
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v17.py tests/test_memory_manager.py tests/test_memory_decay.py tests/test_init_db_upgrade.py -q`
Expected: 新用例全部 FAIL——`last_mention_ts` 列不存在（conftest 未加列）、`add` 仍刷新 created_at、`_relevance_score` 仍用 created_at、归档仍看 created_at、健康检查仍看 created_at。

- [ ] **Step 3: 实现**

**3a. 迁移** `backend/database/migrations.py`：

`SCHEMA_VERSION` 16 → 17，并在版本注释块追加：

```python
# v17: 记忆双时钟 + 重要性 — memories 新增 last_mention_ts（O1：统一
#     提及时钟，add/update/hit 刷新；created_at 自此严格不可变）与
#     importance（I1：提取时 LLM 打 1-10 分，进相关性排序权重 0.3 档）。
#     存量回填 last_mention_ts = MAX(created_at, updated_at, last_used_at)，
#     importance 默认 5（中性）。
```

在 `_migrations[16] = _migrate_v16` 之后追加：

```python
# ── v17 迁移：双时钟 + 重要性 ─────────────────────────────────


async def _migrate_v17(conn: aiosqlite.Connection) -> None:
    """v17: memories.last_mention_ts（统一提及时钟）+ importance（重要性打分）。

    背景（2026-08-03 记忆库诊断 O1/I1）：
    - O1：add 去重刷新 created_at、update_content 保留——同一衰减体系
      两套矛盾时钟。被反复 add 的记忆续命、被反复 update 但未重复 add
      的记忆老化。拆为 created_at 严格不可变（add 不再刷新）+ 新列
      last_mention_ts（add/update/active-recall/hit 统一刷新），衰减与
      90 天归档统一基于 last_mention_ts（mem0 三时间戳范式）；
    - I1：排序缺 importance 因子。提取时 LLM 顺带打 1-10 分存本列，
      进 _relevance_score（权重 0.3 档）。

    存量回填：last_mention_ts 取 MAX(created_at, updated_at, last_used_at)
    ——三者任一都是「提及/写入」信号，取最新即最接近真实最近提及的估计
    （幂等：重跑时 MAX 结果不变）。importance 默认 5——老记忆无打分
    记录，按中性处理。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "last_mention_ts" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN last_mention_ts TEXT")
    if "importance" not in mem_cols:
        await conn.execute(
            "ALTER TABLE memories ADD COLUMN importance INTEGER DEFAULT 5"
        )

    await conn.execute(
        """UPDATE memories SET last_mention_ts = MAX(
               created_at,
               COALESCE(updated_at, created_at),
               COALESCE(last_used_at, created_at))"""
    )
    await conn.execute("UPDATE memories SET importance = 5 WHERE importance IS NULL")

    await conn.commit()
    log.info("迁移 v17 完成：memories.last_mention_ts + importance（双时钟 + 重要性）")


_migrations[17] = _migrate_v17
```

**3b. 新库 DDL** `backend/database/core.py` init_db 的 memories 建表（116-130 行）加两列：

```python
        CREATE TABLE IF NOT EXISTS memories (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            category        TEXT DEFAULT '其他',
            created_at      TEXT NOT NULL,
            last_used_at    TEXT,
            access_count    INTEGER DEFAULT 0,
            hit_count       INTEGER DEFAULT 0,
            last_hit_date   TEXT,
            status          TEXT DEFAULT 'active',
            updated_at      TEXT,
            source          TEXT,
            prev_content    TEXT,
            superseded_by   TEXT,
            last_mention_ts TEXT,
            importance      INTEGER DEFAULT 5
        );
```

**3c. 测试夹具** `backend/tests/conftest.py` 的 memories 建表（14-22 行）同样加两列（`last_mention_ts TEXT` 与 `importance INTEGER DEFAULT 5`）。

**3d. manager.py 双时钟语义**：

- `add`（182-236 行）：签名加 `importance: int = 5`；INSERT 加 `last_mention_ts, importance`；ON CONFLICT SET **移除** `created_at = excluded.created_at`，改为 `last_mention_ts = excluded.last_mention_ts`；docstring 更新去重规则。同时加 `_coerce_importance` 辅助函数（Task 2 复用，一并实现）：

```python
def _coerce_importance(value) -> int:
    """importance 归一化到 1-10（LLM 手滑/缺失兜底为 5，I1）。"""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return 5
    return max(1, min(10, n))
```

```python
        content = _cap_content(content, "add")
        importance = _coerce_importance(importance)
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, last_used_at, access_count,
                status, updated_at, source, last_mention_ts, importance)
               VALUES (?, ?, ?, ?, ?, 0, 'active', ?, ?, ?, ?)
               ON CONFLICT(category, content) WHERE status IN ('active', 'core')
               DO UPDATE SET
                   last_used_at = excluded.last_used_at,
                   updated_at = excluded.updated_at,
                   last_mention_ts = excluded.last_mention_ts""",
            (mem_id, content, category, now, now, now, source, now, importance),
        )
```

- `update_content`（279-285 行）的 UPDATE 加一列：

```python
        cursor = await self._db.execute(
            """UPDATE memories
               SET content = ?, updated_at = ?, prev_content = ?, last_used_at = ?,
                   last_mention_ts = ?   -- O1：覆盖也是一次提及，续命衰减钟
               WHERE id = ? AND status IN ('active', 'core')""",
            (new_content, now, old_content, now, now, memory_id),
        )
```

- `_count_hits`（171-178 行）的 UPDATE 加一列（hit = 被浮现也算提及）：

```python
        await self._db.execute(
            f"""UPDATE memories
                SET hit_count = hit_count + 1, last_hit_date = ?,
                    last_mention_ts = ?   -- O1：被浮现也是一次提及，续命衰减钟
                WHERE id IN ({placeholders})
                  AND COALESCE(last_hit_date, '') != ?""",
            (today, _now(), *ids, today),
        )
```

- `recall` 主动分支 UPDATE（476-487 行）加一列：

```python
            await self._db.execute(
                f"""UPDATE memories
                    SET last_used_at = ?,
                        access_count = access_count + 1,
                        hit_count = hit_count + CASE
                            WHEN COALESCE(last_hit_date, '') != ? THEN 1 ELSE 0 END,
                        last_hit_date = CASE
                            WHEN COALESCE(last_hit_date, '') != ? THEN ?
                            ELSE last_hit_date END,
                        last_mention_ts = ?   -- O1：主动想起也是提及
                    WHERE id IN ({placeholders})""",
                (_now(), today, today, today, _now(), *ids),
            )
```

- `_relevance_score`（97-132 行）：recency 改看 `last_mention_ts`（缺失回退 created_at），加 importance 因子：

```python
    # 时间衰减（O1：基于 last_mention_ts，缺失回退 created_at——
    # 被 add/update/hit 过的记忆续命，created_at 不再兼职衰减时钟）
    last_mention = memory.get("last_mention_ts") or memory.get("created_at")
    days_old = max(0, (now - _parse_time(last_mention)).total_seconds() / 86400)
    recency = math.exp(-0.1 * days_old)
```

```python
    # 重要性（I1：LLM 提取时打分 1-10，权重 0.3 档——与 usage_recency 同档）
    importance_boost = _coerce_importance(memory.get("importance")) * 0.03
```

…并把 return 改为 `recency + access_boost + usage_recency + importance_boost + topic`，docstring 四因子 → 五因子。

- `apply_status_decay`（641-667 行）：归档条件 `created_at <= ?` → `COALESCE(last_mention_ts, created_at) <= ?`；**晋升条件保持 created_at**（年龄闸语义是「存在多久」）：

```python
        cursor = await self._db.execute(
            f"""UPDATE memories SET status = 'archived'
                WHERE status = 'active'
                  AND (access_count + hit_count) <= ?
                  AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
                  AND category NOT IN ({exempt})""",
            (_ARCHIVE_MAX_ACCESSES, archive_cutoff, *_ARCHIVE_EXEMPT_CATEGORIES),
        )
```

- `old_memories`（543-562 行）：两处 `created_at <= ?` → `COALESCE(last_mention_ts, created_at) <= ?`（久远 = 多久没被提及）。

- `recent`（516-523 行）：ORDER BY 两处 `created_at` → `COALESCE(last_mention_ts, created_at)`。

- `check_memory_health`（684-687 行）：`MAX(created_at)` → `MAX(COALESCE(updated_at, created_at))`（写时钟：created_at 不可变后，add/update 刷新 updated_at，hit 不刷——「距上次写入」该看它）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v17.py tests/test_memory_manager.py tests/test_memory_decay.py tests/test_init_db_upgrade.py -q`
Expected: 全部 PASS（新增迁移测试 4 例 + manager 3 例 + decay 3 例 + 既有用例不破）。

- [ ] **Step 5: 提交**

```bash
git add backend/database/migrations.py backend/database/core.py backend/tests/conftest.py backend/memory/manager.py backend/tests/test_migrations_v17.py backend/tests/test_memory_manager.py backend/tests/test_memory_decay.py backend/tests/test_init_db_upgrade.py
git commit -m "feat(memory): schema v17 双时钟 — last_mention_ts 统一提及时钟，created_at 不可变（O1）"
```

---

### Task 2: I1 importance 因子 — 提取打分 1-10 进相关性排序

**Files:**
- Modify: `backend/memory/manager.py`（`_coerce_importance` 已在 Task 1 实现；`add` 已接 importance）
- Modify: `backend/memory/extract.py`（`_EXTRACT_SYSTEM` prompt + `_dispatch` 传参）
- Test: `backend/tests/test_memory_manager.py` / `backend/tests/test_memory_extract.py`

**Interfaces:**
- Consumes: `add(category, content, source=None, importance=5)`（Task 1 已改签名）。
- Produces: `memories.importance` 由提取 LLM 打分写入；`_relevance_score` 五因子含 importance。

**背景：** 诊断 I1：排序缺 importance 因子——「第一次约会」与「今天吃了饭」同权衰减。Generative Agents 为 `0.5·recency + 0.3·importance + 0.2·relevance`。修复：提取时 LLM 顺带打 1-10 分存 importance 列，进 `_relevance_score`（权重 0.3 档，即 `importance * 0.03`，1→0.03 / 10→0.3）。**agentic remember 工具不加参**（文档明示「提取时打分」，且工具「宁多勿少」哲学不配打分负担）。

- [ ] **Step 1: 写失败测试**

追加到 `backend/tests/test_memory_manager.py`：

```python
# ── I1 importance 因子（schema v17）────────────────────────────


async def test_importance_stored_and_default(memory_mgr):
    """I1：add 显式传 importance 落库；不传默认 5（中性）。"""
    hi = await memory_mgr.add("重要的事", "第一次约会", importance=10)
    assert hi["importance"] == 10
    plain = await memory_mgr.add("日常", "今天吃了饭")
    assert plain["importance"] == 5


async def test_importance_clamped_to_1_10(memory_mgr):
    """I1：LLM 手滑超界 → 钳制回 1-10。"""
    assert (await memory_mgr.add("a", "x", importance=99))["importance"] == 10
    assert (await memory_mgr.add("a", "y", importance=0))["importance"] == 1
    assert (await memory_mgr.add("a", "z", importance="7"))["importance"] == 7
    assert (await memory_mgr.add("a", "w", importance="abc"))["importance"] == 5


async def test_importance_boosts_ranking(memory_mgr, tmp_db):
    """I1 核心回归：同等时近下，高重要性记忆浮到前面。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("喜好", "日常流水", importance=1)   # 先加（同分时 rowid 靠前）
    await memory_mgr.add("喜好", "重要的事", importance=10)
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ?", (old, old)
    )
    await tmp_db.commit()

    result = await memory_mgr.recall(limit=2, passive=True)
    assert result[0]["content"] == "重要的事"  # 修复前同分按 rowid → 日常流水在前
```

追加到 `backend/tests/test_memory_extract.py`：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_memory_extract.py -q`
Expected: `test_importance_boosts_ranking` FAIL（`_relevance_score` 已含 importance？——不，Task 1 已实现 `_coerce_importance` 与 `importance_boost`！）——注意：**Task 1 的 `_relevance_score` 已含 importance 因子**，故本测试在 Task 1 后即绿。真正失败的是 extract 两例（`importance` 字段被 dispatch 忽略 → 默认 5）。

> 说明：Task 1 与 Task 2 都改 `_relevance_score`——Task 1 的 diff 已把 importance 因子一并写入（`_coerce_importance` 是两任务共享的辅助函数）。因此本 Task 的失败测试是 extract 两例（重要性字段未透传）；manager 三例是 Task 1 结构的一部分，在 Task 1 步骤 4 已绿。

- [ ] **Step 3: 实现**

`backend/memory/extract.py`：

**3a. 提取提示词**（63-76 行）格式与规则更新：

```python
_EXTRACT_SYSTEM = """你是小汐的记忆管家。从对话中提炼「值得长期记住的关于冷冷的新事实或变化」，只输出 JSON 数组，不要任何解释。

格式：[{"action": "add|update|noop", "category": "类别", "content": "内容", "memory_id": "可选", "importance": 1-10}]

规则：
1. 只提取确定的事实与变化；猜测、闲聊、寒暄、情绪化的临时说法不要记（noop 最常见）。
2. content 用一句话写完，具体、可独立理解（用「冷冷」而不是「她」）。
3. 身份类事实（姓名、生日、学校、家庭住址、身体固定状况）如果已写在冷冷档案里，不要重复提取。
4. category 优先从现有记忆的类别里选；没有合适的才用：「冷冷喜欢的」「冷冷的信息」「去过的地方」「重要的事」「其他」。
5. action 语义：
   - add：新事实；
   - update：与某条现有记忆矛盾或需合并——填该记忆的 memory_id，content 写新内容（变化就合并成「以前……最近……」一句话）；
   - noop：没有值得长期记住的。
6. 最多 5 条 add、3 条 update；宁缺毋滥。
7. importance 只对 add 生效：1-10 打分（1=日常流水一句，5=一般，10=人生大事/重大变化）。update 与 noop 不填。"""
```

**3b. dispatch 透传 importance**（`_dispatch` 的 add 分支，260-272 行）：

```python
            try:
                await mgr.add(category, content, source=source,
                              importance=act.get("importance"))
                added += 1
            except ValueError:
                skipped += 1
```

（mgr.add 内部 `_coerce_importance` 兜底：None/非法 → 5；超界钳回 1-10。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_memory_extract.py -q`
Expected: 全部 PASS（含 extract 新 2 例 + manager 新 3 例）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/extract.py backend/tests/test_memory_extract.py
git commit -m "feat(memory): importance 因子 — 提取 LLM 打分 1-10 进相关性排序（I1）"
```

---

### Task 3: O2 talked 信号改读真实对话时间

**Files:**
- Modify: `backend/memory/manager.py`（新增 `has_recent_chat`）
- Modify: `backend/heartbeat_moods.py`（`_choose_mood` 的 talked 信号源）
- Test: `backend/tests/test_heartbeat.py`

**Interfaces:**
- Consumes: `chat_messages` 表（`role='user'` 的 `created_at`）。
- Produces: `MemoryManager.has_recent_chat(hours=24, now=None) -> bool`。

**背景：** 诊断 O2：`_choose_mood` 的 talked 信号读 `recent(10)` 记忆（`heartbeat_moods.py:171-175`）——「近 24h 有记忆活动」≈「近 24h 有值得记住的对话」，纯闲聊（无记忆产生）不触发。2026-08-03 心情重构后顺带评估：改从 `chat_messages` 按 `created_at` 判断真实对话时间。失败语义保持 v2 不变：任何信号获取失败 → 保持当前词。

- [ ] **Step 1: 写失败测试**

`backend/tests/test_heartbeat.py` 中改写/新增（替换 769-808 行的两个旧用例）：

```python
async def test_choose_mood_talked_signal_from_chat_messages(tmp_db):
    """O2：talked 信号读 chat_messages（真实对话），不是记忆。"""
    import datetime as dt
    import heartbeat
    from memory.manager import MemoryManager
    from types import SimpleNamespace
    from unittest.mock import patch
    from utils.timezone import HKT

    now = dt.datetime.now(HKT)
    await tmp_db.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s-1', 'user', '刚刚聊过天', ?)",
        (now.strftime("%Y-%m-%d %H:%M"),),
    )
    await tmp_db.commit()
    mgr = MemoryManager(tmp_db)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=now,
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]
    # 记忆库不再被读：access_count 保持 0（旧实现 recent() 会给最近记忆计 hit）
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 0


async def test_choose_mood_no_recent_chat_ok(tmp_db):
    """O2：无对话 / 对话超 24h → 不炸，仍正常采样。"""
    import datetime as dt
    import heartbeat
    from memory.manager import MemoryManager
    from types import SimpleNamespace
    from unittest.mock import patch
    from utils.timezone import HKT

    old = (dt.datetime.now(HKT) - dt.timedelta(hours=25)).strftime("%Y-%m-%d %H:%M")
    await tmp_db.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at) "
        "VALUES ('s-1', 'user', '昨天聊过', ?)",
        (old,),
    )
    await tmp_db.commit()
    mgr = MemoryManager(tmp_db)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.LATE_NIGHT]["moods"]


async def test_choose_mood_chat_query_failure_graceful(tmp_db):
    """O2：has_recent_chat 炸了 → 保持当前词（失败语义同 v2）。"""
    import datetime as dt
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch
    from utils.timezone import HKT

    broken_mgr = SimpleNamespace(has_recent_chat=AsyncMock(side_effect=RuntimeError("db lost")))
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=broken_mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        mood = await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mood in heartbeat.TIME_PERIODS[heartbeat.DAYTIME]["moods"]


async def test_choose_mood_calls_has_recent_chat(tmp_db):
    """O2 接线：talked 信号来自 has_recent_chat（chat_messages 时间），
    不再读 recent(10) 记忆。"""
    import datetime as dt
    import heartbeat
    from memory.manager import MemoryManager
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch
    from utils.timezone import HKT

    mgr = MemoryManager(tmp_db)
    mgr.has_recent_chat = AsyncMock(return_value=True)
    app = SimpleNamespace(state=SimpleNamespace(memory_manager=mgr))
    with patch("utils.events.get_active_events", return_value=[]):
        await heartbeat._choose_mood(
            heartbeat.TIME_PERIODS[heartbeat.DAYTIME],
            app=app, now_dt=dt.datetime.now(HKT),
        )
    assert mgr.has_recent_chat.await_count == 1
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat.py -q`
Expected: 旧用例 `test_choose_mood_memory_failure_graceful`（patch `recent`）仍绿但已过时——新用例 `test_choose_mood_calls_has_recent_chat` FAIL（`mgr.has_recent_chat` 不存在，`_choose_mood` 仍调 `recent`）。

- [ ] **Step 3: 实现**

**3a. manager.py** 查询区（`count` 之后）新增：

```python
    async def has_recent_chat(self, hours: int = 24,
                              now: datetime.datetime | None = None) -> bool:
        """近 hours 小时内是否有真实对话（chat_messages 里 user 消息）。

        O2（2026-08-03 心情重构后评估）：_choose_mood 的 talked 信号
        先前读 recent(10) 记忆——「近 24h 有记忆活动」≈「近 24h 有值得
        记住的对话」，纯闲聊（无记忆产生）不触发。改从 chat_messages
        按 created_at 判断真实对话时间。纯只读，不碰任何计数器。
        """
        now = now or datetime.datetime.now(_HKT)
        cutoff = (now - datetime.timedelta(hours=hours)).strftime(_TIME_FMT)
        cursor = await self._db.execute(
            "SELECT 1 FROM chat_messages "
            "WHERE role = 'user' AND created_at >= ? LIMIT 1",
            (cutoff,),
        )
        return await cursor.fetchone() is not None
```

**3b. heartbeat_moods.py** `_choose_mood`（250-254 行）替换 talked 信号源：

```python
        mgr = getattr(app.state, "memory_manager", None) if app is not None else None
        if mgr is not None:
            # O2：talked 读真实对话（chat_messages 近 24h 有 user 消息），
            # 不再读 recent(10) 记忆——纯闲聊（不产生记忆）也算说过话
            talked = await mgr.has_recent_chat(24, now=now_dt)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat.py -q`
Expected: 全部 PASS（新 4 例 + 既有用例不破）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/heartbeat_moods.py backend/tests/test_heartbeat.py
git commit -m "fix(heartbeat): talked 信号改读真实对话时间，不再看记忆（O2）"
```

---

### Task 4: O3 superseded 链上限 + 导出开关

**Files:**
- Modify: `backend/memory/manager.py`（`_MAX_SUPERSEDED_GENERATIONS`、`update_content` 压缩、新 `compress_history`、`history` 排序、`export_json` 开关、`backup_before_clear`）
- Modify: `backend/memory/consolidate.py`（`_apply_merge` 合并后压缩）
- Modify: `backend/api/routes/chat.py`（导出路由加 `include_superseded`）
- Test: `backend/tests/test_superseded.py` / `backend/tests/test_memory_routes.py`

**Interfaces:**
- Consumes: `superseded_by` 链（v15）。`update_content` 每覆盖一次落一行。
- Produces: `compress_history(memory_id)`（公开方法，链 ≤5 代，更旧合成 digest）；`export_json(include_archived=False, include_superseded=False)`。

**背景：** 诊断 O3：superseded 历史链无上限、不入 JSON 导出——`export_json` 谓词只含 active/core/archived，历史链仅存于整库快照备份。Graphiti 时间图无限保留 + valid_at 时间旅行；mem0 覆盖不留链。修复：每记忆保留 N 代（5），更旧合并为一行 digest；`export_json` 加可选开关。

**压缩语义推演：**
- 每次 `update_content` 落 1 行 superseded → 链长 +1。>5 时合并最旧 `len-4` 行成一行 digest（合并进最旧行，删除其余），链恒 ≤5：`[digest, 最新4代]`。
- 下次溢出时 digest 再与下一旧代合并 → digest 逐步累积（超 `_MAX_CONTENT_LEN` 从旧端截断，起始补「…」）。
- 合并按 `rowid ASC`（插入序）——superseded 行的 created_at 全是本记忆最初创建时间（时序诚实），同值无法排序，必须用插入序。
- consolidate 的 `_apply_merge` 会把兄弟行直接置 superseded（绕过 update_content）→ 也调一次 `compress_history` 纳入上限。

- [ ] **Step 1: 写失败测试**

追加到 `backend/tests/test_superseded.py`：

```python
# ── O3 链上限 + 导出开关（schema v17）─────────────────────────


async def test_history_caps_at_max_generations(memory_mgr, tmp_db):
    """O3：superseded 链保留最新 5 代，更旧的合并为一行 digest。"""
    saved = await memory_mgr.add("冷冷的信息", "说法 0")
    for i in range(1, 7):  # 再覆盖 6 次 → 6 个 superseded 行
        assert await memory_mgr.update_content(saved["id"], f"说法 {i}")

    hist = await memory_mgr.history(saved["id"])
    assert len(hist) <= 5                       # 上限 5（含 digest）
    # digest = 最旧 2 代合并（说法 0、说法 1）
    assert "说法 0" in hist[0]["content"]
    assert "说法 1" in hist[0]["content"]
    # 最新 4 代保留逐行
    assert [h["content"] for h in hist[1:]] == ["说法 2", "说法 3", "说法 4", "说法 5"]
    # 当前内容不丢
    assert (await memory_mgr.all())[0]["content"] == "说法 6"


async def test_history_digest_accumulates_across_overflow(memory_mgr, tmp_db):
    """O3：第二次溢出时 digest 继续累积（说法 0-2 都在 digest 里）。"""
    saved = await memory_mgr.add("冷冷的信息", "说法 0")
    for i in range(1, 8):  # 7 次覆盖 → 两次溢出
        await memory_mgr.update_content(saved["id"], f"说法 {i}")

    hist = await memory_mgr.history(saved["id"])
    assert len(hist) <= 5
    assert "说法 0" in hist[0]["content"]
    assert "说法 1" in hist[0]["content"]
    assert "说法 2" in hist[0]["content"]
    assert [h["content"] for h in hist[1:]] == ["说法 3", "说法 4", "说法 5", "说法 6"]


async def test_export_json_default_excludes_superseded(memory_mgr, tmp_db):
    """O3：默认导出不含 superseded 历史链（与读面一致）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    data = json.loads(await memory_mgr.export_json())
    assert len(data["memories"]) == 1
    assert data["memories"][0]["content"] == "新说法"


async def test_export_json_include_superseded(memory_mgr, tmp_db):
    """O3：include_superseded=True 时历史链可导出（此前只存于整库备份）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    data = json.loads(await memory_mgr.export_json(include_superseded=True))
    contents = {m["content"] for m in data["memories"]}
    assert {"旧说法", "新说法"} <= contents


async def test_backup_before_clear_includes_superseded(memory_mgr, tmp_db, tmp_path):
    """O3：清空前备份含 superseded 历史链（完整快照）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    backup_path = await memory_mgr.backup_before_clear(backup_dir=tmp_path)

    data = json.loads(backup_path.read_text(encoding="utf-8"))
    contents = {m["content"] for m in data["memories"]}
    assert "旧说法" in contents  # superseded 历史行在备份里
```

追加到 `backend/tests/test_memory_routes.py`：

```python
async def test_export_include_superseded(client, tmp_db):
    """O3：导出路由支持 include_superseded=true（历史链可回看）。"""
    mgr = MemoryManager(tmp_db)
    saved = await mgr.add("冷冷的信息", "旧说法")
    await mgr.update_content(saved["id"], "新说法")

    r = await client.get("/api/memories/export", params={"include_superseded": True})

    contents = {m["content"] for m in r.json()["memories"]}
    assert {"旧说法", "新说法"} <= contents
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_superseded.py tests/test_memory_routes.py -q`
Expected: 新用例 FAIL——`history` 无上限（6 行）且无 digest；`export_json` 无 `include_superseded` 参数（TypeError）。

- [ ] **Step 3: 实现**

**3a. manager.py** 常量区（`_MAX_JSON_BACKUPS` 附近）加：

```python
# superseded 历史链上限（O3）：单条记忆保留最新 5 代，更旧合并为一行
# digest——链无上限时历史行与活跃行同量级增长，且导出/备份无谓携带。
_MAX_SUPERSEDED_GENERATIONS = 5
```

**3b. `update_content`**（286 行 commit 前）插入压缩调用：

```python
        await self.compress_history(memory_id)  # O3：链上限压缩（不独立 commit）
        await self._db.commit()
        return cursor.rowcount > 0
```

**3c. 新增 `compress_history`**（`history` 方法之后）：

```python
    async def compress_history(self, memory_id: str) -> None:
        """superseded 链上限：每记忆保留最新 N 代，更旧合并为一行 digest。

        O3（2026-08-03 记忆库诊断）：单条记忆被覆盖几十次时，历史行
        与活跃行同量级增长，导出/备份无谓携带失控。上限 5 代：最新 4 代
        保留逐行，更旧的合并为一行 digest（内容以「；」连接，超长从旧端
        截断、起始补「…」），链恒 ≤5 行。digest 是概念摘要——「这条
        记忆改过很多次」仍可追溯，逐代细节让位于上限。

        合并按 rowid ASC（插入序）：superseded 行的 created_at 全是本
        记忆最初创建时间（时序诚实），同值无法排序，必须用插入序。

        本方法不 commit（调用方统一提交）：update_content 与
        consolidate._apply_merge 都在各自事务内调用。
        """
        cursor = await self._db.execute(
            """SELECT id, content, rowid FROM memories
               WHERE superseded_by = ? AND status = 'superseded'
               ORDER BY rowid ASC""",
            (memory_id,),
        )
        rows = await cursor.fetchall()
        if len(rows) <= _MAX_SUPERSEDED_GENERATIONS:
            return
        keep = _MAX_SUPERSEDED_GENERATIONS - 1  # 末位留给 digest
        to_merge = rows[:-keep]
        digest = "；".join(r[1] for r in to_merge)
        if len(digest) > _MAX_CONTENT_LEN:
            # 超长从旧端截断（保留最新端），起始补省略号标记不完整
            digest = "…" + digest[-(_MAX_CONTENT_LEN - 1):]
        keep_id = to_merge[0][0]  # 合并进最旧行（digest 在那行）
        drop_ids = [r[0] for r in to_merge[1:]]
        await self._db.execute(
            "UPDATE memories SET content = ? WHERE id = ?", (digest, keep_id)
        )
        if drop_ids:
            placeholders = ",".join("?" for _ in drop_ids)
            await self._db.execute(
                f"DELETE FROM memories WHERE id IN ({placeholders})", drop_ids
            )
```

**3d. `history`**（294-301 行）排序加 `rowid ASC`（确定性）：

```python
        cursor = await self._db.execute(
            """SELECT * FROM memories
               WHERE superseded_by = ? AND status = 'superseded'
               ORDER BY created_at ASC, rowid ASC""",
            (memory_id,),
        )
```

**3e. `export_json`**（370-388 行）：

```python
    async def export_json(self, include_archived: bool = False,
                          include_superseded: bool = False) -> str:
        """导出记忆为 JSON 字符串（用于下载）。

        include_archived=False 时只含活跃+core（默认导出语义）；
        True 时连同冷藏的 archived 一并导出（D8：冷库可回看）。
        include_superseded=True 时连同 superseded 历史链一并导出
        （O3：历史链此前只存于整库快照备份，加开关后用户可完整回看
        「这条记忆改过哪些说法」）。
        """
        statuses = ["'active'", "'core'"]
        if include_archived:
            statuses.append("'archived'")
        if include_superseded:
            statuses.append("'superseded'")
        status_filter = f"IN ({', '.join(statuses)})"
        cursor = await self._db.execute(
            f"SELECT * FROM memories WHERE status {status_filter} ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        mems = rows_to_dicts(cursor, rows)
        return json.dumps(
            {"memories": mems, "exported_at": datetime.datetime.now(_HKT).isoformat()},
            ensure_ascii=False,
            indent=2,
        )
```

**3f. `backup_before_clear`**（343 行）：

```python
        data = await self.export_json(include_archived=True, include_superseded=True)
```

**3g. consolidate.py `_apply_merge`**（127-132 行，兄弟行置 superseded 后、commit 前）：

```python
    for other in others:
        await conn.execute(
            "UPDATE memories SET status = 'superseded', superseded_by = ? WHERE id = ?",
            (survivor[0], other[0]),
        )
    await mgr.compress_history(survivor[0])  # O3：合并兄弟行也纳入链上限
    await conn.commit()
    return True
```

**3h. chat.py 导出路由**（553-569 行）：

```python
@router.get("/memories/export")
async def export_memories(
    request: Request,
    include_archived: bool = Query(False, description="是否包含已归档（冷藏）记忆"),
    include_superseded: bool = Query(False, description="是否包含已被覆盖的历史说法（superseded 历史链）"),
):
    """导出记忆为 JSON。include_archived=true 时含冷藏记忆（D8：冷库可回看）；
    include_superseded=true 时含覆盖历史链（O3：此前只存于整库快照备份）。"""
    mem = getattr(request.app.state, "memory_manager", None)
    if mem is None:
        raise HTTPException(503, "记忆系统未就绪")
    data = await mem.export_json(include_archived=include_archived,
                                 include_superseded=include_superseded)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_superseded.py tests/test_memory_routes.py tests/test_consolidate.py -q`
Expected: 全部 PASS（新 6 例 + 既有 superseded 6 例 + routes 既有 + consolidate 既有不破）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/memory/consolidate.py backend/api/routes/chat.py backend/tests/test_superseded.py backend/tests/test_memory_routes.py
git commit -m "feat(memory): superseded 链上限 5 代 + 导出开关（O3）"
```

---

## Self-Review

**1. Spec coverage（对照 MEMORY_GAPS 表）**
- O1 双时钟 → Task 1 ✓（`add` 去重不再刷新 created_at；`last_mention_ts` 由 add/update/hit/active-recall 统一刷新；`_relevance_score` recency 与归档、`old_memories`、`recent` 改看它；核心晋升年龄闸保持 created_at）
- I1 importance 因子 → Task 2 ✓（提取 LLM 打 1-10 分；`_coerce_importance` 钳制；进 `_relevance_score` 权重 0.3 档 `*0.03`）
- O2 talked 信号 → Task 3 ✓（`_choose_mood` 改读 `chat_messages` 近 24h user 消息；纯闲聊触发）
- O3 链上限 + 导出 → Task 4 ✓（每记忆 5 代、更旧 digest；`export_json` 加 `include_superseded`；备份含链；路由加参）
- 情绪联动（valence/arousal、mood-congruent）→ 明确**不做**（文档标注「前沿空白，可做差异化」，非本批工作项）
- 健康检查多指标 → 明确**不做**（文档标注「建议后续补」，需先建基线）

**2. Placeholder scan**：无 TBD/TODO。每个代码步骤都有完整代码块。

**3. Type consistency**
- `add(category, content, source=None, importance=5)`：Task 1 定义签名，Task 2 的 `_dispatch` 传 `act.get("importance")`（None → `_coerce_importance` → 5）✓
- `_coerce_importance` 在 manager.py 定义、extract.py 不重复定义（经 mgr.add 兜底）✓
- `last_mention_ts` 在 add/update_content/_count_hits/recall 四处写入语义一致（都是「提及」）；读取处 `or created_at` 回退兜住存量 NULL ✓
- `compress_history(memory_id)` 不 commit：update_content 在 commit 前调用、consolidate 在 commit 前调用 ✓
- `export_json` 参数顺序 `(include_archived, include_superseded)` 与调用方一致 ✓
- `has_recent_chat(hours, now)`：`_choose_mood` 传 `(24, now_dt)` ✓

**4. 顺序依赖**
- Task 1 先于 Task 2：`_relevance_score` 的 importance 因子与 `_coerce_importance` 在 Task 1 一并落地（共享辅助函数），Task 2 只补 extract 透传。若 Task 2 先做需单独加 `_coerce_importance`，也能兼容，但按计划顺序执行最稳。
- Task 3 独立于 1/2/4（只加 manager 查询方法 + heartbeat_moods），可与 Task 4 并行，但按顺序执行。
- Task 4 依赖 Task 1 的 conftest 新列（`update_content` 的 `last_mention_ts` 写入），故在 1 之后。

---

## 执行方式

**本仓库后台 agent 不可靠（项目记忆复核：2026-08-02/03 四批全部失联无提交）——用内联执行，不派后台 agent。**

按 Task 1→4 顺序执行，每 Task 走「写失败测试 → 跑失败 → 实现 → 跑绿 → 提交」循环，完成一个再进下一个。全部完成后跑一次全量 pytest 收尾。