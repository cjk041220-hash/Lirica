# 🐋 小汐

> 「希望你一个人在香港的时候，也不会觉得孤单。」

小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

## 快速启动

```bash
# 1. 复制环境变量文件
cd backend
copy config\.env.example config\.env

# 2. 编辑 config\.env，填入 API Key
#    - DEEPSEEK_API_KEY: DeepSeek API Key
#    - AMAP_API_KEY: 高德地图 Web服务 Key

# 3. 安装依赖
pip install -r requirements.txt

# 4. 启动
python main.py
```

或者双击 `start.bat`。

## API 接口

| 端点 | 说明 |
|------|------|
| `POST /api/chat` | 对话 |
| `POST /api/chat/stream` | 流式对话（SSE） |
| `GET /api/config` | 获取控制台配置（模型、思考强度） |
| `POST /api/config` | 更新控制台配置 |
| `GET /api/health` | 健康检查 |
| `GET /api/memories` | 获取记忆列表 |
| `DELETE /api/memories/{memory_id}` | 删除一条记忆 |
| `GET /api/memories/export` | 导出所有记忆（JSON 下载） |
| `POST /api/memories/clear` | 清空所有记忆 |
| `GET /docs` | Swagger 文档 |

## 项目结构

```
backend/
├── main.py              # 启动入口
├── agent/
│   ├── soul.py          # 小汐的灵魂（系统提示词）
│   └── graph.py         # LangGraph Agent 图
├── api/
│   ├── server.py        # FastAPI 服务器
│   └── routes/
│       ├── chat.py      # 对话路由
│       ├── config.py    # 配置路由（控制台）
│       └── memory.py    # 记忆管理路由
├── config/
│   ├── settings.py      # 配置
│   └── .env             # API Key（不入 git）
├── memory/
│   ├── __init__.py
│   ├── manager.py       # 记忆管理器
│   ├── narrative.py     # 叙事记忆
│   └── location_store.py # GPS 坐标会话缓存
├── providers/
│   └── deepseek.py      # DeepSeek 接入
└── tools/
    ├── weather.py       # 天气（Open-Meteo）
    ├── map_tools.py     # 地图（高德）
    ├── search.py        # 搜索（Tavily）
    ├── memory.py        # 记忆
    ├── diary.py         # 日记
    └── tarot.py         # 塔罗牌占卜

data/
├── memories.yaml        # 记忆数据
├── checkpoints.db       # 对话检查点（SQLite）
└── diaries.yaml         # 日记数据

frontend/
└── manifest.json        # PWA 清单
```

## 技术栈

- **框架**: FastAPI + LangGraph
- **模型**: DeepSeek Chat
- **天气**: Open-Meteo（免费）
- **地图**: 高德地图 Web服务 API
- **搜索**: Tavily
- **数据库**: SQLite（对话检查点）+ YAML（记忆/日记）
- **GPS**: Web Geolocation API
- **PWA**: manifest.json + Apple meta tags

## 核心功能

- **GPS 定位** — 自动获取坐标 → 缓存 30 分钟 → 喂给地图/天气工具
- **PWA 支持** — 可添加到 iOS 主屏幕
- **记忆管理** — 增删查导出清空 + 去重 + source/last_used_at 字段
- **输入校验** — 消息去空白、长度限制、session_id 格式校验
- **日记去重** — 同一天只更新不重复写入
- **控制台** — 模型选择、思考强度调节
- **Markdown 渲染、流式输出、自动重试、健康检查**

## 版本

**v0.3.0**
