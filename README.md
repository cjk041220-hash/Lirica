# 🐋 小汐 (XiaoXi)

> 「希望你一个人在香港的时候，也不会觉得孤单。」

小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

## 快速启动

### 一键启动（Windows）

双击 `start.bat`，自动创建虚拟环境、安装依赖、启动服务。

### 手动启动

```bash
# 后端
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
# 复制 config/.env.example → config/.env，填入 API Key
.venv/Scripts/python main.py

# 前端（Next.js，开发模式）
cd web
npm install
npm run dev        # http://localhost:3000
```

### Docker 部署

```bash
docker compose up -d
# 后端 API: http://localhost:8000
# 健康检查: http://localhost:8000/api/health
```

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 对话（非流式） |
| `/api/chat/stream` | POST | 流式对话（SSE，token 级） |
| `/api/config` | GET | 获取控制台配置 |
| `/api/config` | POST | 更新配置（模型/温度/思考强度） |
| `/api/health` | GET | 健康检查 |
| `/api/memories` | GET | 获取记忆列表 |
| `/api/memories/{id}` | DELETE | 删除一条记忆 |
| `/api/memories/export` | GET | 导出所有记忆（JSON） |
| `/api/memories/clear` | POST | 清空所有记忆（需 `?confirm=true`，自动备份） |
| `/docs` | GET | Swagger 文档 |

## 项目结构

```
小汐sonetto/
├── start.bat                       # Windows 一键启动
├── Dockerfile                      # Docker 构建（多阶段）
├── docker-compose.yml              # Docker Compose 编排
├── PROJECT_SOURCE.md               # 完整技术文档
├── PRODUCT_PLAN.md                 # 产品规划书
├── PRODUCT_REPORT.md               # 产品审查报告
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
│   │       └── config.py           # 配置路由（模型/思考强度切换）
│   │
│   ├── agent/
│   │   ├── graph.py                # LangGraph ReAct Agent 构建
│   │   └── soul.py                 # 系统提示词组装（人格 + 生命状态，不含记忆叙事）
│   │
│   ├── providers/
│   │   └── deepseek.py             # DeepSeek V4 Flash/Pro 接入
│   │
│   ├── tools/                      # 15 个工具
│   │   ├── weather.py              # 天气（Open-Meteo + 高德回退）
│   │   ├── map_tools.py            # 地图（高德：附近搜索/定位/路线）
│   │   ├── search.py               # 网络搜索（Tavily）
│   │   ├── memory.py               # 记忆（增删查导出）
│   │   ├── diary.py                # 日记（写/读，同日去重）
│   │   └── tarot.py                # 塔罗牌（78 张韦特塔罗）
│   │
│   ├── memory/
│   │   ├── manager.py              # 记忆管理器（SQLite, async CRUD）
│   │   ├── narrative.py            # 叙事构建器（记忆 → 提示词注入）
│   │   └── location_store.py       # GPS 坐标会话级缓存
│   │
│   ├── utils/
│   │   ├── logger.py               # GBK 安全的结构化日志
│   │   └── retry.py                # 异步重试装饰器（指数退避）
│   │
│   ├── tests/                      # 测试套件
│   │   ├── conftest.py             # 共享 fixtures
│   │   ├── test_memory_manager.py  # 记忆 CRUD + 去重 + recall LIMIT（18 测试）
│   │   ├── test_soul.py            # 提示词组装（7 测试）
│   │   ├── test_heartbeat.py       # 心跳时段匹配（8 测试）
│   │   ├── test_weather.py         # 天气坐标解析（6 测试）
│   │   ├── test_narrative.py       # 叙事构建器（6 测试）
│   │   ├── test_config_routes.py   # 运行时配置（4 测试）
│   │   ├── test_diary.py           # 日记工具（5 测试）
│   │   ├── test_migrations.py      # 数据库迁移（3 测试）
│   │   └── test_integration.py     # 端到端集成测试
│   │
│   └── data/                       # 运行时数据（不入 git）
│       ├── userdata.db             # 业务数据库
│       ├── checkpoints.db          # 对话状态检查点（定期裁剪）
│       └── backups/                # 自动备份（DB 快照 + 记忆 JSON）
│
└── web/                            # 前端（Next.js 16）
    ├── src/
    │   ├── app/                    # App Router 页面
    │   ├── components/             # UI 组件
    │   └── hooks/                  # 自定义 Hooks（useXiaoxiChat）
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
| 对话持久化 | SQLite（langgraph-checkpoint-sqlite） |
| 心跳调度 | APScheduler（AsyncIOScheduler） |
| 前端 | Next.js 16 + TypeScript + Tailwind CSS v4 |
| 动画 | Framer Motion |
| 容器化 | Docker 多阶段构建 |
| 测试 | pytest + pytest-asyncio（66 测试，~5 秒） |

## 核心能力

### 小汐的 15 个工具

- **🌤️ 天气查询** — Open-Meteo，50+ 城市本地坐标 + 高德地理编码回退
- **📍 附近搜索** — 高德 POI，支持 GPS 自动传入
- **🗺️ 地址定位 / 模糊搜索** — 高德地理编码
- **🚇 公交路线 / 🚲 骑行路线** — 高德路线规划
- **🔍 网络搜索** — Tavily，世界观包装（「去映海里找找看」）
- **🧠 记忆系统** — 记住/回忆/列出/删除/导出，自动去重
- **📖 日记系统** — 写/读，同日 UPDATE 去重
- **🎴 塔罗牌占卜** — 78 张韦特塔罗，3 种牌阵

### 内在系统

- **💓 心跳** — 每 2 小时更新心情+日常事件（按时段匹配心情池，启动时立即触发，更新后自动刷新 Agent）
- **📖 叙事注入** — 每次对话动态注入最近 5 条记忆（SQL LIMIT，不烘焙进 system prompt）
- **🧬 生命状态** — 心情/日常注入系统提示词（心跳后自动刷新，不再过期）
- **📍 GPS 缓存** — 会话级内存缓存（8h TTL，asyncio.Lock）
- **💾 对话持久化** — SQLite checkpointer
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

## 测试

```bash
cd backend
.venv/Scripts/pip install pytest pytest-asyncio
.venv/Scripts/python -m pytest tests/ -v
```

## 版本

**v0.4.2**
