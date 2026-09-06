# 设计：小汐系统全面维护治理与架构解耦规范（2026-09-06）

> 建档日期：2026-09-06  
> 状态：设计定稿（用户已确认，全套深度治理方案）  
> 目标：根除近期快速特性迭代（主动消息/话题库/会话管理）积累的架构债务、单测数据库连接泄漏、分层反向依赖、初始化缺陷与代码规范问题。

---

## 一、问题背景与诊断结论

自 2026-08 部署上线并持续迭代以来，系统新增了主动消息增强（Spec A/B）、会话历史重构（v24 schema）、后台话题库系统（v25 schema）与定时关怀调度等多项能力。全量深度体检发现以下 5 个核心问题：

1. **P0 单测全局数据库连接泄漏**：
   - 现象：`pytest backend/tests/` 运行时，`test_cycle_zz_global_db_leak.py` 报错 `assert core._db is None` 失败。
   - 根因：`backend/tests/test_chat_archive.py` 中的 `test_record_swallows_db_errors` 测试用例触发了 `record_assistant_message`；该函数在底层调用了 `from agent.topic_bank import auto_consume_topics_from_text`；话题库函数调用未打补丁的 `database.core.get_db()`，在单测环境下打穿真实生产库 `userdata.db` 并初始化了全局单例连接 `core._db`，造成全局状态泄漏。

2. **P1 架构分层倒置与职责越界**：
   - 底层存储层 `backend/memory/chat_archive.py` 直接导入上层业务层 `agent.topic_bank`，违反了底层不得依赖高层模块的基本架构原则。
   - `chat_archive._record_message` 中直接混杂了跨域业务逻辑（`session_summaries` 失效删除、`custom_proactive_queue` 标记 skipped、`chat_sessions.kind` 变更等），导致底层存储模块膨胀且职责不清。
   - `backend/heartbeat.py` 在 `_try_proactive_message`、`daily_morning_greeting`、`daily_evening_reminder` 三处存在一字不差的重复 SQL 逻辑用于消费 `custom_proactive_queue`。

3. **P1 数据库表初始化入口裂痕**：
   - `backend/database/core.py` 的 `init_db()` 中显式导入并执行了新增表的 DDL 常量，但遗漏了 v23 的 `SESSION_SUMMARIES_SCHEMA_SQL`。
   - 存在历史残留的 0 字节孤儿数据库文件 `backend/data/xiaoxi.db`。

4. **P2 代码质量与反模式**：
   - `backend/api/routes/chat.py` 的 `ensure_checkpoint_hydrated` 中，在生产代码中动态 `import unittest.mock` 并进行 `isinstance(agent, (AsyncMock, MagicMock))` 检查。
   - 后端存在 11 处 Ruff linter 违规（未使用的 import/变量，import 组织顺序混乱）。

5. **P2 仓库临时调试产物污染**：
   - 根目录被 git 追踪并提交了临时聊天记录文件（`chat_messages_tonight.json` 和 `今晚聊天记录_2026-09-01_2026-09-02.md`）。
   - `scripts/` 中残留已过时的一次性脚手架脚本（`tmp_send_2300.py`、`tmp_server_sync.py`）。

---

## 二、详细架构重构与优化设计

### 1. 架构分层与副作用解耦

#### 原则
- **存储层（`memory/chat_archive.py`）**：严格保持数据持久化的纯粹性，只负责 `chat_sessions` 与 `chat_messages` 的基本增删改查。
- **业务域解耦**：移除 `chat_archive.py` 对 `agent.topic_bank` 的反向依赖；
- **业务副作用调度**：将“用户接话/助手回复时的业务副作用”收敛到统一的编排接口或调用层。
  - 对于会话类型转换（`chat_sessions.kind`）、定制队列作废（`custom_proactive_queue.status = 'skipped'`）以及摘要失效（`session_summaries` 删除）：保留在基于数据库事务的归档联动内，但通过纯 SQL 处理，**严禁跨模块引入上层业务对象**。
  - 对于话题库核销（`auto_consume_topics_from_text`）：移出 `chat_archive.py`，移至上层对话流完成（`routes/chat.py` 完成回复后）或 era 消息落库后显式调用，彻底切断反向依赖。
  - 在 `test_chat_archive.py` 中，所有测试均不再触发 `topic_bank`，单元测试不再连接真实数据库，彻底消除 `test_cycle_zz_global_db_leak.py` 失败的根因。

#### 心跳消费去重
在 `backend/heartbeat.py` 抽取统一的辅助函数：
```python
async def _pop_pending_custom_proactive(conn) -> tuple[str, str] | None:
    """原子获取并核销一条待发的定制主动消息。返回 (msg_id, content) 或 None。"""
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
```
替换 `_try_proactive_message`、`daily_morning_greeting`、`daily_evening_reminder` 三处的重复代码。

---

### 2. 数据库一致性与孤儿文件清理

1. **补齐 DDL 常量执行**：
   在 `backend/database/core.py` 中导入并执行 `SESSION_SUMMARIES_SCHEMA_SQL`：
   ```python
   from database.migrations import (
       ...,
       SESSION_SUMMARIES_SCHEMA_SQL,
       ...
   )
   ...
   await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)
   ```
   使其与 `conftest.py` 以及 `database/migrations.py` 的 DDL 列表保持 100% 对齐。

2. **清理孤儿空文件**：
   删除 `backend/data/xiaoxi.db`（0 字节历史废弃文件，代码已全量使用 `userdata.db`）。

---

### 3. 代码质量与反模式消除

1. **消除生产代码中的 `unittest.mock` 侵入**：
   在 `backend/api/routes/chat.py` 的 `ensure_checkpoint_hydrated` 中：
   移除：
   ```python
   try:
       from unittest.mock import AsyncMock, MagicMock
       if isinstance(agent, (AsyncMock, MagicMock)):
           return
   except ImportError:
       pass
   ```
   改为基于行为与接口能力检查（如检查 `agent` 是否具备真实的 `get_graph` 且非 MagicMock 动态生成的虚拟属性）：
   ```python
   if agent is None or not sid:
       return
   # 若为测试中注入的 Mock 对象，安全绕过
   if type(agent).__module__.startswith("unittest.mock"):
       return
   ```
   既避免了测试 mock 在单元测试中触发图反射错误，又避免了在正常代码中显式实例化/依赖 mock 库。

2. **静态检查与 Ruff 修复**：
   - 运行 `backend/.venv/Scripts/ruff check --fix backend`
   - 手动修正所有未使用的变量（如 `backend/tests/test_topic_bank.py:241` 的 `msg`）、未使用的 import 以及 import 组织顺序，达到 0 errors 状态。

---

### 4. 仓库整洁度与临时文件归档

1. **调试导出文件归档**：
   - 将根目录下的 `chat_messages_tonight.json` 与 `今晚聊天记录_2026-09-01_2026-09-02.md` 移动至 `attic/debug_exports/`，保留历史排查价值的同时彻底清理根目录；
   - 在 `.gitignore` 中增加针对临时聊天记录与调试 JSON 的忽略规则，防止未来再被误提交到根目录：
     ```gitignore
     # 临时调试导出
     *聊天记录*.md
     chat_messages_*.json
     ```
2. **废弃一次性脚本清理**：
   - 安全删除 `scripts/tmp_send_2300.py`（文件头部文档已明确注明“一次性触发，触发后自删”）；
   - 安全删除 `scripts/tmp_server_sync.py`（2026-09-01 的一次性服务器同步脚本）。

---

## 三、验证方案与验收标准

1. **静态代码检查**：
   - `backend/.venv/Scripts/ruff check backend` 输出 `All checks passed!`。
2. **后端全量单测通过**：
   - 运行 `backend/.venv/Scripts/pytest backend/tests`；
   - 验收结果：981 个测试全部通过（0 failures, 1 skipped），特别是 `test_cycle_zz_global_db_leak.py` 必须稳定绿灯。
3. **前端测试与生产构建验证**：
   - 在 `web/` 运行 `npm test -- --run`：13 个测试套件（91 个测试）全部通过；
   - 在 `web/` 运行 `npm run build`：生产构建无报错产出。
4. **Git 仓库状态检查**：
   - `git status` 无未预期的残留脏文件，根目录清爽。
