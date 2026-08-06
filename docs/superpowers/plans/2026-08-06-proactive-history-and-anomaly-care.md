# 主动消息历史感知（Spec A）+ 异常感知关心（Spec B）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让主动消息/早安带上对话历史尾巴（吵架后会求和、聊到一半会跟进），并让对话内的小汐知道现在几点、会对深夜熬夜/考前熬夜/消失后回来做出有节制的关心。

**Architecture:** Spec A 在 `memory/chat_archive.py` 新增纯读函数 `recent_messages`（跨会话最近 20 条、无时间窗、每行带 `[MM-DD HH:MM]` 时间标记），heartbeat 的 `_generate_proactive_message` 与 `daily_morning_greeting` 以函数内延迟 import 注入历史块；为此放宽既有「档案不进 LLM 上下文」隔离自检（heartbeat 成为唯一白名单例外）。Spec B 在请求路径单一入口 `api/routes/chat.py::_resolve_user_content` 注入「当前时间」块（永远在场），新模块 `agent/care.py` 提供纯函数检测器（late_night / event_combo / returned）+ 硬冷却台账 `care_ledger`（迁移 v19，注入即记账），提示与 GPS 行同住一个 `[系统信息：…]` 块，剥离契约不变。

**Tech Stack:** Python 3.12 / FastAPI / aiosqlite（单全局长连接）/ APScheduler / pytest（asyncio_mode=auto）/ SQLite 迁移框架（schema_version 表，当前 v18 → v19）。

**Specs:** `docs/superpowers/specs/2026-08-05-proactive-history-awareness-design.md`（Spec A）、`docs/superpowers/specs/2026-08-05-anomaly-care-design.md`（Spec B）。两份均已用户逐段批准。

## Global Constraints

- 所有时间戳：HKT、`"%Y-%m-%d %H:%M"` 分钟精度（`utils.timezone.now_str` / 各模块 `_TIME_FMT` 口径），测试断言一律用 HKT 构造的固定时刻。
- 降级纪律：读取失败 → 记日志 + 省略/放行，绝不阻断消息生成与对话主链路；写入失败 → 只记日志。
- patch 契约：`heartbeat.get_db` / `heartbeat.datetime` / `heartbeat.random` 等被测试 patch 的模块属性名与所在文件不得变动；heartbeat 新增读取一律**函数内延迟 import**。
- `heartbeat.py` 必须保持单一真实文件（调度/被 patch 函数不得外移）。
- pytest 一律**前台 + `timeout` 前缀**跑（Windows 管道 + python shim 有后台挂死前科）；工作目录 `E:/小汐sonetto/backend`，解释器 `.venv/Scripts/python`。
- 每个任务先红后绿，绿后单独提交；提交信息沿用 `feat/test/refactor(scope): 中文描述` 风格。
- 提示是感知与许可，不是指令；「别每次都唠叨」由确定性台账保证，不押 LLM 自觉。
- 测试不得读/写生产库 `backend/data/userdata.db`：凡是新代码会在既有测试路径上触达 `database.core.get_db()` 全局连接的，必须在同任务内把该测试的库路由到 `tmp_db`。

## 已勘察的现状要点（实施者不必重新侦察）

- `backend/memory/chat_archive.py`：`record_user_message / record_assistant_message / get_messages(session_id, limit)` 等；`created_at` 由 `now_str()` 写入。**没有**跨会话取最近 N 条的读函数。模块 docstring 宣称「本表永远不会被读进 LLM 上下文」——本计划按 Spec A 有意改变该契约。
- `backend/tests/test_chat_archive.py::test_archive_isolation_from_llm_context_paths`：文本扫描 `heartbeat.py`、`memory/narrative.py`、`agent/*.py`，禁止出现 `chat_archive` 字样。Spec A 需要 heartbeat 消费档案 → Task 2 改写该自检（heartbeat 白名单化，agent/ 与 narrative 仍禁止）。
- `backend/heartbeat.py` 模块 docstring（约 27-28 行）写着「不得在此文本中提及对话档案模块名（有结构性隔离自检会扫描本文件文本）」——Task 2 一并更新。
- `_generate_proactive_message(mood, daily_event, hour, now_dt=None)`：独立轻量 LLM 调用（`create_deepseek_llm(model=get_current_model(), temperature=1.0, max_tokens=200)`），user_prompt 依次拼 时段/心情/日常 → 周末行 → 活跃事件 → 24h 防复读。历史块追加在**最后**（LLM 创建之前）。>200 截断、<5 回退「冷冷，小汐在想你~」。
- `daily_morning_greeting()`：天气 → 日程（`schedule_part`）→ `prompt_text` f-string → LLM。历史块插在 `schedule_part` 之后、「请给冷冷发一条早安问候消息」之前。
- 测试 patch 习惯（test_heartbeat.py 既有模式）：`db_core._db = tmp_db`（try/finally 还原）+ `patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM())` + FakeLLM 捕获 `msgs[-1].content`；事件函数 patch `utils.events.get_active_events` / `get_recent_proactive_texts`。
- `api/routes/chat.py::_resolve_user_content(user_message, req, sid)` 是 REST/SSE 共用的**单一入口**（禁止内联副本）；`_location_suffix(lat, lon)` 返回 `\n\n[系统信息：冷冷当前 GPS 坐标：…]`；`_strip_location_suffix` 按 `\n\n\[系统信息：` 切分。档案落库用 `req.message`（不含后缀）。
- chat.py **当前没有** `import datetime`（Task 5 新增）。
- 时段口径（`heartbeat_moods.py`）：`LATE_NIGHT=(0,7)` 深夜、`MORNING=(7,12)` 早晨、`DAYTIME=(12,19)` 白天、`EVENING=(19,24)` 夜晚。Spec B 时段词用「深夜/早晨/下午/晚上」，小时边界与之相同。
- 迁移模式：`database/migrations.py` 注册 `_migrations[N]` + 版本注释 + `SCHEMA_VERSION`；共享 DDL 常量三处同步（migrations / `database/core.py::init_db` / `tests/conftest.py::tmp_db`）。参照 v6/v8 的 `EVENTS_SCHEMA_SQL` / `CHAT_ARCHIVE_SCHEMA_SQL` 模式。
- 事件类型白名单 `_EVENT_TYPES = ("exam", "trip", "appointment", "health", "general")`——**没有 interview**。Spec B 写的 exam/interview，第一期 combo 只认 `exam`（在 care.py 常量注释中留痕）。
- 事件字段：`event_date`（ISO 日期）、`end_date`（可空）、`title`、`event_type`、`status`。
- 生产入口：`uvicorn api.server:app`；settings 读 `backend/config/.env`（`DEEPSEEK_API_KEY` 空则 fail-closed 拒启动）。
- 会受时间注入影响的既有测试（Task 5 更新断言）：`tests/test_gps_optout.py::test_gps_disabled_clears_cache_and_skips_injection` / `test_gps_legacy_client_no_signal_unchanged`、`tests/test_integration.py::test_chat_stream_gps_disabled_clears_cache_and_skips_injection`。
- 会因新代码触达全局 DB 的既有测试（Task 3/4/8 补路由）：`test_proactive.py` 三个 `_generate_proactive_message` 直测、`test_heartbeat.py::test_background_llm_calls_use_runtime_model`、`test_morning_greeting_malformed_daily_keeps_current_weather`、`test_check_and_proactive_message_updates_db`、`_run_heartbeat_at` 助手、`test_integration.py::integration_app` 夹具。

---

### Task 1: Spec A 数据层 —— `recent_messages` + 时间标记格式化

**Files:**
- Modify: `backend/memory/chat_archive.py`（新增两个函数 + 更新模块 docstring）
- Test: `backend/tests/test_chat_archive.py`（增补）

**Interfaces:**
- Consumes: `database.core.get_db`（模块已 import）、`chat_messages` 表（v8，列 id/session_id/role/content/created_at）
- Produces:
  - `async def recent_messages(limit: int = 20, max_chars: int = 1500) -> list[dict]` —— 跨会话按 `id DESC` 取最近 limit 条再反转升序；单条 content 截 100 字；从最新往旧累加预算（`len(content)+20` 行标记开销），超 max_chars 丢最旧；空表返回 `[]`；读取异常**上抛**（调用方降级）
  - `def format_recent_messages(messages: list[dict]) -> str` —— 每行 `[MM-DD HH:MM] 说话人：内容`（user→冷冷、assistant→小汐），最新在最下

- [x] **Step 1: 写失败测试（追加到 `backend/tests/test_chat_archive.py` 末尾）**

```python
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
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_chat_archive.py -q`
Expected: 新增 6 例 FAIL（`AttributeError: module 'memory.chat_archive' has no attribute 'recent_messages'`），既有 17 例保持绿。

- [x] **Step 3: 实现（`backend/memory/chat_archive.py`）**

3a. 模块 docstring 第二段替换（契约变更，与 Task 2 的自检改写呼应）：

```
与 checkpoint（prune_checkpoints 每 2h 裁剪、30 天清线程）和 memories
（narrative.py 注入 LLM 上下文）解耦：重要信息由小汐的记忆系统
自己提取，历史主要是档案。写入面只有 api/routes/chat.py；读面两处：
api/routes/sessions.py（历史列表/回看）与 heartbeat 的主动消息/早安
（Spec A，2026-08-05：经下方 recent_messages 取「历史尾巴」注入生成
prompt——这是本表唯一进 LLM 上下文的通道，函数内延迟 import）。
agent/ 与 narrative.py 仍禁止 import 本模块（隔离有 grep 自检）。

写入函数（record_*）吞掉一切异常只记日志：归档失败不能拖垮对话；
recent_messages 读取异常正常上抛，由调用方降级（历史块可省，消息
生成不可断）；其余读取 / 删除函数正常上抛，由路由层处理。
```

3b. 文件末尾追加：

```python
# ── Spec A：主动消息 / 早安的历史尾巴取面 ─────────────────────
# 本模块唯一的「档案 → LLM 上下文」通道：只有 heartbeat 的主动消息
# 与早安两处（函数内延迟 import）消费，隔离自检见
# tests/test_chat_archive.py::test_archive_isolation_from_llm_context_paths。

_RECENT_CONTENT_MAX = 100   # 单条截断字数
_RECENT_LINE_OVERHEAD = 20  # 「[MM-DD HH:MM] 说话人：」行标记的预算开销


async def recent_messages(limit: int = 20, max_chars: int = 1500) -> list[dict]:
    """跨会话取最近 limit 条消息（时间正序，最新在最后），供主动消息
    与早安生成时注入「历史尾巴」。

    刻意不带时间窗口：闹翻后冷冷三天不来，尾巴仍是那次争吵，正好
    该求和；一个月不来，尾巴恰好支撑「回来啦」。窗口反而漏掉这些。

    截断保护：单条 content 截 _RECENT_CONTENT_MAX 字；从最新往旧累加
    预算（内容长 + 行标记开销），超 max_chars 即停——注入块约几百
    token，不挤占生成空间。读取异常正常上抛，调用方降级。
    """
    conn = await get_db()
    cursor = await conn.execute(
        """
        SELECT role, content, created_at FROM (
            SELECT role, content, created_at, id FROM chat_messages
            ORDER BY id DESC LIMIT ?
        ) ORDER BY id ASC
        """,
        (limit,),
    )
    rows = await cursor.fetchall()
    messages = [
        {"role": r[0], "content": (r[1] or "")[:_RECENT_CONTENT_MAX], "created_at": r[2]}
        for r in rows
    ]

    # 总量封顶：从最新往旧收，超预算丢最旧
    kept: list[dict] = []
    total = 0
    for m in reversed(messages):
        cost = len(m["content"]) + _RECENT_LINE_OVERHEAD
        if kept and total + cost > max_chars:
            break
        kept.append(m)
        total += cost
    kept.reverse()
    return kept


def format_recent_messages(messages: list[dict]) -> str:
    """[{role, content, created_at}] → 带时间标记的交替行文本，最新在最下。

    时间标记是刻意设计：次日早安看到「昨晚 23:42 还僵着」、看到
    「02:13 还在聊天」，才能自然跟进求和或关心熬夜（Spec B 主动消息
    侧的承接全靠它）。
    """
    lines = []
    for m in messages:
        speaker = "冷冷" if m["role"] == "user" else "小汐"
        ts = (m.get("created_at") or "")[5:16]  # "YYYY-MM-DD HH:MM" → "MM-DD HH:MM"
        lines.append(f"[{ts}] {speaker}：{m['content']}")
    return "\n".join(lines)
```

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_chat_archive.py -q`
Expected: 全绿（23 例）。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/memory/chat_archive.py backend/tests/test_chat_archive.py
git commit -m "feat(archive): recent_messages——跨会话历史尾巴只读取面（Spec A 数据层）"
```

---

### Task 2: Spec A 隔离契约改写 —— heartbeat 白名单化

结构性契约变更任务：先改自检与 docstring，让 Task 3 的 heartbeat 消费合法。本任务不引入行为变化。

**Files:**
- Modify: `backend/tests/test_chat_archive.py`（替换 `test_archive_isolation_from_llm_context_paths`，新增一个守卫测试）
- Modify: `backend/heartbeat.py`（模块 docstring 末段）
- Modify: `backend/database/migrations.py`（v8 版本注释措辞）

**Interfaces:**
- Consumes: Task 1 已确立的「recent_messages 是唯一 LLM 上下文通道」语义
- Produces: 新的隔离契约文本——agent/ 与 narrative.py 禁止出现 `chat_archive`；heartbeat.py 允许，但只准走 `recent_messages` / `format_recent_messages`

- [x] **Step 1: 改写自检测试（`backend/tests/test_chat_archive.py`）**

把整个 `test_archive_isolation_from_llm_context_paths` 函数替换为：

```python
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
```

- [x] **Step 2: 更新 `backend/heartbeat.py` 模块 docstring**

把「结构说明（安全拆分）」段最后一句：

```
故本文件须保持为单一真实文件，且不得
在此文本中提及对话档案模块名（有结构性隔离自检会扫描本文件文本）。
```

替换为：

```
故本文件须保持为单一真实文件。2026-08-05（Spec A）起本文件成为对话
档案唯一获准进 LLM 上下文的消费方：_generate_proactive_message 与
daily_morning_greeting 以函数内延迟 import 调 memory.chat_archive 的
recent_messages / format_recent_messages 注入「历史尾巴」；隔离自检
（tests/test_chat_archive.py）相应放宽为本文件白名单（且只准走这两个
专用读函数），agent/ 与 narrative.py 仍禁止。
```

- [x] **Step 3: 更新 `backend/database/migrations.py` v8 注释**

版本注释列表中 v8 条目的末句：

```
#     读进 LLM 上下文（只有 chat.py 写入、sessions.py 读取）
```

改为：

```
#     读进 LLM 上下文（chat.py 写入、sessions.py 读取；2026-08-05
#     Spec A 起 heartbeat 经 recent_messages 注入历史尾巴，是唯一例外）
```

（原句开头「档案仅供历史列表与回看，永远不会被」相应调整为「档案供历史列表与回看；除 Spec A 例外外不会被」，保持注释通顺。）

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_chat_archive.py tests/test_migrations.py -q`
Expected: 全绿（新自检此时即通过——heartbeat 尚未出现 chat_archive 字样；两个守卫测试钉住后续任务的边界）。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/tests/test_chat_archive.py backend/heartbeat.py backend/database/migrations.py
git commit -m "refactor(archive): 隔离自检白名单化——heartbeat 成唯一档案例外（Spec A 契约）"
```

---

### Task 3: Spec A 注入 ① —— 主动消息历史尾巴

**Files:**
- Modify: `backend/heartbeat.py`（`_generate_proactive_message`，在防复读块之后、创建 LLM 之前插入历史块）
- Modify: `backend/tests/test_heartbeat.py`（新增 3 例 + 既有 2 处补库路由）
- Modify: `backend/tests/test_proactive.py`（3 例既有直测补 recent_messages 桩）

**Interfaces:**
- Consumes: `memory.chat_archive.recent_messages() -> list[dict]`、`memory.chat_archive.format_recent_messages(messages) -> str`（Task 1）
- Produces: `_generate_proactive_message` 的 user prompt 在事件/防复读素材之后携带历史块与引导语；历史读取失败/空历史时 prompt 回到现状

- [x] **Step 1: 写失败测试（追加到 `backend/tests/test_heartbeat.py` 末尾）**

```python
# ── Spec A：主动消息历史尾巴注入 ──────────────────────────────


def _capturing_llm(captured, reply="冷冷，小汐在想你~"):
    """FakeLLM 工厂：捕获 HumanMessage 内容（不真调模型）。"""
    from types import SimpleNamespace

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content=reply)

    return FakeLLM()


async def test_proactive_prompt_includes_history_tail(tmp_db):
    """patch recent_messages 返回「吵架尾巴」→ prompt 含历史块与
    「递台阶/别装没发生过」引导词（捕获 LLM 入参，不真调模型）。"""
    import datetime as dt
    import heartbeat
    from unittest.mock import AsyncMock, patch
    from utils.timezone import HKT

    captured = {}
    tail = [
        {"role": "user", "content": "JK 算什么东西，你也配替他说话？",
         "created_at": "2026-08-04 23:41"},
        {"role": "assistant", "content": "你这么说，我不高兴了。",
         "created_at": "2026-08-04 23:42"},
    ]

    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(return_value=tail)):
        await heartbeat._generate_proactive_message(
            "不开心", "映海很安静", 23,
            now_dt=dt.datetime(2026, 8, 5, 9, 0, tzinfo=HKT))

    human = captured["human"]
    assert "你最近和冷冷说过这些话（最新在最后）" in human
    assert "[08-04 23:41] 冷冷：JK 算什么东西，你也配替他说话？" in human
    assert "[08-04 23:42] 小汐：你这么说，我不高兴了。" in human
    assert "递个台阶" in human
    assert "别装没发生过" in human


async def test_proactive_prompt_history_read_failure_degrades(tmp_db):
    """recent_messages 抛异常 → prompt 照常构建、无历史块、消息照常生成。"""
    import heartbeat
    from unittest.mock import AsyncMock, patch

    captured = {}
    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(side_effect=RuntimeError("db lost"))):
        text = await heartbeat._generate_proactive_message("开心", "映海很清澈", 15)

    assert "你最近和冷冷说过这些话" not in captured["human"]
    assert len(text) >= 5  # 消息照常生成（降级不阻断）


async def test_proactive_prompt_empty_history_no_block(tmp_db):
    """空历史（新库/从未对话）→ prompt 不出现历史块。"""
    import heartbeat
    from unittest.mock import AsyncMock, patch

    captured = {}
    with patch("providers.deepseek.create_deepseek_llm",
               return_value=_capturing_llm(captured)), \
         patch("utils.events.get_active_events", return_value=[]), \
         patch("utils.events.get_recent_proactive_texts", return_value=[]), \
         patch("memory.chat_archive.recent_messages",
               new=AsyncMock(return_value=[])):
        await heartbeat._generate_proactive_message("开心", "映海很清澈", 15)

    assert "你最近和冷冷说过这些话" not in captured["human"]
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_heartbeat.py -q -k "history"`
Expected: 前 1 例 FAIL（prompt 无历史块）；另 2 例偶可假绿（无历史块恰是现状）——以第 1 例为准。

- [x] **Step 3: 实现（`backend/heartbeat.py::_generate_proactive_message`）**

在防复读 try/except 块（`except Exception: pass` 结束处，现约 373 行）之后、`from api.routes.config import get_current_model` 之前插入：

```python
    # ── 历史尾巴（Spec A）：把最近对话原文喂给她，由她结合人格自己
    # 判断怎么接——聊到一半的事自然跟进；闹了别扭就按「生气的时候」
    # 的性子来。不带时间窗口：三天不来尾巴仍是那次争吵，正好求和。
    # 读取失败只省略历史块，不阻断消息生成（降级纪律同上方事件段）
    try:
        from memory.chat_archive import recent_messages, format_recent_messages
        history = await recent_messages()
    except Exception as e:
        log.warning("主动消息：读取对话历史失败，省略历史块: %s", e)
        history = []
    if history:
        user_prompt += (
            "\n\n你最近和冷冷说过这些话（最新在最后）：\n"
            + format_recent_messages(history)
            + "\n\n接着这个语气和情境自然地搭话。聊到一半没说完的事，"
            "可以自己跟进；如果上次闹了别扭或气氛僵着，按你生气时的性子"
            "来——可以别扭地先递个台阶，可以傲娇一点，但别装没发生过，"
            "也别翻旧账。"
        )
```

- [x] **Step 4: 补既有测试的库路由（同任务必须做——新代码会在这些路径触达全局 DB）**

4a. `backend/tests/test_heartbeat.py::test_background_llm_calls_use_runtime_model`：with 栈内追加一行
`patch("memory.chat_archive.get_db", return_value=tmp_db),`

4b. `backend/tests/test_heartbeat.py::test_check_and_proactive_message_updates_db`：`hb.get_db = mock_get_db` 之后追加（finally 一并还原）：

```python
    import database.core as db_core
    original_core_db = db_core._db
    db_core._db = tmp_db   # 50% 概率走进 _generate_proactive_message → recent_messages
```

4c. `backend/tests/test_heartbeat.py::_run_heartbeat_at` 助手：`monkeypatch.setattr(hb, "get_db", mock_get_db)` 之后追加：

```python
    async def fake_archive_get_db():
        return tmp_db

    import memory.chat_archive as _archive
    monkeypatch.setattr(_archive, "get_db", fake_archive_get_db)
```

4d. `backend/tests/test_proactive.py` 三个直测（`test_generate_proactive_message_returns_text` / `_truncates_long_text` / `_fallback_on_short_text`）：各自 with 栈追加
`patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])),`
（文件头已导入 `AsyncMock`，无需改 import。）

- [x] **Step 5: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 300 .venv/Scripts/python -m pytest tests/test_heartbeat.py tests/test_proactive.py tests/test_proactive_pipeline.py -q`
Expected: 全绿。

- [x] **Step 6: 提交**

```bash
cd E:/小汐sonetto && git add backend/heartbeat.py backend/tests/test_heartbeat.py backend/tests/test_proactive.py
git commit -m "feat(heartbeat): 主动消息注入对话历史尾巴——吵架后递台阶、聊一半会跟进（Spec A）"
```

---

### Task 4: Spec A 注入 ② —— 早安历史尾巴

**Files:**
- Modify: `backend/heartbeat.py`（`daily_morning_greeting`）
- Modify: `backend/tests/test_heartbeat.py`（新增 1 例 + 既有 1 处补库路由）

**Interfaces:**
- Consumes: Task 1 的两个函数（同 Task 3）
- Produces: 早安 prompt 在日程段之后携带「昨晚你们聊到这里」历史块；读取失败/空历史省略

- [x] **Step 1: 写失败测试（追加到 `backend/tests/test_heartbeat.py` 末尾）**

```python
async def test_morning_prompt_includes_history_tail(tmp_db):
    """早安对称用例：昨晚的对话尾巴进早安 prompt（「昨晚你们聊到这里」块）。"""
    import database.core as db_core
    import heartbeat
    from types import SimpleNamespace
    from unittest.mock import AsyncMock, patch

    original = db_core._db
    db_core._db = tmp_db
    captured = {}
    tail = [
        {"role": "user", "content": "我先睡了，晚安。", "created_at": "2026-08-05 23:30"},
        {"role": "assistant", "content": "晚安冷冷，做个好梦。", "created_at": "2026-08-05 23:31"},
    ]

    class FakeLLM:
        async def ainvoke(self, msgs):
            captured["human"] = msgs[-1].content
            return SimpleNamespace(content="冷冷早安呀～")

    async def fake_fetch(client, lat, lon):
        return {}

    try:
        with patch("providers.deepseek.create_deepseek_llm", return_value=FakeLLM()), \
             patch("memory.location_store.get_any", return_value=None), \
             patch("tools.weather._fetch_open_meteo", side_effect=fake_fetch), \
             patch("utils.web_push.send_push", return_value=0), \
             patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=tail)):
            await heartbeat.daily_morning_greeting()

        human = captured["human"]
        assert "昨晚你们聊到这里（最新在最后）" in human
        assert "[08-05 23:30] 冷冷：我先睡了，晚安。" in human
        assert "[08-05 23:31] 小汐：晚安冷冷，做个好梦。" in human
        assert "如果昨晚收尾僵着，早上是你先开口" in human
    finally:
        db_core._db = original
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_heartbeat.py -q -k "morning_prompt_includes_history"`
Expected: FAIL（prompt 无历史块）。

- [x] **Step 3: 实现（`backend/heartbeat.py::daily_morning_greeting`）**

3a. 在日程段 try/except（`log.warning("早安问候：获取今日日程失败…")` 之后、`prompt_text = (` 之前）插入：

```python
    # ── 历史尾巴（Spec A）：早安是每天第一次开口，吵架后的第二天早上
    # 最关键。带时间戳的尾巴让她看得见「昨晚 23:42 还僵着」「02:13 还
    # 在聊天」。读取失败只省略本段（同天气/日程段的降级纪律）
    history_part = ""
    try:
        from memory.chat_archive import recent_messages, format_recent_messages
        history = await recent_messages()
        if history:
            history_part = (
                "\n昨晚你们聊到这里（最新在最后）：\n"
                + format_recent_messages(history)
                + "\n如果昨晚收尾僵着，早上是你先开口——挑个自然的方式破冰，"
                "别扭一点也没关系。天气、美食、今日一歌照旧。\n"
            )
    except Exception as e:
        log.warning("早安问候：读取对话历史失败，省略历史块: %s", e)
```

3b. `prompt_text` 中把

```python
        f"{schedule_part}\n"
```

改为

```python
        f"{schedule_part}"
        f"{history_part}\n"
```

- [x] **Step 4: 补既有测试的库路由**

`backend/tests/test_heartbeat.py::test_morning_greeting_malformed_daily_keeps_current_weather`：with 栈追加
`patch("memory.chat_archive.get_db", return_value=tmp_db),`
（该用例只 patch 了 heartbeat.get_db，新代码会经 chat_archive.get_db 触达全局连接。）

- [x] **Step 5: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 300 .venv/Scripts/python -m pytest tests/test_heartbeat.py -q`
Expected: 全绿（含 `test_morning_prompt_mentions_weekend` / `test_morning_greeting_malformed_daily_keeps_current_weather` 等全部既有用例）。

- [x] **Step 6: 提交**

```bash
cd E:/小汐sonetto && git add backend/heartbeat.py backend/tests/test_heartbeat.py
git commit -m "feat(heartbeat): 早安注入昨晚对话尾巴——僵着就由她先破冰（Spec A）"
```

---

### Task 5: Spec B ① —— 请求路径当前时间注入（地基）

**Files:**
- Modify: `backend/heartbeat_moods.py`（新增 `period_label`）
- Modify: `backend/heartbeat.py`（再导出清单加 `period_label`）
- Modify: `backend/api/routes/chat.py`（`import datetime` + `_HKT`；`_time_line` / `_gps_line` / `_system_info_block`；重写 `_resolve_user_content`；`_location_suffix` 变为兼容包装）
- Modify: `backend/tests/test_gps_optout.py`（2 例断言更新 + 1 例补断言）
- Modify: `backend/tests/test_integration.py`（1 例断言更新 + 新增 2 例）
- Modify: `backend/tests/test_heartbeat.py`（新增 `period_label` 边界 1 例）

**Interfaces:**
- Consumes: `heartbeat_moods` 时段小时边界（0-7 深夜 / 7-12 早晨 / 12-19 下午 / 19-24 晚上）
- Produces:
  - `heartbeat_moods.period_label(hour: int) -> str`（再导出 `heartbeat.period_label`）
  - `chat._time_line(now_dt) -> str`：`现在是 YYYY-MM-DD 周X HH:MM，时段词。`
  - `chat._gps_line(lat, lon) -> str`：原 GPS 措辞逐字不变（含地图参数提示）
  - `chat._system_info_block(now_dt, care_lines: list[str], lat_lon: tuple[float, float] | None) -> str`：`\n\n[系统信息：` + 时间行 + care 行 + GPS 行（可选）+ `]`——Task 8 会把 care_lines 填上，本任务恒传 `[]`
  - `_resolve_user_content` 永远附加该块（gps_enabled=False 只去掉 GPS 行，时间行仍在）；`_location_suffix` 输出保持逐字节兼容（既有测试契约）

- [x] **Step 1: 写失败测试**

1a. 追加到 `backend/tests/test_heartbeat.py`：

```python
def test_period_label_boundaries():
    """时段词与 get_current_period 同一口径：0/6 深夜，7 早晨，12 下午，19 晚上。"""
    from heartbeat import period_label

    assert period_label(0) == "深夜"
    assert period_label(6) == "深夜"
    assert period_label(7) == "早晨"
    assert period_label(11) == "早晨"
    assert period_label(12) == "下午"
    assert period_label(18) == "下午"
    assert period_label(19) == "晚上"
    assert period_label(23) == "晚上"
```

1b. 追加到 `backend/tests/test_integration.py`：

```python
# ── Spec B：当前时间注入（请求路径地基）──────────────────────


async def test_chat_injects_time_block_with_period_word(client, integration_app):
    """请求路径注入当前时间块：日期 + 星期 + 时分 + 时段词，与 GPS 同居一块。"""
    import datetime as dt
    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)  # 周三凌晨

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "睡不着", "session_id": "s-time",
            "latitude": 22.3, "longitude": 114.17,
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 02:14，深夜。" in content
    assert "GPS 坐标" in content
    # 同一个 [系统信息：…] 块：一个前缀、一个收尾括号
    assert content.count("[系统信息：") == 1
    assert content.rstrip().endswith("]")


async def test_chat_history_strips_time_and_gps_block(client, integration_app):
    """时间行 + GPS 行同居的整块，剥离后历史回显干净（补断言）。"""
    from types import SimpleNamespace
    from langchain_core.messages import AIMessage, HumanMessage

    combined = (
        "\n\n[系统信息：现在是 2026-08-05 周三 02:14，深夜。\n"
        "冷冷当前 GPS 坐标：纬度 22.3，经度 114.1"
        "（地图工具的 location 参数请用「经度,纬度」格式：114.1,22.3）。]"
    )
    integration_app.state.agent.aget_state = AsyncMock(return_value=SimpleNamespace(values={
        "messages": [
            HumanMessage(content="睡不着" + combined),
            AIMessage(content="小汐陪你。"),
        ]
    }))

    resp = await client.get("/api/chat/history?session_id=hist-time")
    assert resp.status_code == 200
    assert resp.json()["messages"][0]["content"] == "睡不着"
```

1c. 更新 `backend/tests/test_gps_optout.py::test_gps_disabled_clears_cache_and_skips_injection` 断言：

```python
    result = await _build_input_with_location("你好", req, "s-off")

    content = result["messages"][0].content
    assert content.startswith("你好")
    assert "GPS 坐标" not in content   # 关即消失（原契约保持）
    assert "现在是" in content          # 时间块永远在场（Spec B）
    assert await location_store.get("s-off") is None  # 缓存已清
```

1d. 更新 `test_gps_legacy_client_no_signal_unchanged` 断言：

```python
    content = result["messages"][0].content
    assert content.startswith("在吗")
    assert "GPS 坐标" not in content
    assert "现在是" in content          # 无坐标也有时间块（Spec B）
    assert await location_store.get("s-legacy") is None
```

1e. `test_gps_enabled_still_updates_and_injects` 追加 `assert "现在是" in result["messages"][0].content`。

1f. 更新 `backend/tests/test_integration.py::test_chat_stream_gps_disabled_clears_cache_and_skips_injection`：docstring ① 改为「图输入 content 以原始消息开头、无 GPS 行、有时间块」，断言改为：

```python
    content = captured["input"]["messages"][0].content
    assert content.startswith("陪我说说话")
    assert "GPS 坐标" not in content
    assert "现在是" in content           # 时间块永远在场（Spec B）
    assert await location_store.get("s-stream-off") is None
    assert await location_store.get_any() is None
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 300 .venv/Scripts/python -m pytest tests/test_heartbeat.py -q -k "period_label" && timeout 300 .venv/Scripts/python -m pytest tests/test_integration.py tests/test_gps_optout.py -q`
Expected: `period_label` ImportError/AttributeError；集成侧时间块断言 FAIL。

- [x] **Step 3: 实现**

3a. `backend/heartbeat_moods.py`（`get_current_period` 之后）：

```python
def period_label(hour: int) -> str:
    """时段词：深夜（0-6）/早晨（7-11）/下午（12-18）/晚上（19-23）。

    小时边界与 get_current_period 的四个时段完全一致，供 api 请求路径
    注入「现在是……深夜」时间块复用（Spec B），禁止另立划分口径。
    """
    if hour < 7:
        return "深夜"
    if hour < 12:
        return "早晨"
    if hour < 19:
        return "下午"
    return "晚上"
```

3b. `backend/heartbeat.py` 再导出清单（约 90-94 行 `from heartbeat_moods import (`）加入 `period_label`。

3c. `backend/api/routes/chat.py`：

头部 import 区追加：

```python
import datetime
...
from heartbeat_moods import period_label
from utils.timezone import HKT as _HKT
```

`_location_suffix` 重写为（docstring 保留，末句「前缀与 _strip_location_suffix 的切分耦合，勿改」移至 `_system_info_block`）：

```python
_WEEKDAY_LABELS = ("周一", "周二", "周三", "周四", "周五", "周六", "周日")


def _time_line(now_dt: datetime.datetime) -> str:
    """当前时间行（Spec B 地基）：她总知道现在几点。

    格式与设计文档逐字一致：`现在是 2026-08-05 周三 02:14，深夜。`
    时段词走 heartbeat_moods.period_label（与心跳同一口径）。
    """
    return (
        f"现在是 {now_dt.strftime('%Y-%m-%d')} "
        f"{_WEEKDAY_LABELS[now_dt.weekday()]} {now_dt.strftime('%H:%M')}，"
        f"{period_label(now_dt.hour)}。"
    )


def _gps_line(lat: float, lon: float) -> str:
    """GPS 坐标行（措辞不变，同时服务天气分参与地图整串）。"""
    return (
        f"冷冷当前 GPS 坐标：纬度 {lat}，经度 {lon}"
        f"（地图工具的 location 参数请用「经度,纬度」格式：{lon},{lat}）。"
    )


def _location_suffix(lat: float, lon: float) -> str:
    """仅 GPS 的系统信息块（兼容旧契约；请求路径现走 _system_info_block）。"""
    return "\n\n[系统信息：" + _gps_line(lat, lon) + "]"


def _system_info_block(now_dt: datetime.datetime, care_lines: list[str],
                       lat_lon: tuple[float, float] | None) -> str:
    """统一的 [系统信息：…] 块：时间行永远在场，关心提示行（Spec B
    ④ 接入）与 GPS 行按需加入。

    前缀 `\\n\\n[系统信息：` 与 _strip_location_suffix 的切分耦合，勿改：
    三类行同居一块，历史回显按同一前缀整块剥离。
    """
    lines = [_time_line(now_dt)]
    lines.extend(care_lines)
    if lat_lon:
        lines.append(_gps_line(*lat_lon))
    return "\n\n[系统信息：" + "\n".join(lines) + "]"
```

`_resolve_user_content` 重写（docstring 保留体检回归教训段，语义段更新）：

```python
async def _resolve_user_content(user_message: str, req: ChatRequest, sid: str) -> str:
    """GPS 副作用 + 上下文后缀的**单一入口**（REST 与 SSE 两条路径共用）。

    体检回归教训：此逻辑曾在本文件两处各存一份，修复「GPS 关不净」时
    只改了 REST 辅助函数，漏了 SSE 端点的内联副本——前端实际只走 SSE，
    开关在生产路径上失效而测试全绿（测的是没人调的抽象层）。凡请求
    预处理需要「两端口共用」的逻辑，一律收敛到本函数，禁止内联副本。

    语义：gps_enabled=False → 清本会话缓存 + 不注入 GPS 行；
    带坐标 → 更新缓存；有缓存 → 追加 GPS 行。
    当前时间行永远注入（Spec B：她总知道现在几点）；时间行、关心
    提示行与 GPS 行同住一个 [系统信息：…] 块，剥离契约不变。
    """
    now_dt = datetime.datetime.now(_HKT)

    lat_lon = None
    if req.gps_enabled is False:
        # 显式关闭信号：立即清掉本会话缓存坐标（体检「GPS 关不净」）。
        # 时间行不受影响——关 GPS 关不掉时钟
        await location_store.forget(sid)
    else:
        if req.latitude is not None and req.longitude is not None:
            await location_store.update(sid, req.latitude, req.longitude)
        lat_lon = await location_store.get(sid)

    return user_message + _system_info_block(now_dt, [], lat_lon)
```

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 600 .venv/Scripts/python -m pytest tests/test_integration.py tests/test_gps_optout.py tests/test_heartbeat.py -q`
Expected: 全绿（`test_location_suffix_serves_both_tools`、`test_chat_history_returns_messages`、两条「档案不落后缀」回归不动而绿）。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/heartbeat_moods.py backend/heartbeat.py backend/api/routes/chat.py backend/tests/test_gps_optout.py backend/tests/test_integration.py backend/tests/test_heartbeat.py
git commit -m "feat(chat): 请求路径注入当前时间块——她总知道现在几点（Spec B 地基）"
```

---

### Task 6: Spec B ② —— 纯函数检测器 `agent/care.py`

**Files:**
- Create: `backend/agent/care.py`
- Test: `backend/tests/test_care.py`（新文件）

**Interfaces:**
- Consumes: 无（纯函数；时间均为 HKT aware datetime；事件为 `utils.events.get_active_events` 形状的 dict）
- Produces:
  - 类型常量：`LATE_NIGHT = "late_night"`、`EVENT_COMBO = "event_combo"`、`RETURNED = "returned"`；`COOLDOWN_HOURS = {LATE_NIGHT: 72, EVENT_COMBO: 24}`（returned 无冷却）
  - `detect_care_hints(now_dt, recent_user_times: list[datetime], active_events: list[dict], *, last_user_time: datetime | None = None) -> list[dict]`，元素形如 `{"type": ..., "text": ...}`；吞并规则：`event_combo` 命中则不发 `late_night`

- [x] **Step 1: 写失败测试（新文件 `backend/tests/test_care.py`）**

```python
"""异常感知关心（Spec B）—— 纯函数检测器 + 冷却台账测试。"""
import datetime as dt

import pytest

from agent import care
from utils.timezone import HKT


def _now(d=5, h=2, mi=14):
    """固定基准：2026-08-05（周三）HKT。"""
    return dt.datetime(2026, 8, d, h, mi, tzinfo=HKT)


# ── 纯函数检测器 ──────────────────────────────────────────────


def test_late_night_window_boundaries():
    """深夜窗口边界：00:00 / 04:59 触发，05:00 不触发。"""
    assert [h["type"] for h in care.detect_care_hints(_now(h=0, mi=0), [], [])] == [care.LATE_NIGHT]
    assert [h["type"] for h in care.detect_care_hints(_now(h=4, mi=59), [], [])] == [care.LATE_NIGHT]
    assert care.detect_care_hints(_now(h=5, mi=0), [], []) == []


def test_late_night_counts_nights_dedup_by_day():
    """近 7 天深夜消息按天去重计数；历史晚数 + 当前这晚，N≥2 才注明。"""
    now = _now(d=5, h=2)
    # 两个时间戳同在 08-03（算一晚）、一个在 08-04 → 历史 2 晚
    times = [_now(d=3, h=1, mi=0), _now(d=3, h=3, mi=30), _now(d=4, h=2, mi=0)]

    hints = care.detect_care_hints(now, times, [])
    assert len(hints) == 1
    assert "第 3 个熬夜的夜晚" in hints[0]["text"]  # 历史 2 晚 + 当前

    hints_first = care.detect_care_hints(now, [], [])
    assert hints_first[0]["text"] == "冷冷现在还没睡。"  # 首晚不注明


def test_event_combo_absorbs_late_night():
    """24h 内有考试 + 深夜 → event_combo 命中并吞并 late_night。"""
    now = _now(d=5, h=2)
    events = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-05"}]

    hints = care.detect_care_hints(now, [], events)
    assert [h["type"] for h in hints] == [care.EVENT_COMBO]
    assert "今天有「期末考」，她还没睡" == hints[0]["text"]

    # 明天有考试 → 「明天」措辞
    events_tm = [{"title": "模拟考", "event_type": "exam", "event_date": "2026-08-06"}]
    hints_tm = care.detect_care_hints(now, [], events_tm)
    assert hints_tm[0]["text"] == "明天有「模拟考」，她还没睡"

    # 跨多天（昨天开始、明天结束）→ 仍算今天
    events_span = [{"title": "考试周", "event_type": "exam",
                    "event_date": "2026-08-04", "end_date": "2026-08-06"}]
    hints_span = care.detect_care_hints(now, [], events_span)
    assert hints_span[0]["text"] == "今天有「考试周」，她还没睡"


def test_event_combo_negative_cases():
    """事件在后天 / 非考试类型 / 白天有考试 → 不构成 combo。"""
    now = _now(d=5, h=2)
    far = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-07"}]
    assert [h["type"] for h in care.detect_care_hints(now, [], far)] == [care.LATE_NIGHT]

    trip = [{"title": "旅行", "event_type": "trip", "event_date": "2026-08-05"}]
    assert [h["type"] for h in care.detect_care_hints(now, [], trip)] == [care.LATE_NIGHT]

    daytime_exam = [{"title": "期末考", "event_type": "exam", "event_date": "2026-08-05"}]
    assert care.detect_care_hints(_now(d=5, h=15), [], daytime_exam) == []


def test_returned_gap_thresholds():
    """距上一条用户消息：4 天不触发 / 5 天触发 / 回来后不再触发。"""
    now = _now(d=5, h=15)

    assert care.detect_care_hints(now, [now - dt.timedelta(days=4, hours=23)], []) == []

    hints = care.detect_care_hints(now, [now - dt.timedelta(days=5)], [])
    assert [h["type"] for h in hints] == [care.RETURNED]
    assert "她已经 5 天没来了，这是回来了" == hints[0]["text"]

    # 触发后当前消息归档为最新 → 间隔归零 → 下一条不再触发
    assert care.detect_care_hints(now, [now - dt.timedelta(minutes=1)], []) == []


def test_returned_not_fired_for_empty_history():
    """从未对话（新库）→ 无「回来」可言。"""
    assert care.detect_care_hints(_now(h=15), [], []) == []
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_care.py -q`
Expected: collection error / ImportError（`agent.care` 不存在）。

- [x] **Step 3: 实现（新文件 `backend/agent/care.py`）**

```python
"""异常感知关心（Spec B）—— 检测器与冷却台账。

所有异常都发生在冷冷来聊天那一刻：深夜聊天、考前熬夜、消失后回来，
全是她发消息时才能观测到。本模块服务请求路径（api/routes/chat.py 的
_resolve_user_content 单一入口）：纯函数 detect_care_hints 产出提示，
冷却台账保证同一由头不唠叨（项目前科：LLM 自觉纪律实盘不可靠——
「别每次都说」必须由确定性状态保证，不能押在模型自觉上）。

提示只是感知与许可，不是指令：说不说、怎么说完全留给 LLM 按人格
发挥（与事件上下文「数据不是指令」的既有纪律同调）。
"""
import datetime

from utils.logger import get_logger
from utils.timezone import HKT

log = get_logger(__name__)

# 提示类型（也是 care_ledger 的主键）
LATE_NIGHT = "late_night"
EVENT_COMBO = "event_combo"
RETURNED = "returned"

# 各类型独立冷却（小时）。returned 不记台账：≥5 天间隔条件天然单次触发
COOLDOWN_HOURS = {LATE_NIGHT: 72, EVENT_COMBO: 24}

_LATE_NIGHT_START = 0    # HKT 0:00
_LATE_NIGHT_END = 5      # – 4:59（5 点起天快亮了，劝睡反而别扭）
_RETURNED_GAP_DAYS = 5
_NIGHT_COUNT_WINDOW_DAYS = 7
# Spec B 提及 exam/interview；interview 不在系统事件类型白名单
# （utils.events._EVENT_TYPES），第一期 combo 只认 exam
_COMBO_EVENT_TYPES = ("exam",)

_TIME_FMT = "%Y-%m-%d %H:%M"


def _is_late_night(now_dt: datetime.datetime) -> bool:
    return _LATE_NIGHT_START <= now_dt.hour < _LATE_NIGHT_END


def _late_night_days(recent_user_times, now_dt) -> int:
    """近 7 天深夜（0-5 点）用户消息按天去重计数。

    窗口为 [now-7d, now]：晚于 now 的时间戳（测试里归档时钟与注入
    时钟不同源时可能出现）一律排除。
    """
    cutoff = now_dt - datetime.timedelta(days=_NIGHT_COUNT_WINDOW_DAYS)
    days = {
        t.date() for t in recent_user_times
        if cutoff <= t <= now_dt and _is_late_night(t)
    }
    return len(days)


def detect_care_hints(now_dt, recent_user_times, active_events, *,
                      last_user_time=None) -> list[dict]:
    """纯函数：由 (当前时间, 最近用户消息时间, 活跃事件) 产出关心提示。

    返回 [{"type": ..., "text": ...}]。优先级纪律：同一请求
    event_combo 命中则不发 late_night（更具体的理由吞掉泛化的，
    避免一句话两个唠叨由头）。
    """
    hints: list[dict] = []

    # ── returned：距上一条用户消息 ≥5 天 ────────────────────
    last = last_user_time if last_user_time is not None else (
        max(recent_user_times) if recent_user_times else None
    )
    if last is not None and last <= now_dt:
        gap_days = (now_dt - last).total_seconds() / 86400
        if gap_days >= _RETURNED_GAP_DAYS:
            hints.append({
                "type": RETURNED,
                "text": f"她已经 {int(gap_days)} 天没来了，这是回来了",
            })

    if not _is_late_night(now_dt):
        return hints

    # ── event_combo：24h 内（今天/明天）有考试类事件 ─────────
    today = now_dt.date()
    tomorrow = today + datetime.timedelta(days=1)
    combo_event = None
    for ev in active_events:
        if ev.get("event_type") not in _COMBO_EVENT_TYPES:
            continue
        try:
            start = datetime.date.fromisoformat(ev.get("event_date") or "")
            end = (datetime.date.fromisoformat(ev["end_date"])
                   if ev.get("end_date") else start)
        except ValueError:
            continue
        if start <= tomorrow and end >= today:
            combo_event = (ev, "今天" if start <= today else "明天")
            break

    # ── 吞并规则：combo 命中则不发泛化的 late_night ──────────
    if combo_event:
        ev, day_word = combo_event
        hints.append({
            "type": EVENT_COMBO,
            "text": f"{day_word}有「{ev['title']}」，她还没睡",
        })
    else:
        nights = _late_night_days(recent_user_times, now_dt) + 1  # +当前这晚
        text = "冷冷现在还没睡。"
        if nights >= 2:
            text += f"近 7 天里这是第 {nights} 个熬夜的夜晚"
        hints.append({"type": LATE_NIGHT, "text": text})

    return hints
```

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_care.py -q`
Expected: 6 例全绿。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/agent/care.py backend/tests/test_care.py
git commit -m "feat(care): 异常感知纯函数检测器——深夜/考前熬夜/消失后回来（Spec B）"
```

---

### Task 7: Spec B ③ —— 冷却台账（迁移 v19 + 读写 + 过滤）

**Files:**
- Modify: `backend/database/migrations.py`（`SCHEMA_VERSION = 19` + `CARE_LEDGER_SCHEMA_SQL` + `_migrate_v19` + v19 版本注释）
- Modify: `backend/database/core.py`（`init_db` 新安装路径执行该 DDL）
- Modify: `backend/tests/conftest.py`（`tmp_db` 夹具执行该 DDL）
- Modify: `backend/agent/care.py`（追加 `load_ledger` / `record_hint` / `filter_cooled`）
- Modify: `backend/tests/test_care.py`（追加台账测试）
- Test: `backend/tests/test_migrations_v19.py`（新文件）

**Interfaces:**
- Consumes: Task 6 的 `COOLDOWN_HOURS`、类型常量
- Produces:
  - `CARE_LEDGER_SCHEMA_SQL`（三处共享：migrations / core.init_db / conftest.tmp_db）
  - `async def load_ledger() -> dict[str, str]`（读取失败 → `{}` = 不滤）
  - `async def record_hint(hint_type: str, now: str) -> None`（UPSERT；写失败只记日志）
  - `filter_cooled(hints, ledger, now_dt) -> list[dict]`（纯函数；returned 永不滤；台账时间戳异常 → 放行）

- [x] **Step 1: 写失败测试**

1a. 新文件 `backend/tests/test_migrations_v19.py`：

```python
"""迁移 v19 回归 — care_ledger（异常感知关心冷却台账）。

自建 v18 时代裸库，测迁移 SQL 本身。
"""
import aiosqlite
import pytest

from database.migrations import _migrate_v19

_V18_DDL = """
CREATE TABLE memories (
    id TEXT PRIMARY KEY, content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    valence REAL DEFAULT 0, arousal REAL DEFAULT 0
);
"""


@pytest.fixture
async def bare_db(tmp_path):
    conn = await aiosqlite.connect(str(tmp_path / "v19.db"))
    await conn.executescript(_V18_DDL)
    await conn.commit()
    yield conn
    await conn.close()


async def test_v19_creates_care_ledger(bare_db):
    await _migrate_v19(bare_db)

    cursor = await bare_db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='care_ledger'"
    )
    assert await cursor.fetchone() is not None

    cursor = await bare_db.execute("PRAGMA table_info(care_ledger)")
    cols = {row[1] for row in await cursor.fetchall()}
    assert {"type", "last_at"} <= cols


async def test_v19_idempotent_and_preserves_rows(bare_db):
    """重跑迁移不丢台账数据（CREATE IF NOT EXISTS 幂等）。"""
    await _migrate_v19(bare_db)
    await bare_db.execute(
        "INSERT INTO care_ledger (type, last_at) VALUES ('late_night', '2026-08-05 02:14')"
    )
    await bare_db.commit()

    await _migrate_v19(bare_db)  # 重跑

    cursor = await bare_db.execute(
        "SELECT last_at FROM care_ledger WHERE type = 'late_night'"
    )
    assert (await cursor.fetchone())[0] == "2026-08-05 02:14"
```

1b. 追加到 `backend/tests/test_care.py`：

```python
# ── 冷却台账（care_ledger，迁移 v19）──────────────────────────


@pytest.fixture
def care_db(tmp_db, monkeypatch):
    """把 agent.care 的读库接到临时库。"""
    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(care, "get_db", fake_get_db)
    return tmp_db


async def test_ledger_record_then_load_upserts(care_db):
    """注入即记账；同类型再记 → 覆盖时间（UPSERT）。"""
    await care.record_hint(care.LATE_NIGHT, "2026-08-05 02:14")
    assert await care.load_ledger() == {care.LATE_NIGHT: "2026-08-05 02:14"}

    await care.record_hint(care.LATE_NIGHT, "2026-08-06 01:00")
    assert (await care.load_ledger())[care.LATE_NIGHT] == "2026-08-06 01:00"


def test_filter_cooled_blocks_within_cooldown():
    """冷却期内同类滤掉；不同类型互不影响；returned 永不滤。"""
    now = _now(d=5, h=2)
    hints = [
        {"type": care.LATE_NIGHT, "text": "a"},
        {"type": care.EVENT_COMBO, "text": "b"},
        {"type": care.RETURNED, "text": "c"},
    ]

    ledger = {care.LATE_NIGHT: "2026-08-04 03:00"}   # 23h 前 < 72h → 滤
    passed = care.filter_cooled(hints, ledger, now)
    assert [h["type"] for h in passed] == [care.EVENT_COMBO, care.RETURNED]

    ledger2 = {care.EVENT_COMBO: "2026-08-04 01:00"}  # 25h 前 > 24h → 放行
    passed2 = care.filter_cooled(hints, ledger2, now)
    assert [h["type"] for h in passed2] == [care.LATE_NIGHT, care.EVENT_COMBO, care.RETURNED]


def test_filter_cooled_bad_timestamp_passes():
    """台账时间戳异常 → 放行（宁可多关心）。"""
    now = _now(d=5, h=2)
    hints = [{"type": care.LATE_NIGHT, "text": "a"}]
    assert care.filter_cooled(hints, {care.LATE_NIGHT: "垃圾时间戳"}, now) == hints


async def test_load_ledger_failure_degrades_to_no_filter(monkeypatch):
    """表读取失败 → 降级不滤（宁可多关心，不因 DB 抖动丢感知）。"""
    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(care, "get_db", broken_get_db)
    assert await care.load_ledger() == {}


async def test_record_hint_failure_swallowed(monkeypatch):
    """写入失败只记日志，不上抛。"""
    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(care, "get_db", broken_get_db)
    await care.record_hint(care.LATE_NIGHT, "2026-08-05 02:14")  # 不抛即通过
```

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 120 .venv/Scripts/python -m pytest tests/test_migrations_v19.py tests/test_care.py -q`
Expected: `_migrate_v19` ImportError；`care.load_ledger` AttributeError；台账用例因 tmp_db 无 care_ledger 表而失败。

- [x] **Step 3: 实现**

3a. `backend/database/migrations.py`：

版本注释列表末尾追加：

```python
# v19: 异常感知关心冷却台账 — 新增 care_ledger 表（type 主键 + last_at）。
#     Spec B：late_night 72h / event_combo 24h 各自独立冷却，「注入即
#     记账」——LLM 最后没提这茬也算冷却开始；防唠叨由确定性状态保证，
#     不押在模型自觉上（记忆写路径 31 条消息零触发的前科）。
SCHEMA_VERSION = 19
```

`MEMORY_EXTRACT_STATE_SQL` 等常量附近（与共享 DDL 常量放一处）追加：

```python
# 异常感知关心冷却台账（迁移 v19 / Spec B）：各提示类型独立冷却，
# 注入即记账。三处共享此常量（同 EVENTS_SCHEMA_SQL 模式）。
CARE_LEDGER_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS care_ledger (
        type     TEXT PRIMARY KEY,
        last_at  TEXT NOT NULL
    );
"""
```

文件末尾（`_migrations[18]` 之后、「核心迁移逻辑」之前）追加：

```python
# ── v19 迁移：异常感知关心冷却台账 ────────────────────────────


async def _migrate_v19(conn: aiosqlite.Connection) -> None:
    """v19: care_ledger — 异常感知关心的硬冷却台账（Spec B）。

    纯新表，无存量回填。全新安装由 core.init_db 执行共享 DDL 常量，
    老库走本迁移，两者终态一致（同 v6/v8 模式）。
    """
    await conn.executescript(CARE_LEDGER_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v19 完成：care_ledger 表已建立")


_migrations[19] = _migrate_v19
```

3b. `backend/database/core.py::init_db`：`from database.migrations import (` 块加入 `CARE_LEDGER_SCHEMA_SQL`；在 `await conn.executescript(MEMORY_EXTRACT_STATE_SQL)` 之后追加：

```python
    # care_ledger（迁移 v19）：异常感知关心冷却台账（Spec B）
    await conn.executescript(CARE_LEDGER_SCHEMA_SQL)
```

3c. `backend/tests/conftest.py::tmp_db`：import 块加入 `CARE_LEDGER_SCHEMA_SQL`，并在 `await conn.executescript(MEMORY_EXTRACT_STATE_SQL)` 后追加 `await conn.executescript(CARE_LEDGER_SCHEMA_SQL)`（注释同步：`care_ledger（迁移 v19）`）。

3d. `backend/agent/care.py` 追加（文件头 import 区加 `from database.core import get_db`）：

```python
async def load_ledger() -> dict[str, str]:
    """读取冷却台账 {type: last_at}。

    读取失败降级为**不滤**（返回空 dict——宁可多关心，不因 DB 抖动
    丢感知；与项目「失败放行」惯例一致）。
    """
    try:
        conn = await get_db()
        cursor = await conn.execute("SELECT type, last_at FROM care_ledger")
        return {r[0]: r[1] for r in await cursor.fetchall()}
    except Exception:
        log.warning("冷却台账读取失败，降级为不滤", exc_info=True)
        return {}


async def record_hint(hint_type: str, now: str) -> None:
    """注入即记账：冷却写时机 = 注入时（LLM 最后没提这茬也算冷却开始）。
    写入失败只记日志。"""
    try:
        conn = await get_db()
        await conn.execute(
            """INSERT INTO care_ledger (type, last_at) VALUES (?, ?)
               ON CONFLICT(type) DO UPDATE SET last_at = excluded.last_at""",
            (hint_type, now),
        )
        await conn.commit()
    except Exception:
        log.warning("冷却台账写入失败（type=%s）", hint_type, exc_info=True)


def filter_cooled(hints: list[dict], ledger: dict[str, str],
                  now_dt: datetime.datetime) -> list[dict]:
    """冷却期内的同类提示滤掉；各类型独立；returned 永不滤。

    台账时间戳异常 → 放行（失败语义同读取：宁可多关心）。
    """
    passed = []
    for h in hints:
        cooldown = COOLDOWN_HOURS.get(h["type"])
        if cooldown is None:
            passed.append(h)
            continue
        last = ledger.get(h["type"])
        if not last:
            passed.append(h)
            continue
        try:
            last_dt = datetime.datetime.strptime(last, _TIME_FMT).replace(tzinfo=HKT)
        except (ValueError, TypeError):
            passed.append(h)
            continue
        if (now_dt - last_dt).total_seconds() >= cooldown * 3600:
            passed.append(h)
    return passed
```

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 300 .venv/Scripts/python -m pytest tests/test_migrations_v19.py tests/test_care.py tests/test_migrations.py tests/test_init_db_upgrade.py -q`
Expected: 全绿（迁移框架与 init_db 升级链回归不动而绿）。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/database/migrations.py backend/database/core.py backend/tests/conftest.py backend/agent/care.py backend/tests/test_care.py backend/tests/test_migrations_v19.py
git commit -m "feat(care): 冷却台账 care_ledger（迁移 v19）——注入即记账，防唠叨确定性兜底（Spec B）"
```

---

### Task 8: Spec B ④ —— 请求路径接线 + 人格补一行

**Files:**
- Modify: `backend/agent/care.py`（追加 `user_message_times` / `last_user_message_time` / `collect_care_lines`）
- Modify: `backend/api/routes/chat.py`（`_resolve_user_content` 接入 care 流水线）
- Modify: `backend/agent/soul.py`（「说话的样子」段补两行深夜纪律）
- Modify: `backend/tests/test_integration.py`（`integration_app` 夹具补库路由 + 新增 4 例）
- Modify: `backend/tests/test_gps_optout.py`（3 例补库路由夹具）
- Modify: `backend/tests/test_care.py`（人格断言 1 例）

**Interfaces:**
- Consumes: Task 6 `detect_care_hints`、Task 7 `load_ledger/record_hint/filter_cooled/COOLDOWN_HOURS`、`utils.events.get_active_events(limit=20)`、`chat_messages.created_at`
- Produces:
  - `async def user_message_times(now_dt, days=7) -> list[datetime]`（近 7 天用户消息时间，升序，只取时间戳）
  - `async def last_user_message_time() -> datetime | None`（最近一条用户消息，无窗口）
  - `async def collect_care_lines(now_dt) -> list[str]`（取数→检测→冷却过滤→注入即记账→提示行；任何失败 → `[]`）
  - `_resolve_user_content` 输出的 `[系统信息：…]` 块携带通过的提示行
  - `BASE_PERSONA`「说话的样子」段新增深夜劝睡纪律两行

- [x] **Step 1: 写失败测试**

1a. 追加到 `backend/tests/test_care.py`：

```python
def test_persona_has_late_night_care_discipline():
    """人格补一行：深夜劝睡纪律进了 BASE_PERSONA（防唠叨的模型侧另一半）。"""
    from agent.soul import BASE_PERSONA

    assert "劝睡可以" in BASE_PERSONA
    assert "别每次都提" in BASE_PERSONA
```

1b. `backend/tests/test_integration.py::integration_app` 夹具补库路由——在 `chat_archive_module.get_db = mock_get_db` 之后追加：

```python
    # Spec B：care 流水线（care_ledger / chat_messages 时间戳 / 活跃事件）
    # 同样必须接到 tmp_db，否则对话路径会读/写穿生产库
    import agent.care as care_module
    import utils.events as events_module
    original_care_get_db = care_module.get_db
    original_events_get_db = events_module.get_db
    care_module.get_db = mock_get_db
    events_module.get_db = mock_get_db
```

yield 之后的还原段对应追加：

```python
    care_module.get_db = original_care_get_db
    events_module.get_db = original_events_get_db
```

1c. 追加到 `backend/tests/test_integration.py`：

```python
# ── Spec B：异常感知关心注入（深夜/冷却/降级）──────────────────


async def test_chat_late_night_injects_care_hint_and_records_ledger(
    client, integration_app, tmp_db
):
    """深夜请求：时间块 + 关心提示同居一块；注入即记账（台账落库）。"""
    import datetime as dt
    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "睡不着", "session_id": "s-care-1",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 02:14，深夜。" in content
    assert "冷冷现在还没睡。" in content
    assert content.count("[系统信息：") == 1   # 与时间行同居一块

    cursor = await tmp_db.execute(
        "SELECT last_at FROM care_ledger WHERE type = 'late_night'"
    )
    row = await cursor.fetchone()
    assert row is not None and row[0] == "2026-08-05 02:14"  # 注入即记账


async def test_chat_late_night_second_request_cooled_down(
    client, integration_app, tmp_db
):
    """冷却台账生效：72h 内第二个深夜不再注入 late_night 提示。"""
    import datetime as dt
    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        await client.post("/api/chat/stream", json={
            "message": "第一条", "session_id": "s-care-2",
        })
        resp = await client.post("/api/chat/stream", json={
            "message": "第二条", "session_id": "s-care-2",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是" in content        # 时间块永远在场（层次设计）
    assert "还没睡" not in content    # 提示被 72h 冷却滤掉


async def test_chat_daytime_only_time_block(client, integration_app, tmp_db):
    """白天请求：只注入时间块，无关心提示，不落台账。"""
    import datetime as dt
    from utils.timezone import HKT

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 15, 0, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "下午好", "session_id": "s-care-day",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是 2026-08-05 周三 15:00，下午。" in content
    assert "还没睡" not in content

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM care_ledger")
    assert (await cursor.fetchone())[0] == 0


async def test_chat_care_pipeline_failure_degrades(client, integration_app, monkeypatch):
    """care 读库全炸 → 时间块照注入、提示省略，对话不阻断。"""
    import datetime as dt
    from agent import care
    from utils.timezone import HKT

    async def broken_get_db():
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(care, "get_db", broken_get_db)

    captured = _install_capturing_stream(integration_app)
    fake_now = dt.datetime(2026, 8, 5, 2, 14, tzinfo=HKT)

    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat/stream", json={
            "message": "在吗", "session_id": "s-care-broken",
        })
    _, done = await _collect_sse(resp)
    assert done

    content = captured["input"]["messages"][0].content
    assert "现在是" in content
    assert "还没睡" not in content


async def test_chat_rest_path_also_injects_time_block(client, integration_app):
    """REST /api/chat 与 SSE 共用单一入口：同样带时间块。"""
    import datetime as dt
    from utils.timezone import HKT

    fake_now = dt.datetime(2026, 8, 5, 15, 0, tzinfo=HKT)
    with patch("api.routes.chat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        resp = await client.post("/api/chat", json={
            "message": "你好", "session_id": "s-rest-time",
        })
    assert resp.status_code == 200

    call = integration_app.state.agent.ainvoke.await_args
    content = call[0][0]["messages"][0].content
    assert "现在是 2026-08-05 周三 15:00，下午。" in content
```

1d. `backend/tests/test_gps_optout.py` 追加夹具并给三个直测接线（care 流水线现在会在 `_build_input_with_location` 里读库）：

```python
@pytest.fixture
def route_care_to_tmp(tmp_db, monkeypatch):
    """care 流水线（台账/消息时间/活跃事件）读库接到临时库，不碰生产库。"""
    from agent import care
    from utils import events as events_mgr

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(care, "get_db", fake_get_db)
    monkeypatch.setattr(events_mgr, "get_db", fake_get_db)
    return tmp_db
```

三个测试改签名：`test_gps_disabled_clears_cache_and_skips_injection(route_care_to_tmp)`、`test_gps_enabled_still_updates_and_injects(route_care_to_tmp)`、`test_gps_legacy_client_no_signal_unchanged(route_care_to_tmp)`（函数体不变）。

- [x] **Step 2: 跑测试确认红**

Run: `cd E:/小汐sonetto/backend && timeout 300 .venv/Scripts/python -m pytest tests/test_care.py -q -k "persona" && timeout 600 .venv/Scripts/python -m pytest tests/test_integration.py -q -k "care"`
Expected: 人格断言 FAIL；care 注入用例 FAIL（提示未接线）。

- [x] **Step 3: 实现**

3a. `backend/agent/care.py` 追加：

```python
def _parse_ts(s: str):
    try:
        return datetime.datetime.strptime(s, _TIME_FMT).replace(tzinfo=HKT)
    except (ValueError, TypeError):
        return None


async def user_message_times(now_dt: datetime.datetime,
                             days: int = _NIGHT_COUNT_WINDOW_DAYS) -> list[datetime.datetime]:
    """近 days 天用户消息的 created_at（HKT，升序）。只取时间戳不取内容。"""
    conn = await get_db()
    cutoff = (now_dt - datetime.timedelta(days=days)).strftime(_TIME_FMT)
    cursor = await conn.execute(
        "SELECT created_at FROM chat_messages "
        "WHERE role = 'user' AND created_at >= ? ORDER BY created_at ASC",
        (cutoff,),
    )
    return [t for t in (_parse_ts(r[0]) for r in await cursor.fetchall()) if t]


async def last_user_message_time():
    """最近一条用户消息的时间（无窗口——消失 30 天也要能算出间隔）。"""
    conn = await get_db()
    cursor = await conn.execute(
        "SELECT created_at FROM chat_messages WHERE role = 'user' "
        "ORDER BY id DESC LIMIT 1"
    )
    row = await cursor.fetchone()
    return _parse_ts(row[0]) if row else None


async def collect_care_lines(now_dt: datetime.datetime) -> list[str]:
    """请求路径一行接入：取数 → 检测 → 冷却过滤 → 注入即记账 → 提示行。

    任何环节失败 → 返回空列表（关心提示缺了不阻断对话；宁可漏一次
    关心，不可让请求路径挂掉）。注意调用时机：chat 路由在归档当前
    消息**之前**调用本函数，故「最近一条用户消息」天然是上一条。
    """
    try:
        times = await user_message_times(now_dt)
        last = await last_user_message_time()
        from utils import events as events_mgr
        active = await events_mgr.get_active_events()
    except Exception:
        log.warning("异常感知：读取信号失败，本次不注入关心提示", exc_info=True)
        return []

    hints = detect_care_hints(now_dt, times, active, last_user_time=last)
    if not hints:
        return []

    ledger = await load_ledger()
    passed = filter_cooled(hints, ledger, now_dt)
    for h in passed:
        if h["type"] in COOLDOWN_HOURS:   # returned 不记账（间隔条件天然单次）
            await record_hint(h["type"], now_dt.strftime(_TIME_FMT))
    return [h["text"] for h in passed]
```

3b. `backend/api/routes/chat.py::_resolve_user_content`：`lat_lon` 逻辑之后、return 之前插入：

```python
    # 异常感知关心（Spec B）：检测 + 冷却过滤 + 注入即记账。
    # collect_care_lines 内部已全兜底，这里再包一层只是纵深防御——
    # 关心提示任何失败都不得阻断对话
    try:
        from agent.care import collect_care_lines
        care_lines = await collect_care_lines(now_dt)
    except Exception:
        log.warning("异常感知关心整体失败，跳过", exc_info=True)
        care_lines = []

    return user_message + _system_info_block(now_dt, care_lines, lat_lon)
```

（替换 Task 5 版本的 `return user_message + _system_info_block(now_dt, [], lat_lon)`。）

3c. `backend/agent/soul.py`「## 说话的样子」段末尾（「不在每条消息里都提到海洋/鲸鱼/映海」一行之后）追加两行：

```
- 她深夜还在说话：劝睡可以，轻轻的，一句就够，别每次都提
- 她说知道了、说睡不着，就别念了——陪着，或者换个话头
```

- [x] **Step 4: 跑测试确认绿**

Run: `cd E:/小汐sonetto/backend && timeout 600 .venv/Scripts/python -m pytest tests/test_integration.py tests/test_gps_optout.py tests/test_care.py -q`
Expected: 全绿（两条「档案不落后缀」回归、GPS 开关联动用例不动而绿）。

- [x] **Step 5: 提交**

```bash
cd E:/小汐sonetto && git add backend/agent/care.py backend/api/routes/chat.py backend/agent/soul.py backend/tests/test_integration.py backend/tests/test_gps_optout.py backend/tests/test_care.py
git commit -m "feat(chat): 异常感知关心接线——深夜/考前/回归提示 + 硬冷却 + 人格劝睡纪律（Spec B）"
```

---

### Task 9: 全量回归 + 冒烟（临时副本 :8001，零污染取证）

**Files:** 无代码变更（纯验证）。若回归发现破绿，修复后回到对应任务补测。

- [x] **Step 1: 全量测试**

Run: `cd E:/小汐sonetto/backend && timeout 900 .venv/Scripts/python -m pytest tests -q`
Expected: 全部通过。**必须前台跑**（后台 pytest 在此机有挂死前科）。

- [x] **Step 2: 记录生产库指纹（零污染判据）**

Run: `sha256sum E:/小汐sonetto/backend/data/userdata.db`（记录输出；若常驻实例正在写 WAL，静置几分钟再取）

- [x] **Step 3: 临时副本冒烟（带生产库副本，验 v18→v19 升级链）**

```bash
TMP=$(mktemp -d)
cp -r E:/小汐sonetto/backend $TMP/backend
rm -rf $TMP/backend/.venv $TMP/backend/__pycache__
printf 'DEEPSEEK_API_KEY=sk-fake-smoke-test\nXIAOXI_API_TOKEN=smoke-fake-token\n' > $TMP/backend/config/.env
```

（`.env` 两个 key 必须非空否则 fail-closed 拒启动；假 key 的 LLM 401 会被既有 try/except 兜住，正好顺便验证降级路径。）

- [x] **Step 4: 启动 :8001 并验证**

用 `run_in_background: true` 起服务（命令：`cd $TMP/backend && PYTHONPATH=$TMP/backend E:/小汐sonetto/backend/.venv/Scripts/python -m uvicorn api.server:app --host 127.0.0.1 --port 8001`），等待日志出现「迁移到 schema v19 完成」与 Uvicorn running，然后前台执行：

```bash
# 健康检查
curl -s -H "Authorization: Bearer smoke-fake-token" http://127.0.0.1:8001/api/health

# 对话（假 key → LLM 401 → 500 友好文案；但预处理与归档应已完成）
curl -s -X POST http://127.0.0.1:8001/api/chat \
  -H "Authorization: Bearer smoke-fake-token" -H "Content-Type: application/json" \
  -d '{"message":"冒烟测试","session_id":"smoke-1"}'

# 归档已落（证明 _resolve_user_content 全链路无异常跑完）
curl -s -H "Authorization: Bearer smoke-fake-token" \
  "http://127.0.0.1:8001/api/sessions/smoke-1/messages"
```

Expected: health 返回 `{"status":"ok",...}`；chat 返回 500 友好文案（「小汐被浪花打了一下……」）；sessions 返回含 `{"role":"user","content":"冒烟测试"}` 且**不含** `[系统信息`。

再验副本库 schema（用仓库 venv，路径走 argv 无中文）：

```bash
E:/小汐sonetto/backend/.venv/Scripts/python - "$TMP/backend/data/userdata.db" <<'PY'
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
print("schema_version:", conn.execute("SELECT MAX(version) FROM schema_version").fetchone()[0])
print("care_ledger:", conn.execute(
    "SELECT name FROM sqlite_master WHERE name='care_ledger'").fetchone())
PY
```

Expected: `schema_version: 19`、`care_ledger: ('care_ledger',)`。

- [x] **Step 5: 收尾与零污染核验**

```bash
# 停副本服务（wmic 方式在此机最稳）
wmic process where "CommandLine like '%port 8001%'" call terminate
rm -rf $TMP
sha256sum E:/小汐sonetto/backend/data/userdata.db   # 与 Step 2 一致
```

Expected: 生产库 hash 前后一致（冒烟只碰副本）。若中途有后台挂死进程，同样用 wmic 终止；`scripts/cleanup_test_residue.py --yes` 如需执行必须先经用户单独批准。

- [x] **Step 6: 无代码变更则无需提交；如有修复，修复提交后重跑 Step 1。**

---

## 计划内已知偏差（相对 spec 原文）

1. **event_combo 只认 `exam`**：Spec B 写 exam/interview，但系统事件类型白名单（`utils.events._EVENT_TYPES`）没有 interview；扩白名单超出本 spec 范围。已在 `agent/care.py` 常量注释留痕。
2. **`returned` 检测取「最近一条用户消息」无 7 天窗口**：spec 写「近 7 天即可」，但 >7 天的消失（如一个月）用 7 天窗口查不到上一条消息、会漏检。纯函数与 7 天窗口测试语义不受影响，仅取数侧多一个 LIMIT 1 查询。
3. **三处既有测试的断言/路由更新**（GPS 关闭用例的「无后缀」断言改为「无 GPS 行但有时间块」；heartbeat/proactive 若干用例补 tmp_db 路由）：均为两份 spec 的直接后果（时间永远注入、heartbeat 开始读档案），已逐处列在对应任务内。
