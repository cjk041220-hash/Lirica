# 🐋 小汐 (XiaoXi Sonetto) — 完整技术文档

> 「希望你一个人在香港的时候，也不会觉得孤单。」
>
> 小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

---

## 项目结构

```
小汐sonetto/
├── .gitignore                   # Git 排除规则
├── .dockerignore                # Docker 构建排除
├── Dockerfile                   # 后端容器构建（Python 3.12-slim 多阶段，--workers 1）
├── docker-compose.yml           # 一键部署编排
├── README.md                    # 项目说明 + 快速启动
├── start.bat                    # Windows 一键启动（自动建 venv + 装依赖）
│
├── docs/                        # 项目文档
│   ├── PROJECT_SOURCE.md        # 本文档（完整技术参考）
│   └── PROMPT_GUIDE.md          # 小汐人格 Prompt 修改指南
│
├── backend/
│   ├── main.py                  # 启动入口（uvicorn）
│   ├── version.py               # 版本号 v0.4.2（唯一定义处）
│   ├── heartbeat.py             # 心跳调度器（每 2h，更新心情+事件+刷新 Agent+定期维护）
│   ├── pytest.ini               # 测试配置（asyncio_mode = auto）
│   ├── requirements.txt         # Python 依赖（含 pytest）
│   │
│   ├── config/
│   │   ├── settings.py          # 全局配置（pydantic-settings, .env 加载）
│   │   ├── .env                 # API Key（不入 git）
│   │   └── .env.example         # 配置模板
│   │
│   ├── database/
│   │   ├── core.py              # 数据库连接管理 + checkpoint 裁剪 + Online Backup API
│   │   └── migrations.py        # 轻量级 schema 迁移框架（schema_version 表）
│   │
│   ├── api/
│   │   ├── server.py            # FastAPI 应用工厂 + 生命周期管理
│   │   ├── middleware/
│   │   │   ├── __init__.py      # 中间件子包导出
│   │   │   ├── auth.py          # 认证 + IP 限流（含定期清理）
│   │   │   └── websocket_reject.py  # WebSocket 拒绝
│   │   └── routes/
│   │       ├── chat.py          # 对话路由（REST + SSE 流式 + 断开检测）+ 记忆管理端点
│   │       └── config.py        # 配置路由（模型/思考强度切换，运行时重建 Agent）
│   │
│   ├── agent/
│   │   ├── graph.py             # LangGraph ReAct Agent 构建器
│   │   └── soul.py              # 系统提示词组装（3 段常量 + 生命状态注入，不含记忆叙事）
│   │
│   ├── providers/
│   │   └── deepseek.py          # DeepSeek V4 Flash/Pro 接入（OpenAI 兼容）
│   │
│   ├── tools/                   # 15 个工具（全部 async，含网络重试）
│   │   ├── __init__.py          # 工具汇总导出 + get_all_tools() + set_http_client()
│   │   ├── weather.py           # 天气查询（Open-Meteo + 高德回退，3 次重试）
│   │   ├── map_tools.py         # 地图工具（高德：搜索/定位/路线，2 次重试）
│   │   ├── search.py            # 网络搜索（Tavily，2 次重试）
│   │   ├── memory.py            # 记忆工具（5 个：增删查导出列表）
│   │   ├── diary.py             # 日记工具（写/读，同日 UPDATE 去重）
│   │   ├── tarot.py             # 塔罗牌占卜（78 张韦特，3 种牌阵）
│   │   └── tarot_deck.yaml      # 78 张韦特塔罗牌数据
│   │
│   ├── memory/
│   │   ├── manager.py           # 记忆管理器（CRUD + recall LIMIT + 清空前自动备份）
│   │   ├── narrative.py         # 叙事构建器（分类分组，per-request 注入）
│   │   └── location_store.py    # GPS 坐标会话级缓存（asyncio.Lock, 8h TTL）
│   │
│   ├── utils/
│   │   ├── logger.py            # GBK 安全的结构化日志
│   │   └── retry.py             # 异步重试装饰器（指数退避，仅对网络瞬时错误）
│   │
│   ├── tests/                   # 测试套件（66 个测试）
│   │   ├── conftest.py          # 共享 fixtures（tmp_db, memory_mgr, integration_app）
│   │   ├── test_memory_manager.py   # 记忆 CRUD + 去重 + recall LIMIT（18 测试）
│   │   ├── test_soul.py         # 提示词组装 + 生命状态注入（7 测试）
│   │   ├── test_heartbeat.py    # 心跳时段匹配 + DB UPSERT（8 测试）
│   │   ├── test_weather.py      # 坐标解析 + None 哨兵 + WMO 码（6 测试）
│   │   ├── test_narrative.py    # 叙事构建 + SQL LIMIT（6 测试）
│   │   ├── test_config_routes.py    # 运行时配置 + 预设验证（4 测试）
│   │   ├── test_diary.py        # 日记写入/去重/读取（5 测试）
│   │   ├── test_migrations.py   # 迁移框架（3 测试）
│   │   └── test_integration.py  # 端到端 HTTP 集成测试（9 测试）
│   │
│   └── data/                    # 运行时数据（不入 git）
│       ├── userdata.db          # 业务数据库
│       ├── checkpoints.db       # LangGraph 对话状态（定期裁剪）
│       └── backups/             # 自动备份目录
│           ├── userdata_backup_*.db     # SQLite Online Backup 快照（保留最近 5 份）
│           └── memories_backup_*.json   # 清空记忆前的 JSON 备份
│
└── web/                         # 前端 Next.js 16（App Router + TypeScript + Tailwind v4）
    ├── package.json
    ├── next.config.ts
    ├── tsconfig.json
    ├── postcss.config.mjs
    ├── eslint.config.mjs
    ├── public/                  # 静态资源
    └── src/
        ├── app/
        │   ├── globals.css      # 全局样式 + CSS 变量（温暖纸色/治愈水色/毛玻璃）
        │   ├── layout.tsx       # 根布局
        │   └── page.tsx         # 首页（三层 Z 轴布局骨架）
        ├── components/
        │   └── LivingPanel.tsx  # 毛玻璃聊天面板（静态骨架）
        └── hooks/
            └── useXiaoxiChat.ts # 流式聊天 Hook（SSE, 已就绪待接通）
```

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | FastAPI + LangGraph (create_agent) |
| LLM | DeepSeek V4 Flash / V4 Pro（OpenAI 兼容接口） |
| 天气 | Open-Meteo（免费，无需 Key）+ 高德地理编码回退 |
| 地图 | 高德地图 Web 服务 API（POI/地理编码/路线） |
| 搜索 | Tavily（1000 次/月免费） |
| 心跳调度 | APScheduler (AsyncIOScheduler)，每 2h，启动立即触发 |
| 业务数据库 | SQLite (aiosqlite)，WAL 模式，PRAGMA 优化 |
| 对话检查点 | SQLite (langgraph-checkpoint-sqlite)，定期裁剪 |
| 数据库备份 | SQLite Online Backup API（每 2h 自动快照，保留 5 份） |
| 数据库迁移 | 自研轻量框架（schema_version 表 + 版本函数） |
| HTTP 客户端 | httpx（lifespan 管理的共享 AsyncClient） |
| 网络重试 | 自研 `with_retry` 装饰器（指数退避，按 API 分档） |
| 前端 | Next.js 16 (App Router) + TypeScript + Tailwind CSS v4 |
| 动画 | Framer Motion |
| 容器化 | Docker 多阶段构建 + docker-compose（--workers 1） |
| 测试 | pytest + pytest-asyncio（66 测试，~5 秒） |
| GPS | Web Geolocation API（前端，待集成） |

---

## 数据库设计（userdata.db）

### memories 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | TEXT PK | UUID hex[:16] |
| category | TEXT | 记忆类别 |
| content | TEXT | 记忆内容 |
| source | TEXT | 来源 (dialogue) |
| time | TEXT | 创建时间 (YYYY-MM-DD HH:MM) |
| last_used_at | TEXT | 最后被召回时间 |
| weight | INTEGER DEFAULT 10 | 权重 |
| emotion_tag | TEXT | 情绪标签（预留） |

索引：`idx_memories_category(category)`, `idx_memories_cat_content(category, content)`

### diaries 表

| 字段 | 类型 | 说明 |
|------|------|------|
| date | TEXT PK | 日期 (YYYY-MM-DD) |
| title | TEXT | 标题 (M月D日) |
| content | TEXT | 日记内容 |

### life_state 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK DEFAULT 1 | 唯一行 |
| mood | TEXT | 心情（开心/发呆/困倦…） |
| daily_event | TEXT | 日常事件 |
| updated_at | TEXT | 最后更新时间 |

### schema_version 表（迁移系统）

| 字段 | 类型 | 说明 |
|------|------|------|
| version | INTEGER PK | schema 版本号 |
| applied_at | TEXT | 迁移执行时间 |

当前 `SCHEMA_VERSION = 1`（初始 schema）。添加新迁移只需在 `migrations.py` 中递增版本号并注册迁移函数。

---

## 核心模块说明

### database/core.py — 数据库连接管理 + 运维

**连接管理：**
- 全局长连接（`init_db_connection()`），WAL + NORMAL sync + 5s busy_timeout
- `get_db()` 双重检查锁惰性初始化
- `init_db()` 建表 + 建索引 + life_state 初始行 + 调用 `ensure_schema()`
- `close_db_connection()` 优雅关闭（含异常日志）

**Checkpoint 裁剪（`prune_checkpoints()`）：**
- 使用独立 aiosqlite 连接打开 `checkpoints.db`（WAL 模式下与 AsyncSqliteSaver 并发安全）
- 每个 thread 只保留最近 10 个 checkpoint（`ROW_NUMBER() OVER (PARTITION BY thread_id)`）
- 清理孤立的 `checkpoint_writes` 和 `checkpoint_blobs` 记录
- 删除超过 50 条后执行 `VACUUM` 回收磁盘空间
- 由心跳任务每 2h 自动执行

**数据库备份（`backup_database()`）：**
- 使用 `aiosqlite.backup()`（SQLite Online Backup API）创建一致性快照
- 比 `shutil.copy()` 更安全——即使源数据库正在写入也能得到一致备份
- 自动保留最近 5 份，删除更旧的备份
- 由心跳任务每 2h 自动执行

### database/migrations.py — 迁移框架

- `SCHEMA_VERSION` 常量：当前 schema 版本
- `_migrations` 字典：`{版本号: 迁移函数}`
- `ensure_schema(conn)` → 检查 schema_version 表，按顺序执行未应用的迁移
- 幂等安全：多次执行不重复

### memory/manager.py — 记忆管理器

- 基于 aiosqlite 全局连接，全部异步方法
- `add(category, content)` → dict（自动去重：同 category + content 只更新时间）
- `delete(id)` → bool, `clear()` → int, `export_json()` → str
- `recall(category="", limit=20)` → list[dict]（**默认 LIMIT 20 防止撑爆 LLM 上下文**，自动更新 last_used_at）
- `backup_before_clear()` → Path（清空前导出 JSON 备份到 `data/backups/`）
- `list_categories()` → dict[str,int], `count()` → int
- `all()` → list[dict]（DESC），`recent(limit)` → list[dict]（SQL LIMIT，ASC）

### memory/narrative.py — 叙事构建器

- `async build(max_items=30)` → str
- 调用 `manager.recent(max_items)` 取最近 N 条（SQL 层面 LIMIT，无全表扫描）
- 按类别分组 → 生成 Markdown 文本
- **注意**：叙事只在 per-request 时注入用户消息前缀（不再烘焙进 system prompt）

### agent/soul.py — 系统提示词组装

- **3 个常量**：`BASE_PERSONA`（人格核心）、`INTERACTION_RULES`（交互铁律）、`TOOL_GUIDELINES`（工具指引）
- `async build_system_prompt()` → 读取 life_state → 按序组装：
  `[人格] → [生命状态] → [规则] → [工具]`
- **不再包含记忆叙事**——叙事由 chat.py 按请求动态注入，避免双重注入和过期记忆
- 生命状态读取异常 → `log.warning`（不静默吞错）

### heartbeat.py — 心跳调度器 + 系统刷新 + 定期维护

- `AsyncIOScheduler`，每 2 小时触发，**启动时立即执行首次**（`next_run_time=now`）
- 4 个时段（深夜/早晨/白天/夜晚），各有独立心情池 + 事件池
- `get_current_period(hour)` → if-elif 链匹配
- `check_and_proactive_message(app)` → 接受 app 引用：
  1. UPSERT life_state 表（心情 + 日常事件）
  2. **`_refresh_agent(app)`** → 重建 system prompt（读取最新 life_state）+ 热替换 agent 实例
  3. **`_periodic_maintenance()`** → 调用 `prune_checkpoints()` + `backup_database()`
- Agent 热替换并发安全：CPython GIL 保证 `app.state.agent` 引用赋值原子性，进行中的请求持有旧 agent 完成，新请求拿到新 agent

### api/server.py — 生命周期

启动流程（全异步）：
1. 启动校验 → API Key 必须存在
2. 业务数据库 → `init_db_connection()` + `init_db()`
3. 记忆系统 → `MemoryManager()` + `NarrativeBuilder()`
4. 共享 HTTP 客户端 → `httpx.AsyncClient(timeout=10)` + `set_http_client()`
5. 工具集 → `get_all_tools()`（15 个 async 工具）
6. LLM → `create_deepseek_llm()`（默认 V4 Flash）
7. 系统提示词 → `build_system_prompt()`（**不含记忆叙事**，叙事由 chat.py 按请求注入）
8. Agent 图 → `AsyncSqliteSaver` / `MemorySaver` 回退 + `build_agent()`
9. 保存 `app.state.model` 引用供心跳刷新使用
10. 心跳 → `scheduler.start()`（传入 app 引用，首次立即触发）

关闭：`scheduler.shutdown()` + `http_client.aclose()` + `db_conn.close()` + `close_db_connection()`

### api/routes/chat.py — 对话路由

**SSE 流式输出（`/api/chat/stream`）：**
- 使用 `agent.astream_events(version="v2")` 实现 token 级流式
- **断开检测**：循环内 `await request.is_disconnected()`，客户端关闭后立即停止生成，避免白烧 API Token
- **`asyncio.CancelledError` 捕获**：ASGI 服务器取消任务时正确 re-raise
- 三种事件类型：`{"content": "..."}`, `{"type": "tool_start", ...}`, `{"type": "tool_end", ...}`
- 终止信号：`data: [DONE]`

**记忆叙事注入（`_build_dynamic_context()`）：**
- 每次请求调用 `narrative_builder.build(max_items=5)` 取最近 5 条记忆
- 作为系统上下文前缀注入用户消息（唯一的记忆注入点，system prompt 中不含记忆）

**清空保护（`/api/memories/clear`）：**
- 必须携带 `?confirm=true` 查询参数，否则返回 400 + 操作说明
- 执行前自动调用 `backup_before_clear()` 导出 JSON 备份
- 返回 `{cleared, backup}` 告知备份路径

### utils/retry.py — 异步重试装饰器

- `with_retry(max_attempts, base_delay)` → 装饰器
- **只对瞬时网络错误重试**：`TimeoutException`, `ConnectError`, `ReadError`, `WriteError`, `PoolTimeout`
- **不对 HTTP 状态码错误重试**（4xx/5xx 表示服务端已明确回复）
- 指数退避：delay = base_delay × 2^(attempt-1)
- 各工具按 API 特性分档：Open-Meteo 3 次（免费）、高德 2 次（付费）、Tavily 2 次

### api/middleware/ — 中间件子包

- `auth.py`：认证（Bearer Token）+ IP 限流（按端点分桶，每 100 请求清理过期 bucket）
- `websocket_reject.py`：拒绝所有 WebSocket 连接（1008 关闭码）

---

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 非流式对话（含 GPS 坐标支持） |
| `/api/chat/stream` | POST | 流式对话（SSE, token 级，含断开检测） |
| `/api/config` | GET | 获取控制台配置 |
| `/api/config` | POST | 更新配置（模型/温度/思考强度），重建 Agent |
| `/api/health` | GET | 健康检查（认证后返回完整诊断） |
| `/api/memories` | GET | 获取所有记忆列表 |
| `/api/memories/{id}` | DELETE | 删除一条记忆 |
| `/api/memories/export` | GET | 导出所有记忆（JSON 下载） |
| `/api/memories/clear` | POST | 清空所有记忆（**需 `?confirm=true`**，自动备份） |
| `/docs` | GET | Swagger 文档 |

---

## 运行时数据流

```
启动
  └→ init_db_connection() → WAL + PRAGMA
  └→ init_db() → 建表 + ensure_schema()
  └→ create_deepseek_llm() → 默认 V4 Flash
  └→ build_system_prompt() → SELECT life_state → 组装 Prompt（不含记忆叙事）
  └→ app.state.model = model（供心跳刷新使用）
  └→ heartbeat → 首次立即触发 → UPSERT life_state + 刷新 Agent + 维护

对话请求
  └→ middleware: 认证 + 限流检查
  └→ chat.py: _build_dynamic_context() → narrative_builder.build(max_items=5) → 最近 5 条记忆
  └→ chat.py: _build_input_with_location() → GPS 缓存注入
  └→ agent.ainvoke/astream_events → LangGraph → 工具调用 → 回复
  └→ SSE 流：每轮检测 is_disconnected()，客户端断开立即终止
  └→ 工具: remember → MemoryManager.add() → memories 表
  └→ 工具: write_diary → diaries 表

心跳 (每 2h)
  └→ get_current_period(hour) → 匹配时段
  └→ random.choice(moods) + random.choice(events)
  └→ UPSERT life_state
  └→ _refresh_agent(app) → 重建 system prompt（读取最新心情）→ 热替换 agent
  └→ _periodic_maintenance()
      └→ prune_checkpoints() → 每 thread 保留 10 个，清理孤儿记录
      └→ backup_database() → Online Backup API 快照，保留 5 份
```

---

## 记忆注入架构（v0.4.2 重构）

```
旧架构（v0.4.1，双重注入）：
  System Prompt:  [人格] + [生命状态] + [规则] + [工具] + [30 条记忆叙事]  ← 启动时烘焙，过期不刷新
  User Message:   [30 条记忆叙事] + [GPS] + [用户输入]                   ← 每请求注入
  → LLM 看到同一份记忆两次，且 system prompt 中的记忆永远停留在启动时刻

新架构（v0.4.2，单一注入 + 心情同步）：
  System Prompt:  [人格] + [生命状态] + [规则] + [工具]                   ← 不含记忆，心跳后自动刷新
  User Message:   [5 条近期记忆] + [GPS] + [用户输入]                    ← 每请求注入，唯一的记忆来源
  → LLM 只看到一次记忆（最新 5 条），深度回忆交给 recall 工具按需查询
  → 心情始终与实际状态同步（心跳后自动刷新 system prompt）
```

---

## 测试套件（66 测试，~5 秒）

| 文件 | 测试数 | 覆盖范围 |
|------|--------|---------|
| `test_memory_manager.py` | 18 | CRUD、去重、recall() LIMIT（默认/自定义/分类）、export |
| `test_soul.py` | 7 | 提示词组装、生命状态注入、**叙事排除验证**、DB 异常容错 |
| `test_heartbeat.py` | 8 | 4 时段匹配、3 边界值、DB UPSERT |
| `test_weather.py` | 6 | 坐标解析、None 哨兵、WMO 码映射 |
| `test_narrative.py` | 6 | 空库/有记忆/max_items/分类/时序 |
| `test_config_routes.py` | 4 | 默认值、预设完整性、模型名一致性 |
| `test_diary.py` | 5 | 写入/同日去重/按日期读/最近 5 篇 |
| `test_migrations.py` | 3 | schema_version 表、幂等性、版本号 |
| `test_integration.py` | 9 | HTTP 端到端：health/config/chat/stream/memories/CORS/校验 |

运行：`cd backend && .venv/Scripts/python -m pytest tests/ -v`

---

## 部署

### Docker（推荐）

```bash
docker compose up -d
# 后端 API: http://localhost:8000
# 健康检查: http://localhost:8000/api/health
```

> ⚠️ Dockerfile 显式锁定 `--workers 1`。APScheduler 是模块级单例，多 Worker 会导致心跳任务重复执行。如需水平扩展，应将调度器拆到独立进程或使用分布式锁。

### 手动

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
# 配置 .env
.venv/Scripts/python main.py
```

### Windows

双击 `start.bat`。

---

## 变更日志

### v0.4.2 → 架构安全加固（2026-07-21）

#### 🔴 关键修复

- **记忆叙事双重注入消除**：system prompt 不再烘焙记忆叙事，改为 per-request 动态注入最近 5 条（之前是启动时烘焙 30 条 + 每请求再注入 30 条 = 双重 60 条）
- **心情过期修复**：心跳更新 life_state 后自动重建 system prompt + 热替换 agent，小汐的心情不再停留在启动时刻
- **SSE 流断开检测**：循环内 `request.is_disconnected()` + `asyncio.CancelledError` 捕获，客户端关闭后不再白烧 DeepSeek API Token
- **recall 工具 LIMIT**：`recall()` 默认 LIMIT 20，防止大量记忆撑爆 LLM 上下文窗口

#### 🟠 安全加固

- **清空记忆保护**：`/api/memories/clear` 必须携带 `?confirm=true`，执行前自动备份到 `data/backups/memories_backup_<timestamp>.json`

#### 🟡 运维加固

- **Checkpoint 裁剪**：`prune_checkpoints()` 每 thread 保留最近 10 个 checkpoint，清理孤儿 writes/blobs，大量删除后 VACUUM
- **数据库自动备份**：`backup_database()` 使用 SQLite Online Backup API 创建一致性快照，保留最近 5 份
- 裁剪 + 备份随心跳每 2h 自动执行

#### 🟢 代码质量

- **Dockerfile 显式 `--workers 1`**：防止未来误配多 Worker 导致 APScheduler 重复执行
- **Session ID 完整 UUID**：`_ensure_sid()` 去掉 `[:8]` 截断，使用完整 UUID4（36 字符）
- **工具网络重试**：新增 `utils/retry.py` 异步重试装饰器（指数退避），Open-Meteo 3 次、高德 2 次、Tavily 2 次
- 新增 3 个 recall LIMIT 测试，更新 soul 测试验证叙事排除

### v0.4.1 → 架构补强（2026-07-21）

- 新增数据库迁移框架（`database/migrations.py`，schema_version 表）
- 新增 Dockerfile + docker-compose.yml + .dockerignore
- 新增 9 个端到端集成测试（HTTP 全链路 + mock Agent）
- 新增 3 个迁移系统测试
- `_build_input_with_location()` 修复 `await in non-async def` 崩溃隐患
- 非流式 chat 端点补充 GPS 坐标支持
- `tarot()` 从 sync 改为 async（与其余 14 个工具一致）
- 移除 `app.state._db_conn` dead state
- server.py 静态资源路径修正（`frontend/` → `web/out/`）
- README 全面重写
- 清理 stale 文件（memories.yaml, test_lifespan.db, test_output.txt）
- .gitignore 补全
- 项目文档移至 `docs/` 目录

### v0.4.0 → 综合修复（2026-07-21）

#### 部署前必修（🔴）
- 模型默认名称 `deepseek-chat` → `deepseek-v4-flash`（与 AVAILABLE_MODELS 对齐）
- 叙事构建器改用 SQL `LIMIT`（`MemoryManager.recent()`），消除全表扫描
- `soul.py` 生命状态读取异常补充 `log.warning`（消除静默吞错）
- 新增 51 个测试（7 个测试文件）

#### 首月修复（🟡）
- 心跳调度器添加 `next_run_time` 立即触发首次更新
- `_resolve_coords` 失败标记从 `(0,0)` 改为 `None`（消除几内亚湾误判）
- 注释从 V3/R1 更新为 V4 Flash/Pro
- `start.bat` 端口释放添加 `pause` 确认
- `.env.example` 模型名同步更新

#### 代码卫生（🟢）
- 裸 `except Exception: pass` 全部补充日志
- 中间件提取到 `api/middleware/` 子包（auth + websocket_reject）
- httpx 客户端复用（lifespan 管理，消除每请求新建连接）
- `location_store.py` 从 `threading.Lock` 迁移到 `asyncio.Lock`
- 限流器添加定期清理，防止 IP bucket 内存泄漏
- `get_current_period()` 改为 if-elif 链
- 配置重建时跳过冗余叙事构建

### v0.3.0 → SQLite 全异步重构 + 生命状态（2026-07-20）
- `database/core.py` 独立数据库模块
- MemoryManager / NarrativeBuilder / 日记工具全部 async 重写（YAML → SQLite）
- `agent/soul.py` 拆分为 BASE_PERSONA / INTERACTION_RULES / TOOL_GUIDELINES
- 心跳系统 + 生命状态注入

### v0.2.1 → 早期版本
- GPS 定位、PWA 支持、塔罗牌占卜
- 记忆管理 API、输入校验
- 前端 Next.js 迁移（骨架阶段）

---

*文档更新时间：2026-07-21 · 当前版本：v0.4.2 · 测试：66 passed*
