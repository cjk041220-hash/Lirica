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
`.venv/bin/python` 即可。Python 需要 3.12（见 `.python-version`）。

### 手动启动

```bash
# 后端
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements-dev.txt
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
>   应视为已泄露——请轮换 DeepSeek / 高德 / Tavily 密钥与
>   VAPID 私钥（四把），并在 Linux 服务器上执行 `chmod 600 backend/config/.env`。

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 对话（非流式） |
| `/api/chat/stream` | POST | 流式对话（SSE，token 级） |
| `/api/sessions` | GET | 对话档案列表（标题/类型/预览/时间，永久保留） |
| `/api/sessions` | POST | 新建空会话行（点「开一段新聊天」即落库，列表立刻可见） |
| `/api/sessions/{id}/messages` | GET | 对话档案回看（最近 N 条，升序，带时间戳） |
| `/api/sessions/{id}` | DELETE | 删除一段对话档案（级联删消息 + 清 LLM 上下文线程） |
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
| `/api/events` | GET | 日历月范围查询（`?from=&to=`，日程类白名单 + archived 回看） |
| `/api/events` | POST | 日历创建事件（`source=user`，与 LLM 工具共享去重；409 = 相似提示） |
| `/api/events/{id}` | POST | 编辑事件（白名单字段；生日/纪念日只读 403） |
| `/api/events/{id}` | DELETE | 软删事件（置墓碑，挡住 LLM/检测器重建） |
| `/docs` | GET | Swagger 文档 |

> `/docs` 仅在未设置 `XIAOXI_API_TOKEN` 时开放（设了 token = 生产部署，docs 自动关闭，
> 避免危险端点的参数说明随 Swagger 公网裸奔）。

## 项目结构

```
小汐sonetto/
├── start.bat / start.sh            # 一键启动（Windows / macOS+Linux）
├── Dockerfile                      # Docker 构建（多阶段）
├── docker-compose.yml              # Docker Compose 编排
├── docs/                           # 文档（历史体检/审计报告已归档至 git 历史，见提交 c00fb11）
│   ├── CHANGELOG.md                # 变更摘要（按批次倒序）
│   ├── OPTIMIZATION_ROADMAP_2026-08-12.md  # 优化路线图（核验结论/永久纪律/P0-P3 批次）
│   ├── PERSONA_DEEP_DIVE_2026-08-10.md  # 人格深度优化调研与实施规划
│   ├── PROMPT_GUIDE.md             # 人格 Prompt 修改指南（含人格锚同步纪律）
│   ├── PRIVACY.md                  # 数据与隐私说明
│   ├── PRODUCT_REPORT_2026-08-02.md  # 产品报告
│   └── SERVER_ADMIN.md             # 服务器管理手册（域名/部署/备份/密钥轮换）
│
├── .superpowers/                   # SDD 工作流产物（批次 brief/report/HANDOFF/A-B 数据）
│
├── deploy/                         # 生产部署配置
│   ├── Caddyfile.example           # Caddy 反代（禁 gzip 保 SSE + HSTS）
│   ├── nginx.example.conf          # Nginx 反代（proxy_buffering off + XFF）
│   └── README.md                   # 部署手册（含密钥轮换与备份策略）
│
├── scripts/                        # 辅助脚本（一次性资源工具已归档 attic/）
│   ├── cleanup_test_residue.py     # 清理测试残留数据
│   ├── verify_tools.py             # 工具联通性实测
│   ├── check_sw_version.py         # Service Worker 版本核对
│   ├── cloud_backup_server.sh      # 云端备份
│   ├── upload_data_to_server.sh    # 数据上传
│   ├── e2e/
│   │   ├── ui_test.cjs             # 浏览器级交互验证（headless Edge）
│   │   ├── gps_test.cjs            # GPS 端到端实测
│   │   ├── pink_verify.cjs         # 粉色主题体检
│   │   └── features_check.cjs      # 特性检查
│   └── persona_eval/               # 人格探针实跑层（:8001 副本真 key 对话）
│       ├── probe.py                # 剧本探针 + 回复级负断言
│       ├── ab_mood_style.{py,sh}   # mood 文风 A/B 双副本盲测
│       └── run_persona_eval.sh     # 一键探针回归
│
├── backend/
│   ├── main.py                     # 启动入口（uvicorn，单 worker）
│   ├── version.py                  # 版本号（唯一定义处）
│   ├── heartbeat.py                # 心跳调度器（2h 生命状态 + 主动消息 + 维护链 + 早安/前夜提醒/日记）
│   ├── heartbeat_moods.py          # 情绪气候（Russell 情绪空间 + 五信号 + 高斯采样，纯逻辑）
│   ├── era.py                      # era 独处时间线（presence 路由 + 三写一推）
│   ├── pytest.ini                  # 测试配置
│   ├── requirements.txt            # Python 依赖（生产，不含测试工具）
│   ├── requirements-dev.txt        # 开发依赖（= requirements.txt + pytest）
│   │
│   ├── config/
│   │   ├── settings.py             # 全局配置（pydantic-settings, .env）
│   │   ├── .env                    # API Key（不入 git）
│   │   └── .env.example            # 配置模板
│   │
│   ├── database/
│   │   ├── core.py                 # 数据库连接管理 + checkpoint 裁剪 + 自动备份（含脱敏选项）
│   │   └── migrations.py           # 轻量级 schema 迁移框架（v1→v22）
│   │
│   ├── api/
│   │   ├── server.py               # FastAPI 应用工厂 + 生命周期管理
│   │   ├── middleware/
│   │   │   ├── auth.py             # 认证 + IP 限流中间件
│   │   │   ├── body_limit.py       # 请求体 1MiB 上限
│   │   │   ├── request_id.py       # X-Request-Id + 未捕获异常兜底
│   │   │   ├── security_headers.py # 安全响应头（纯 ASGI，不缓冲 SSE）
│   │   │   └── websocket_reject.py # WebSocket 拒绝中间件
│   │   └── routes/
│   │       ├── chat.py             # 对话路由（REST + SSE 流式 + 断开看门狗）+ 记忆管理
│   │       ├── config.py           # 配置路由（模型/思考强度切换，原子重建+回滚）
│   │       ├── events.py           # 日历路由（CRUD + 墓碑软删）
│   │       ├── presence.py         # 在线上报（era 离线判定信号源）
│   │       ├── push.py             # 推送路由（VAPID 公钥/订阅/未读主动消息）
│   │       ├── sessions.py         # 对话档案路由（历史列表/回看/删除）
│   │       └── status.py           # 状态路由（/api/status 生命状态 + /api/diaries 日记列表）
│   │
│   ├── agent/
│   │   ├── graph.py                # Agent 图（create_agent + 三中间件：工具兜底/记忆注入/人格锚）
│   │   ├── soul.py                 # 系统提示词组装（人格/档案/关系成长/事件/铁律/示例）
│   │   └── care.py                 # 异常感知关心（深夜/考试熬夜/回归 + 冷却台账）
│   │
│   ├── providers/
│   │   └── deepseek.py             # DeepSeek V4 Flash/Pro 工厂（元数据/回退/max_tokens 钳制）
│   │
│   ├── tools/                      # 23 个工具
│   │   ├── weather.py              # 天气（Open-Meteo + 高德回退）
│   │   ├── map_tools.py            # 地图（高德：附近搜索/定位/路线 + nav 联动）
│   │   ├── search.py               # 网络搜索（Tavily）
│   │   ├── memory.py               # 记忆（增删改查导出）
│   │   ├── diary.py                # 日记（写/读，同日去重）
│   │   ├── events.py               # 事件（增删改查，「惦记」系统）
│   │   ├── cycle.py                # 生理期（状态查询/登记/修正）
│   │   ├── tarot.py                # 塔罗牌（78 张韦特塔罗）
│   │   └── tarot_deck.yaml         # 塔罗牌数据（牌义文案）
│   │
│   ├── memory/
│   │   ├── manager.py              # 记忆管理器（六因子召回 + superseded 链 + 分层衰减）
│   │   ├── narrative.py            # 叙事构建器（画像优先 + hybrid 检索注入）
│   │   ├── extract.py              # 后台记忆提取（水位线 + 死信 + 健康检查）
│   │   ├── consolidate.py          # dreaming-lite 周期整合（近义合并 + 24h 节流）
│   │   ├── profile.py              # 动态画像蒸馏（「小汐眼里的冷冷」≤500 字）
│   │   ├── chat_archive.py         # 对话档案（会话/消息持久化，永不回喂 LLM）
│   │   └── location_store.py       # GPS 坐标会话级缓存
│   │
│   ├── utils/
│   │   ├── logger.py               # GBK 安全的结构化日志
│   │   ├── retry.py                # 异步重试装饰器（指数退避）
│   │   ├── errors.py               # 错误脱敏（防 API Key 随异常文本泄漏）
│   │   ├── events.py               # 事件管理器（登记/归档/提示词注入/五类检测器）
│   │   ├── cycle_tracker.py        # 生理期追踪器
│   │   ├── web_push.py             # Web Push 发送器（VAPID 签名）
│   │   ├── security.py             # 启动安全加固检查（fail closed）
│   │   ├── timezone.py             # 时区纪律（统一 HKT/UTC+8）
│   │   ├── time_words.py           # 相对时间词→绝对日期归一化
│   │   ├── geo.py                  # 坐标转换（WGS-84↔GCJ-02）
│   │   ├── tokens.py               # token 计数（cl100k 近似）
│   │   └── token_budget.py         # 本地 token 预算告警
│   │
│   ├── tests/                      # 测试套件（74 文件 897 用例，以 pytest --collect-only 为准）
│   │   ├── conftest.py             # 共享 fixtures（与生产共享 DDL 常量）
│   │   ├── test_migrations_v9.py … test_migrations_v22.py  # 迁移逐版本独立测试
│   │   ├── test_persona_eval.py    # 访谈式人格探针（锚点一致性元测试）
│   │   └── …                       # 其余按模块一一对应（auth/care/era/heartbeat/memory/…）
│   │
│   └── data/                       # 运行时数据（不入 git）
│       ├── userdata.db             # 业务数据库
│       ├── checkpoints.db          # 对话状态检查点（定期裁剪）
│       └── backups/                # 自动备份（DB 快照 + 记忆 JSON）
│
└── web/                            # 前端（Vite 8 + React 19 + Tailwind v4，PWA）
    ├── src/
    │   ├── components/             # CharacterSprite/SkyBackground/ChatPanel/四个面板/TarotCards …
    │   ├── hooks/                  # useChat（SSE 流式）/ usePush / usePresence / useConfig / useStatus …
    │   ├── api/                    # client（统一请求层）/ events / location
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
| 测试 | pytest + pytest-asyncio（897 用例，以 `pytest --collect-only` 为准）+ 前端 vitest 58 用例 + 浏览器 E2E（ui_test 29 交互点） |

## 核心能力

### 小汐的 23 个工具

- **🌤️ 天气查询** — Open-Meteo，50+ 城市本地坐标 + 高德地理编码回退
- **📍 附近搜索** — 高德 POI，支持 GPS 自动传入（WGS-84→GCJ-02 自动转换）
- **🗺️ 地址定位 / 模糊搜索** — 高德地理编码（纯高德单引擎）
- **🚇 公交路线 / 🚲 骑行路线** — 高德路线规划
- **🗾 看地图 / 去这里** — 工具结果附目的地跳转：内地唤起高德 App，港澳/海外 iOS 唤起 Apple Maps（香港数据最好），其余浏览器 Google Maps 兜底（跳转链接无需任何 key）
- **🔍 网络搜索** — Tavily，世界观包装（「去映海里找找看」）
- **🧠 记忆系统** — 记住/回忆/列出/导出，自动去重 + importance 打分 + 情绪标签
- **✏️ 记忆更新 / 删除** — update_memory 覆盖矛盾、合并变化；delete_memory 忘掉过时的
- **📖 日记系统** — 写/读，同日 UPDATE 去重
- **📅 事件登记 / 查看** — create_event 记下未来要发生的事（考试/旅行/约定），小汐会惦记、会关心、会自动翻篇；list_events 看她的小本本
- **✏️ 事件更新 / 删除** — update_event 改日程；delete_event 删掉（墓碑机制挡住小汐偷偷记回来）
- **🌸 生理期管理** — get_period_status 查周期状态；log_period 记日子；amend_period 修正日期（删/挪歧义消解）
- **🎴 塔罗牌占卜** — 78 张韦特塔罗，3 种牌阵

### 内在系统

- **💓 心跳** — 5 个定时任务（HKT）：2h 脉冲更新心情+日常事件（启动即触发）/ 08:00 早安（注入今日日程）/ 23:00 前夜提醒 / 1h 事件看门狗（归档过期 + 检测新事件）/ 22:30 自动日记；更新后自动刷新 Agent；维护窗口还跑记忆整合 pass（dreaming-lite，合并同类近义记忆）
- **📖 叙事注入** — 每次对话混合召回 5 条记忆：话题相关性 top-3 + 最新记忆补足配额，按时间正序注入；多因子加权排序（新鲜度/访问热度/话题命中/importance/情绪亲和度）；不烘焙进 system prompt
- **🌱 后台记忆提取** — 对话后由 LLM 在后台提取值得记住的事（水位线推进、去重 noop、连续失败进死信），顺带打 importance 1-10 分与情绪坐标；健康检查多指标打点（7 天窗口提取率/增长率/归档占比）
- **🎭 情绪联动** — 心情走 Russell 情绪平面（早晨锚定 + 最小停留）；记忆浮现按 mood-congruent 加权软调制（不硬过滤，防情绪固化）；当前心情存为核心记忆，superseded 链可回放
- **🧠 记忆衰减** — 分层衰减：active→core（高频浮现晋升，叙事保底席位）/ active→archived（长期未提及冷藏不删）；「重要的事」类别豁免归档
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
| `DEEPSEEK_BASE_URL` | ❌ | 默认 `https://api.deepseek.com/v1` |
| `DEEPSEEK_MODEL` | ❌ | 默认 `deepseek-v4-flash` |
| `AMAP_API_KEY` | ❌ | 高德地图 Key（唯一地图引擎；POI/路线/「看地图去这里」跳转需要） |
| `TAVILY_API_KEY` | ❌ | Tavily Key（搜索功能需要） |
| `XIAOXI_API_TOKEN` | ❌ | API 认证 Token（部署公网时设置） |
| `XIAOXI_ALLOW_INSECURE` | ❌ | 显式承担「无 token 绑定非回环地址」风险（仅受控内网；默认 false = fail-closed） |
| `CORS_ORIGINS` | ❌ | 允许的前端来源，逗号分隔 |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | ❌ | Web Push 密钥（不填则推送不可用，前端开关自动灰态） |
| `VAPID_CLAIM_MAILTO` | ❌ | VAPID 声明联系邮箱（默认占位值即可） |
| `TRUST_PROXY` | ❌ | 是否在可信反代后信任 X-Forwarded-For（限流 IP 归因） |
| `LOG_FILE` | ❌ | 日志落盘路径（留空 = 仅 stderr；10MB×7 轮转） |
| `LOG_LEVEL` | ❌ | 日志级别（默认 INFO） |
| `BACKUP_SCRUB_SENSITIVE` | ❌ | 备份剔除生理期 + 对话档案（默认 false = 完整备份） |

> **SSE 流协议**（`/api/chat/stream`）：每行 `data: <json>\n\n`，JSON 为 `{"content":"增量"}` / `{"type":"tool_start"|"tool_end",...}` / `{"type":"state","state":"happy"}`，结尾 `data: [DONE]\n\n` 后关流。

## 测试

```bash
cd backend
.venv/Scripts/pip install pytest pytest-asyncio
.venv/Scripts/python -m pytest tests/ -v
```

## 版本

**5.0**
