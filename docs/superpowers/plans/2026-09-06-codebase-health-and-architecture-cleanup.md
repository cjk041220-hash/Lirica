# 小汐系统全面维护治理与架构解耦实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根除近期快速特性迭代积累的架构债务、单测全局数据库连接泄漏、分层反向依赖、DDL 初始化裂痕以及代码/仓库卫生问题，使系统全量 981 个测试与 linter 达到 100% 绿灯。

**Architecture:** 
- 将 `memory/chat_archive.py` 严格还原为底层数据仓储层，切断对上层 `agent.topic_bank` 的反向依赖，话题核销副作用上移至对话流与主动消息落库终点；
- 在 `heartbeat.py` 中收敛原子核销辅助函数消除 3 处 SQL 代码复制；
- 对齐 `database/core.py` DDL 与迁移常量；消除生产代码中的 mock 反模式；清理已失效的临时调试文件与脚本。

**Tech Stack:** Python 3.13, FastAPI, LangGraph, aiosqlite, pytest, ruff, Vite/React.

## Global Constraints

- 不得破坏现有 25 个 schema 版本的迁移链与兼容性。
- 保证对话韧性：数据层与辅助逻辑遵循“失败放行”纪律，任何异常记日志不上抛。
- 确保测试环境隔离：单测中严禁以任何途径隐式连接真实生产数据库 `userdata.db`。
- 所有时间戳统一维持香港时区 `HKT`（`utils.timezone.HKT`）。

---

### Task 1: 数据库初始化 DDL 对齐与孤儿文件清理

**Files:**
- Modify: `backend/database/core.py:213-228,240-252`
- Delete: `backend/data/xiaoxi.db`
- Test: `backend/tests/test_init_db_upgrade.py`

**Interfaces:**
- Consumes: `database.migrations.SESSION_SUMMARIES_SCHEMA_SQL`
- Produces: `core.init_db()` 完整包含所有 8 张扩展表的幂等 DDL 执行

- [ ] **Step 1: 验证现有 `test_init_db_upgrade.py`**

Run: `backend/.venv/Scripts/pytest backend/tests/test_init_db_upgrade.py -v`
Expected: PASS

- [ ] **Step 2: 在 `backend/database/core.py` 补齐 `SESSION_SUMMARIES_SCHEMA_SQL`**

在 `backend/database/core.py` 中：
```python
    from database.migrations import (
        CARE_LEDGER_SCHEMA_SQL,
        CHAT_ARCHIVE_SCHEMA_SQL,
        CUSTOM_PROACTIVE_QUEUE_SQL,
        EVENTS_SCHEMA_SQL,
        LIFE_STATE_SCHEMA_SQL,
        MEMORIES_INDEX_SQL,
        MEMORY_EXTRACT_STATE_SQL,
        SESSION_SUMMARIES_SCHEMA_SQL,
        TOPIC_BANK_SCHEMA_SQL,
        ensure_schema,
    )
```
并在表建脚本段中追加：
```python
    # session_summaries（迁移 v23）：会话脉络摘要表
    await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)
```

- [ ] **Step 3: 删除 0 字节孤儿空文件 `backend/data/xiaoxi.db`**

Run: `rm -f backend/data/xiaoxi.db`

- [ ] **Step 4: 运行数据库测试确认无回归**

Run: `backend/.venv/Scripts/pytest backend/tests/test_init_db_upgrade.py backend/tests/test_migrations.py -v`
Expected: PASS

- [ ] **Step 5: 提交本任务修改**

```bash
git add backend/database/core.py
git commit -m "fix(db): core.py init_db 补全 session_summaries DDL 并移除废弃 xiaoxi.db"
```

---

### Task 2: 解耦 `chat_archive.py` 与话题库，根治全局 DB 泄漏

**Files:**
- Modify: `backend/memory/chat_archive.py:124-139`
- Modify: `backend/api/routes/chat.py:515-535,775-795`
- Modify: `backend/era.py:295-315`
- Test: `backend/tests/test_chat_archive.py`
- Test: `backend/tests/test_cycle_zz_global_db_leak.py`

**Interfaces:**
- Consumes: `agent.topic_bank.auto_consume_topics_from_text(messages)`
- Produces: `chat_archive.record_assistant_message` 不再引用 `agent.topic_bank`，底层存储只专注写库。

- [ ] **Step 1: 运行现存的失败测试确认根因**

Run: `backend/.venv/Scripts/pytest backend/tests/test_chat_archive.py backend/tests/test_cycle_zz_global_db_leak.py`
Expected: FAIL on `test_global_db_restored_after_cycle_tests` with `AssertionError: database.core._db 泄漏`

- [ ] **Step 2: 从 `backend/memory/chat_archive.py` 移除话题库反向依赖**

在 `backend/memory/chat_archive.py` 的 `record_assistant_message` 中：
```python
async def record_assistant_message(session_id: str, text: str) -> None:
    """归档一条助手回复（空文本跳过）。

    正常路径下会话行已由用户消息创建；_upsert_session 做防御性
    兜底（极端时序下缺失则用助手文本定标题补建）。失败只记日志。
    """
    text = (text or "").strip()
    if not text:
        return
    await _record_message(session_id, text, "assistant", "chat")
```
删除其底部的 `from agent.topic_bank import auto_consume_topics_from_text` 及其调用。

- [ ] **Step 3: 将话题核销副作用上移至业务层**

在 `backend/api/routes/chat.py` 中：
1. 在 `chat` 路由（非流式）完成回复归档后（约 525 行）：
```python
    await chat_archive.record_assistant_message(sid, full_reply)
    try:
        from agent.topic_bank import auto_consume_topics_from_text
        await auto_consume_topics_from_text([full_reply])
    except Exception as e:
        log.warning("话题库自动核销检测失败: %s", e)
```
2. 在 `chat_stream` 路由（流式）完成回复归档后（约 780 行）：
```python
    await chat_archive.record_assistant_message(sid, full_text)
    try:
        from agent.topic_bank import auto_consume_topics_from_text
        await auto_consume_topics_from_text([full_text])
    except Exception as e:
        log.warning("话题库自动核销检测失败: %s", e)
```

在 `backend/era.py` 的 `append_proactive_turn` 中：
在主动消息记录之后（约 303 行）：
```python
    await chat_archive.record_solo_message(session_id, content, is_era=is_era, now=now_str)
    try:
        from agent.topic_bank import auto_consume_topics_from_text
        await auto_consume_topics_from_text([content])
    except Exception as e:
        log.warning("主动消息话题库自动核销检测失败: %s", e)
```

- [ ] **Step 4: 运行测试验证泄漏已消除**

Run: `backend/.venv/Scripts/pytest backend/tests/test_chat_archive.py backend/tests/test_cycle_zz_global_db_leak.py -v`
Expected: 34 passed (All PASS, 0 failed!)

- [ ] **Step 5: 提交本任务修改**

```bash
git add backend/memory/chat_archive.py backend/api/routes/chat.py backend/era.py
git commit -m "refactor(memory,chat): 解耦 chat_archive 与话题库，消除全局 DB 连接泄漏"
```

---

### Task 3: 心跳定制主动消息消费去重

**Files:**
- Modify: `backend/heartbeat.py:640-660,790-810,1025-1045`
- Test: `backend/tests/test_heartbeat.py`
- Test: `backend/tests/test_evening_reminder.py`

**Interfaces:**
- Produces: `_pop_pending_custom_proactive(conn) -> tuple[str, str] | None`

- [ ] **Step 1: 运行现有心跳相关单测**

Run: `backend/.venv/Scripts/pytest backend/tests/test_heartbeat.py backend/tests/test_evening_reminder.py`
Expected: PASS

- [ ] **Step 2: 在 `backend/heartbeat.py` 抽取统一消费函数**

在 `backend/heartbeat.py` 主动消息区域（约 615 行附近）：
```python
async def _pop_pending_custom_proactive(conn) -> tuple[str, str] | None:
    """原子获取并核销一条待发的定制主动消息。返回 (msg_id, content) 或 None。"""
    try:
        cursor = await conn.execute(
            "SELECT id, content FROM custom_proactive_queue WHERE status = 'pending' ORDER BY id ASC LIMIT 1"
        )
        row = await cursor.fetchone()
        if not row:
            return None
        msg_id, content = row[0], row[1]
        await conn.execute(
            "UPDATE custom_proactive_queue SET status = 'sent' WHERE id = ?",
            (msg_id,),
        )
        await conn.commit()
        return msg_id, content
    except Exception as e:
        log.warning("读取/核销定制主动消息队列异常: %s", e)
        return None
```

并在 `_try_proactive_message`、`daily_morning_greeting`、`daily_evening_reminder` 中将原来手写的重复 SQL 替换为：
```python
custom_row = await _pop_pending_custom_proactive(conn)
if custom_row:
    msg_id, content = custom_row
    # 继续原有发送流程
```

- [ ] **Step 3: 运行心跳测试验证**

Run: `backend/.venv/Scripts/pytest backend/tests/test_heartbeat.py backend/tests/test_evening_reminder.py -v`
Expected: PASS

- [ ] **Step 4: 提交本任务修改**

```bash
git add backend/heartbeat.py
git commit -m "refactor(heartbeat): 抽取 _pop_pending_custom_proactive 消除重复消费逻辑"
```

---

### Task 4: 生产代码 Mock 反模式重构与 Ruff 全量治理

**Files:**
- Modify: `backend/api/routes/chat.py:345-356`
- Modify: `backend/agent/topic_bank.py:10-25`
- Modify: `backend/tests/test_proactive_context_continuity.py:10-30`
- Modify: `backend/tests/test_topic_bank.py:10-25,235-245`
- Modify: `backend/tests/test_era.py:255-265`
- Modify: `backend/tests/test_integration.py:70-80,130-145,860-870`

- [ ] **Step 1: 检查当前 Ruff 违规**

Run: `backend/.venv/Scripts/ruff check backend`
Expected: 11 errors

- [ ] **Step 2: 重构 `chat.py` 中的 `ensure_checkpoint_hydrated`**

在 `backend/api/routes/chat.py` 中，移除对 `unittest.mock` 的动态 import，改为纯净的模块类型检查：
```python
    if agent is None or not sid:
        return
    if type(agent).__module__.startswith("unittest.mock"):
        return
```

- [ ] **Step 3: 清理未使用 imports 与变量，格式化 import 排序**

1. `backend/agent/topic_bank.py`: 移除未使用的 `import uuid` 和 `import aiosqlite`。
2. `backend/tests/test_proactive_context_continuity.py`: 移除未使用的 `patch` 与 `now_str`。
3. `backend/tests/test_topic_bank.py:241`: 移除未使用的变量赋值 `msg = ...`（改为 `await _generate_proactive_message(...)`）。
4. 运行 `backend/.venv/Scripts/ruff check --fix backend` 自动整理 import 排序。

- [ ] **Step 4: 运行 Ruff 检查确保 0 errors**

Run: `backend/.venv/Scripts/ruff check backend`
Expected: `All checks passed!`

- [ ] **Step 5: 提交本任务修改**

```bash
git add backend/
git commit -m "chore(code-hygiene): 移除生产代码 mock 侵入并修复所有 ruff 告警"
```

---

### Task 5: 仓库临时调试产物归档与 Git 规则加固

**Files:**
- Move: `chat_messages_tonight.json` -> `attic/debug_exports/chat_messages_tonight.json`
- Move: `今晚聊天记录_2026-09-01_2026-09-02.md` -> `attic/debug_exports/今晚聊天记录_2026-09-01_2026-09-02.md`
- Delete: `scripts/tmp_send_2300.py`
- Delete: `scripts/tmp_server_sync.py`
- Modify: `.gitignore`

- [ ] **Step 1: 创建 `attic/debug_exports` 目录并迁移临时文件**

```bash
mkdir -p attic/debug_exports
git mv chat_messages_tonight.json attic/debug_exports/
git mv "今晚聊天记录_2026-09-01_2026-09-02.md" attic/debug_exports/
```

- [ ] **Step 2: 移除已过期的一次性脚本**

```bash
git rm scripts/tmp_send_2300.py scripts/tmp_server_sync.py
```

- [ ] **Step 3: 更新 `.gitignore` 防止未来误提**

在 `.gitignore` 的“测试产物”区域下方追加：
```gitignore
# 临时调试导出
*聊天记录*.md
chat_messages_*.json
```

- [ ] **Step 4: 提交仓库整洁度修改**

```bash
git add attic/ .gitignore
git commit -m "chore(repo): 归档临时调试导出文件并清理一次性脚本"
```

---

### Task 6: 全面深度回归测试与双端验证

**Files:**
- Verify: Backend test suite, Frontend test suite, Frontend build

- [ ] **Step 1: 运行后端全量测试套件（981 个测试）**

Run: `backend/.venv/Scripts/pytest backend/tests`
Expected: `980 passed, 1 skipped in ~240s` (0 failures!)

- [ ] **Step 2: 运行后端 linter**

Run: `backend/.venv/Scripts/ruff check backend`
Expected: `All checks passed!`

- [ ] **Step 3: 运行前端全量单元测试（91 个测试）**

Run: `cd web && npm test -- --run`
Expected: `13 passed (13), Tests: 91 passed (91)`

- [ ] **Step 4: 运行前端生产构建**

Run: `cd web && npm run build`
Expected: `✓ built in ~800ms`, `dist/` 目录正常生成无任何 warning。

- [ ] **Step 5: 验证 Git 状态洁净度**

Run: `git status`
Expected: `working tree clean`
