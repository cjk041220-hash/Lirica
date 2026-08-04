# 记忆健康检查多指标实施计划（2026-08-04）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/MEMORY_GAPS_2026-08-03.md` 最后一项「健康检查多指标」（提取率 / 记忆增长率 / 归档率），防单指标误报，并建立基线积累机制。这也是 MEMORY_GAPS 的**收尾批次**：O1/O2/I1/O3 + 情绪联动（schema v18）均已实施，O4 记录不修——本批完成后记忆库诊断全部闭环。

**Architecture:** 全部改动在 `backend`（Python），**零 schema 变更**（无 v19 迁移——所有指标纯 SQL 窗口统计，`memories.created_at` 自 v17 起不可变 = 首次记住时间戳，`chat_messages.created_at` 天然时间戳；归档时间戳不存在且不值得为此加列）。`manager.py` 是改动主体（`check_memory_health` 扩展 + `_HEALTH_WINDOW_DAYS` 常量 + abnormal 组合判定升级）；`memory/extract.py` 是消费方（INFO 打点扩为多指标时间序列 = 基线数据源；告警按三态区分文本）。不动前端、不动 schema、不引入新依赖、不改 SSE/工具签名。

## 勘察结果（2026-08-04 设计前读码核对）

任务书（MEMORY_GAPS）所说事实全部核对属实，补充数据面事实：

- **`chat_messages`**（迁移 v8 DDL）：`id AUTOINCREMENT / session_id / role / content / created_at`——可按 created_at 窗口统计对话体量。conftest 的 tmp_db 经共享常量 `CHAT_ARCHIVE_SCHEMA_SQL` 已建表 ✓
- **`memories.created_at`**：v17（O1）起 add 去重不再刷新、update_content 保留——**不可变 = 首次记住时间**，`WHERE created_at >= cutoff` 就是「近窗口新增」，无需差分快照。superseded 历史行 status='superseded' 不在读面谓词，窗口统计按 `status IN ('active','core')` 天然排除 ✓
- **归档时间戳不存在**：`apply_status_decay` 的 UPDATE 只改 status 不刷 updated_at——「近 N 天归档数」无法窗口统计。归档率改用**存量占比**（archived / active+core+archived），反映「库是否在膨胀/积压」的慢信号 ✓
- **`memory_extract_state`** 单行表只有 last_run_at / health_warned_at / fail_count / consolidate_ran_at——无历史。趋势数据源选「每 2h INFO 日志时间序列」（零成本、grep 即得），不做快照差分列（零 schema 纪律）✓
- **现有两个误报点**（多指标批要修的）：
  1. 「写路径失灵」只看全历史 `count == 0`——纯闲聊 7 天也会报；且**抓不到「曾经写过、最近停摆」**（2026-08-01 实盘教训 D3 的后半段场景）；
  2. 「记忆停滞」`days_since_write >= 7` **无对话也报**——7 天没打开小汐 = 正常闲置，误报。
- **测试夹具**：`test_memory_extract.py` 的 `_seed_messages` 灌的 created_at 是**今天**（近 7 天窗口内），现有健康检查测试语义与窗口判定天然兼容；唯一需语义更新的既有测试是 `test_health_abnormal_stale`（10 天前记忆 + 无对话，新语义下应为正常）✓
- `manager.py` 已有 `_TIME_FMT` / `_HKT` / `_now` 导入（utils.timezone）；`_MOOD_ROW_FILTER`（schema v18）是既有的情绪账本行排除谓词，新增查询全部沿用 ✓

## 关键决策（7 个决策点的答案）

1. **零 schema 变更**：所有指标 = 纯 SELECT 窗口统计。MEMORY_GAPS 建议的「提取率 / 记忆增长率 / 归档率」中，前两个直接可算；归档率因无归档时间戳改用存量占比（决策 2）。不做 memory_extract_state 快照差分列——趋势 = 日志时间序列（每 2h 一条 INFO，已含全部指标）。
2. **归档率 = 存量占比** `archived / (active + core + archived)`（全表 0 → 0.0）：SQLite 拿不到归档发生时间（apply_status_decay 不刷 updated_at），窗口差分需加列——为一个观测指标动 schema 不值。占比语义：长期高 → 库在冷藏积压；长期 0 且库大 → 衰减可能失效（consolidate 之外的观测口）。
3. **窗口**：`_HEALTH_WINDOW_DAYS = 7` 独立常量（不与 `_HEALTH_WARN_DAYS` 复用——两值今天恰巧都 7，但语义不同：写路径窗口 vs 停滞阈值，未来调参不应互相牵连）。截止时间 `now - timedelta(days=7)`，与 days_since_write 的「天」粒度一致。
4. **abnormal 组合升级（修两个误报 + 补一个新检测）**：
   - 写路径异常：`chats_7d >= _HEALTH_MIN_CHATS and added_7d == 0`——从「全历史 count==0」改为 7 天窗口判定，**覆盖两种失灵**：从未写入（count==0 且窗口有对话）与最近停摆（count>0 但窗口零新增——现有检查抓不到的后半段）；
   - 记忆停滞：`days_since_write >= _HEALTH_WARN_DAYS and chats_7d >= 1`——**有对话才报**；7 天没打开小汐（无对话）不报，修误报 2。
   - 旧判定 `count == 0 and chat_count >= 10`（全历史）不再单独保留：`count==0 且全历史对话 ≥10` 蕴含在窗口判定里（对话都发生在过去 7 天内才成立时；更早的对话本就不该触发「最近」写路径告警）。`chat_count`（全历史）键保留返回体（向后兼容），不再参与判定。
5. **提取率不进 abnormal**：默认动作「今天没事发生」天然压低提取率——纯闲聊 7 天 10 条与写路径失灵在指标上不可区分（这正是「需先建立基线」的含义：基线数据 = 每 2h INFO 日志，攒 2-4 周后可从实盘分布里校准「正常闲聊的提取率下限」，届时再决定是否加告警）。本轮提取率**只做观测**：进返回体 + INFO 打点，阈值注释写明「待实盘基线校准」。
6. **归档率不进 abnormal**：慢信号（库健康观测）。consolidate 与分层衰减本身就是预期活动（合并/归档会让 count 变化，不构成异常）。同样只做观测。
7. **职责划分**：`manager.check_memory_health` 保持「纯只读、无副作用」契约（docstring 明示，消费方负责日志与节流——v11 既有分工）；extract 层负责 INFO 打点扩展 + 告警三态文本（从未写入 / 最近停摆 / 停滞且有对话）。返回体**向后兼容**：既有四键（active_count / days_since_write / chat_count / abnormal）语义不变（chat_count 保留全历史义），新键附加。abnormal 的语义变化是有意的行为修正，对应 2 个测试的语义更新。

## Global Constraints

- 改完必须全量 pytest 绿：`cd backend && .venv/Scripts/python.exe -m pytest -q`（现有 663 passed 基线不能被破坏）。
- **零 schema 变更**：不动 migrations.py / core.py init_db / conftest DDL；无 v19。
- **不引入新依赖**；不改任何工具签名与 SSE 协议；前端零改动。
- 新增 SQL 全部挂 `_MOOD_ROW_FILTER`（情绪账本行不进窗口统计与占比——它每小时被心跳 update，计入会污染「近窗口新增」与归档占比语义）。
- 返回体新键全部**默认有值**（None 只在提取率无对话时出现）——消费方无需 get() 防御。
- 阈值常量保持集中在 manager.py 尾部「记忆健康检查阈值」区；extract 层需要 `_HEALTH_MIN_CHATS` 做告警三态分支 → 从 memory.manager 显式 import（现有已 import MemoryManager，同文件无环）。
- 内联执行，不派后台 agent（项目记忆复核：2026-08-02/03/04 多批后台 agent 全部失联，已成复现规律）。

---

### Task 1: manager 多指标 + abnormal 组合判定

**Files:**
- Modify: `backend/memory/manager.py`（`check_memory_health` 扩展 + `_HEALTH_WINDOW_DAYS` 常量）
- Modify: `backend/tests/test_memory_extract.py`（健康检查段：1 例语义更新、1 例移除拆 2、新增 4 例）
- Test: `backend/tests/test_memory_extract.py` / `backend/tests/test_memory_decay.py` / `backend/tests/test_mood_memory.py`

**Interfaces:**
- Consumes: `chat_messages.created_at`；`memories.created_at/status`（v17 不可变 + 读面谓词）；`_MOOD_ROW_FILTER`（v18）。
- Produces: `check_memory_health() -> dict` 新键 `chats_7d / added_7d / extract_rate_7d / archived_count / archive_ratio`；abnormal 组合判定升级。

**背景：** MEMORY_GAPS 最后一项。单指标（全历史 count==0、无对话也报的停滞）有两个已知误报 + 一个盲区（曾经写过、最近停摆）。多指标 = 7 天窗口活动统计（提取率） + 库结构观测（归档占比），组合判定修误报，观测指标积累基线。

- [ ] **Step 1: 写失败测试**

`backend/tests/test_memory_extract.py` 健康检查段（现 L477-534）改造：

**A. 语义更新** `test_health_abnormal_no_memories_with_chats`（L480-486）追加两行窗口断言（10 条对话都是今天灌的 → chats_7d=10）：

```python
async def test_health_abnormal_no_memories_with_chats(tmp_db):
    """有足够对话量（≥10 条）却零记忆 → 写路径异常信号（从未写入）。"""
    await _seed_messages(tmp_db, ["在吗"] * 10)
    health = await MemoryManager(tmp_db).check_memory_health()
    assert health["active_count"] == 0
    assert health["chat_count"] == 10
    assert health["abnormal"] is True
    assert health["chats_7d"] == 10
    assert health["added_7d"] == 0
```

**B. 移除** `test_health_abnormal_stale`（L489-501，10 天前记忆 + 无对话——新语义下是正常闲置），**替换为**两个语义分明的测试：

```python
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
```

**C. 新增**（追加在 `test_health_normal_fresh_memory` 之后）：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_extract.py -q`
Expected: 新增 4 例 FAIL（KeyError 'chats_7d' 等键不存在）；`test_health_abnormal_stale_with_chats` FAIL（abnormal 现为 False——无对话不报）；`test_health_idle_no_chats_is_normal` FAIL（abnormal 现为 True——误报未修）；`test_health_abnormal_no_memories_with_chats` 的追加断言 FAIL（键不存在）。`test_health_abnormal_stopped_after_prior_memories` FAIL（abnormal False）。其余既有测试全绿（键新增不影响旧断言）。

- [ ] **Step 3: 实现**

**3a. `backend/memory/manager.py`** —— 阈值常量区（L1031-1032）改为：

```python
# ── 记忆健康检查阈值 ─────────────────────────────────────────────
# 写路径异常（近 _HEALTH_WINDOW_DAYS 天窗口）：窗口内 ≥_HEALTH_MIN_CHATS
# 条对话却零新增记忆——覆盖「从未写入」与「曾经写过、最近停摆」两种失灵；
# 记忆停滞：距上次写入 ≥_HEALTH_WARN_DAYS 天且窗口内仍有对话——「7 天没
# 打开小汐」是正常闲置，不该报（2026-08-04 多指标批修误报）。
# 阈值偏保守（宁可错报一次，不可再静默一周）。
# 提取率/归档占比不做告警阈值（基线积累期）：默认动作「今天没事发生」
# 天然压低提取率，告警下限需 2-4 周实盘 INFO 日志时间序列校准后再定。
_HEALTH_MIN_CHATS = 10
_HEALTH_WARN_DAYS = 7
_HEALTH_WINDOW_DAYS = 7
```

**3b. `check_memory_health`** 完整重写（L965-1007）：

```python
    async def check_memory_health(self) -> dict:
        """记忆系统健康指标（纯只读，无副作用、不写日志）。

        实盘教训（2026-08-01 评审 D3）：写入完全依赖 LLM 自觉调用工具，
        曾出现「31 条真实对话、0 条记忆」跑一周无人察觉。本方法产出
        abnormal 信号，由调用方（memory/extract.py 的
        check_and_report_memory_health）负责节流告警：

        - 写路径异常（近 _HEALTH_WINDOW_DAYS 天窗口）：窗口内有相当
          对话量（>= _HEALTH_MIN_CHATS）却零新增记忆——覆盖「从未写入」
          与「曾经写过、最近停摆」两种失灵（2026-08-04 多指标批）；
        - 记忆停滞：距上次写入超过 _HEALTH_WARN_DAYS 天且窗口内仍有
          对话——「7 天没打开小汐」是正常闲置，不该报（原实现无对话
          也报，误报；2026-08-04 修正）。

        多指标（2026-08-04，MEMORY_GAPS 健康检查收尾）：提取率 /
        归档占比等只做观测信息（进返回体与消费方 INFO 日志），不参与
        abnormal——默认动作「今天没事发生」会天然压低提取率，告警阈值
        需实盘基线（每 2h INFO 日志时间序列）校准后再考虑。
        """
        cursor = await self._db.execute(
            # O1：created_at 不可变后，写时钟看 MAX(COALESCE(updated_at,
            # created_at))——add/update 刷新 updated_at，hit 不刷，「距上次
            # 写入」该看它；只 update 不新增的记忆系统不该误报停滞
            # v18：排除情绪核心记忆——它每小时可能被心跳 update，若计入，
            # 「0 记忆」与「记忆停滞」两条告警恒假，健康检查全废
            "SELECT COUNT(*), MAX(COALESCE(updated_at, created_at)) "
            f"FROM memories WHERE status IN ('active', 'core') {_MOOD_ROW_FILTER}"
        )
        row = await cursor.fetchone()
        count, newest = (row[0], row[1]) if row else (0, None)

        now = datetime.datetime.now(_HKT)
        days_since_write = None
        if newest:
            days_since_write = max(0.0, (now - _parse_time(newest)).total_seconds() / 86400)

        # ── 多指标（2026-08-04）：近窗口活动 + 库结构观测 ──────────
        # 窗口统计全部基于 created_at（v17 后不可变 = 首次记住时间戳）：
        # 「近窗口新增」无需差分快照。新增数按读面谓词过滤（superseded
        # 历史行与情绪账本行都不算——v18 _MOOD_ROW_FILTER 纪律贯穿）。
        # 归档占比是存量而非窗口差：SQLite 无归档时间戳（apply_status_decay
        # 不刷 updated_at），占比反映「库是否在膨胀/积压」的慢信号。
        cutoff = (now - datetime.timedelta(days=_HEALTH_WINDOW_DAYS)).strftime(_TIME_FMT)
        cursor = await self._db.execute(
            "SELECT COUNT(*) FROM chat_messages WHERE created_at >= ?", (cutoff,)
        )
        chats_7d = (await cursor.fetchone())[0]
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE created_at >= ? "
            f"AND status IN ('active', 'core') {_MOOD_ROW_FILTER}",
            (cutoff,),
        )
        added_7d = (await cursor.fetchone())[0]
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE status = 'archived' {_MOOD_ROW_FILTER}"
        )
        archived_count = (await cursor.fetchone())[0]
        total = count + archived_count
        archive_ratio = archived_count / total if total else 0.0

        chats = 0
        if count == 0:
            # 只在零记忆时才查全历史对话量（兼容旧返回体语义）
            cursor = await self._db.execute("SELECT COUNT(*) FROM chat_messages")
            chats = (await cursor.fetchone())[0]

        abnormal = (
            (chats_7d >= _HEALTH_MIN_CHATS and added_7d == 0)
            or (
                days_since_write is not None
                and days_since_write >= _HEALTH_WARN_DAYS
                and chats_7d >= 1
            )
        )
        return {
            "active_count": count,
            "days_since_write": days_since_write,
            "chat_count": chats,
            "abnormal": abnormal,
            # 多指标（2026-08-04，基线积累期——告警阈值待实盘数据校准）
            "chats_7d": chats_7d,
            "added_7d": added_7d,
            "extract_rate_7d": (added_7d / chats_7d) if chats_7d else None,
            "archived_count": archived_count,
            "archive_ratio": archive_ratio,
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_extract.py tests/test_memory_decay.py tests/test_mood_memory.py -q`
Expected: 全 PASS。test_memory_decay 的「update 刷新不报停滞」（L440-453：无对话 + days_since_write<7）不受影响；test_mood_memory 的「情绪行不计入」（active_count==0 / days_since_write is None）不受影响（abnormal 两分支都依赖窗口对话，mood 行被排除后 chats_7d=0）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/manager.py backend/tests/test_memory_extract.py
git commit -m "feat(memory): 健康检查多指标 — 7 天窗口提取率/增长率/归档占比 + 组合判定修两误报"
```

---

### Task 2: extract 消费方 — 多指标 INFO 打点 + 告警三态

**Files:**
- Modify: `backend/memory/extract.py`（`check_and_report_memory_health`：INFO 打点扩展 + 告警三态文本；import `_HEALTH_MIN_CHATS`）
- Modify: `backend/tests/test_memory_extract.py`（健康检查段追加 3 例 + INFO 断言扩展）

**Interfaces:**
- Consumes: Task 1 的多指标返回体。
- Produces: INFO 日志含提取率/归档占比（基线时间序列）；WARNING 按三态区分文本（从未写入 / 最近停摆 / 停滞且有对话）。

**背景：** manager 侧指标已就位，消费方是观测与告警的出口。INFO 打点扩为完整多指标——每 2h 一条即「基线积累」的时间序列数据源（MEMORY_GAPS 明言需先建立基线）。告警文本三态化：同一 abnormal 下三种根因（写路径从未写入 / 写路径最近停摆 / 记忆停滞），日志可直接区分排查方向。

- [ ] **Step 1: 写失败测试**

`backend/tests/test_memory_extract.py` 健康检查段追加（`test_health_report_warns_then_throttles` 之后）：

```python
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
    mgr = MemoryManager(tmp_db)
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
```

`test_health_report_normal_logs_info`（L526-534）INFO 断言扩展——多指标进日志（基线数据源）：

```python
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_extract.py -q`
Expected: 新增 3 例 FAIL（现有告警文本不含「从未写入/停摆/停滞」三态；test_health_report_warns_never_written 现文本是「已有 %d 条对话消息但 0 条记忆」——含「记忆」但无「从未写入」）；INFO 断言 FAIL（无「提取率」）。其余全绿。

- [ ] **Step 3: 实现**

**3a. `backend/memory/extract.py`** —— import 行（L31）改为：

```python
from memory.manager import MemoryManager, _HEALTH_MIN_CHATS
```

**3b. `check_and_report_memory_health`**（L427-467）重写：

```python
async def check_and_report_memory_health(conn=None) -> dict:
    """记忆健康检查 + 节流告警（心跳维护第 5 段）。

    实盘教训（2026-08-01 评审 D3）：0 记忆跑一周无告警。本函数把
    manager.check_memory_health 的纯指标转成日志：异常时 24h 节流
    WARNING（三态区分——从未写入 / 最近停摆 / 停滞且有对话，日志可
    直接区分排查方向），正常时 INFO 打点。INFO 行含全部多指标
    （提取率 / 归档占比）= 基线积累的时间序列数据源（2026-08-04
    MEMORY_GAPS 收尾：告警阈值待 2-4 周实盘数据校准）。
    返回 {指标..., "warned": bool}。
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()
    mgr = MemoryManager(conn)
    health = await mgr.check_memory_health()

    if not health["abnormal"]:
        stale = (
            f"{health['days_since_write']:.0f}天"
            if health["days_since_write"] is not None
            else "从未写入"
        )
        rate = (
            f"{health['extract_rate_7d'] * 100:.0f}%"
            if health["extract_rate_7d"] is not None
            else "无对话"
        )
        log.info(
            "记忆健康：count=%d, 距上次写入=%s, 近7天 chats=%d/新增=%d"
            "（提取率 %s, 归档占比 %d%%）",
            health["active_count"], stale, health["chats_7d"],
            health["added_7d"], rate, health["archive_ratio"] * 100,
        )
        return {**health, "warned": False}

    now = datetime.datetime.now(_HKT)
    if not await _throttle_allowed(conn, now):
        return {**health, "warned": False}
    await _mark_health_warned(conn, now)

    if health["active_count"] == 0:
        log.warning(
            "记忆健康异常：近 7 天已有 %d 条对话消息但 0 条记忆 —— 写路径"
            "疑似失灵（从未写入），检查 remember 工具与后台提取",
            health["chats_7d"],
        )
    elif health["chats_7d"] >= _HEALTH_MIN_CHATS and health["added_7d"] == 0:
        log.warning(
            "记忆健康异常：近 7 天已有 %d 条对话消息但 0 条新增记忆 —— 写路径"
            "疑似停摆（库里有 %d 条旧记忆），检查后台提取水位线与 remember",
            health["chats_7d"], health["active_count"],
        )
    else:
        log.warning(
            "记忆健康异常：%d 条记忆但已 %.0f 天无新写入，且近 7 天仍有对话"
            " —— 记忆停滞",
            health["active_count"], health["days_since_write"],
        )
    return {**health, "warned": True}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && .venv/Scripts/python.exe -m pytest tests/test_memory_extract.py -q`
Expected: 全 PASS（含新增 3 例与 INFO 扩展断言；`test_health_report_warns_then_throttles` 的节流语义不变——首次 WARNING 仍只记一次）。

- [ ] **Step 5: 提交**

```bash
git add backend/memory/extract.py backend/tests/test_memory_extract.py
git commit -m "feat(memory): 健康告警三态文本 + INFO 多指标打点（基线时间序列数据源）"
```

---

### Task 3: 文档同步 + 全量回归

- [ ] **Step 1: 全量回归**

Run: `cd backend && .venv/Scripts/python.exe -m pytest -q`
Expected: 全绿（663 基线 + 本批净增约 6 例）。任何失败先修再进下一步。

- [ ] **Step 2: 更新 MEMORY_GAPS 状态**

Modify: `docs/MEMORY_GAPS_2026-08-03.md`
- 顶部状态块（L8-17）：把「尚未实施的：O4（记录不修）、健康检查多指标」改为「尚未实施的：O4（记录不修）——健康检查多指标已于 2026-08-04 实施（全量 pytest 绿）」，并追加一行：
  `> - 健康检查多指标 → 2026-08-04（7 天窗口提取率 / 新增增长率 / 归档存量占比；组合判定修「7 天不用误报停滞」+ 新增「曾经写过、最近停摆」检测；提取率/归档占比仅观测，告警阈值待 2-4 周实盘 INFO 日志基线校准）`
- 「健康检查」小节（L55-59）补一句实施位置：`manager.check_memory_health` 多指标 + abnormal 组合判定；`extract.check_and_report_memory_health` 告警三态 + INFO 多指标打点（基线数据源）。

- [ ] **Step 3: 提交**

```bash
git add docs/MEMORY_GAPS_2026-08-03.md docs/superpowers/plans/2026-08-04-memory-health-metrics.md
git commit -m "docs: MEMORY_GAPS 健康检查多指标状态更新为已实施（记忆库诊断闭环）"
```

---

## Self-Review

**1. Spec coverage（对照 MEMORY_GAPS 建议）**
- 「补多指标（提取率 / 记忆增长率 / 归档率）」→ Task 1：`extract_rate_7d`（近 7 天新增/近 7 天对话）、`added_7d`（增长率分子，7 天窗口绝对新增）、`archive_ratio`（归档存量占比——归档时间戳不存在，占比是可行替代并写明理由）✓
- 「防单指标误报」→ Task 1 组合判定：停滞加「窗口有对话」条件（修「7 天不用误报」）；写路径改窗口判定（纯闲聊误报面收窄为 7 天 ≥10 条）✓
- 「默认动作天然压低提取率，需先建立基线」→ 提取率不进 abnormal；基线 = 每 2h INFO 日志多指标时间序列（Task 2）；阈值注释与文档明示「2-4 周实盘校准」✓

**2. 已知限制（记录在案）**
- 「纯闲聊 7 天 ≥10 条 + 0 新增」与「写路径停摆」在指标上不可区分——提取率基线未建前，写路径告警阈值保持保守（宁可错报一次，不可再静默一周）。这正是基线积累期存在的理由。
- 归档占比是存量快照而非时间差：consolidate 的合并行（update_content，count 不变）与 clear（全删）都不在占比里显形——占比只观测「分层衰减是否在工作」的慢信号。
- `chat_count`（全历史）保留返回体但不再参与判定（决策 4）。

**3. Type consistency（返回体与消费方）**
- `check_memory_health()` 新键全部有值（除无对话时 `extract_rate_7d=None`）；消费方三处引用（INFO 打点 3 键、告警 2 键）逐一核对存在 ✓
- `_HEALTH_MIN_CHATS` 从 manager import 到 extract：memory.manager 不 import memory.extract → 无环 ✓
- 既有消费方（test_memory_decay / test_mood_memory / test_memory_extract 旧断言）只读旧四键 → 兼容 ✓

**4. 顺序依赖**
- T1 → T2：T2 的告警三态分支依赖 T1 的 `added_7d / chats_7d` 键（T2 测试在 T1 后自然绿）。
- T3 文档最后（状态写「已实施」需真实全绿）。

**5. 零 schema / 零前端确认**
- 无迁移、无 DDL 改动（conftest 不动——指标全部走既有表）；SCHEMA_VERSION 停留 18 ✓
- 前端零改动（健康检查是后端心跳内部件，无 API 面）✓
- 不引入新依赖 ✓

## 执行方式

**本仓库后台 agent 不可靠（项目记忆复核：2026-08-02/03/04 多批全部失联无提交）——用内联执行，不派后台 agent。**

按 Task 1→3 顺序执行，每 Task 走「写失败测试 → 跑失败 → 实现 → 跑绿 → 提交」循环，完成一个再进下一个。pytest 命令统一：`cd backend && .venv/Scripts/python.exe -m pytest -q`（子集跑相关文件提速，Task 3 全量收尾）。提交信息照各 Task 给出的文案（Conventional Commits 风格，与 v17/v18 批一致）。
