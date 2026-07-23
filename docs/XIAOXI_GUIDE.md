# 🐋 小汐（XiaoXi）—— 项目导览

> 如果你刚拿到这个项目、完全不知道从哪看起，先读这篇。

小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

技术上说，这是一个 **AI 陪伴聊天工具**，后端用 Python 提供服务，前端用浏览器展示界面。

---

## 一、项目长什么样

```
小汐sonetto/
├── backend/          ← 🐍 后端（Python，主力代码在这里）
├── web/              ← 🌐 前端（Vite + React 19 + Tailwind v4，PWA，已完成并接通后端）
├── scripts/          ← 🛠️ 辅助脚本（生成图标/压缩立绘、浏览器 E2E 验证）
├── docs/             ← 📄 各种文档
├── Dockerfile        ← 🐳 用 Docker 一键部署的配方
├── docker-compose.yml
├── start.bat         ← 🪟 Windows 双击启动脚本
└── README.md         ← 📖 项目介绍 + 启动教程
```

---

## 二、后端（backend/）—— 最核心的部分

```
backend/
├── main.py           ← **启动入口**（双击运行它）/ 点这里运行
├── version.py        ← 版本号（v0.4.4）
├── requirements.txt  ← Python 依赖清单
│
├── config/           ← 配置
│   ├── settings.py   ← 读取 .env 中的 API Key 等配置
│   └── .env          ← 你填的 API Key（**别上传到 git**）
│
├── api/              ← API 接口（对外暴露的 HTTP 服务）
│   ├── server.py     ← 启动 FastAPI 服务、组装各组件
│   ├── routes/
│   │   ├── chat.py   ← **聊天接口**（接收你发的消息、SSE 流式回复）
│   │   ├── config.py ← 运行时配置（切换模型、调温度）
│   │   └── push.py   ← 推送订阅（浏览器通知）
│   └── middleware/
│       └── auth.py   ← 认证 + 限流中间件（防别人乱用）
│
├── agent/            ← 小汐的"脑子"
│   ├── graph.py      ← LangGraph Agent 图（思考+调用工具的引擎）
│   └── soul.py       ← **人格系统提示词**（小汐的性格设定，可以改这里）
│
├── providers/        ← AI 模型
│   └── deepseek.py   ← DeepSeek V4 Flash/Pro 接入（核心依赖）
│
├── tools/            ← 小汐的"能力"（15 个工具，每个是一份技能）
│   ├── weather.py    ← 🌤️ 查天气（Open-Meteo）
│   ├── map_tools.py  ← 📍 搜附近/查地址/规划路线（高德地图）
│   ├── search.py     ← 🔍 网络搜索（Tavily）
│   ├── memory.py     ← 🧠 **记住/回忆** 关于你的事（核心功能）
│   ├── diary.py      ← 📖 写日记/读日记
│   ├── tarot.py      ← 🎴 塔罗牌占卜
│   └── tarot_deck.yaml ← 78 张韦特塔罗牌的数据
│
├── memory/           ← 记忆系统
│   ├── manager.py    ← 记忆的增删改查（存 SQLite 里）
│   ├── narrative.py  ← 把记忆织成一段话，每次对话前注入给小汐
│   └── location_store.py ← GPS 坐标临时缓存
│
├── database/         ← 数据库
│   ├── core.py       ← 连接管理 + 建表 + 备份
│   └── migrations.py ← schema 版本迁移（帮你无损升级数据库结构）
│
├── utils/            ← 小工具
│   ├── logger.py     ← 日志打印（解决中文 emoji 在 Windows 乱码）
│   ├── retry.py      ← 重试工具（网络请求失败自动重试）
│   ├── errors.py     ← 错误脱敏（不让 API Key 随报错泄漏）
│   ├── cycle_tracker.py  ← 生理期追踪
│   └── web_push.py   ← Web 推送（浏览器通知）
│
├── heartbeat.py      ← 💓 **心跳**（每 2 小时自动更新心情 + 主动关怀）
│
├── tests/            ← 测试（155 个，跑一下看看有没有坏）
│   ├── test_memory_tools.py   ← 记忆工具测试
│   ├── test_cycle_tracker.py  ← 生理期测试
│   ├── test_auth_middleware.py ← 认证中间件测试
│   └── ...（共 20+ 个测试文件）
│
└── data/             ← 运行时数据（不传 git）
    ├── userdata.db           ← 记忆/日记/生理期都存这
    ├── checkpoints.db        ← 对话历史
    └── backups/              ← 自动备份
```

---

## 三、后端启动流程（从头到尾发生了什么）

当你在 `backend/` 路径下执行 `python main.py` 时：

```
① main.py 调 uvicorn 启动 FastAPI 服务器（端口 8000）
   ↓
② api/server.py 的 lifespan 开始初始化：
   ├─ 检查 .env 中有没有 API Key（没有就报错退出）
   ├─ 连接 SQLite 数据库 → 执行迁移（自动升级表结构）
   ├─ 创建记忆系统（MemoryManager）
   ├─ 创建 HTTP 客户端（地图/天气工具共用）
   ├─ 加载 15 个工具
   ├─ 连接 DeepSeek 模型（ChatOpenAI）
   ├─ 组装 Agent 图（脑子 + 能力 = 小汐）
   └─ 启动心跳（每 2 小时更新一次心情）
   ↓
③ 开始接受请求（http://127.0.0.1:8000）
```

每次你发一条消息：

```
① POST /api/chat/stream  → 后端收到消息
   ↓
② Agent 用当前聊天历史和记忆决定怎么回复
   ↓
③ 如果需要查天气/搜地图等，调用对应工具，拿到结果继续思考
   ↓
④ 最终回复以 SSE 流式返回（逐字展示给用户）
```

---

## 四、关键概念解释

| 概念 | 小汐的话 | 人话 |
|------|---------|------|
| **Agent** | 小汐的"自我" | 一个程序，每次你说话它就决定"该想什么、该用什么工具" |
| **Tool** | 小汐的能力 | 查天气、搜地图、写日记等具体功能，每个是一个 Python 函数 |
| **Memory** | 小汐记住的事 | 存在 SQLite 里的你的信息，按类别组织（喜欢的/不喜欢的/重要的） |
| **System Prompt** | 小汐的"人格设定" | `agent/soul.py` 里的一大段话，告诉小汐她是谁、该怎么说话 |
| **Life State** | 小汐今天的"心情" | 心跳每 2 小时随机选一个心情+日常活动，小汐的语气跟着变 |
| **Narrative** | 小汐每次聊天前看的"小抄" | 每次对话前从数据库取出最近 5 条记忆，注入给小汐看 |
| **Checkpointer** | 小汐的"短期记忆" | 存着刚才聊了什么，这样她能记住上下文 |
| **Heartbeat** | 小汐的"生物钟" | 每 2 小时更新心情、维护数据库、偶尔主动给你发消息 |

---

## 五、你想改什么，该去哪

| 你想做的事 | 路径 |
|----------|------|
| 修改小汐的性格/语气 | `agent/soul.py` → 改 `BASE_PERSONA` 或 `INTERACTION_RULES` |
| 让她掌握一个新能力 | `tools/` 下新建一个文件，参考其他工具的写法 |
| 调整「记住」和「回忆」逻辑 | `tools/memory.py` |
| 改数据库结构 | `database/migrations.py` → 仿照 `_migrate_v5` 写新迁移 |
| 换 AI 模型 | `providers/deepseek.py` + 改 `.env` 的 `DEEPSEEK_MODEL` |
| 调对话参数（温度/长度） | 前端控制台 `POST /api/config` 或直接改 `config.py` 的默认值 |
| 去掉生理期功能 | `heartbeat.py` 和 `soul.py` 中删除相关引用 + 删 `cycle_tracker.py` |
| 调试、看错误日志 | 命令行中打印的日志，或检查 `backend/data/` 下的数据库 |

---

## 六、测试怎么写 ? 怎么跑 ?

```bash
cd backend
.venv/Scripts/python -m pytest tests/           # 跑全部 155 个测试
.venv/Scripts/python -m pytest tests/ -v        # 详细模式，看每个测试名字
.venv/Scripts/python -m pytest tests/test_memory_tools.py  # 只跑记忆工具测试
```

好的测试都是"**纯函数式测试**"——不依赖网络、不依赖真实数据库、不调用 LLM。新建的测试要遵循：
- 用 `conftest.py` 里的 `tmp_db` fixture 拿到临时数据库
- 用 `monkeypatch` 模拟外部依赖（网络请求、时间等）
- 只测输入输出，不测内部实现

---

## 七、小贴士

- **日志有中文乱码？** 执行 `set PYTHONIOENCODING=utf-8` 后再启动（Windows）。
- **数据库坏了？** 检查 `backend/data/backups/` 下的备份恢复。
- **想清空对话上下文？** 删掉 `checkpoints.db` 文件重启就好。
- **不要直接修改 `userdata.db`** —— 通过 API 或工具操作。
- **改完后一定要跑测试**：`pytest tests/ -v`，确保没打断既有功能。

---

*第一次看完整项目文档感觉信息量很大是正常的，逐个文件夹点开看看就能理解了。如果还有哪里不清楚，直接问我。*
