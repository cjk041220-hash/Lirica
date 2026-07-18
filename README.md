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
│       └── config.py    # 配置路由（控制台）
├── config/
│   ├── settings.py      # 配置
│   └── .env             # API Key（不入 git）
├── providers/
│   └── deepseek.py      # DeepSeek 接入
└── tools/
    ├── weather.py       # 天气（Open-Meteo）
    ├── map_tools.py     # 地图（高德）
    ├── search.py        # 搜索（Tavily）
    ├── memory.py        # 记忆
    └── diary.py         # 日记
```

## 技术栈

- **框架**: FastAPI + LangGraph
- **模型**: DeepSeek Chat
- **天气**: Open-Meteo（免费）
- **地图**: 高德地图 Web服务 API
- **搜索**: Tavily
