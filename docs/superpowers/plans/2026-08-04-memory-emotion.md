# 记忆库情绪联动实施计划（schema v18）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/MEMORY_GAPS_2026-08-03.md`「情绪联动」三项，作为 schema v18（上一批 v17 计划 L1088 声明「明确不做」，本批用户已拍板纳入范围）：

1. 记忆带 valence/arousal 标签（提取时 LLM 顺带打分，A-MEM note tags 先例）；
2. 浮现按当前情绪效价加权（mood-congruent，Bower 1981）——**加权而非硬过滤**，防情绪固化回路（只想起同色记忆 → 情绪更固化）；
3. 「当前情绪」存成一条核心记忆，UPDATE 保留 superseded 历史 → 情绪可回放。**用户拍板：这条情绪记忆只存不浮现**——不进叙事注入、不参与 recall/top_core/old_memories 候选，仅作回放与调制信号来源。

**Architecture:** 全部改动在 `backend`（Python）。一个 v18 迁移（memories 加 `valence`/`arousal` 两列，同步 init_db / conftest 的 DDL）；`manager.py` 是改动主体（add 写入路径 / `_relevance_score` 第六因子 / `recall` 签名 / 新增 `get_mood_valence`、`upsert_mood_memory` / 读面排除谓词 `_MOOD_ROW_FILTER`）；`extract.py` 走打分（prompt + dispatch）；`memory/narrative.py` 把 mood 因子传下去（`graph.py` 零改动）；`heartbeat.py` 两处（心情变化落情绪记忆 + 自动日记 SQL 排除）。不动前端（life_state mood 词表不变 → moodToSprite 映射不变；新列经 `SELECT *` 下发导出 JSON，前端忽略未知字段无害）、不动 SSE 协议、不动 LLM 主对话、不改 remember 工具签名（v17 先例）。

**Tech Stack:** Python 3.13 + aiosqlite + pytest（`backend/tests/`，pytest.ini `asyncio_mode=auto`；conftest 的 `tmp_db` / `memory_mgr` fixture）。

## 勘察抽查结果（2026-08-04 设计前读码核对）

任务书所列事实**全部核对属实**（行号一致），仅一处路径更正：

- `narrative.py` 位于 `backend/memory/narrative.py`（不在 agent/ 下）。`graph.py` L100 调 `narrative_builder.build(max_items=5, query=query)`——mood 因子在 build 内部查库传下去即可，**graph.py 与 pre_model_hook 零改动**，test_graph_hook / test_tokens 的 build 桩签名不受影响。
- 三源 DDL 纪律确认：core.py L116-132 / conftest.py L13-24 / migrations v17 模板（L748-786：PRAGMA 守卫 → ALTER ADD COLUMN → 回填 → commit）✓
- `_relevance_score` 五因子与二段排序确认（hit 位是排序键首位、topic 权重 1.0 为层内分）✓
- `heartbeat_moods.py` 只 import stdlib + utils.logger（不 import heartbeat/manager）→ memory.manager 顶层 import `MOOD_VECTORS` **无循环导入** ✓
- `update_content` 的 superseded 行只复制 (content, category, created_at=最初, source)，**不复制 importance/valence** → 情绪回放靠 content 里带时间戳文案实现；本批不改 update_content（控制影响面）✓
- v17 计划文档 L1088「情绪联动明确不做」确为上批范围声明 ✓

**任务书未列、读码新发现（本计划全部消化）：**

1. `check_memory_health` 误报风险：情绪行若不排除，其存在使「0 记忆」告警恒假（count≥1）；且心情高频更新刷 `updated_at`，「记忆停滞」告警也恒假。**必须排除**。
2. `heartbeat.daily_diary` 有一段裸 SQL 读 memories（「今天冷冷新告诉小汐的事」）→ 情绪行**首次创建当天**会混进日记素材。**必须排除**。
3. `utils/events.py detect_memory_echoes`（L757）走 `old_memories` → 90 天后情绪行会变成「记忆回声」主动事件。**old_memories 必须排除**。
4. `consolidate.py` 走 `list_categories` + `recall_by_category` → 排除后情绪类别永不进整合视野（本身 1 行也够不上 ≥8 条门槛，双保险）。
5. `count()` 消费方：server.py 启动日志、`/api/status` memory_count、DELETE 路由 remaining → 排除后这些面计「关于冷冷的记忆」，语义更诚实。
6. conftest 种子行 life_state.mood='开心'，MOOD_VECTORS['开心']=(0.85, 0.55) → get_mood_valence 测试可直接断言这两个值。

## 关键决策（7 个决策点的答案）

1. **schema v18 列**：`valence REAL DEFAULT 0`、`arousal REAL DEFAULT 0`，范围 [-1,1] 由写入侧 `_coerce_valence`/`_coerce_arousal` 钳制（SQLite ALTER 不能加 CHECK；与 v17 importance「写入侧钳制」先例一致）。存量回填 0=中性：ALTER ADD COLUMN 的 DEFAULT 会写入存量行，再补一条 `UPDATE ... WHERE IS NULL` 兜底（幂等）。三源同步：`_migrate_v18` / core.py init_db DDL / conftest.py DDL。
2. **提取打分**：prompt 格式行加两键，规则 8 明示「只对 add 生效」并给 -1/0/1 三档锚点（锚点示例是 LLM 打分稳定的关键）；`_dispatch` 透传 `act.get("valence")`/`act.get("arousal")`；manager 侧缺失/非法/NaN → 0.0。**remember 工具不加参数**（v17 先例：agentic 写路径默认 0 中性）。去重重提同内容时 valence/arousal 随最新打分刷新（情绪是状态量，最新信号胜出；importance 保持首写语义不变）。
3. **mood-congruent 因子**：亲和度 = `1 − |v_mem − v_mood|/2 ∈ [0,1]`；**权重 0.15**（= importance 档 0.3 的一半；远低于 topic 档 1.0 与二段硬分层 → 压不过 topic；满幅差 0.15 ≈ 1.5 天时近差 → 只在近持平场景换位，即「软调制」语义）。valence NULL 或 0 → 亲和度**恒定 0.5、与 mood_valence 无关**——不被系统性惩罚（用等式测试钉死：score(mood=+1) == score(mood=−1)）。当前效价来源：新增 `manager.get_mood_valence()` 读 life_state.mood → MOOD_VECTORS（顶层 import heartbeat_moods，已核对无环）；`recall(..., mood_valence=None)` 可选参数，narrative.build 每次查一次传下去（graph.py 零改动）；extract 冲突上下文与 agentic recall 不传 → 因子关闭，行为与 v17 逐字节一致。**old_memories 不做效价加权**：RANDOM() 抽样是记忆回声的「惊喜」机制，加权需改全量取回 + Python 加权采样，且 mood 偏置的回声正是另一种情绪固化回路；需求文档的加权只针对浮现主路径（narrative recall）。
4. **情绪核心记忆**：固定确定性 id `MOOD_MEMORY_ID = "mood_current"`（幂等 upsert + 最便宜的 SQL 排除 + 可测试）。新增 `upsert_mood_memory(mood, now=None)`：首次直接 INSERT `status='core'` 行（绕过 add 的写死 'active'）；此后走 **update_content 复用 superseded 链**（用户要可回放；顺带继承 5 代上限压缩）。content = `"{now} 起，小汐的心情是「{mood}」"`——**时间戳入文案是必需**：superseded 行的 created_at 一律是本记忆最初创建时间（update_content 的时序诚实设计），只有文案能携带每次生效时间。去重索引核对无冲突：类别「小汐的心情」专属 + content 含时间戳必不重复 + superseded 行不在部分索引谓词内。类别词汇表定义在 extract.py 规则 4 与 remember docstring（{冷冷喜欢的/冷冷的信息/去过的地方/重要的事/其他}），「小汐的心情」不冲突。source='system:mood'。心跳时机：`check_and_proactive_message` 内 life_state commit 之后，条件与 mood_changed_at 刷新同一个 `mood != current_mood`（即最小停留放行/早晨锚定放行后才落；被拦回的换词自然不落）。行的 valence/arousal 存 MOOD_VECTORS 坐标（调制信号落账本；更新时单独 UPDATE 这两列，不改 update_content）。
5. **只存不浮现实例化**：排除机制 = **id 谓词** `_MOOD_ROW_FILTER = "AND id != 'mood_current'"`（确定性单行、单写入方、零歧义、谓词最便宜；superseded 链行已因 status='superseded' 天然不可见，无需本谓词照顾）。排除读面（浮现/计数类）：recall（两路）/ recent / top_core（不排除会**永久霸占 core 保底席位**）/ old_memories / recall_by_category / list_categories（类别不对 LLM 可见 → remember 与整合永不触碰）/ count / all（面板 + export_memories 工具 → 不进 LLM 上下文）/ check_memory_health / daily_diary 裸 SQL。保留账本面：export_json（下载 + backup_before_clear）/ history()（回放本身）/ clear()（先备份后全删，语义不变）/ apply_status_decay（只动 active，core 行天然免疫，**无需任何工作**）。去重索引：情绪行在谓词内但无害（决策 4 已核对）。hit/access 计数器：不被任何读面返回 → `_count_hits` 天然不碰（测试断言钉死）。
6. **任务拆分**：T1 迁移+三源 DDL → T2 写入路径+提取打分 → T3 mood 因子+narrative 接线 → T4 情绪核心记忆（upsert+排除+心跳接线）→ T5 收尾。依赖：T2/T3/T4 都需要 T1 的列。
7. **Self-Review**：见文末（三项需求逐条对照、type consistency、循环导入、前后端零改动）。

## Global Constraints

- 改完必须全量 pytest 绿：`cd backend && .venv/Scripts/python.exe -m pytest -q`（现有 633 passed 基线不能被破坏）。
- 迁移 v18 幂等（原地重跑不报错、结果不变）；`SCHEMA_VERSION` 17→18。
- 三处 DDL 永不漂移：`database/core.py` init_db / `tests/conftest.py` / 迁移（v18 用 ALTER 补齐，新库由 init_db 的 CREATE TABLE 直接含新列）。
- **不引入新依赖**；不改 `remember` 工具签名（情绪打分只走提取路径，agentic 写路径默认 0 中性）；不改 `narrative.build` 与 `_relevance_score` 既有调用方的兼容性（新参数全部 keyword + 默认值，缺省行为 = v17）；不改 `update_content` 的签名与行为。
- mood 因子在 `mood_valence=None` 时**必须逐字节不改变**排序（extract 冲突上下文、agentic recall、全部既有测试走此路径）。
- 内联执行，不派后台 agent（v17 批先例：2026-08-02/03 四批后台 agent 全部失联）。

---

### Task 1: schema v18 迁移 + 三源 DDL（valence / arousal 两列）

**Files:**
- Modify: `backend/database/migrations.py`（SCHEMA_VERSION 18 + 版本注释 + `_migrate_v18`）
- Modify: `backend/database/core.py`（init_db 的 memories DDL 加两列）
- Modify: `backend/tests/conftest.py`（tmp_db 的 memories DDL 加两列）
- Modify: `backend/tests/test_init_db_upgrade.py`（逐列断言加两行）
- Test: `backend/tests/test_migrations_v18.py`（新建）

**Interfaces:**
- Consumes: v17 终态 memories 表。
- Produces: `memories.valence REAL DEFAULT 0`、`memories.arousal REAL DEFAULT 0`（存量回填 0=中性）。

**背景：** 情绪联动第一步——给记忆一个 Russell 情绪空间坐标。选 REAL DEFAULT 0 而不是可空：中性是存量与缺失的统一语义（`_mood_affinity` 对 0/NULL 同判中性），读面无需处处 COALESCE。不加 CHECK 约束：SQLite ALTER 无法附加 CHECK，写入侧钳制是 v17 importance 的既定先例。

- [ ] **Step 1: 写失败测试**

**A. 迁移测试** `backend/tests/test_migrations_v18.py`（新建，自建 v17 时代裸库，测迁移 SQL 本身；模板照 test_migrations_v17.py）：

```python
"""迁移 v18 回归 — memories.valence + arousal（情绪联动情绪标签）。

自建 v17 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v18

_V17_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
    last_used_at TEXT, access_count INTEGER DEFAULT 0,
    hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
    status TEXT DEFAULT 'active',
    updated_at TEXT, source TEXT, prev_content TEXT,
    superseded_by TEXT,
    last_mention_ts TEXT,
    importance INTEGER DEFAULT 5
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v18.db"))
    await conn.executescript(_V17_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def _columns(conn, table) -> set[str]:
    cursor = await conn.execute(f"PRAGMA table_info({table})")
    return {row[1] for row in await cursor.fetchall()}


async def test_v18_adds_columns(bare_db):
    await _migrate_v18(bare_db)
    cols = await _columns(bare_db, "memories")
    assert "valence" in cols
    assert "arousal" in cols


async def test_v18_backfills_neutral_zero(bare_db):
    """存量行回填 0 = 中性（老记忆无情绪打分，按中性处理）。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.0, 0.0)


async def test_v18_null_backfill_idempotent(bare_db):
    """NULL 兜底回填 + 幂等：手动置 NULL 后重跑迁移仍归 0。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    await bare_db.execute("UPDATE memories SET valence = NULL, arousal = NULL WHERE id = 'm1'")
    await bare_db.commit()
    await _migrate_v18(bare_db)  # 重跑：列已存在走守卫，NULL 回填生效
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.0, 0.0)


async def test_v18_does_not_touch_scored_rows(bare_db):
    """已有合法值（全新安装路径）重跑迁移不被覆盖。"""
    await bare_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('m1', '旧记忆', '其他', '2026-01-01 10:00', 'active')"
    )
    await bare_db.commit()
    await _migrate_v18(bare_db)
    await bare_db.execute("UPDATE memories SET valence = 0.7, arousal = -0.3 WHERE id = 'm1'")
    await bare_db.commit()
    await _migrate_v18(bare_db)
    cursor = await bare_db.execute("SELECT valence, arousal FROM memories WHERE id = 'm1'")
    assert tuple(await cursor.fetchone()) == (0.7, -0.3)
```

**B. 升级链路断言** —— `backend/tests/test_init_db_upgrade.py` 的 `test_init_db_upgrades_legacy_v1_database` 逐列断言块（现 L57-70），在 importance 断言后追加两行：

```python
        assert "last_mention_ts" in columns  # v17 O1 统一提及时钟
        assert "importance" in columns       # v17 I1 重要性打分
        assert "valence" in columns          # v18 情绪联动：效价
        assert "arousal" in columns          # v18 情绪联动：唤醒度
        assert "time" not in columns
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v18.py tests/test_init_db_upgrade.py -q`
Expected: test_migrations_v18.py 收集错误（`ImportError: cannot import name '_migrate_v18'`）；test_init_db_upgrade 新增两条断言 FAIL（列不存在）。

- [ ] **Step 3: 实现**

**3a. `backend/database/migrations.py`**

版本注释块（L61-65 v17 条目之后）追加一条，并把 `SCHEMA_VERSION = 17`（L66）改为 `18`：

```python
# v18: 情绪联动 — memories 新增 valence / arousal（Russell 情绪空间标签，
#     REAL DEFAULT 0，范围 -1..1 由写入侧钳制）。提取时 LLM 顺带打分；
#     进 _relevance_score 第六因子 mood-congruent 加权（manager.py，
#     加权而非硬过滤——防只浮现同色记忆的情绪固化回路）。存量回填
#     0 = 中性：老记忆无情绪打分，亲和度取 0.5 常量，不被系统性偏置。
SCHEMA_VERSION = 18
```

文件尾部（`_migrations[17] = _migrate_v17` 之后、「核心迁移逻辑」分隔线之前）新增：

```python
# ── v18 迁移：情绪联动情绪标签 ────────────────────────────────


async def _migrate_v18(conn: aiosqlite.Connection) -> None:
    """v18: memories.valence + arousal（情绪联动：记忆的情绪空间标签）。

    背景（2026-08-03 记忆库诊断「情绪联动」，schema v18 批）：
    - 记忆带 (valence, arousal) Russell 空间标签，提取时 LLM 打分写入，
      供 _relevance_score 第六因子 mood-congruent 加权（加权而非硬过滤——
      防「只想起同色记忆 → 情绪更固化」回路）；
    - 标签是纯增量列：不影响任何既有读面谓词、去重索引与衰减。

    存量回填 0 = 中性：老记忆无情绪打分，manager._mood_affinity 对
    NULL/0 一律取亲和度 0.5 常量——不被 mood 因子偏向也不被系统性惩罚。
    ALTER TABLE ADD COLUMN ... DEFAULT 0 已把默认值写入存量行，UPDATE
    只是 NULL 兜底（幂等；重跑时已有合法值的行不受影响）。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "valence" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN valence REAL DEFAULT 0")
    if "arousal" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN arousal REAL DEFAULT 0")

    await conn.execute("UPDATE memories SET valence = 0 WHERE valence IS NULL")
    await conn.execute("UPDATE memories SET arousal = 0 WHERE arousal IS NULL")

    await conn.commit()
    log.info("迁移 v18 完成：memories.valence + arousal（情绪联动情绪标签）")


_migrations[18] = _migrate_v18
```

**3b. `backend/database/core.py`** init_db 的 memories DDL（L116-132），`importance` 行后加两列（完整 CREATE TABLE 如下）：

```sql
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
            importance      INTEGER DEFAULT 5,
            valence         REAL DEFAULT 0,
            arousal         REAL DEFAULT 0
        );
```

**3c. `backend/tests/conftest.py`** tmp_db 的 memories DDL（L13-24），`importance` 行后加两列：

```python
        CREATE TABLE IF NOT EXISTS memories (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
            last_used_at TEXT, access_count INTEGER DEFAULT 0,
            hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
            status TEXT DEFAULT 'active',
            updated_at TEXT, source TEXT, prev_content TEXT,
            superseded_by TEXT,
            last_mention_ts TEXT,
            importance INTEGER DEFAULT 5,
            valence REAL DEFAULT 0,
            arousal REAL DEFAULT 0
        );
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_migrations_v18.py tests/test_init_db_upgrade.py tests/test_migrations.py -q`
Expected: 全 PASS（test_migrations.py 的 `== SCHEMA_VERSION` 断言是动态值，自动跟随 18）。随后全量确认 conftest DDL 改动无连带破坏：`.venv/Scripts/python.exe -m pytest -q`。

- [ ] **Step 5: 提交**

```bash
git add backend/database/migrations.py backend/database/core.py backend/tests/conftest.py backend/tests/test_migrations_v18.py backend/tests/test_init_db_upgrade.py
git commit -m "feat(db): schema v18 — memories.valence/arousal 情绪标签（迁移 + 三源 DDL）"
```

---

### Task 2: add() 写入路径 + 提取 LLM 打 valence/arousal 分

**Files:**
- Modify: `backend/memory/manager.py`（`_coerce_unit`/`_coerce_valence`/`_coerce_arousal`；`add` 签名 + INSERT + ON CONFLICT）
- Modify: `backend/memory/extract.py`（`_EXTRACT_SYSTEM` prompt + `_dispatch` 传参）
- Test: `backend/tests/test_memory_manager.py` / `backend/tests/test_memory_extract.py`

**Interfaces:**
- Consumes: Task 1 的 valence/arousal 列。
- Produces: `add(category, content, source=None, importance=5, valence=None, arousal=None)`；提取 LLM 的 add 动作带 valence/arousal 落库。

**背景：** 需求 1「提取时 LLM 顺带打分」。打分稳定性靠 prompt 里的三档锚点（-1/0/1 各给例子）。manager 侧 `_coerce_valence`/`_coerce_arousal` 是 `_coerce_importance` 的同款钳制范本：缺失/非法/NaN → 0.0（中性），超界钳回 [-1,1]。JSON 可产生 NaN 字面量，float() 不拦 NaN，必须显式判。**remember 工具不传新参**（v17 先例：agentic 写路径默认中性，不给主对话工具加打分负担）。去重（ON CONFLICT）时 valence/arousal 随最新打分刷新——情绪是状态量，同一事实重提时的最新情绪色彩胜出；importance 保持 v17 的首写语义不变（两列语义不同，刻意两套纪律，代码注释写明）。

- [ ] **Step 1: 写失败测试**

**A. manager 行为测试** —— 追加到 `backend/tests/test_memory_manager.py`（文件已有 `import pytest`）：

```python
# ── 情绪标签写入（schema v18 情绪联动）────────────────────────


async def test_valence_arousal_stored_and_default(memory_mgr):
    """v18：add 显式传 valence/arousal 落库；不传默认 0.0（中性）。"""
    tagged = await memory_mgr.add("冷冷的信息", "冷冷今天很难过", valence=-0.7, arousal=0.3)
    assert tagged["valence"] == pytest.approx(-0.7)
    assert tagged["arousal"] == pytest.approx(0.3)
    plain = await memory_mgr.add("日常", "今天吃了饭")
    assert plain["valence"] == 0.0
    assert plain["arousal"] == 0.0


async def test_valence_arousal_clamped_and_coerced(memory_mgr):
    """v18：LLM 手滑超界/非法值 → 钳制或归 0（NaN 必须归 0，不能入库）。"""
    m = await memory_mgr.add("a", "x", valence=3.5, arousal=-4)
    assert m["valence"] == pytest.approx(1.0)
    assert m["arousal"] == pytest.approx(-1.0)
    m = await memory_mgr.add("a", "y", valence="0.5", arousal="abc")
    assert m["valence"] == pytest.approx(0.5)
    assert m["arousal"] == pytest.approx(0.0)
    m = await memory_mgr.add("a", "z", valence="nan", arousal=None)
    assert m["valence"] == pytest.approx(0.0)
    assert m["arousal"] == pytest.approx(0.0)


async def test_add_dedup_refreshes_valence(memory_mgr):
    """v18：重提同一事实，情绪标签随最新打分刷新（importance 保持首写不变）。"""
    first = await memory_mgr.add("冷冷的信息", "冷冷怕打雷", valence=-0.6, arousal=0.4)
    again = await memory_mgr.add("冷冷的信息", "冷冷怕打雷", valence=-0.2, arousal=0.1)
    assert again["id"] == first["id"]          # 去重命中同一行
    assert again["valence"] == pytest.approx(-0.2)
    assert again["arousal"] == pytest.approx(0.1)
```

**B. 提取打分测试** —— 追加到 `backend/tests/test_memory_extract.py`（沿用现有 `fake_llm` / `_seed_messages` 夹具）：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_memory_extract.py -q`
Expected: manager 3 例 FAIL（`add() got an unexpected keyword argument 'valence'`）；extract 2 例 FAIL（valence 字段被 dispatch 忽略 → 落库 0.0，第一例断言 -0.7 不等）。既有测试全绿（add 尚未改签名，旧调用不受影响）。

- [ ] **Step 3: 实现**

**3a. `backend/memory/manager.py`** —— `_coerce_importance`（L80-86）之后新增钳制函数：

```python
def _coerce_unit(value) -> float:
    """valence/arousal 归一化到 [-1, 1]（schema v18 情绪联动）。

    LLM 手滑/缺失兜底为 0.0（中性）；NaN 显式归 0——json.loads 接受
    NaN 字面量，float() 不拦它，入库会污染亲和度计算。
    """
    try:
        x = float(value)
    except (TypeError, ValueError):
        return 0.0
    if x != x:  # NaN
        return 0.0
    return max(-1.0, min(1.0, x))


def _coerce_valence(value) -> float:
    """valence（情绪效价）钳制：缺失/非法 → 0.0 中性。"""
    return _coerce_unit(value)


def _coerce_arousal(value) -> float:
    """arousal（唤醒度）钳制：缺失/非法 → 0.0 中性。"""
    return _coerce_unit(value)
```

**3b. `manager.py` 的 `add()`**（L202-264）签名加两参、INSERT 加两列、ON CONFLICT 加两列刷新。完整方法体：

```python
    async def add(
        self,
        category: str,
        content: str,
        source: str | None = None,
        importance: int = 5,
        valence=None,
        arousal=None,
    ) -> dict:
        """添加一条记忆（原子去重）。返回实际生效的记忆条目。

        去重规则：同一 category 下已有完全相同的 content（活跃行）时，
        不重复添加，只刷新 last_mention_ts 和 last_used_at（以及 updated_at，
        v12 语义：再次想起也视为一次内容刷新；source 保留首次来源）。
        **created_at 严格不可变**（O1，schema v17）：它是「第一次记住」
        的时间，去重刷新不再触碰——否则被反复 add 的记忆衰减时钟被重置，
        与被反复 update 的记忆老化形成两套矛盾时钟。

        importance（I1，schema v17）：提取时 LLM 打 1-10 分，进相关性
        排序权重 0.3 档；agentic remember 不传默认 5（中性）。
        **importance 去重时保持首写**（第一次记住时的重要性判断稳定）。

        valence/arousal（情绪联动，schema v18）：提取时 LLM 打 Russell
        情绪空间分（各 -1..1，缺失/非法 → 0.0 中性，见 _coerce_unit）。
        与 importance 刻意不同的纪律：**去重时随最新打分刷新**——情绪是
        状态量，同一事实重提时最新的情绪色彩胜出（提取重试也自愈：
        首跑漏打分落 0，次跑补上即刷新）。agentic remember 不传默认 0。

        source（v12）：来源追踪——后台提取传 ``chat:{session_id}``，
        agentic remember 不传（NULL）。「小汐为什么记得这个」可追溯。

        去重由数据库层的唯一部分索引 (category, content)
        WHERE status IN ('active', 'core')
        （迁移 v5）强制保证：INSERT ... ON CONFLICT DO UPDATE 一条语句原子完成，
        彻底消除「先查后写」在并发下产生重复行的 TOCTOU 竞态。
        """
        # 空 category 入库会让 recall_by_category 的 LIKE '%%' 匹配全类别、
        # 矛盾检测上下文串味；由调用方（remember 工具）转友好文案
        category = (category or "").strip()
        if not category:
            raise ValueError("category 不能为空")
        content = (content or "").strip()
        if not content:
            raise ValueError("content 不能为空")
        content = _cap_content(content, "add")
        importance = _coerce_importance(importance)
        valence = _coerce_valence(valence)
        arousal = _coerce_arousal(arousal)
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, last_used_at, access_count,
                status, updated_at, source, last_mention_ts, importance,
                valence, arousal)
               VALUES (?, ?, ?, ?, ?, 0, 'active', ?, ?, ?, ?, ?, ?)
               ON CONFLICT(category, content) WHERE status IN ('active', 'core')
               DO UPDATE SET
                   last_used_at = excluded.last_used_at,
                   updated_at = excluded.updated_at,
                   last_mention_ts = excluded.last_mention_ts,
                   valence = excluded.valence,
                   arousal = excluded.arousal""",
            (mem_id, content, category, now, now, now, source, now, importance,
             valence, arousal),
        )
        await self._db.commit()

        # 读回实际生效的行（可能是新插入的，也可能是被刷新的既有行），
        # 返回真实的 id 与 access_count
        cursor = await self._db.execute(
            """SELECT * FROM memories
               WHERE category = ? AND content = ? AND status IN ('active', 'core')""",
            (category, content),
        )
        row = await cursor.fetchone()
        return rows_to_dicts(cursor, [row])[0]
```

（其余方法不动。`tools/memory.py` remember 的 `add(category, content)` 调用因新参有默认值自动兼容——v17 先例同款。）

**3c. `backend/memory/extract.py`** —— `_EXTRACT_SYSTEM`（L63-77）完整替换为（格式行加两键、新增规则 8；三档锚点是打分稳定性的关键，照抄勿删）：

```python
_EXTRACT_SYSTEM = """你是小汐的记忆管家。从对话中提炼「值得长期记住的关于冷冷的新事实或变化」，只输出 JSON 数组，不要任何解释。

格式：[{"action": "add|update|noop", "category": "类别", "content": "内容", "memory_id": "可选", "importance": 1-10, "valence": -1~1, "arousal": -1~1}]

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
7. importance 只对 add 生效：1-10 打分（1=日常流水一句，5=一般，10=人生大事/重大变化）。update 与 noop 不填。
8. valence / arousal 只对 add 生效，给这条事实打一个情绪坐标：
   - valence 是这件事对冷冷而言的情绪效价：-1=明确的坏事/痛苦（生病、失去、吵架），0=中性事实（日程、信息、偏好陈述），1=明确的好事/快乐（实现愿望、好消息、被爱）；
   - arousal 是这件事的唤醒度：-1=平静低能量（习惯、常态、淡淡的），0=一般，1=激动高能量（惊喜、崩溃、急切）；
   - 中性事实两个都填 0；拿不准就往 0 靠。update 与 noop 不填。"""
```

**3d. `_dispatch`** 的 add 分支（L269-274）透传两参：

```python
            try:
                # I1：LLM 顺带打 1-10 分（mgr.add 内部 _coerce_importance
                # 兜底：None/非法 → 5，超界钳回 1-10）
                # v18 情绪联动：顺带打情绪坐标（_coerce_valence/_coerce_arousal
                # 兜底：None/非法/NaN → 0.0，超界钳回 [-1,1]）
                await mgr.add(category, content, source=source,
                              importance=act.get("importance"),
                              valence=act.get("valence"),
                              arousal=act.get("arousal"))
                added += 1
            except ValueError:
                skipped += 1
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_memory_extract.py tests/test_memory_tools.py -q`
Expected: 全 PASS（含新增 5 例；test_memory_tools 验证 remember 工具未传新参的兼容性）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/memory/extract.py backend/tests/test_memory_manager.py backend/tests/test_memory_extract.py
git commit -m "feat(memory): 提取 LLM 打 valence/arousal 情绪分进 add 写入路径（schema v18）"
```

---

### Task 3: mood-congruent 第六因子 + narrative 接线（加权而非硬过滤）

**Files:**
- Modify: `backend/memory/manager.py`（`_MOOD_AFFINITY_WEIGHT` / `_mood_affinity` / `_relevance_score` 第六因子 / `recall` 加 `mood_valence` 参数 / 新增 `get_mood_valence` / 顶层 import MOOD_VECTORS）
- Modify: `backend/memory/narrative.py`（build 里查一次 mood_valence 传给 recall）
- Test: `backend/tests/test_memory_manager.py` / `backend/tests/test_narrative.py`

**Interfaces:**
- Consumes: Task 1/2 的 valence 列；`life_state.mood`（单行 id=1）；`heartbeat_moods.MOOD_VECTORS`（10 个中文 mood 词 → Russell 坐标）。
- Produces: `_relevance_score(memory, now, query="", mood_valence=None)` 六因子；`recall(..., mood_valence=None)`；`get_mood_valence() -> float | None`。

**关键决策回顾：**
- **公式**：亲和度 = `1 − |v_mem − v_mood|/2 ∈ [0,1]`；**权重 0.15**——importance 档（0.3 档）的一半、远低于 topic 档（1.0）与二段硬分层（hit 位是排序键首位，mood 只进层内分）→ 压不过话题；满幅差 0.15 ≈ exp(-0.1·d) 里约 1.5 天的时近差 → 只在近持平时换位，正是「软调制、不硬过滤」：负效价记忆在正性心情下仍可靠话题/时近浮现，防情绪固化回路。
- **中性免疫**：valence 为 NULL（存量/未打分）或显式 0 → 亲和度恒定 0.5，**与 mood_valence 取值无关**——中性记忆之间的相对排序不受 mood 影响，也不被任何心情系统性惩罚。等式测试钉死。
- **当前效价来源**：`get_mood_valence()` 读 life_state.mood → MOOD_VECTORS 的 valence 分量；无行/未知词 → None（因子自动关闭）。顶层 `from heartbeat_moods import MOOD_VECTORS`——已核对 heartbeat_moods 只 import stdlib + utils.logger，无循环。
- **传参链**：graph.py 零改动；narrative.build 内部查一次（每次 build 一行单行查询，单连接下近零开销），传给 recall 的相关路。`recent` 是纯时近路不加权——「刚发生的事在场」本身就是防固化的对冲配额。
- **old_memories 不加权**（维持 RANDOM() 纯随机）：记忆回声是「翻小本本」的惊喜机制；SQL 层无法加权抽样，改全量取回 + Python 加权得不偿失；且 mood 偏置的回声 = 另一条情绪固化通路。需求文档的加权只针对浮现主路径。
- **mood_valence=None 时逐字节退回 v17**：`_mood_affinity` 返回 0.0（不是 0.5×w）——extract 冲突上下文召回、agentic recall 工具、全部既有测试走此路径，行为零变化。

- [ ] **Step 1: 写失败测试**

**A. 公式级测试** —— 追加到 `backend/tests/test_memory_manager.py`：

```python
# ── mood-congruent 第六因子（schema v18 情绪联动）──────────────


def _mem(valence=None, days=0):
    """构造最小记忆 dict（公式级单测用）。"""
    import datetime
    from utils.timezone import HKT
    ts = (datetime.datetime.now(HKT) - datetime.timedelta(days=days)).strftime("%Y-%m-%d %H:%M")
    return {"created_at": ts, "last_mention_ts": ts, "access_count": 0,
            "importance": 5, "valence": valence}


def test_mood_affinity_congruent_outranks_opposed():
    """v18：当前心情效价相近的记忆分高，相反的低（其余因子全同）。"""
    import datetime
    from memory.manager import _relevance_score
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT)
    pos, neg = _mem(valence=0.8), _mem(valence=-0.8)

    assert _relevance_score(pos, now, "", mood_valence=0.85) > \
           _relevance_score(neg, now, "", mood_valence=0.85)
    # 心情翻转 → 排序翻转（加权是对称的，不偏向某一侧）
    assert _relevance_score(pos, now, "", mood_valence=-0.85) < \
           _relevance_score(neg, now, "", mood_valence=-0.85)


def test_mood_affinity_neutral_not_systematically_penalized():
    """v18 防固化校验：valence 缺失/0 的中性记忆，mood 因子与其取值无关。"""
    import datetime
    from memory.manager import _relevance_score
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT)
    for mem in (_mem(valence=None), _mem(valence=0.0), _mem()):  # 缺键也中性
        s_pos = _relevance_score(mem, now, "", mood_valence=1.0)
        s_neg = _relevance_score(mem, now, "", mood_valence=-1.0)
        assert s_pos == s_neg  # 严格等式：中性记忆不吃任何心情的偏置


def test_mood_factor_off_without_mood_valence():
    """v18：mood_valence=None → 因子整体关闭，与 v17 行为逐字节一致。"""
    import datetime
    from memory.manager import _relevance_score
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT)
    for v in (None, 0.0, 0.9, -0.9):
        mem = _mem(valence=v)
        assert _relevance_score(mem, now, "") == \
               _relevance_score(mem, now, "", mood_valence=None)
```

**B. recall / get_mood_valence 行为测试** —— 追加到 `backend/tests/test_memory_manager.py`：

```python
async def test_recall_mood_valence_biases_ranking(memory_mgr, tmp_db):
    """v18 核心回归：mood-congruent 加权——同调记忆先浮现，且异调记忆
    仍在结果里（加权而非硬过滤，防情绪固化回路）。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("日常", "开心的事", valence=0.8)
    await memory_mgr.add("日常", "难过的事", valence=-0.8)
    # 拉平全部时钟与热度，让差异只剩 mood 因子
    now = datetime.datetime.now(HKT).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ?, last_used_at = NULL",
        (now, now),
    )
    await tmp_db.commit()

    happy = await memory_mgr.recall(passive=True, mood_valence=0.85)
    assert [m["content"] for m in happy] == ["开心的事", "难过的事"]

    sad = await memory_mgr.recall(passive=True, mood_valence=-0.85)
    assert [m["content"] for m in sad] == ["难过的事", "开心的事"]
    # 加权而非硬过滤：两种心情下两条都在场
    assert len(happy) == len(sad) == 2


async def test_recall_without_mood_valence_keeps_v17_order(memory_mgr, tmp_db):
    """v18：不传 mood_valence（extract/agentic recall 路径）→ v17 行为不变。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("日常", "先记住的")
    await memory_mgr.add("日常", "后记住的", valence=0.9)
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=30)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? WHERE content = '后记住的'",
        (old, old),
    )
    await tmp_db.commit()

    result = await memory_mgr.recall(passive=True)  # 无 mood_valence
    assert result[0]["content"] == "先记住的"      # 时近主导，valence 不参与


async def test_get_mood_valence_maps_life_state(memory_mgr, tmp_db):
    """v18：life_state.mood → MOOD_VECTORS valence；未知词/无行 → None。"""
    # conftest 种子行 mood='开心' → MOOD_VECTORS['开心'] = (0.85, 0.55)
    assert await memory_mgr.get_mood_valence() == pytest.approx(0.85)

    await tmp_db.execute("UPDATE life_state SET mood = '担心' WHERE id = 1")
    await tmp_db.commit()
    assert await memory_mgr.get_mood_valence() == pytest.approx(-0.55)

    await tmp_db.execute("UPDATE life_state SET mood = '不存在的词' WHERE id = 1")
    await tmp_db.commit()
    assert await memory_mgr.get_mood_valence() is None

    await tmp_db.execute("DELETE FROM life_state")
    await tmp_db.commit()
    assert await memory_mgr.get_mood_valence() is None
```

**C. narrative 接线测试** —— 追加到 `backend/tests/test_narrative.py`：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_narrative.py -q`
Expected: 新增 7 例 FAIL（`_relevance_score` 无 mood_valence 参 → TypeError；`recall` 无该参；`get_mood_valence` 不存在 → AttributeError；narrative 未传 kwargs → KeyError 'mood_valence'）。既有测试全绿。

- [ ] **Step 3: 实现**

**3a. `backend/memory/manager.py`** 顶部 import 区（`from database.core import rows_to_dicts` 之后）加：

```python
# Russell 情绪空间坐标（heartbeat_moods 单持有，本模块只读引用）。
# 无循环导入：heartbeat_moods 只 import stdlib + utils.logger。
from heartbeat_moods import MOOD_VECTORS
```

`_bigram_overlap` 之后（`_relevance_score` 之前）新增权重常量与亲和度函数：

```python
# mood-congruent 因子权重（schema v18 情绪联动）：0.15 = importance 档
# （0.3）的一半，远低于话题档（1.0）与二段硬分层——压不过话题命中，
# 满幅差 ≈1.5 天时近差，只在近持平时换位：软调制，不是硬过滤。
_MOOD_AFFINITY_WEIGHT = 0.15


def _mood_affinity(memory: dict, mood_valence: float | None) -> float:
    """mood-congruent 亲和度 [0,1]（schema v18 情绪联动第六因子）。

    亲和度 = 1 - |v_mem - v_mood| / 2：记忆效价与当前心情效价越接近分越高
    （Bower 1981 mood-congruent memory）。**加权而非硬过滤**——异效价记忆
    仍可凭话题/时近/热度浮现，防「只想起同色记忆 → 情绪更固化」回路。

    中性免疫：valence 缺失（存量/未打分）或显式 0 → 亲和度恒定 0.5，
    与 mood_valence 取值无关——中性记忆不吃任何心情的偏置，也不被系统性
    惩罚（回归测试用严格等式钉死）。

    mood_valence=None（调用方未提供当前心情）→ 返回 0.0，因子整体关闭，
    评分与 v17 逐字节一致（extract 冲突上下文 / agentic recall 走此路径）。
    """
    if mood_valence is None:
        return 0.0
    raw = memory.get("valence")
    if raw is None or _coerce_valence(raw) == 0.0:
        return 0.5
    return 1.0 - abs(_coerce_valence(raw) - mood_valence) / 2.0
```

**3b. `_relevance_score`**（L110-151）加参 + 第六因子。完整函数体：

```python
def _relevance_score(memory: dict, now: datetime.datetime, query: str = "",
                     mood_valence: float | None = None) -> float:
    """计算记忆相关性分数。

    六因子加权（O1/I1 schema v17；mood-congruent schema v18）：
    - 时间衰减（last_mention_ts，半衰期 ≈ 7 天——O1：统一提及时钟，
      被 add/update/hit 过的记忆续命，created_at 不再兼职衰减）
    - 访问热度（access_count，对数衰减防止老热门垄断）
    - 最近使用（last_used_at，半衰期 ≈ 3.5 天）
    - 重要性（importance 1-10，LLM 打分，权重 0.3 档——I1）
    - 话题命中（query 与 content+category 的字面重叠，B1 新增，权重 1.0）
    - 情绪亲和（当前心情效价与记忆 valence 的接近度——schema v18，
      权重 0.15 软调制；mood_valence=None 时因子关闭，等同 v17）
    """
    # 时间衰减（O1：基于 last_mention_ts，缺失回退 created_at——
    # 被 add/update/hit 过的记忆续命，created_at 不再兼职衰减时钟）
    last_mention = memory.get("last_mention_ts") or memory.get("created_at")
    days_old = max(0, (now - _parse_time(last_mention)).total_seconds() / 86400)
    recency = math.exp(-0.1 * days_old)

    # 访问热度
    access_count = memory.get("access_count", 0) or 0
    access_boost = math.log1p(access_count) * 0.15

    # 最近使用
    last_used = memory.get("last_used_at")
    if last_used:
        used_days = max(0, (now - _parse_time(last_used)).total_seconds() / 86400)
        usage_recency = math.exp(-0.2 * used_days) * 0.3
    else:
        usage_recency = 0.0

    # 重要性（I1：LLM 提取时打分 1-10，权重 0.3 档——与 usage_recency 同档）
    importance_boost = _coerce_importance(memory.get("importance")) * 0.03

    # 话题加分（权重 1.0）：层内排序用——同为话题命中时，重叠比例高的
    # 靠前。（层间「命中优先」由 recall 的二段排序保证，不靠这个权重硬扛
    # 新记忆的 recency+usage 双加成。）
    if query:
        haystack = f"{memory.get('content', '')} {memory.get('category', '')}"
        topic = _bigram_overlap(query, haystack)
    else:
        topic = 0.0

    # 情绪亲和（schema v18）：mood-congruent 软调制，加权而非硬过滤
    mood_boost = _MOOD_AFFINITY_WEIGHT * _mood_affinity(memory, mood_valence)

    return recency + access_boost + usage_recency + importance_boost + topic + mood_boost
```

**3c. `recall()`**（L473 起）签名加 `mood_valence: float | None = None`，docstring 补一段，两处 `_sort_key` 把参数传进 `_relevance_score`。改动点：

```python
    async def recall(
        self, category: str = "", limit: int = 20, passive: bool = False,
        query: str = "", mood_valence: float | None = None,
    ) -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。

        ……（原 docstring 保留）……

        mood_valence（schema v18 情绪联动）：当前心情的效价（-1..1）。
        传入时进 _relevance_score 第六因子 mood-congruent 加权（软调制，
        不硬过滤）；None（agentic recall 工具 / extract 冲突上下文）→
        因子关闭，行为与 v17 一致。叙事注入路径由 NarrativeBuilder 查
        life_state 后传入。
        """
```

两处排序键（L526-535）改为：

```python
        now = datetime.datetime.now(_HKT)
        if query:
            q_grams = _char_bigrams(query)

            def _sort_key(m):
                haystack = f"{m.get('content', '')} {m.get('category', '')}"
                hit = 1 if q_grams & _char_bigrams(haystack) else 0
                return (hit, _relevance_score(m, now, query, mood_valence))
        else:
            def _sort_key(m):
                return _relevance_score(m, now, query, mood_valence)
```

**3d. 新增 `get_mood_valence`** —— 加在 `has_recent_chat` 之后（查询区）：

```python
    async def get_mood_valence(self) -> float | None:
        """当前心情效价（schema v18 情绪联动的调制信号源）。

        读 life_state 单行 mood → MOOD_VECTORS 的 Russell valence 分量。
        无行 / mood 为空 / 未知词 → None（调用方关闭 mood 因子，浮现
        退回 v17 行为）。mood 词表与心跳 _choose_mood 的抽样空间同源
        （heartbeat_moods.MOOD_VECTORS），永不漂移。
        """
        cursor = await self._db.execute(
            "SELECT mood FROM life_state WHERE id = 1"
        )
        row = await cursor.fetchone()
        if not row or not row[0]:
            return None
        vec = MOOD_VECTORS.get(row[0])
        return vec[0] if vec is not None else None
```

**3e. `backend/memory/narrative.py`** `build()`（L29-55 段）查一次传下去。改动段（其余不动）：

```python
        if self._manager is None:
            return ""
        total = await self._manager.count()
        if total == 0:
            return ""

        # 情绪联动（schema v18）：查一次当前心情效价，传给 recall 的
        # mood-congruent 因子。未知 mood 词 / 无 life_state 行 → None，
        # 因子自动关闭，行为退回 v17（降级路径有测试钉死）。
        mood_valence = await self._manager.get_mood_valence()

        # passive=True：叙事注入是系统被动读取，不能计入访问热度。
        # 否则会形成正反馈闭环——top 记忆每次注入都被刷新 last_used_at /
        # access_count，永远霸占相关性配额，新记忆挤不进来。
        relevance_k = min(3, max_items)
        relevant = await self._manager.recall(
            limit=relevance_k, passive=True, query=query,
            mood_valence=mood_valence,
        )
        recent = await self._manager.recent(max_items)
```

（build 签名不变 → graph.py、test_graph_hook / test_tokens 的 build 桩零改动。recent 路不加权：时近配额是防情绪固化的天然对冲。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_manager.py tests/test_narrative.py tests/test_graph_hook.py tests/test_pre_model_hook.py tests/test_memory_extract.py -q`
Expected: 全 PASS（含新增 7 例；既有叙事/钩子/提取测试验证 None 路径零变化——中性记忆 affinity 恒定 0.5 的等式性质同时保护了「conftest 种子 mood='开心' 下全部存量测试 valence=0」的排序不变性）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/memory/narrative.py backend/tests/test_memory_manager.py backend/tests/test_narrative.py
git commit -m "feat(memory): mood-congruent 浮现加权第六因子 — 加权而非硬过滤（schema v18）"
```

---

### Task 4: 情绪核心记忆 — upsert + 只存不浮现 + 心跳接线

**Files:**
- Modify: `backend/memory/manager.py`（`MOOD_MEMORY_ID`/`MOOD_MEMORY_CATEGORY`/`MOOD_MEMORY_SOURCE` 常量 + `_MOOD_ROW_FILTER` + 新增 `upsert_mood_memory` + 十个读面加排除谓词）
- Modify: `backend/heartbeat.py`（`check_and_proactive_message` 换词落库 + `daily_diary` SQL 排除）
- Test: `backend/tests/test_mood_memory.py`（新建）/ `backend/tests/test_heartbeat.py`（追加 4 例）

**Interfaces:**
- Consumes: Task 1 的两列；`update_content`（v15 superseded 链 + O3 链上限，行为不改）；`MOOD_VECTORS`（Task 3 已 import）。
- Produces: `MemoryManager.upsert_mood_memory(mood, now=None) -> dict | None`；`memories` 里固定 id `mood_current` 的 core 行 + superseded 回放链。

**关键决策回顾：**
- **固定确定性 id** `"mood_current"`：幂等 upsert、最便宜的 SQL 排除（`AND id != 'mood_current'`）、测试可直接断言。uuid 动态 id 每次写前还得先查，没有好处。
- **绕过 add 的写死 'active'**：新 manager 方法直接裸 SQL——首次 INSERT `status='core'`；此后走 `update_content(MOOD_MEMORY_ID, content)` 复用 superseded 链（用户要「可回放」；顺带继承 compress_history 的 5 代上限与 last_mention_ts 续命）。**不用 add 的 ON CONFLICT**：add 只会落 active，且去重语义（同内容只刷新）与「每次换词留一代历史」冲突。
- **content 文案**：`"{now} 起，小汐的心情是「{mood}」"`。时间戳入文案是必需——update_content 的 superseded 行 created_at 一律用本记忆**最初**创建时间（时序诚实设计），列上拿不到每代生效时间，只有文案能携带。副作用是每代 content 必不同 → 天然避免去重索引与幂等误判。
- **行上 valence/arousal**：存 MOOD_VECTORS[mood] 坐标（调制信号也落账本，导出/回放可读数值）。首次 INSERT 带上；更新时 update_content 不动这两列（不改共享方法），upsert 内补一条 UPDATE。
- **去重索引核对**（任务书要求）：部分唯一索引谓词是 `status IN ('active','core')`——情绪行是 core，**在谓词内**；但类别「小汐的心情」专属、content 含时间戳必不重复 → 不挡任何 add()，自身也不冲突。superseded 历史行不在谓词内 → 不挡同内容重提（虽然时间戳使「同内容」永不出现）。update_content 的 UPDATE 语句新 content 唯一 → 不触发 ON CONFLICT IntegrityError。
- **只存不浮现**：排除机制用 **id 谓词**（不用 category/source——单写入方系统里 id 最确定、最便宜、零歧义）。排除读面：recall（两路）/ recent / top_core / old_memories / recall_by_category / list_categories / count / all / check_memory_health / daily_diary 裸 SQL。保留：export_json（下载与清空前备份 = 回放账本）、history()（回放机制本身）、clear()（先备份后全删，语义不变）、apply_status_decay（只动 active，core 行天然免疫，**零工作**）。hit/access 计数器：不被任何读面返回 → `_count_hits` 天然不碰（测试断言钉死）。
- **category**：「小汐的心情」。与提取类别词汇表（extract.py 规则 4 / remember docstring：冷冷喜欢的/冷冷的信息/去过的地方/重要的事/其他）零冲突；不在 `_ARCHIVE_EXEMPT_CATEGORIES`（无意义——core 行不参与衰减）；list_categories 排除后 remember 工具的类别引导与 consolidate 的类别视野都看不见它。
- **心跳时机**：`check_and_proactive_message` 内 life_state commit 之后，条件与 mood_changed_at 刷新同一个 `mood != current_mood`——即**最小停留放行后/早晨锚定放行后**才落库；被拦回的换词自然不落。异常隔离（try/except + log），不拖累 life_state 主流程。

- [ ] **Step 1: 写失败测试**

**A. manager 层** —— 新建 `backend/tests/test_mood_memory.py`：

```python
"""情绪核心记忆回归（schema v18 情绪联动）。

「当前情绪」存成一条 core 记忆：只存不浮现（一切浮现/计数读面排除），
UPDATE 走 superseded 链 → 情绪可回放（history / 导出账本）。
"""
import pytest

from memory.manager import (
    MOOD_MEMORY_ID,
    MOOD_MEMORY_CATEGORY,
    MOOD_MEMORY_SOURCE,
)


async def test_upsert_creates_core_row(memory_mgr):
    """首次落情绪：core 行，固定 id，类别/来源/情绪坐标正确。"""
    row = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    assert row["id"] == MOOD_MEMORY_ID
    assert row["status"] == "core"
    assert row["category"] == MOOD_MEMORY_CATEGORY
    assert row["source"] == MOOD_MEMORY_SOURCE
    assert row["content"] == "2026-08-04 10:00 起，小汐的心情是「开心」"
    assert row["valence"] == pytest.approx(0.85)   # MOOD_VECTORS["开心"]
    assert row["arousal"] == pytest.approx(0.55)
```
```python


async def test_upsert_update_grows_superseded_chain(memory_mgr, tmp_db):
    """第二次换心情：走 update_content——旧说法落 superseded 链，
    created_at 保留首次，行上情绪坐标刷新为新心情。"""
    first = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    await memory_mgr.upsert_mood_memory("担心", now="2026-08-04 16:00")

    cursor = await tmp_db.execute(
        "SELECT content, created_at, valence, arousal FROM memories WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    content, created, valence, arousal = await cursor.fetchone()
    assert content == "2026-08-04 16:00 起，小汐的心情是「担心」"
    assert created == first["created_at"]          # 首次时间保留（可回放锚点）
    assert valence == pytest.approx(-0.55)          # 坐标刷新为新心情
    assert arousal == pytest.approx(0.25)

    history = await memory_mgr.history(MOOD_MEMORY_ID)
    assert len(history) == 1
    assert "开心" in history[0]["content"]
    assert history[0]["status"] == "superseded"
    assert history[0]["superseded_by"] == MOOD_MEMORY_ID


async def test_upsert_same_content_is_noop(memory_mgr, tmp_db):
    """同分钟同心情重复调用 → no-op，不空落历史行。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    again = await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    assert again is None
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE superseded_by = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_upsert_chain_capped_five_generations(memory_mgr, tmp_db):
    """换心情 8 次：superseded 链继承 O3 的 5 代上限（更旧压缩为 digest）。"""
    moods = ["开心", "好奇", "期待", "满足", "担心", "困倦", "兴奋", "活泼"]
    for i, m in enumerate(moods):
        await memory_mgr.upsert_mood_memory(m, now=f"2026-08-10 {i:02d}:00")

    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE superseded_by = ? AND status = 'superseded'",
        (MOOD_MEMORY_ID,),
    )
    assert (await cursor.fetchone())[0] <= 5

    cursor = await tmp_db.execute(
        "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert "活泼" in (await cursor.fetchone())[0]   # 当前行是最后一次心情


async def test_mood_row_excluded_from_all_surface_reads(memory_mgr, tmp_db):
    """只存不浮现：recall/recent/top_core/old_memories/recall_by_category
    /list_categories/count/all 全部排除情绪核心记忆。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    normal = await memory_mgr.add("冷冷的信息", "冷冷今天去了映海")

    assert [m["id"] for m in await memory_mgr.recall(passive=True)] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.recent()] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.top_core(limit=5)] == []  # core 席也不占
    assert [m["id"] for m in await memory_mgr.old_memories(min_age_days=0)] == [normal["id"]]
    assert await memory_mgr.recall_by_category(MOOD_MEMORY_CATEGORY) == []
    assert MOOD_MEMORY_CATEGORY not in await memory_mgr.list_categories()
    assert await memory_mgr.count() == 1
    assert [m["id"] for m in await memory_mgr.all()] == [normal["id"]]
    assert [m["id"] for m in await memory_mgr.all(include_archived=True)] == [normal["id"]]

    # hit/access 计数器天然不碰它（不被任何读面返回 → _count_hits 不触达）
    cursor = await tmp_db.execute(
        "SELECT hit_count, access_count FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert tuple(await cursor.fetchone()) == (0, 0)


async def test_check_memory_health_ignores_mood_row(memory_mgr):
    """健康检查只数关于冷冷的记忆：只有情绪行时仍是「零记忆」。

    回归：若情绪行计入，「0 记忆」告警恒假；且心情高频更新刷
    updated_at，「记忆停滞」告警也恒假——两条健康防线全废。
    """
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    health = await memory_mgr.check_memory_health()
    assert health["active_count"] == 0
    assert health["days_since_write"] is None


async def test_mood_row_kept_in_export_ledger(memory_mgr):
    """账本面保留：export_json 含情绪核心记忆与 superseded 链（回放源）。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-08-04 10:00")
    await memory_mgr.upsert_mood_memory("担心", now="2026-08-04 16:00")
    data = await memory_mgr.export_json(include_superseded=True)
    assert "2026-08-04 16:00 起，小汐的心情是「担心」" in data
    assert "2026-08-04 10:00 起，小汐的心情是「开心」" in data  # 历史链也在


async def test_mood_row_immune_to_status_decay(memory_mgr, tmp_db):
    """core 不降级：情绪记忆再老、零计数也不会被归档。"""
    await memory_mgr.upsert_mood_memory("开心", now="2026-01-01 10:00")
    await tmp_db.execute(
        "UPDATE memories SET created_at = '2026-01-01 10:00', "
        "last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    await tmp_db.commit()

    result = await memory_mgr.apply_status_decay()

    assert result["archived"] == 0
    cursor = await tmp_db.execute(
        "SELECT status FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == "core"
```

**B. 心跳接线测试** —— 追加到 `backend/tests/test_heartbeat.py`（沿用现有 `_run_heartbeat_at` 助手与 fake `_choose_mood` 模式）：

```python
# ── 情绪联动：心跳换词落情绪核心记忆（schema v18）────────────


async def test_heartbeat_mood_change_writes_mood_memory(tmp_db, monkeypatch):
    """换词放行（距上次 ≥6h）：心情变化落情绪核心记忆（core 行、固定 id）。"""
    import datetime as dt
    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    cursor = await tmp_db.execute(
        "SELECT status, category, content FROM memories WHERE id = ?",
        (MOOD_MEMORY_ID,),
    )
    row = await cursor.fetchone()
    assert row is not None
    assert row[0] == "core"
    assert row[1] == "小汐的心情"
    assert "开心" in row[2] and "2026-08-03 16:00" in row[2]


async def test_heartbeat_second_change_grows_chain(tmp_db, monkeypatch):
    """第二次换词：旧心情落 superseded 链（回放账本在心跳链路上成立）。"""
    import datetime as dt
    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID, MemoryManager

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()
    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    now_dt2 = dt.datetime(2026, 8, 4, 7, 0, tzinfo=hb._HKT)  # 次日早晨锚定
    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt2, "期待")

    history = await MemoryManager(tmp_db).history(MOOD_MEMORY_ID)
    assert len(history) == 1 and "开心" in history[0]["content"]
    cursor = await tmp_db.execute(
        "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert "期待" in (await cursor.fetchone())[0]


async def test_heartbeat_no_change_no_mood_memory(tmp_db, monkeypatch):
    """心情词未变：不落情绪记忆（首次部署生命状态行已有 mood 的场景）。"""
    import datetime as dt
    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 16, 0, tzinfo=hb._HKT)
    await tmp_db.execute(
        "UPDATE life_state SET mood = '开心', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_heartbeat_hold_blocks_mood_memory(tmp_db, monkeypatch):
    """最小停留拦回的换词：life_state 不变，情绪记忆也不落。"""
    import datetime as dt
    import heartbeat as hb
    from memory.manager import MOOD_MEMORY_ID

    now_dt = dt.datetime(2026, 8, 3, 10, 0, tzinfo=hb._HKT)  # 距 08:00 仅 2h
    await tmp_db.execute(
        "UPDATE life_state SET mood = '满足', mood_changed_at = '2026-08-03 08:00' "
        "WHERE id = 1"
    )
    await tmp_db.commit()

    (mood, _, _), _ = await _run_heartbeat_at(tmp_db, monkeypatch, now_dt, "开心")

    assert mood == "满足"  # 被 6h 最小停留拦回
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
    )
    assert (await cursor.fetchone())[0] == 0


async def test_daily_diary_excludes_mood_memory(tmp_db):
    """自动日记素材：情绪核心记忆是小汐自己的账本，不算「冷冷新告诉小汐的事」。"""
    from unittest.mock import AsyncMock, MagicMock, patch
    import heartbeat as hb
    from memory.manager import MemoryManager
    from utils.timezone import now_str

    mgr = MemoryManager(tmp_db)
    await mgr.upsert_mood_memory("开心", now=now_str())  # created_at = 今天
    await mgr.add("冷冷的信息", "冷冷今天说想去海边")

    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock(return_value=MagicMock(content="今天的日记内容。"))

    with patch("heartbeat.get_db", return_value=tmp_db), \
         patch("utils.events.get_db", return_value=tmp_db), \
         patch("providers.deepseek.create_deepseek_llm", return_value=fake_llm):
        await hb.daily_diary()

    material = fake_llm.ainvoke.await_args[0][0][1].content
    assert "冷冷今天说想去海边" in material
    assert "小汐的心情" not in material
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_mood_memory.py tests/test_heartbeat.py -q`
Expected: test_mood_memory.py 收集错误（`ImportError: cannot import name 'MOOD_MEMORY_ID'`）；test_heartbeat 新增 5 例 FAIL（`upsert_mood_memory` 不存在 / 情绪行未落 / 日记素材混入情绪行）。既有测试全绿。

- [ ] **Step 3: 实现**

**3a. `backend/memory/manager.py`** —— 模块常量区（`_MAX_SUPERSEDED_GENERATIONS` 附近）新增：

```python
# ── 情绪核心记忆（schema v18 情绪联动）────────────────────────
# 「当前情绪」固定行：status='core'，**只存不浮现**——一切浮现/计数
# 读面按 id 排除（_MOOD_ROW_FILTER），仅作回放（history / 导出账本）
# 与调制信号来源。固定确定性 id 的好处：幂等 upsert、最便宜的 SQL
# 排除谓词、测试可直接断言（uuid 动态 id 每次写前还得先查）。
MOOD_MEMORY_ID = "mood_current"
MOOD_MEMORY_CATEGORY = "小汐的心情"
MOOD_MEMORY_SOURCE = "system:mood"

# 排除谓词（id 排除而非 category/source：确定性单行、单写入方，谓词
# 最便宜且零歧义；superseded 链行已因 status='superseded' 天然跌出
# 读面，无需本谓词照顾）。挂在所有浮现/计数读面的 WHERE 后。
_MOOD_ROW_FILTER = f"AND id != '{MOOD_MEMORY_ID}'"
```

**3b. 新增 `upsert_mood_memory`** —— 放在 `top_core` 之后（分层与衰减区之前）：

```python
    async def upsert_mood_memory(self, mood: str, now: str | None = None) -> dict | None:
        """把一次心情变化落进情绪核心记忆（回放账本，schema v18 情绪联动）。

        首次调用 INSERT core 行（add() 只会落 active，且其同内容去重语义
        与「每次换词留一代历史」冲突，故情绪行走裸 SQL）；此后走
        update_content——旧说法落 superseded 历史链（v15）并继承链上限
        5 代压缩（compress_history），created_at 保留首次创建时间——
        情绪轨迹可逐代回放（history() / export_json）。

        content 内嵌落库时间：superseded 行的 created_at 一律是本记忆
        最初创建时间（时序诚实设计），列上拿不到每代生效时间，只有文案
        能携带；副作用是每代 content 必不同，天然规避去重索引与幂等误判。

        行上 valence/arousal 存 MOOD_VECTORS 坐标——调制信号也落账本
        （导出/回放可读数值）。update_content 不动这两列（不改共享方法），
        本方法在链更新后补一条 UPDATE。

        只存不浮现：本行被一切浮现/计数读面排除（_MOOD_ROW_FILTER），
        不进叙事注入、不参与 recall/top_core/old_memories 候选。
        同分钟同心情重复调用 → no-op 返回 None（不空落历史行）。
        """
        mood = (mood or "").strip()
        if not mood:
            return None
        now = now or _now()
        content = f"{now} 起，小汐的心情是「{mood}」"
        vec = MOOD_VECTORS.get(mood, (0.0, 0.0))

        cursor = await self._db.execute(
            "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
        )
        row = await cursor.fetchone()

        if row is None:
            await self._db.execute(
                """INSERT INTO memories
                   (id, content, category, created_at, last_used_at, access_count,
                    status, updated_at, source, last_mention_ts, importance,
                    valence, arousal)
                   VALUES (?, ?, ?, ?, ?, 0, 'core', ?, ?, ?, 5, ?, ?)""",
                (MOOD_MEMORY_ID, content, MOOD_MEMORY_CATEGORY, now, now,
                 now, MOOD_MEMORY_SOURCE, now, vec[0], vec[1]),
            )
            await self._db.commit()
        else:
            if row[0] == content:
                return None  # 同分钟同心情：no-op
            await self.update_content(MOOD_MEMORY_ID, content)
            await self._db.execute(
                "UPDATE memories SET valence = ?, arousal = ? WHERE id = ?",
                (vec[0], vec[1], MOOD_MEMORY_ID),
            )
            await self._db.commit()

        cursor = await self._db.execute(
            "SELECT * FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
        )
        return rows_to_dicts(cursor, [await cursor.fetchone()])[0]
```

**3c. 读面排除** —— manager.py 内十个查询挂 `_MOOD_ROW_FILTER`（改后 SQL 全文如下，均为 WHERE 后追加；f-string 化的加 f 前缀）：

recall 类别路（L500-504）：

```python
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE category LIKE ? ESCAPE '\' AND status IN ('active', 'core')
                    {_MOOD_ROW_FILTER}""",
                (f"%{_escape_like(category)}%",),
            )
```

recall 全量路（L511-513）：

```python
            cursor = await self._db.execute(
                f"SELECT * FROM memories WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}"
            )
```

recall_by_category（L582-586）：

```python
        cursor = await self._db.execute(
            f"""SELECT * FROM memories
                WHERE category LIKE ? ESCAPE '\' AND status IN ('active', 'core')
                {_MOOD_ROW_FILTER}
                ORDER BY created_at DESC LIMIT ?""",
            (f"%{_escape_like(category)}%", limit),
        )
```

recent（L598-604）：

```python
        cursor = await self._db.execute(
            f"""SELECT * FROM (
                    SELECT * FROM memories
                    WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}
                    ORDER BY COALESCE(last_mention_ts, created_at) DESC LIMIT ?   -- O1
                ) ORDER BY COALESCE(last_mention_ts, created_at) ASC""",
            (limit,),
        )
```

old_memories 两支（L631-646，exclude 有/无各一支）：

```python
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE status IN ('active', 'core')
                      AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
                      AND id NOT IN ({placeholders})
                      {_MOOD_ROW_FILTER}
                    ORDER BY RANDOM() LIMIT ?""",
                (cutoff, *exclude, limit),
            )
        else:
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE status IN ('active', 'core')
                      AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
                      {_MOOD_ROW_FILTER}
                    ORDER BY RANDOM() LIMIT ?""",
                (cutoff, limit),
            )
```

list_categories（L654-656）：

```python
        cursor = await self._db.execute(
            f"""SELECT category, COUNT(*) as cnt FROM memories
                WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}
                GROUP BY category"""
        )
```

count（L662-664）：

```python
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}"
        )
```

top_core（L710-713）——不排除会永久霸占 core 保底席位：

```python
        cursor = await self._db.execute(
            f"""SELECT * FROM memories WHERE status = 'core' {_MOOD_ROW_FILTER}
                ORDER BY COALESCE(last_used_at, created_at) DESC LIMIT ?""",
            (limit,),
        )
```

all（L692-696）：

```python
        status_filter = "IN ('active', 'core')" if not include_archived \
            else "IN ('active', 'core', 'archived')"
        cursor = await self._db.execute(
            f"SELECT * FROM memories WHERE status {status_filter} {_MOOD_ROW_FILTER} "
            "ORDER BY created_at DESC"
        )
```

check_memory_health 的 COUNT/MAX 查询（L790-796）——排除理由写进注释：

```python
        cursor = await self._db.execute(
            # O1：created_at 不可变后，写时钟看 MAX(COALESCE(updated_at,
            # created_at))——add/update 刷新 updated_at，hit 不刷，「距上次
            # 写入」该看它；只 update 不新增的记忆系统不该误报停滞。
            # v18：排除情绪核心记忆——它每小时可能被心跳 update，若计入，
            # 「0 记忆」与「记忆停滞」两条告警恒假，健康检查全废
            "SELECT COUNT(*), MAX(COALESCE(updated_at, created_at)) "
            f"FROM memories WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}"
        )
```

**不改动**（逐项核对过）：`export_json`（账本面保留情绪行与链——回放/备份源）；`history()`（回放机制本身）；`clear()`（全表删，清空前备份已含情绪行）；`delete()`（按 id 删，面板不可见该 id，无影响）；`apply_status_decay`（只动 active，core 情绪行天然免疫）；`compress_history`/`update_content`（被 upsert 复用，行为不改）。

**3d. `backend/heartbeat.py`** —— `check_and_proactive_message` 的 life_state commit（现 L170）之后、`log.info` 之前插入：

```python
        await conn.commit()

        # ── 情绪联动（schema v18）：心情变化落情绪核心记忆 ─────────
        # 只存不浮现：仅供回放（superseded 链）与调制信号，不参与任何
        # 浮现（manager._MOOD_ROW_FILTER 排除一切读面）。复用
        # update_content 历史链（5 代上限）。条件与 mood_changed_at 刷新
        # 同一判断——只有真换词（最小停留放行 / 早晨锚定放行）才落库，
        # 被拦回的换词自然不落。异常隔离，不拖累 life_state 主流程。
        if mood != current_mood:
            try:
                from memory.manager import MemoryManager
                await MemoryManager(conn).upsert_mood_memory(mood, now=now)
            except Exception:
                log.exception("情绪核心记忆落库失败")

        log.info("%02d:00 -> 心情：%s | 日常：%s", hour, mood, daily_event)
```

（局部 import 与 `_periodic_maintenance` 的既有风格一致；`memory.manager` 不 import heartbeat，无循环。）

**3e. `heartbeat.py` 的 `daily_diary`** —— 素材收集段 memories 裸 SQL（现 L849-854）加排除：

```python
    memories_lines = []
    try:
        from memory.manager import MOOD_MEMORY_ID
        conn = await get_db()
        # 读面谓词与全项目约定对齐 IN ('active','core')（体检 D6）。
        # v18 情绪联动：排除情绪核心记忆——那是小汐自己的心情账本，
        # 不是「冷冷新告诉小汐的事」，不该进日记素材
        cursor = await conn.execute(
            """SELECT category, content FROM memories
               WHERE status IN ('active', 'core') AND created_at LIKE ?
                 AND id != ?
               ORDER BY created_at DESC LIMIT 5""",
            (f"{today_str}%", MOOD_MEMORY_ID),
        )
        memories_lines = [f"- {r[0]}：{r[1][:60]}" for r in await cursor.fetchall()]
    except Exception as e:
        log.warning("自动日记读取今日记忆失败：%s", e)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_mood_memory.py tests/test_heartbeat.py tests/test_memory_manager.py tests/test_memory_decay.py tests/test_superseded.py tests/test_consolidate.py tests/test_memory_routes.py -q`
Expected: 全 PASS（含新增 8+5=13 例；test_memory_decay 的谓词一致性测试、test_consolidate 的类别视野、test_memory_routes 的面板端点验证排除无连带破坏）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/heartbeat.py backend/tests/test_mood_memory.py backend/tests/test_heartbeat.py
git commit -m "feat(memory): 情绪核心记忆 — 只存不浮现 + superseded 回放 + 心跳接线（schema v18）"
```

---

### Task 5: 收尾 — 全量 pytest + 文档状态同步

- [ ] **Step 1: 全量回归**

Run: `cd backend && .venv/Scripts/python.exe -m pytest -q`
Expected: 全绿（633 基线 + 本批新增约 26 例）。任何失败先修再进下一步。

- [ ] **Step 2: 更新 MEMORY_GAPS 状态**

Modify: `docs/MEMORY_GAPS_2026-08-03.md`
- 顶部状态块（L8-15）：在「尚未实施的：O4（记录不修）、情绪联动、健康检查多指标」中把「情绪联动」移出，追加一行：
  `> - 情绪联动 → schema v18（valence/arousal 标签 + mood-congruent 加权浮现 + 情绪核心记忆「只存不浮现」，2026-08-04，全量 pytest 绿）`
- 「情绪联动」小节（L36-42）三条各加 ✅ 标记并补实现位置（manager._mood_affinity / manager.upsert_mood_memory / extract prompt 规则 8）。

- [ ] **Step 3: 提交**

```bash
git add docs/MEMORY_GAPS_2026-08-03.md
git commit -m "docs: MEMORY_GAPS 情绪联动状态更新为已实施（schema v18）"
```

---

## Self-Review

**1. Spec coverage（对照需求三项）**
- 需求 1「记忆带 valence/arousal 标签（提取时 LLM 顺带打分）」→ Task 1（两列 + 三源 DDL + 存量 0 中性回填）+ Task 2（prompt 规则 8 三档锚点只对 add 生效；_dispatch 透传；_coerce_valence/_coerce_arousal 缺失/非法/NaN → 0.0、超界钳 [-1,1]）✓
- 需求 2「浮现按当前情绪效价加权（加权而非硬过滤，防情绪固化）」→ Task 3：第六因子亲和度 1−|Δv|/2、权重 0.15（压不过 topic 的 1.0 档与二段硬分层）；`test_recall_mood_valence_biases_ranking` 断言两种心情下异调记忆**仍在结果里**（加权不硬过滤）；中性免疫等式测试钉死「NULL/0 不被系统性惩罚」；recent 时近配额 + old_memories 保持纯随机 = 防固化的两条对冲 ✓
- 需求 3「当前情绪存核心记忆 + superseded 回放 + 只存不浮现」→ Task 4：固定 id core 行（绕过 add 的 active 写死，裸 INSERT）；换词走 update_content 落 superseded 链（继承 5 代上限）；content 带时间戳可回放；heartbeat 在最小停留/早晨锚定放行后接线；十个读面 id 谓词排除，叙事注入（经 recall/recent/top_core）自然不见它；hit/access 计数天然不碰 ✓

**2. Placeholder scan**：无 TBD/TODO；每个实现步骤均为完整代码块（无省略号）。

**3. Type consistency（新签名所有调用点）**
- `add(category, content, source=None, importance=5, valence=None, arousal=None)`：remember 工具不传新参（默认 0.0 中性）✓；extract `_dispatch` 传 `act.get(...)`（None → 0.0）✓；upsert_mood_memory 不走 add（裸 INSERT）✓
- `_relevance_score(memory, now, query="", mood_valence=None)`：既有直调测试用 `(mem, now, "")` 位置参，兼容 ✓；recall 内部两处 `_sort_key` 均已传第四参 ✓
- `recall(..., mood_valence=None)`：narrative.build 传 `get_mood_valence()` 结果 ✓；extract L330 与 tools/memory.py 不传 → None → 因子关闭 ✓
- `upsert_mood_memory(mood, now=None)`：heartbeat 传 `(mood, now=now)`（now 是已格式化的 "%Y-%m-%d %H:%M"）✓；测试传显式 now 字符串 ✓
- `get_mood_valence() -> float | None`：narrative 传给 recall 的 mood_valence 参数类型一致（float | None）✓
- `_MOOD_ROW_FILTER` 只拼进 WHERE 后，全部十个查询的参数绑定逐一核对过（占位符数量不变）✓

**4. 循环导入风险（已逐一核对 import 图）**
- `memory.manager` → `heartbeat_moods`（顶层）：heartbeat_moods 只 import datetime/math/random/utils.logger，不 import heartbeat 也不 import memory → 无环 ✓
- `heartbeat` → `memory.manager`（函数内局部 import，两处）：memory.manager 不 import heartbeat → 无环；局部 import 与 _periodic_maintenance 既有风格一致，不改动模块级加载序 ✓
- `memory.narrative` 无新增 import ✓；`memory.extract` 无新增 import ✓

**5. 前后端零改动确认**
- life_state schema 与 mood 词表（10 词）不变 → 前端 moodToSprite 映射、/api/status 的 mood 字段、soul.py 的「今天我的心情」注入全部零改动 ✓
- graph.py / pre_model_hook 零改动（build 签名不变）→ test_graph_hook / test_tokens 的桩兼容 ✓
- 前端记忆面板 GET /api/memories → all()：唯一行为变化是内部账本行不出现（有意设计）；新列 valence/arousal 经 SELECT * 下发导出 JSON，前端忽略未知字段 ✓
- SSE 协议、remember/update_memory/recall 工具签名不变 ✓

**6. 顺序依赖**
- T1 先于一切（T2/T3/T4 都依赖新列存在——conftest DDL 缺列会让 add 的 13 列 INSERT 直接 OperationalError）。
- T2 → T3：T3 的排序测试需要 add 能落 valence。
- T3 → T4：T4 复用 T3 引入的 MOOD_VECTORS 顶层 import（单独做 T4 时也要加，顺序只是省事）。
- 每 Task 内部严格「写失败测试 → 跑失败 → 实现 → 跑绿 → 提交」；Task 4 的读面排除与 upsert 在同一个提交（排除没有 upsert 就无行可排，拆开会出假绿/假红）。

**7. 已知限制（记录在案，不修）**
- superseded 链行不复制 valence/arousal（update_content 不改；回放靠 content 文案里的时间戳+心情词；未来做「心情轨迹图表」时可加列传播或解析文案）。
- 情绪行在**首次心情变化**时才创建（首次部署若心情一直未变则无行）——无碍：get_mood_valence 读 life_state 而非情绪行，调制信号不依赖它存在。
- old_memories 不加权（理由见 Task 3 决策）；extract 冲突上下文不加权（有意：矛盾检测不该被心情染色）。

## 执行方式

**本仓库后台 agent 不可靠（项目记忆复核：2026-08-02/03 四批全部失联无提交）——用内联执行，不派后台 agent。**

按 Task 1→5 顺序执行，每 Task 走「写失败测试 → 跑失败 → 实现 → 跑绿 → 提交」循环，完成一个再进下一个。pytest 命令统一：`cd backend && .venv/Scripts/python.exe -m pytest -q`（子集跑相关文件提速，Task 5 全量收尾）。提交信息照各 Task 给出的文案（Conventional Commits 风格，与 v17 批一致）。
