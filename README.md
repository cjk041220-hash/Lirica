# 🐋 小汐 (XiaoXi)

> 「希望你一个人在香港的时候，也不会觉得孤单。」

小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

## 快速启动

### 一键启动（Windows）

双击 `start.bat`，自动创建虚拟环境、安装依赖、启动服务。

### 一键启动（macOS / Linux）

```bash
chmod +x start.sh   # 首次需要
./start.sh          # 自动创建虚拟环境、安装依赖、构建前端、启动服务
```

手动启动路径与 Windows 相同，把 `.venv\Scripts\python` 换成
`.venv/bin/python` 即可。Python 需要 3.10+（推荐 3.12，见 `.python-version`）。

### 手动启动

```bash
# 后端
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
# 复制 config/.env.example → config/.env，填入 API Key
.venv/Scripts/python main.py

# 前端（Vite + React 19，开发模式）
cd web
npm install
npm run dev        # http://localhost:3000，/api 自动代理到 :8000

# 生产一体部署：构建前端，FastAPI 会把 web/dist 挂到 :8000 根路径
npm run build      # 产出 web/dist，之后只启后端即可（前后端同端口）
```

### Docker 部署

```bash
docker compose up -d
# 后端 API: http://localhost:8000
# 健康检查: http://localhost:8000/api/health
```

> ⚠️ **安全部署须知**
>
> - docker-compose 默认只把端口绑定到 `127.0.0.1`（仅本机可访问）。
> - **不要**在未配置 `XIAOXI_API_TOKEN` 的情况下把 `8000` 端口暴露到公网：
>   空 token 时 API 无认证，任何人都能读取记忆、对话（消耗你的 API 账单）
>   甚至清空数据库。
> - 公网部署的正确姿势：前置 Nginx/Caddy 反向代理 + HTTPS + 认证层，
>   同时设置 `XIAOXI_API_TOKEN`。
> - 启动日志中出现 `XIAOXI_API_TOKEN 未设置` 警告时，请确认仅本机使用。
>
> **部署约束**
>
> - 后端为**单用户、单进程**设计：运行时配置、GPS 缓存、限流桶、心跳
>   调度器都是进程内状态，**只能以单 worker 运行**（main.py / Dockerfile
>   已按此配置）。多 worker 会导致限流失效与心跳重复。
> - 数据库为单条 aiosqlite 长连接共享事务，适配低并发个人场景。
> - 备份文件（`data/backups/`）含记忆/日记/生理期记录，目录权限自动
>   收紧为 700/600（Linux 生效；Windows 开发机请依赖 NTFS 权限/全盘加密）；
>   请勿将其置于可被他人读取的位置，也不要提交入 git。
> - **密钥轮换**：若 `.env` 曾以过宽权限（如 644）存放或被他人读取过，
>   应视为已泄露——请轮换 DeepSeek / 高德 / Tavily 密钥与 VAPID 私钥，
>   并在 Linux 服务器上执行 `chmod 600 backend/config/.env`。

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 对话（非流式） |
| `/api/chat/stream` | POST | 流式对话（SSE，token 级） |
| `/api/chat/history` | GET | 当前会话刷新恢复（读 checkpoint，会被定期裁剪） |
| `/api/sessions` | GET | 对话档案列表（标题/预览/时间，永久保留） |
| `/api/sessions/{id}/messages` | GET | 对话档案回看（最近 N 条，升序） |
| `/api/sessions/{id}` | DELETE | 删除一段对话档案（级联删消息） |
| `/api/config` | GET | 获取控制台配置 |
| `/api/config` | POST | 更新配置（模型/温度/思考强度；部分更新，返回合并后全量） |
| `/api/health` | GET | 健康检查 |
| `/api/status` | GET | 生命状态：心情/日常（页面「碎碎念」） |
| `/api/diaries` | GET | 日记列表（含每晚自动写的日记） |
| `/api/memories` | GET | 获取记忆列表 |
| `/api/push/vapid-key` | GET | VAPID 公钥（推送订阅前置） |
| `/api/push/subscribe` | POST/DELETE | 注册 / 取消推送订阅（DELETE 接受 body 或 query） |
| `/api/push/unread` | GET | 未读主动消息（心跳生成） |
| `/api/push/read` | POST | 标记主动消息已读 `{message_ids}` |
| `/api/memories/{id}` | DELETE | 删除一条记忆 |
| `/api/memories/export` | GET | 导出所有记忆（JSON） |
| `/api/memories/clear` | POST | 清空所有记忆（需 `?confirm=true`，自动备份） |
| `/docs` | GET | Swagger 文档 |

> `/docs` 仅在未设置 `XIAOXI_API_TOKEN` 时开放（设了 token = 生产部署，docs 自动关闭，
> 避免危险端点的参数说明随 Swagger 公网裸奔）。

## 项目结构

```
小汐sonetto/
├── start.bat                       # Windows 一键启动
├── Dockerfile                      # Docker 构建（多阶段）
├── docker-compose.yml              # Docker Compose 编排
├── docs/                           # 文档
│   ├── PROJECT_SOURCE.md           # 完整技术文档
│   ├── PRODUCT_REPORT_2026-07-26.md  # 全项目检查产品报告（3 轮测试 + 5 路审查 + 修复方案）
│   ├── PRODUCT_AUDIT_2026-07-25.md  # 全项目代码审计报告
│   ├── PROMPT_GUIDE.md             # 提示词指南
│   └── PRIVACY.md                  # 数据与隐私说明
│
├── scripts/                        # 辅助脚本
│   ├── gen_assets.py               # 生成 PWA 图标 + 立绘 PNG→WebP 压缩
│   ├── cleanup_test_residue.py     # 清理测试残留数据
│   ├── verify_tools.py             # 工具联通性实测
│   ├── trim_sprites.py             # 立绘预处理
│   └── e2e/
│       ├── ui_test.cjs             # 浏览器级交互验证（headless Edge，17 交互点）
│       └── gps_test.cjs            # GPS 端到端实测（坐标注入 + 上传断言）
│
├── backend/
│   ├── main.py                     # 启动入口（uvicorn）
│   ├── version.py                  # 版本号（唯一定义处）
│   ├── heartbeat.py                # 心跳调度器（每 2h 更新生命状态+刷新 Agent+定期维护）
│   ├── pytest.ini                  # 测试配置
│   ├── requirements.txt            # Python 依赖
│   │
│   ├── config/
│   │   ├── settings.py             # 全局配置（pydantic-settings, .env）
│   │   ├── .env                    # API Key（不入 git）
│   │   └── .env.example            # 配置模板
│   │
│   ├── database/
│   │   ├── core.py                 # 数据库连接管理 + checkpoint 裁剪 + 自动备份
│   │   └── migrations.py           # 轻量级 schema 迁移框架
│   │
│   ├── api/
│   │   ├── server.py               # FastAPI 应用工厂 + 生命周期管理
│   │   ├── middleware/
│   │   │   ├── auth.py             # 认证 + IP 限流中间件
│   │   │   └── websocket_reject.py # WebSocket 拒绝中间件
│   │   └── routes/
│   │       ├── chat.py             # 对话路由（REST + SSE 流式 + 断开检测）+ 记忆管理
│   │       ├── config.py           # 配置路由（模型/思考强度切换）
│   │       ├── push.py             # 推送路由（VAPID 公钥/订阅/未读主动消息）
│   │       ├── sessions.py         # 对话档案路由（历史列表/回看/删除）
│   │       └── status.py           # 状态路由（/api/status 生命状态 + /api/diaries 日记列表）
│   │
│   ├── agent/
│   │   ├── graph.py                # LangGraph ReAct Agent 构建
│   │   └── soul.py                 # 系统提示词组装（人格 + 生命状态，不含记忆叙事）
│   │
│   ├── providers/
│   │   └── deepseek.py             # DeepSeek V4 Flash/Pro 接入
│   │
│   ├── tools/                      # 18 个工具
│   │   ├── weather.py              # 天气（Open-Meteo + 高德回退）
│   │   ├── map_tools.py            # 地图（高德：附近搜索/定位/路线）
│   │   ├── search.py               # 网络搜索（Tavily）
│   │   ├── memory.py               # 记忆（增删改查导出）
│   │   ├── diary.py                # 日记（写/读，同日去重）
│   │   ├── events.py               # 事件（create_event/list_events，「惦记」系统）
│   │   ├── tarot.py                # 塔罗牌（78 张韦特塔罗）
│   │   └── tarot_deck.yaml         # 塔罗牌数据（牌义文案）
│   │
│   ├── memory/
│   │   ├── manager.py              # 记忆管理器（SQLite, async CRUD）
│   │   ├── narrative.py            # 叙事构建器（记忆 → 提示词注入）
│   │   ├── chat_archive.py         # 对话档案（会话/消息持久化，永不回喂 LLM）
│   │   └── location_store.py       # GPS 坐标会话级缓存
│   │
│   ├── utils/
│   │   ├── logger.py               # GBK 安全的结构化日志
│   │   ├── retry.py                # 异步重试装饰器（指数退避）
│   │   ├── errors.py               # 错误脱敏（防 API Key 随异常文本泄漏）
│   │   ├── events.py               # 事件管理器（登记/归档/提示词注入/检测器）
│   │   ├── cycle_tracker.py        # 生理期追踪器
│   │   ├── web_push.py             # Web Push 发送器（VAPID 签名）
│   │   ├── security.py             # 启动安全加固检查（fail closed）
│   │   └── timezone.py             # 时区纪律（统一 HKT/UTC+8）
│   │
│   ├── tests/                      # 测试套件（逐文件计数不在此维护，以 pytest --collect-only 为准）
│   │   ├── conftest.py             # 共享 fixtures
│   │   ├── test_auth_middleware.py # 认证 + IP 限流中间件
│   │   ├── test_backup_permissions.py  # 备份权限收紧
│   │   ├── test_chat_archive.py    # 对话档案（CRUD/边界/韧性/LLM 隔离自检）
│   │   ├── test_checkpoint_prune.py    # Checkpoint 裁剪 + 僵尸 thread 清理
│   │   ├── test_config_routes.py   # 运行时配置（默认值/思考强度/统一重建）
│   │   ├── test_cycle_tracker.py   # 生理期追踪器
│   │   ├── test_diary.py           # 日记工具（写入/去重/读取）
│   │   ├── test_errors.py          # 错误脱敏（防 Key 泄漏回归）
│   │   ├── test_event_tools.py     # 事件工具层（create_event/list_events）
│   │   ├── test_events.py          # 事件管理器（登记/去重/归档/检测器）
│   │   ├── test_heartbeat.py       # 心跳时段匹配
│   │   ├── test_init_db_upgrade.py # 旧版数据库升级路径
│   │   ├── test_integration.py     # 端到端集成测试
│   │   ├── test_memory_manager.py  # 记忆管理器（CRUD/去重/相关性排序）
│   │   ├── test_memory_tools.py    # 记忆工具层（remember/recall）
│   │   ├── test_migrations.py      # 数据库迁移
│   │   ├── test_narrative.py       # 叙事构建器
│   │   ├── test_pre_model_hook.py  # 记忆注入 + 消息裁剪
│   │   ├── test_proactive.py       # 主动消息系统
│   │   ├── test_push_routes.py     # 推送路由
│   │   ├── test_security.py        # 启动安全加固（fail closed）
│   │   ├── test_soul.py            # 系统提示词组装
│   │   ├── test_status_routes.py   # 状态路由（/api/status + /api/diaries）
│   │   ├── test_tarot.py           # 塔罗工具（占卜 + 数据损坏降级）
│   │   ├── test_weather.py         # 天气工具（坐标解析/WMO 码映射）
│   │   └── test_web_push.py        # Web Push 发送器
│   │
│   └── data/                       # 运行时数据（不入 git）
│       ├── userdata.db             # 业务数据库
│       ├── checkpoints.db          # 对话状态检查点（定期裁剪）
│       └── backups/                # 自动备份（DB 快照 + 记忆 JSON）
│
└── web/                            # 前端（Vite 8 + React 19 + Tailwind v4，PWA）
    ├── src/
    │   ├── components/             # RoomScene / CharacterSprite / ChatPanel / ConfigPanel / MemoriesPanel …
    │   ├── hooks/                  # useChat（SSE 流式）/ useConfig / usePush
    │   └── assets/icons/           # 内联 SVG 图标组件
    ├── public/
    │   ├── sprites/                # 10 张角色立绘（WebP，含 halfawake 深夜状态；PNG→WebP 约 23MB→2MB）
    │   ├── sw.js                   # Service Worker（cache-first + 推送通知）
    │   └── manifest.json           # PWA manifest
    ├── dist/                       # 生产构建产物（由 FastAPI 挂载，npm run build 生成）
    ├── vite.config.js              # 开发期 /api → :8000 代理
    └── package.json
```

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | FastAPI + LangGraph |
| LLM | DeepSeek V4 Flash / V4 Pro（OpenAI 兼容接口） |
| 天气 | Open-Meteo（免费，无需 Key） |
| 地图 | 高德地图 Web 服务 API |
| 搜索 | Tavily（1000 次/月免费） |
| 数据库 | SQLite（aiosqlite 异步，WAL 模式） |
| 对话持久化 | SQLite checkpointer（短期上下文，定期裁剪）+ 对话档案表（永久回看） |
| 心跳调度 | APScheduler（AsyncIOScheduler） |
| 前端 | Vite 8 + React 19 + Tailwind CSS v4（PWA，Web Push） |
| 动画 | CSS / Tailwind 自定义动画（呼吸 / 淡入 / 星空 / 浮云） |
| 容器化 | Docker 多阶段构建 |
| 测试 | pytest + pytest-asyncio（320+ 测试，以 `pytest --collect-only` 为准）+ 浏览器 E2E（20+ 交互点） |

## 核心能力

### 小汐的 18 个工具

- **🌤️ 天气查询** — Open-Meteo，50+ 城市本地坐标 + 高德地理编码回退
- **📍 附近搜索** — 高德 POI，支持 GPS 自动传入
- **🗺️ 地址定位 / 模糊搜索** — 高德地理编码
- **🚇 公交路线 / 🚲 骑行路线** — 高德路线规划
- **🔍 网络搜索** — Tavily，世界观包装（「去映海里找找看」）
- **🧠 记忆系统** — 记住/回忆/列出/导出，自动去重
- **✏️ 记忆更新 / 删除** — update_memory 覆盖矛盾、合并变化；delete_memory 忘掉过时的
- **📖 日记系统** — 写/读，同日 UPDATE 去重
- **📅 事件登记** — create_event 记下未来要发生的事（考试/旅行/约定），小汐会惦记、会关心、会自动翻篇
- **📔 事件查看** — list_events 看小汐的小本本上记了哪些 upcoming 的事
- **🎴 塔罗牌占卜** — 78 张韦特塔罗，3 种牌阵

### 内在系统

- **💓 心跳** — 每 2 小时更新心情+日常事件（按时段匹配心情池，启动时立即触发，更新后自动刷新 Agent）
- **📖 叙事注入** — 每次对话动态注入最近 5 条记忆（SQL LIMIT，不烘焙进 system prompt）
- **🧬 生命状态** — 心情/日常注入系统提示词（心跳后自动刷新，不再过期）
- **📍 GPS 缓存** — 会话级内存缓存（8h TTL，asyncio.Lock）
- **💾 对话持久化** — SQLite checkpointer（小汐的短期工作记忆，每 2h 裁剪）+ 对话档案（`chat_sessions`/`chat_messages`，永久保留、可回看续聊，**永不进入 LLM 上下文**——重要信息由记忆系统自己提取）
- **🔒 安全** — 可选 Bearer Token 认证 + IP 速率限制 + WebSocket 拒绝 + 清空保护
- **🛡️ 运维** — Checkpoint 自动裁剪（每 thread 保留 10 个）+ SQLite Online Backup（保留 5 份）
- **🔄 重试** — 外部 API 调用指数退避重试（Open-Meteo 3 次、高德/Tavily 2 次）

## 环境变量

复制 `backend/config/.env.example` 为 `backend/config/.env`：

| 变量 | 必填 | 说明 |
|------|------|------|
| `DEEPSEEK_API_KEY` | ✅ | DeepSeek API Key |
| `DEEPSEEK_MODEL` | ❌ | 默认 `deepseek-v4-flash` |
| `AMAP_API_KEY` | ❌ | 高德地图 Key（地图/路线功能需要） |
| `TAVILY_API_KEY` | ❌ | Tavily Key（搜索功能需要） |
| `XIAOXI_API_TOKEN` | ❌ | API 认证 Token（部署公网时设置） |
| `CORS_ORIGINS` | ❌ | 允许的前端来源，逗号分隔 |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | ❌ | Web Push 密钥（不填则推送不可用，前端开关自动灰态） |
| `TRUST_PROXY` | ❌ | 是否在可信反代后信任 X-Forwarded-For（限流 IP 归因） |

> **SSE 流协议**（`/api/chat/stream`）：每行 `data: <json>\n\n`，JSON 为 `{"content":"增量"}` / `{"type":"tool_start"|"tool_end",...}` / `{"type":"state","state":"happy"}`，结尾 `data: [DONE]\n\n` 后关流。

## 测试

```bash
cd backend
.venv/Scripts/pip install pytest pytest-asyncio
.venv/Scripts/python -m pytest tests/ -v
```

## 版本

**5.0**
