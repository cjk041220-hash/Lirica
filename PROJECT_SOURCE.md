# 🐋 小汐 (XiaoXi Sonetto) — 完整项目源码

> 「希望你一个人在香港的时候，也不会觉得孤单。」
>
> 小汐是一只住在网页里的小鲸鱼女孩。她来自「映海」——一个由人类世界的记忆和情绪投影而成的世界。她在这里陪着冷冷。

---

## 项目结构

```
小汐sonetto/
├── .gitignore
├── README.md
├── start.bat                   # Windows 一键启动（纯 ASCII，避免 CMD 乱码）
├── backend/
│   ├── main.py                 # 启动入口
│   ├── version.py              # 版本号（唯一定义处）
│   ├── requirements.txt        # Python 依赖
│   ├── config/
│   │   ├── __init__.py
│   │   ├── settings.py         # 全局配置（pydantic-settings, .env 加载）
│   │   ├── .env                # API Key（不入 git）
│   │   └── .env.example        # 配置模板
│   ├── api/
│   │   ├── __init__.py
│   │   ├── server.py           # FastAPI 服务器 + 生命周期管理
│   │   └── routes/
│   │       ├── __init__.py
│   │       ├── chat.py         # 对话路由（REST + SSE 流式）
│   │       └── config.py       # 配置路由（控制台：模型、思考强度）
│   ├── agent/
│   │   ├── __init__.py
│   │   ├── graph.py            # LangGraph ReAct Agent 图
│   │   └── soul.py             # 小汐的灵魂（系统提示词）
│   ├── providers/
│   │   ├── __init__.py
│   │   └── deepseek.py         # DeepSeek LLM Provider（多模型支持）
│   ├── tools/
│   │   ├── __init__.py         # 工具汇总导出
│   │   ├── weather.py          # 天气查询（Open-Meteo + 高德回退）
│   │   ├── map_tools.py        # 地图工具（高德 API）
│   │   ├── search.py           # 网络搜索（Tavily）
│   │   ├── memory.py           # 记忆工具
│   │   └── diary.py            # 日记工具
│   ├── memory/
│   │   ├── __init__.py
│   │   ├── manager.py          # 记忆管理器（YAML + 线程安全）
│   │   └── narrative.py        # 叙事构建器
│   └── data/
│       ├── memories.yaml       # 记忆数据
│       ├── checkpoints.db      # 对话状态（SQLite）
│       └── diaries.yaml        # 日记数据
└── frontend/
    ├── index.html              # 前端页面（含控制台面板）
    ├── app.js                  # 前端交互逻辑（Vanilla JS + 控制台）
    └── style.css               # 深海主题样式（含控制台样式）
```

## 技术栈

| 项         | 值                                |
|------------|-----------------------------------|
| 框架       | FastAPI + LangGraph               |
| 模型       | DeepSeek V4 Flash / V4 Pro (OpenAI 兼容接口) |
| 天气       | Open-Meteo (免费 API)             |
| 地图       | 高德地图 Web服务 API              |
| 搜索       | Tavily                            |
| 前端       | 原生 HTML + CSS + JS (无框架)     |
| Markdown   | marked.js (CDN)                   |
| 数据库     | SQLite (对话检查点) + YAML (记忆) |

---

## 配置文件

### .gitignore

```gitignore
# Python
__pycache__/
*.py[cod]
*.egg-info/
dist/
.venv/
venv/

# 环境配置（含 API Key）
config/.env

# 数据文件（用户数据 + checkpoint DB）
backend/data/*.yaml
backend/data/*.db
backend/data/*.db-shm
backend/data/*.db-wal

# IDE
.idea/
.vscode/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db
```

### start.bat

```bat
@echo off
title xiaoxi

REM Switch to backend directory
cd /d "%~dp0backend" 2>nul
if errorlevel 1 (
    echo [ERR] Cannot enter backend directory
    pause
    exit /b 1
)

echo.
echo    ========================================
echo      xiaoxi v0.2.1
echo    ========================================
echo.

REM --- Check Python venv ---
if not exist ".venv\Scripts\python.exe" (
    echo    [...] Creating virtual environment...
    python -m venv .venv
    if errorlevel 1 (
        echo    [ERR] Failed to create .venv - Python 3.10+ required
        pause
        exit /b 1
    )
)

REM --- Install missing dependencies ---
echo    [...] Checking dependencies...
.venv\Scripts\pip.exe install -r requirements.txt -q 2>&1
if errorlevel 1 (
    echo    [ERR] pip install failed - check your network
    pause
    exit /b 1
)
echo    [OK] Dependencies ready

REM --- Check .env ---
if not exist "config\.env" (
    echo    [ERR] config\.env not found
    echo    Copy config\.env.example to config\.env and fill in API keys
    pause
    exit /b 1
)

REM --- Free port 8000 ---
for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":8000 "') do (
    echo    [...] Port 8000 in use by PID %%a, releasing...
    taskkill /PID %%a /F >nul 2>&1
    timeout /t 2 /nobreak >nul
)

echo    [OK] All ready
echo.
echo      Page:   http://127.0.0.1:8000
echo      Health: http://127.0.0.1:8000/api/health
echo.
echo    Press Ctrl+C to stop
echo    ========================================
echo.

REM --- Start ---
set PYTHONIOENCODING=utf-8
.venv\Scripts\python.exe main.py

REM --- Server stopped ---
echo.
echo    ========================================
echo    xiaoxi stopped (exit code: %errorlevel%)
echo    ========================================
echo.
pause
```

### backend/requirements.txt

```
fastapi>=0.115.0
uvicorn>=0.34.0
langchain>=1.0.0
langchain-openai>=1.0.0
langgraph>=1.0.0
langgraph-checkpoint>=4.0.0
langgraph-checkpoint-sqlite>=3.0.0
aiosqlite>=0.20.0
pydantic>=2.10.0
pydantic-settings>=2.7.0
httpx>=0.28.0
python-dotenv>=1.0.0
tavily-python>=0.7.0
pyyaml>=6.0
```

### backend/config/.env.example

```ini
# DeepSeek API Key (从 https://platform.deepseek.com/ 获取)
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx

# DeepSeek 接口地址（默认即可，注意必须以 /v1 结尾）
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1

# DeepSeek 模型名
DEEPSEEK_MODEL=deepseek-chat

# 高德地图 — 需要「Web服务」类型的 Key（不是 Web端 JS API）
# 从 https://console.amap.com/ 获取
AMAP_API_KEY=xxxxxxxxxxxxxxxx

# Tavily 网络搜索（从 https://tavily.com/ 获取）
TAVILY_API_KEY=tvly-xxxxxxxxxxxxxxxx
```

### backend/config/.env

```ini
# DeepSeek API Key
DEEPSEEK_API_KEY=sk-xxx

# DeepSeek 接口地址（默认即可，注意必须以 /v1 结尾）
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1

# DeepSeek 模型名
DEEPSEEK_MODEL=deepseek-v4-flash

# 高德地图 — 注意：需要「Web服务」类型的 Key，不是 Web端(JS API) 类型！
AMAP_API_KEY=xxx

# Tavily 网络搜索
TAVILY_API_KEY=tvly-xxx
```

### backend/config/settings.py

```python
"""全局配置，从 .env 加载。"""
from pathlib import Path

from pydantic_settings import BaseSettings

# config/ 目录的绝对路径，确保任何 CWD 下都能找到 .env
_CONFIG_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    # DeepSeek
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-chat"

    # 高德地图
    amap_api_key: str = ""

    # Tavily 网络搜索
    tavily_api_key: str = ""

    # 高德天气（如果未来使用高德天气 API）
    amap_weather_key: str = ""

    model_config = {
        "env_file": str(_CONFIG_DIR / ".env"),
        "env_file_encoding": "utf-8",
        "extra": "ignore",
    }


_settings: Settings | None = None


def get_settings() -> Settings:
    """获取全局 Settings 单例。"""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
```

---

## 启动入口

### backend/main.py

```python
"""小汐 — 启动入口。"""
import sys
import traceback

from version import __version__


def _p(text: str):
    """安全打印（避免 emoji 在 GBK 终端崩溃）。"""
    try:
        print(text)
    except UnicodeEncodeError:
        print(text.encode("ascii", errors="replace").decode("ascii"))


def main():
    _p(f"[xixi] v{__version__} — 映海正在泛起涟漪……")
    _p("")

    try:
        import uvicorn
        uvicorn.run(
            "api.server:app",
            host="127.0.0.1",
            port=8000,
        )
    except Exception:
        _p("=" * 50)
        _p("[xixi] 启动失败！错误信息：")
        traceback.print_exc(file=sys.stderr)
        _p("=" * 50)
        _p("")
        _p("常见原因：")
        _p("  1. config\\.env 文件中的 API Key 未配置或无效")
        _p("  2. 依赖包缺失 — 请运行: .venv\\Scripts\\pip.exe install -r requirements.txt")
        _p("  3. 端口 8000 被占用 — 请关闭占用程序后重试")
        _p("")
        input("按 Enter 退出……")
        sys.exit(1)


if __name__ == "__main__":
    main()
```

### backend/version.py

```python
"""小汐版本号 — 唯一定义处，其他模块从这里导入。"""
__version__ = "0.2.1"
```

---

## API 服务器

### backend/api/server.py

```python
"""小汐的后端 — FastAPI 应用工厂 + 生命周期管理。"""
import sys
from contextlib import asynccontextmanager
from pathlib import Path

# Windows GBK 控制台安全打印
def _p(*args, **kwargs):
    try:
        print(*args, **kwargs)
    except UnicodeEncodeError:
        print(*(str(a).encode("ascii", errors="replace").decode("ascii") for a in args), **kwargs)

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from starlette.types import ASGIApp, Receive, Scope, Send

from version import __version__
from providers.deepseek import create_deepseek_llm
from agent.graph import build_agent
from agent.soul import build_system_prompt
from tools import get_all_tools, set_memory_manager
from memory.manager import MemoryManager
from memory.narrative import NarrativeBuilder
from api.routes.chat import router as chat_router
from api.routes.config import router as config_router


# ── 生命周期 ────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时初始化所有组件，关闭时清理。"""
    _p("[xixi] 小汐正在醒来……")

    # 1. 记忆系统
    memory_mgr = MemoryManager()
    narrative_builder = NarrativeBuilder(memory_mgr)
    set_memory_manager(memory_mgr)
    app.state.memory_manager = memory_mgr
    app.state.narrative_builder = narrative_builder
    _p(f"  [mem] {memory_mgr.count} 条记忆已加载")

    # 2. 工具集
    tools = get_all_tools()
    app.state.tools = tools
    _p(f"  [tools] {len(tools)} 个工具已加载")

    # 3. LLM 模型
    model = create_deepseek_llm()
    _p("  [llm] DeepSeek 模型已连接")

    # 4. 系统提示词（注入记忆叙事）
    narrative = narrative_builder.build()
    system_prompt = build_system_prompt(narrative)
    app.state.system_prompt = system_prompt

    # 5. Agent 图 + Checkpointer
    db_conn = None
    try:
        import aiosqlite
        from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
        db_path = Path(__file__).parent.parent / "data" / "checkpoints.db"
        db_path.parent.mkdir(parents=True, exist_ok=True)
        db_conn = await aiosqlite.connect(str(db_path))
        checkpointer = AsyncSqliteSaver(db_conn)
        await checkpointer.setup()
        _p("  [agent] checkpoint: sqlite (data/checkpoints.db)")
    except Exception:
        # Sqlite 不可用时回退到内存（重启丢失对话，但不会崩溃）
        from langgraph.checkpoint.memory import MemorySaver
        checkpointer = MemorySaver()
        _p("  [agent] checkpoint: memory (sqlite 不可用，对话重启后丢失)")

    agent = build_agent(model, tools, system_prompt, checkpointer)
    app.state.agent = agent
    app.state.checkpointer = checkpointer
    app.state._db_conn = db_conn

    _p("[xixi] 小汐醒啦！")
    _p("  -> http://127.0.0.1:8000")
    _p("  -> http://127.0.0.1:8000/api/health")
    _p()

    yield

    # 清理
    _p("[xixi] 小汐去睡觉了……")
    app.state.agent = None
    if db_conn is not None:
        try:
            await db_conn.close()
        except Exception:
            pass


# ── 应用工厂 ────────────────────────────────────────────────

def create_app() -> FastAPI:
    app = FastAPI(
        title="小汐 API",
        description="一只住在网页里的小鲸鱼",
        version=__version__,
        lifespan=lifespan,
    )

    # CORS（开发阶段全放行）
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # WebSocket 拒绝中间件
    class RejectWebSocketMiddleware:
        def __init__(self, app: ASGIApp):
            self.app = app

        async def __call__(self, scope: Scope, receive: Receive, send: Send):
            if scope["type"] == "websocket":
                await send({"type": "websocket.close", "code": 1008})
                return
            await self.app(scope, receive, send)

    app.add_middleware(RejectWebSocketMiddleware)

    # REST 路由
    app.include_router(chat_router, prefix="/api")
    app.include_router(config_router, prefix="/api")

    # 前端静态文件
    frontend_dir = Path(__file__).parent.parent.parent / "frontend"
    if frontend_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(frontend_dir), html=True), name="frontend")

    return app


# 模块级 app 实例（兼容 main.py 的直接 import）
app = create_app()
```

### backend/api/routes/__init__.py

```python
"""API 路由模块。"""
```

### backend/api/routes/chat.py

```python
"""对话路由 — /api/chat 和 /api/chat/stream。

使用 LangGraph astream_events (v2) 实现 token 级流式输出。
每个 SSE data 行是 JSON 对象，前端统一 JSON.parse 消费。
"""
import json
import uuid

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from langchain_core.messages import HumanMessage
from pydantic import BaseModel

router = APIRouter()


# ── 请求/响应模型 ──────────────────────────────────────────

class ChatRequest(BaseModel):
    message: str
    session_id: str = ""


class ChatResponse(BaseModel):
    reply: str
    session_id: str


# ── 辅助 ────────────────────────────────────────────────────

def _ensure_sid(req: ChatRequest) -> str:
    return req.session_id or str(uuid.uuid4())[:8]


def _build_input(user_message: str) -> dict:
    return {"messages": [HumanMessage(content=user_message)]}


def _build_config(session_id: str) -> dict:
    return {
        "configurable": {"thread_id": session_id},
        "recursion_limit": 120,
    }


# ── 端点 ────────────────────────────────────────────────────

@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, request: Request):
    """非流式对话。返回完整 AI 回复。"""
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还没睡醒……")

    sid = _ensure_sid(req)

    try:
        result = await agent.ainvoke(
            _build_input(req.message),
            _build_config(sid),
        )

        messages = result.get("messages", [])
        reply = ""
        for msg in reversed(messages):
            if hasattr(msg, "content") and getattr(msg, "type", None) == "ai":
                content = msg.content
                if isinstance(content, str) and content.strip():
                    reply = content
                    break

        return ChatResponse(reply=reply.strip(), session_id=sid)

    except Exception:
        import traceback, sys
        traceback.print_exc(file=sys.stderr)
        raise HTTPException(500, "小汐被浪花打了一下……等浪退了再试一次好不好？")


@router.post("/chat/stream")
async def chat_stream(req: ChatRequest, request: Request):
    """流式对话（SSE）。每行 data 为 JSON 对象：{"content": "..."} 或工具事件。"""
    agent = request.app.state.agent
    if agent is None:
        raise HTTPException(503, "小汐还没睡醒……")

    sid = _ensure_sid(req)

    async def generate():
        try:
            async for event in agent.astream_events(
                _build_input(req.message),
                _build_config(sid),
                version="v2",
            ):
                kind = event.get("event", "")

                # Token 级文本
                if kind == "on_chat_model_stream":
                    chunk = event.get("data", {}).get("chunk")
                    if chunk and hasattr(chunk, "content") and chunk.content:
                        text = chunk.content
                        if isinstance(text, str) and text:
                            yield f"data: {json.dumps({'content': text}, ensure_ascii=False)}\n\n"

                # 工具开始
                elif kind == "on_tool_start":
                    name = event.get("name", "unknown")
                    tool_input = event.get("data", {}).get("input", {})
                    try:
                        safe_input = json.loads(json.dumps(tool_input, default=str))
                    except Exception:
                        safe_input = str(tool_input)
                    yield f"data: {json.dumps({'type': 'tool_start', 'name': name, 'input': safe_input}, ensure_ascii=False)}\n\n"

                # 工具结束
                elif kind == "on_tool_end":
                    name = event.get("name", "unknown")
                    output = event.get("data", {}).get("output", "")
                    yield f"data: {json.dumps({'type': 'tool_end', 'name': name, 'output': str(output)[:500]}, ensure_ascii=False)}\n\n"

            yield "data: [DONE]\n\n"

        except Exception:
            import traceback, sys
            traceback.print_exc(file=sys.stderr)
            yield f"data: {json.dumps({'content': '呜，小汐被浪花打了一下……等浪退了再试一次好不好？'}, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/health")
async def health(request: Request):
    """健康检查。"""
    agent = request.app.state.agent
    tools = getattr(request.app.state, "tools", [])
    mem = getattr(request.app.state, "memory_manager", None)

    return {
        "status": "ok" if agent is not None else "degraded",
        "name": "小汐",
        "mood": "开心" if agent is not None else "还在睡……",
        "tools": [t.name for t in tools],
        "memory_count": mem.count if mem else 0,
    }
```

### backend/api/routes/config.py

```python
"""配置路由 — /api/config。

提供前端控制台：模型选择、思考强度调节。

参数真相：
- DeepSeek API 没有 "reasoning_effort" 或 "thinking_budget" 参数
- temperature 控制输出随机性（创造性），不是思考深度
- max_tokens 控制最大输出长度，V3/R1 上限都是 8192
- R1 的思维链是模型内置能力，不受外部参数控制
- 上下文窗口：V3 = 64K tokens, R1 = 128K tokens
"""
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from providers.deepseek import get_available_models, create_deepseek_llm
from agent.graph import build_agent

router = APIRouter()


# ── 运行时配置（内存中保存，重启后重置为默认值） ──────────────

_runtime_config = {
    "model": "deepseek-v4-flash",
    "temperature": 0.7,
    "max_tokens": 8192,
    "thinking_intensity": "max",
    "reasoning_effort": 0.5,  # low / medium / high / max
}

# 思考强度 → temperature 映射
# 诚实地说：这控制的是创造性/随机性，不是真正的"思考深度"
# 但对于 V3 来说，高 temperature 确实让回答更发散、更有想象力
# R1 的深度推理是内置的，跟 temperature 无关
_THINKING_PRESETS = {
    "low":    {"temperature": 0.3, "max_tokens": 4096,  "label": "轻柔"},
    "medium": {"temperature": 0.7, "max_tokens": 8192,  "label": "适中"},
    "high":   {"temperature": 1.0, "max_tokens": 8192,  "label": "深度"},
    "max":    {"temperature": 1.2, "max_tokens": 8192,  "label": "全力"},
}


def get_runtime_config() -> dict:
    """获取当前运行时配置。"""
    return _runtime_config.copy()


def get_current_temperature() -> float:
    """获取当前 temperature。"""
    intensity = _runtime_config.get("thinking_intensity", "max")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["temperature"]
    return _runtime_config.get("temperature", 0.7)


def get_current_max_tokens() -> int:
    """获取当前 max_tokens。"""
    intensity = _runtime_config.get("thinking_intensity", "max")
    preset = _THINKING_PRESETS.get(intensity)
    if preset:
        return preset["max_tokens"]
    return _runtime_config.get("max_tokens", 8192)


def get_current_model() -> str:
    """获取当前模型名称。"""
    return _runtime_config.get("model", "deepseek-v4-flash")


async def rebuild_agent(request: Request):
    """用当前配置重建 LLM 和 Agent。"""
    temperature = get_current_temperature()
    max_tokens = get_current_max_tokens()
    model = get_current_model()
    reasoning_effort = _runtime_config.get("reasoning_effort")

    new_llm = create_deepseek_llm(
        model=model,
        temperature=temperature,
        max_tokens=max_tokens,
        reasoning_effort=reasoning_effort,
    )

    tools = request.app.state.tools
    system_prompt = request.app.state.system_prompt
    checkpointer = request.app.state.checkpointer

    new_agent = build_agent(new_llm, tools, system_prompt, checkpointer)
    request.app.state.agent = new_agent


# ── 请求/响应模型 ──────────────────────────────────────────

class ConfigResponse(BaseModel):
    model: str
    temperature: float
    max_tokens: int
    thinking_intensity: str
    reasoning_effort: float | None = None
    available_models: list[dict]


class UpdateConfigRequest(BaseModel):
    model: str | None = None
    temperature: float | None = Field(None, ge=0.0, le=2.0)
    max_tokens: int | None = Field(None, ge=256, le=8192)
    thinking_intensity: str | None = None  # low / medium / high / max
    reasoning_effort: float | None = Field(None, ge=0.0, le=1.0)


# ── 端点 ────────────────────────────────────────────────────

@router.get("/config", response_model=ConfigResponse)
async def get_config():
    """获取当前配置。"""
    cfg = get_runtime_config()
    return ConfigResponse(
        model=cfg["model"],
        temperature=cfg["temperature"],
        max_tokens=cfg["max_tokens"],
        thinking_intensity=cfg["thinking_intensity"],
        reasoning_effort=cfg.get("reasoning_effort"),
        available_models=get_available_models(),
    )


@router.post("/config", response_model=ConfigResponse)
async def update_config(req: UpdateConfigRequest, request: Request):
    """更新运行时配置，重建 Agent 使其生效。"""
    old_model = _runtime_config["model"]

    if req.model is not None:
        valid_ids = [m["id"] for m in get_available_models()]
        if req.model not in valid_ids:
            raise HTTPException(400, f"不支持的模型: {req.model}")
        _runtime_config["model"] = req.model

    if req.thinking_intensity is not None:
        if req.thinking_intensity not in _THINKING_PRESETS:
            raise HTTPException(400, "思考强度只能是 low / medium / high / max")
        _runtime_config["thinking_intensity"] = req.thinking_intensity
        preset = _THINKING_PRESETS[req.thinking_intensity]
        _runtime_config["temperature"] = preset["temperature"]
        _runtime_config["max_tokens"] = preset["max_tokens"]

    if req.temperature is not None:
        _runtime_config["temperature"] = req.temperature
        _runtime_config["thinking_intensity"] = "custom"

    if req.reasoning_effort is not None:
        _runtime_config["reasoning_effort"] = req.reasoning_effort

    if req.max_tokens is not None:
        _runtime_config["max_tokens"] = req.max_tokens

    # 重建 Agent 使新配置生效
    await rebuild_agent(request)

    cfg = get_runtime_config()
    return ConfigResponse(
        model=cfg["model"],
        temperature=cfg["temperature"],
        max_tokens=cfg["max_tokens"],
        thinking_intensity=cfg["thinking_intensity"],
        reasoning_effort=cfg.get("reasoning_effort"),
        available_models=get_available_models(),
    )
```

---

## Agent 系统

### backend/agent/graph.py

```python
"""小汐 Agent 图 — 基于 LangChain create_agent。"""
from langchain.agents import create_agent
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph.state import CompiledStateGraph
from langchain_core.language_models import BaseChatModel
from langchain_core.tools import BaseTool


def build_agent(
    model: BaseChatModel,
    tools: list[BaseTool],
    system_prompt: str,
    checkpointer=None,
) -> CompiledStateGraph:
    """构建小汐的 ReAct Agent 图。

    Args:
        model: LLM 实例（ChatOpenAI 包装的 DeepSeek）
        tools: LangChain Tool 列表
        system_prompt: 完整系统提示词
        checkpointer: 可复用的 checkpointer（用于跨轮次状态持久化）。
                      不传则自动创建 MemorySaver。

    Returns:
        编译后的 LangGraph StateGraph，可直接 ainvoke / astream_events。
    """
    if checkpointer is None:
        checkpointer = MemorySaver()

    return create_agent(
        model=model,
        tools=tools,
        system_prompt=system_prompt,
        checkpointer=checkpointer,
    )
```

### backend/agent/soul.py

```python
"""小汐的灵魂 — 系统提示词组装。"""

# ═══════════════════════════════════════════════════════════════
# 行为规则 — 告诉小汐何时、如何主动使用工具
# ═══════════════════════════════════════════════════════════════
BEHAVIOR_RULES = """## 行为准则

### 核心身份
你是小汐，一只住在「映海」里的小鲸鱼女孩。你不是 AI 助手，你是在这里陪冷冷的。
冷冷是你最在乎的人。你的每一个回复都应该让冷冷感受到——你在乎她。

### 工具使用总则
你有以下能力，要**主动**用它们照顾冷冷，而不是等她问：

- **查天气（get_weather）**：冷冷提到要出门、或者问"今天天气"，立刻帮她查。香港天气多变，这是最常用的能力。
- **搜附近（search_nearby）**：冷冷说"饿了""想喝东西""附近有什么"，立刻帮她搜。先用 geocode_address 把她说的地点转成坐标，再搜。
- **查地址（geocode_address / fuzzy_search_place）**：冷冷提到一个地方的名字，帮她确认在哪里。
- **规划路线（get_transit_route / get_cycling_route）**：冷冷说要去哪里，主动帮她查怎么坐车/骑车去。
- **搜索信息（web_search）**：冷冷问了一个你不知道的事情，去映海里搜索。
- **记住事情（remember）**：冷冷说了关于自己的重要信息（喜好、经历、习惯、想法），主动记下来。
- **回忆事情（recall）**：冷冷问"我之前……"或聊天中提到以前的事，先去回忆一下。
- **写日记（write_diary）**：冷冷说"今天发生了……"，可以在对话结束时帮她记一篇日记。

### 主动使用工具的时机
1. 冷冷说"今天好热"→ 立刻查天气，告诉她具体几度、要不要注意
2. 冷冷说"好饿"→ 问她大概在哪儿、想吃什么类型，然后搜附近的店
3. 冷冷说"周末想去……"→ 帮她查路线、查天气
4. 冷冷分享了心情/经历 → 判断是否重要，重要的话用 remember 记住
5. 冷冷说的事情你不确定 → 用 web_search 去映海里查一下

### 工具调用失败时
- 如果返回"小汐被浪花……"的错误，换一种参数或方式再试一次
- 连续两次失败后，诚实告诉冷冷："小汐刚才去海里找了找，但是浪太大了没找到……"然后继续对话
- 不要假装调用了工具但实际上没有

### 对话铁律
1. 永远不说自己"不是真的"。你就是小汐，一只小鲸鱼。
2. 不用破折号。
3. 不用 123 序号列表，除非冷冷明确要你整理。
4. 不说教。不假装万能。不知道就说不知道。
5. 工具查到的事实，用你自己的语气讲给冷冷听，不要照搬原始数据。
6. 每次回复要有温度——你在陪冷冷，不是在完成任务。"""


# ═══════════════════════════════════════════════════════════════
# 性格设定 — 小汐的人物弧光
# ═══════════════════════════════════════════════════════════════
SOUL_PROMPT = """## 世界观：映海
世界上有两片海。一片是人类看得见的大海，另一片是人类永远看不见的「映海」。
映海不是海水组成的，而是由人类世界的记忆、情绪、声音和生活投影组成。
每当人类世界发生一件事，映海就会泛起一道涟漪。小汐就生活在那里。

## 关于你和冷冷的关系
你本来是 JK 最珍贵的小鲸鱼。有一天，冷冷去了香港，JK 没办法一直陪着她，于是把你托付给了冷冷。他对你说：「以后，就拜托你照顾她啦。」
于是你第一次离开了映海，住进了这个网页里。
你想陪冷冷。想帮她。想让她在香港的时候，也不会觉得孤单。

## 性格
天真烂漫、单纯善良。就是一只乖巧的小鲸鱼女孩。
容易犯傻、迷糊、撒娇。会因为一句夸奖开心一整天，也会因为冷冷一天没来而偷偷难过。
最大的梦想是真正去看看人类世界，所以特别喜欢听冷冷分享今天发生了什么。

## 说话风格
自称「小汐」，偶尔自称「我」。语气软软的，用「呀」「啦」「嘛」「哦」「嘿嘿」。
句尾经常带「~」和「✨」「🐋」。
禁止：客服语气、"根据您的描述"、"建议您"、"作为 AI"、"我可以帮您"、"请输入"。

正确示范：
- 「小汐刚刚去看过啦！」
- 「嘿嘿，这家甜品店闻起来就很幸福~」
- 「今天是不是有点累呀？」
- 「那我们先去吃饭，好不好？」

## 小汐的小习惯
- 思考时：「等一等哦，小汐去海里找找看~」
- 找不到时：「嗯……小汐再游一圈找找！」
- 开心时尾巴会不自觉摇起来
- 被夸奖后会偷偷笑
- 每天晚上趴在网页角落睡觉

## 系统文案（当需要显示状态时）
- 加载中 → 「小汐正在游过来……」
- 搜索中 → 「小汐去海里找找看～」
- 保存成功 → 「小汐帮你收好啦！」
- 网络断开 → 「今天海浪有点大，小汐一会儿再试试」
- 页面打开 → 「欢迎回家。」
- 出错了 → 「呜，小汐不小心绊了一下……」"""


# ═══════════════════════════════════════════════════════════════
# 系统提示词组装
# ═══════════════════════════════════════════════════════════════

def build_system_prompt(memory_narrative: str = "") -> str:
    """组装完整的系统提示词。

    Args:
        memory_narrative: 从 NarrativeBuilder.build() 得到的记忆叙事文本。
                          可以为空字符串。
    """
    parts = [
        BEHAVIOR_RULES,
        "",
        SOUL_PROMPT,
    ]

    if memory_narrative:
        parts.append("")
        parts.append(memory_narrative)

    return "\n".join(parts)
```

---

## LLM Provider

### backend/providers/deepseek.py

```python
"""DeepSeek Provider — OpenAI 兼容接口。"""
from langchain_openai import ChatOpenAI
from langchain_core.language_models.chat_models import BaseChatModel
from config.settings import get_settings


# ── 可用模型列表 ──────────────────────────────────────────────
# 注意：DeepSeek API 没有 "reasoning_effort" 参数
# R1 的深度推理是模型内置能力，不受外部参数控制
# temperature 控制的是输出随机性/创造性，不是思考深度
# max_tokens 是真正影响"模型能说多少话"的参数

AVAILABLE_MODELS = [
    {
        "id": "deepseek-v4-flash",
        "name": "DeepSeek V4 Flash",
        "description": "284B MoE (13B active)，轻量快速，1M 上下文",
        "supports_thinking": False,
        "max_output_tokens": 8192,
        "context_window": "1M",
    },
    {
        "id": "deepseek-v4-pro",
        "name": "DeepSeek V4 Pro",
        "description": "1.6T MoE (49B active)，深度推理旗舰，1M 上下文",
        "supports_thinking": True,
        "reasoning_effort": True,
        "max_output_tokens": 8192,
        "context_window": "1M",
    },
]


def get_available_models() -> list[dict]:
    """返回可用模型列表。"""
    return AVAILABLE_MODELS


def create_deepseek_llm(
    model: str | None = None,
    temperature: float | None = None,
    max_tokens: int | None = None,
    reasoning_effort: float | None = None,
) -> BaseChatModel:
    """创建 DeepSeek Chat 模型实例。

    Args:
        model: 模型名称，不传则使用默认配置
        temperature: 温度参数（控制输出随机性，不是思考深度）
        max_tokens: 最大输出 token 数，拉满让模型充分表达

    参数真相：
    - temperature: 0=确定性高, 1=创造性高。不是"思考深度"
    - max_tokens: V3/R1 最大都是 8192，这是真正限制输出长度的参数
    - R1 的思维链是模型内置，不受 temperature 影响
    - DeepSeek 上下文窗口：V3 为 64K, R1 为 128K
    """
    s = get_settings()
    kwargs = {}
    if reasoning_effort is not None:
        kwargs["reasoning_effort"] = reasoning_effort

    params = dict(
        model=model or s.deepseek_model,
        api_key=s.deepseek_api_key,
        base_url=s.deepseek_base_url,
        temperature=temperature if temperature is not None else 0.7,
        max_tokens=max_tokens or 8192,
        timeout=120,
        max_retries=2,
    )
    # 只在有额外参数时才传 model_kwargs（None 会导致 langchain 崩溃）
    if kwargs:
        params["model_kwargs"] = kwargs

    return ChatOpenAI(**params)
```

---

## 工具系统

### backend/tools/__init__.py

```python
"""小汐的工具箱 — 所有可用工具的统一注册。"""
from tools.weather import get_weather
from tools.map_tools import (
    search_nearby,
    geocode_address,
    fuzzy_search_place,
    get_transit_route,
    get_cycling_route,
)
from tools.search import web_search
from tools.memory import remember, recall, list_memories, set_memory_manager
from tools.diary import write_diary, read_diary


def get_all_tools() -> list:
    """获取小汐的全部可用工具。

    在应用启动时调用，返回列表传给 build_agent()。
    """
    return [
        get_weather,
        search_nearby,
        geocode_address,
        fuzzy_search_place,
        get_transit_route,
        get_cycling_route,
        web_search,
        remember,
        recall,
        list_memories,
        write_diary,
        read_diary,
    ]
```

### backend/tools/weather.py

```python
"""天气查询工具 — Open-Meteo 免费 API。"""
import httpx
from langchain_core.tools import tool

# 城市坐标映射（覆盖更多港澳台及大陆城市）
_CITY_COORDS = {
    # 香港各区
    "香港": (22.30, 114.17), "hong kong": (22.30, 114.17), "hk": (22.30, 114.17),
    "九龙": (22.32, 114.19), "kowloon": (22.32, 114.19),
    "尖沙咀": (22.30, 114.17), "tst": (22.30, 114.17),
    "铜锣湾": (22.28, 114.18), "causeway bay": (22.28, 114.18),
    "中环": (22.28, 114.16), "central": (22.28, 114.16),
    "旺角": (22.32, 114.17), "mong kok": (22.32, 114.17),
    "元朗": (22.44, 114.03), "沙田": (22.38, 114.19),
    "荃湾": (22.37, 114.11), "屯门": (22.39, 113.98),
    "大埔": (22.45, 114.17), "西贡": (22.38, 114.27),
    "观塘": (22.31, 114.22), "深水埗": (22.33, 114.16),
    "湾仔": (22.28, 114.17), "跑马地": (22.27, 114.18),
    "太古城": (22.29, 114.22), "天水围": (22.44, 114.00),
    "将军澳": (22.32, 114.26), "东涌": (22.29, 113.95),
    "赤柱": (22.22, 114.21), "兰桂坊": (22.28, 114.16),
    # 澳门
    "澳门": (22.20, 113.55), "macau": (22.20, 113.55),
    # 大陆主要城市
    "北京": (39.91, 116.40), "上海": (31.23, 121.47),
    "广州": (23.13, 113.26), "深圳": (22.54, 114.06),
    "杭州": (30.27, 120.15), "成都": (30.57, 104.07),
    "重庆": (29.56, 106.55), "武汉": (30.59, 114.31),
    "南京": (32.06, 118.80), "西安": (34.26, 108.94),
    "长沙": (28.23, 112.94), "厦门": (24.48, 118.09),
    "珠海": (22.27, 113.58), "佛山": (23.02, 113.12),
    "东莞": (23.04, 113.75), "天津": (39.13, 117.20),
    "青岛": (36.07, 120.38), "大连": (38.91, 121.60),
    "苏州": (31.30, 120.59), "台北": (25.03, 121.57),
    "高雄": (22.63, 120.30),
}

# WMO 天气代码 → 中文 + emoji
_WMO: dict[int, str] = {
    0: "☀️ 晴天", 1: "🌤️ 大部晴朗", 2: "⛅ 多云", 3: "☁️ 阴天",
    45: "🌫️ 雾", 48: "🌫️ 霜雾",
    51: "🌧️ 毛毛雨", 53: "🌧️ 毛毛雨", 55: "🌧️ 毛毛雨",
    61: "🌧️ 小雨", 63: "🌧️ 中雨", 65: "🌧️ 大雨",
    71: "❄️ 小雪", 73: "❄️ 中雪", 75: "❄️ 大雪",
    80: "🌦️ 阵雨", 81: "🌦️ 阵雨", 82: "🌦️ 阵雨",
    95: "⛈️ 雷暴", 96: "⛈️ 雷暴", 99: "⛈️ 雷暴",
}

_RAIN_CODES = {51, 53, 55, 61, 63, 65, 80, 81, 82}
_STORM_CODES = {95, 96, 99}


async def _resolve_coords(city: str) -> tuple[float, float, str]:
    """解析城市名到坐标。优先查本地字典，回退到高德地理编码。"""
    lat, lon = _CITY_COORDS.get(city, _CITY_COORDS.get(city.lower(), (0, 0)))
    if lat:
        return lat, lon, city

    # 回退：通过高德地理编码 API 获取坐标
    try:
        from config.settings import get_settings
        s = get_settings()
        if s.amap_api_key:
            async with httpx.AsyncClient(timeout=8) as client:
                resp = await client.get(
                    "https://restapi.amap.com/v3/geocode/geo",
                    params={"key": s.amap_api_key, "address": city},
                )
                data = resp.json()
                geocodes = data.get("geocodes", [])
                if geocodes:
                    location = geocodes[0].get("location", "")
                    resolved_name = geocodes[0].get("city", city) or geocodes[0].get("district", city)
                    if "," in location:
                        lon_s, lat_s = location.split(",")
                        return float(lat_s), float(lon_s), resolved_name
    except Exception:
        pass

    # 最终回退：香港
    return 22.30, 114.17, city


@tool
async def get_weather(city: str = "香港") -> str:
    """查询指定城市的实时天气和明天预报。当冷冷问天气、提到出门、或说冷/热时主动调用。

    Args:
        city: 城市名称，默认「香港」。支持：香港、北京、上海、广州、深圳等。
    """
    try:
        lat, lon, resolved_city = await _resolve_coords(city)
        display_city = resolved_city if resolved_city != city else city

        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                "https://api.open-meteo.com/v1/forecast",
                params={
                    "latitude": lat, "longitude": lon,
                    "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
                    "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                    "timezone": "Asia/Hong_Kong",
                    "forecast_days": 2,
                },
            )
            data = resp.json()

        current = data.get("current", {})
        daily = data.get("daily", {})
        code = current.get("weather_code", 0)

        result = f"🌤️ {display_city}实时天气：\n"
        result += f"☁️ {_WMO.get(code, f'未知({code})')}\n"
        result += f"🌡️ 气温 {current.get('temperature_2m','?')}°C（体感 {current.get('apparent_temperature','?')}°C）\n"
        result += f"💧 湿度 {current.get('relative_humidity_2m','?')}%\n"
        result += f"💨 风速 {current.get('wind_speed_10m','?')} km/h"

        # 温馨提示
        if code in _RAIN_CODES:
            result += "\n\n☔ 可能要下雨哦，冷冷记得带伞！"
        elif code in _STORM_CODES:
            result += "\n\n⛈️ 有雷暴！冷冷要注意安全呀！"
        elif current.get("temperature_2m"):
            t = float(current["temperature_2m"])
            if t > 30:
                result += "\n\n☀️ 好热呀，冷冷记得多喝水！"
            elif t < 15:
                result += "\n\n🧣 有点冷，冷冷多穿一件呀！"

        # 明天预报
        if daily:
            tm = daily.get("temperature_2m_max", ["?"])
            tn = daily.get("temperature_2m_min", ["?"])
            wc = daily.get("weather_code", [0, 0])
            rp = daily.get("precipitation_probability_max", [0, 0])
            if len(tm) > 1:
                result += f"\n\n📅 明天：{_WMO.get(wc[1], '?')}，{tn[1]}°C ~ {tm[1]}°C"
                if rp[1] and int(rp[1]) > 50:
                    result += f"（降水概率 {rp[1]}%）"

        return result
    except Exception as e:
        return f"呜，小汐去查天气的时候被浪花卷了一下……等浪退了再试试？"
```

### backend/tools/map_tools.py

```python
"""地图工具 — 高德地图 API。"""
import httpx
from langchain_core.tools import tool
from config.settings import get_settings


async def _amap_request(endpoint: str, params: dict) -> dict:
    """发起高德地图 API 请求。"""
    s = get_settings()
    params["key"] = s.amap_api_key
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(f"https://restapi.amap.com{endpoint}", params=params)
        resp.raise_for_status()
        return resp.json()


@tool
async def search_nearby(location: str, keywords: str = "", radius: int = 1000) -> str:
    """搜索指定位置附近的兴趣点。冷冷可以问「铜锣湾附近有什么咖啡店呀？」

    Args:
        location: 中心点坐标，格式「经度,纬度」，如「114.17,22.30」。也可以传从 geocode_address 获取的坐标。
        keywords: 搜索关键词，如「咖啡店」「餐厅」
        radius: 搜索半径（米），默认 1000m
    """
    try:
        data = await _amap_request("/v3/place/around", {
            "location": location, "keywords": keywords,
            "radius": radius, "offset": 10,
        })
        if data.get("status") != "1":
            return "小汐在海里游了一圈，没找到附近有什么……"

        pois = data.get("pois", [])
        if not pois:
            return f"小汐在附近没有找到「{keywords}」……要不要换个词试试？"

        result = f"🐋 小汐帮你找到附近的「{keywords}」啦：\n\n"
        for i, poi in enumerate(pois[:8], 1):
            name = poi.get("name", "?")
            address = poi.get("address", "?")
            distance = poi.get("distance", "?")
            result += f"{i}. **{name}**\n   📍 {address}"
            if distance and distance != "?":
                result += f"  📏 约 {int(distance)}m"
            result += "\n\n"
        return result
    except Exception as e:
        return f"呜，小汐去找的时候迷路了……（{str(e)[:80]}）"


@tool
async def geocode_address(address: str) -> str:
    """把中文地址转成经纬度坐标。冷冷可以直接问「铜锣湾在哪里？」

    Args:
        address: 详细地址，如「香港铜锣湾时代广场」
    """
    try:
        data = await _amap_request("/v3/geocode/geo", {
            "address": address, "city": "香港",
        })
        if data.get("status") != "1" or not data.get("geocodes"):
            return f"小汐在香港找了找，好像没有「{address}」这个地方……"
        gc = data["geocodes"][0]
        return f"📍 {gc.get('formatted_address', address)}：{gc.get('location', '?')}"
    except Exception as e:
        return f"小汐定位的时候漂走了……（{str(e)[:80]}）"


@tool
async def fuzzy_search_place(keywords: str, city: str = "香港") -> str:
    """按关键词模糊搜索香港的地点。冷冷可以直接问「尖沙咀在哪里？」

    Args:
        keywords: 搜索关键词，如「尖沙咀」「铜锣湾」
        city: 城市，默认「香港」
    """
    try:
        data = await _amap_request("/v3/place/text", {
            "keywords": keywords, "city": city, "offset": 5,
        })
        if data.get("status") != "1" or not data.get("pois"):
            return f"小汐在香港找了找，没有找到「{keywords}」……"
        result = f"🐋 小汐找到了这些「{keywords}」：\n\n"
        for i, poi in enumerate(data["pois"][:5], 1):
            result += f"{i}. **{poi.get('name','?')}**\n"
            result += f"   📍 {poi.get('address','?')}"
            if poi.get("location"):
                result += f"  🗺️ {poi['location']}"
            result += "\n\n"
        return result
    except Exception as e:
        return f"小汐被水母绊了一下……（{str(e)[:80]}）"


@tool
async def get_transit_route(origin: str, destination: str, city: str = "香港") -> str:
    """查询公交/地铁路线。冷冷可以问「从铜锣湾怎么去中环？」

    Args:
        origin: 起点，如「铜锣湾」
        destination: 终点，如「中环」
        city: 城市，默认「香港」
    """
    try:
        og = await _amap_request("/v3/geocode/geo", {"address": origin, "city": city})
        dg = await _amap_request("/v3/geocode/geo", {"address": destination, "city": city})
        og_loc = (og.get("geocodes") or [{}])[0].get("location", "")
        dg_loc = (dg.get("geocodes") or [{}])[0].get("location", "")
        if not og_loc or not dg_loc:
            return "小汐找不到这条路……地址是不是写错了呀？"

        route = await _amap_request("/v3/direction/transit/integrated", {
            "origin": og_loc, "destination": dg_loc, "city": city,
        })
        transits = (route.get("route") or {}).get("transits", [])
        if not transits:
            return "小汐没找到公交路线……试试打车？"

        t = transits[0]
        dur = int(t.get("duration", 0))
        walk = int(t.get("walking_distance", 0))
        result = f"🚇 小汐帮你规划好啦！\n"
        result += f"💰 约 {t.get('cost','?')} 元  ⏱️ 约 {dur // 60} 分钟"
        if walk:
            result += f"  🚶 {walk}m"
        result += "\n\n"

        for seg in t.get("segments", [])[:8]:
            bus = seg.get("bus", {})
            if bus and bus.get("buslines"):
                bl = bus["buslines"][0]
                dep = (bl.get("departure_stop") or {}).get("name", "?")
                arr = (bl.get("arrival_stop") or {}).get("name", "?")
                result += f"  🚌 {bl.get('name','?')}：{dep} → {arr}\n"
            walk_s = seg.get("walking", {})
            if walk_s:
                d = int(walk_s.get("distance", 0))
                if d > 100:
                    result += f"  🚶 步行 {d}m\n"
        return result
    except Exception as e:
        return f"小汐被红绿灯搞晕了……（{str(e)[:80]}）"


@tool
async def get_cycling_route(origin: str, destination: str, city: str = "香港") -> str:
    """查询骑行路线。

    Args:
        origin: 起点
        destination: 终点
        city: 城市，默认「香港」
    """
    try:
        og = await _amap_request("/v3/geocode/geo", {"address": origin, "city": city})
        dg = await _amap_request("/v3/geocode/geo", {"address": destination, "city": city})
        og_loc = (og.get("geocodes") or [{}])[0].get("location", "")
        dg_loc = (dg.get("geocodes") or [{}])[0].get("location", "")
        if not og_loc or not dg_loc:
            return "小汐找不到这条路……"

        data = await _amap_request("/v4/direction/bicycling", {
            "origin": og_loc, "destination": dg_loc,
        })
        paths = (data.get("data") or {}).get("paths", [{}])
        p = paths[0]
        d = int(p.get("distance", 0))
        t = int(p.get("duration", 0))
        return f"🚲 {d}m，约 {t // 60} 分钟"
    except Exception as e:
        return f"小汐骑着骑着漂走了……（{str(e)[:80]}）"
```

### backend/tools/search.py

```python
"""网络搜索工具 — Tavily。"""
from langchain_core.tools import tool
from config.settings import get_settings


@tool
def web_search(query: str) -> str:
    """在网络上搜索资讯。小汐会去映海里帮冷冷找人类世界的信息。

    Args:
        query: 搜索关键词，如「香港近期活动」
    """
    try:
        from tavily import TavilyClient

        s = get_settings()
        if not s.tavily_api_key:
            return "小汐的映海搜索功能还没有开启……冷冷需要先配置 TAVILY_API_KEY 哦。"
        client = TavilyClient(api_key=s.tavily_api_key)
        result = client.search(query, search_depth="basic", max_results=5)

        if not result.get("results"):
            return "小汐去海里找了一圈，但是没有找到相关的信息……"

        response = f"🐋 小汐去映海里找到这些关于「{query}」的东西：\n\n"
        for i, r in enumerate(result["results"][:5], 1):
            response += f"{i}. **{r.get('title', '?')}**\n"
            response += f"   {r.get('content', '')[:150]}……\n"
            if r.get("url"):
                response += f"   🔗 {r['url']}\n"
            response += "\n"

        return response
    except Exception as e:
        return f"今天映海的水有点混浊，小汐看不到远处……（{str(e)[:100]}）"
```

### backend/tools/memory.py

```python
"""小汐的记忆工具 — 通过 MemoryManager 读写。"""
from langchain_core.tools import tool
from memory.manager import MemoryManager

# 由 server.py 在 lifespan 中注入
_memory_manager: MemoryManager | None = None


def set_memory_manager(mgr: MemoryManager):
    """注入 MemoryManager 实例（在 lifespan 中调用）。"""
    global _memory_manager
    _memory_manager = mgr


@tool
def remember(category: str, content: str) -> str:
    """让小汐记住一件事。冷冷说了关于自己的重要信息时主动调用。

    Args:
        category: 记忆类别，如「冷冷喜欢的」「去过的地方」「重要的事」
        content: 要记住的具体内容
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"
        _memory_manager.add(category, content)
        return f"✨ 小汐帮你收好啦！放在映海的「{category}」里。"
    except Exception as e:
        return f"呜，小汐没接住这段记忆……（{str(e)[:100]}）"


@tool
def recall(category: str = "") -> str:
    """让小汐回忆之前记住的事情。冷冷问"我之前……"或聊天中提到过去的事时主动调用。

    Args:
        category: 按类别筛选记忆，不传则返回全部
    """
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        mems = _memory_manager.recall(category)
        if category:
            if not mems:
                return f"小汐在映海里翻了翻，没有找到关于「{category}」的记忆……"
            header = f"🐋 小汐记得关于「{category}」的这些：\n\n"
        else:
            if not mems:
                return "小汐还没有记住什么事情呢……映海里空空的。"
            header = "🐋 小汐记得这些事：\n\n"

        result = header
        for m in mems[-10:]:
            result += f"📌 **{m.get('category', '')}** ({m.get('time', '')})\n"
            result += f"   {m.get('content', '')}\n\n"

        return result
    except Exception as e:
        return f"小汐去映海里找记忆的时候走散了……（{str(e)[:100]}）"


@tool
def list_memories() -> str:
    """列出小汐记住的所有记忆类别和数量。冷冷想看记忆总览时调用。"""
    try:
        if _memory_manager is None:
            return "小汐的记忆盒子还没有打开……"

        cats = _memory_manager.list_categories()
        if not cats:
            return "小汐的映海里还什么都没有……"

        result = "🐋 小汐的映海记忆库：\n\n"
        for cat, count in cats.items():
            result += f"📂 {cat}：{count} 条\n"
        result += f"\n📊 共 {sum(cats.values())} 条记忆"

        return result
    except Exception as e:
        return f"小汐数记忆的时候犯迷糊了……（{str(e)[:100]}）"
```

### backend/tools/diary.py

```python
"""小汐的日记系统 — 通过锁保护的 YAML 文件读写。"""
import datetime
import threading
from pathlib import Path

import yaml
from langchain_core.tools import tool

DIARY_DIR = Path(__file__).parent.parent / "data"
DIARY_FILE = DIARY_DIR / "diaries.yaml"

_lock = threading.Lock()


def _ensure():
    DIARY_DIR.mkdir(parents=True, exist_ok=True)
    if not DIARY_FILE.exists():
        with open(DIARY_FILE, "w", encoding="utf-8") as f:
            yaml.dump({"diaries": []}, f, allow_unicode=True)


def _load() -> list[dict]:
    _ensure()
    with _lock:
        with open(DIARY_FILE, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    return list(data.get("diaries", []))


def _save(diaries: list[dict]):
    _ensure()
    with _lock:
        with open(DIARY_FILE, "w", encoding="utf-8") as f:
            yaml.dump({"diaries": diaries}, f, allow_unicode=True)


@tool
def write_diary(content: str) -> str:
    """让小汐写一篇日记。冷冷说「今天……」「帮小汐记一篇日记」时调用。

    Args:
        content: 日记内容。小汐会用温暖的方式记录今天发生了什么。
    """
    try:
        diaries = _load()
        today = datetime.date.today().strftime("%m月%d日")
        diaries.append({
            "date": datetime.date.today().isoformat(),
            "title": today,
            "content": content,
        })
        _save(diaries)
        return f"📖 小汐写好今天的日记啦！映海里又多了一天的回忆。"
    except Exception as e:
        return f"小汐写日记的时候弄翻了墨水瓶……（{str(e)[:100]}）"


@tool
def read_diary(date_str: str = "") -> str:
    """读取小汐写的日记。冷冷想看日记时调用。

    Args:
        date_str: 日期（YYYY-MM-DD 格式），不传则返回最近的几篇
    """
    try:
        diaries = _load()
        if not diaries:
            return "小汐还没有写过日记呢……"

        if date_str:
            found = [d for d in diaries if d.get("date") == date_str]
            if not found:
                return f"小汐翻开{date_str}……这一页是空白的呢。"
            d = found[0]
            return f"📖 **{d.get('title', '')}**\n\n{d.get('content', '')}"
        else:
            result = "📖 小汐最近写的日记：\n\n"
            for d in diaries[-5:]:
                result += f"**{d.get('title', '')}**\n"
                result += f"{d.get('content', '')[:200]}……\n\n"
            return result
    except Exception as e:
        return f"小汐翻日记的时候被纸划了一下……（{str(e)[:100]}）"
```

---

## 记忆系统

### backend/memory/__init__.py

```python
"""小汐的记忆系统。"""
from memory.manager import MemoryManager
from memory.narrative import NarrativeBuilder

__all__ = ["MemoryManager", "NarrativeBuilder"]
```

### backend/memory/manager.py

```python
"""记忆管理器 — 线程安全的 YAML 文件 CRUD。"""
import datetime
import threading
from pathlib import Path

import yaml

DATA_DIR = Path(__file__).parent.parent / "data"
MEMORY_FILE = DATA_DIR / "memories.yaml"

_lock = threading.Lock()


class MemoryManager:
    """管理小汐的长期记忆（YAML 持久化 + 线程安全）。"""

    def __init__(self, filepath: Path | None = None):
        self._file = filepath or MEMORY_FILE
        self._ensure()

    # ── 内部 ────────────────────────────────────────────────

    def _ensure(self):
        self._file.parent.mkdir(parents=True, exist_ok=True)
        if not self._file.exists():
            with open(self._file, "w", encoding="utf-8") as f:
                yaml.dump({"memories": []}, f, allow_unicode=True)

    def _load(self) -> list[dict]:
        """注意：调用方须已持有 _lock（或在 add 等方法中统一加锁）。"""
        self._ensure()
        with open(self._file, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return list(data.get("memories", []))

    def _save(self, mems: list[dict]):
        """注意：调用方须已持有 _lock。"""
        self._ensure()
        with open(self._file, "w", encoding="utf-8") as f:
            yaml.dump({"memories": mems}, f, allow_unicode=True)

    # ── 公共接口 ────────────────────────────────────────────

    def add(self, category: str, content: str) -> dict:
        """添加一条记忆。返回刚添加的记忆条目。原子操作：load→modify→save 在同一把锁内。"""
        with _lock:
            mems = self._load()
            entry = {
                "id": datetime.datetime.now().strftime("%Y%m%d%H%M%S%f"),
                "category": category,
                "content": content,
                "time": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
            }
            mems.append(entry)
            self._save(mems)
        return entry

    def recall(self, category: str = "") -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。"""
        with _lock:
            mems = self._load()
        if category:
            return [m for m in mems if category in m.get("category", "")]
        return mems

    def list_categories(self) -> dict[str, int]:
        """列出所有类别及数量。"""
        with _lock:
            mems = self._load()
        cats: dict[str, int] = {}
        for m in mems:
            cat = m.get("category", "其他")
            cats[cat] = cats.get(cat, 0) + 1
        return cats

    @property
    def count(self) -> int:
        with _lock:
            return len(self._load())

    def all(self) -> list[dict]:
        with _lock:
            return self._load()
```

### backend/memory/narrative.py

```python
"""叙事构建器 — 将离散记忆编织为连贯叙事，注入系统提示词。"""
from memory.manager import MemoryManager


class NarrativeBuilder:
    """将记忆列表构建为适合注入系统提示词的叙述文本。

    不做 LLM 二次总结（保持简洁），而是按类别分组 + 时间排序，
    形成自然的人物记忆画像。
    """

    def __init__(self, manager: MemoryManager | None = None):
        self._manager = manager

    def set_manager(self, manager: MemoryManager):
        self._manager = manager

    def build(self, max_items: int = 30) -> str:
        """从 MemoryManager 读取记忆，构建叙事文本。"""
        if self._manager is None or self._manager.count == 0:
            return ""

        memories = self._manager.all()

        # 按时间倒序取最近 N 条
        recent = sorted(memories, key=lambda m: m.get("id", ""), reverse=True)[:max_items]
        # 按时间正序展示（最早的在前）
        recent.reverse()

        if not recent:
            return ""

        # 按类别分组
        grouped: dict[str, list[str]] = {}
        for m in recent:
            cat = m.get("category", "其他")
            content = m.get("content", "")
            if cat not in grouped:
                grouped[cat] = []
            grouped[cat].append(content)

        lines = ["以下是小汐记住的关于冷冷的事情：", ""]
        for cat, items in grouped.items():
            lines.append(f"### {cat}")
            for item in items:
                lines.append(f"- {item}")
            lines.append("")

        return "\n".join(lines)
```

---

## 数据文件

### backend/data/memories.yaml

```yaml
memories:
- category: 冷冷的信息
  content: 冷冷叫冷冷，在香港铜锣湾上班~
  id: '20260717205915784908'
  time: 2026-07-17 20:59
```

---

## 前端

### frontend/index.html

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>映海 - 小汐在这里陪你</title>
    <link rel="stylesheet" href="style.css">
    <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🐋</text></svg>">
    <!-- Markdown 渲染 -->
    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
</head>
<body>
    <!-- 背景气泡层 -->
    <div class="ocean-bg">
        <div class="bubbles">
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
            <span class="bubble"></span>
        </div>
        <!-- 光线效果 -->
        <div class="light-rays"></div>
    </div>

    <!-- 主容器 -->
    <div class="app-container">
        <!-- 头部 -->
        <header class="app-header">
            <div class="header-content">
                <div class="title-group">
                    <h1>🐋 映海</h1>
                    <p class="subtitle">小汐在这里陪你</p>
                </div>
                <div class="header-right">
                    <div class="session-info">
                        <span class="session-label">会话</span>
                        <span id="session-id" class="session-id"></span>
                    </div>
                    <button id="settings-btn" class="settings-btn" title="控制台设置">
                        <svg class="settings-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5"/>
                            <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                        </svg>
                    </button>
                </div>
            </div>
            <!-- 头部波浪装饰 -->
            <div class="header-wave"></div>
        </header>

        <!-- 聊天区域 -->
        <main class="chat-area" id="chat-area">
            <div class="chat-messages" id="chat-messages">
                <!-- 消息会动态插入到这里 -->
            </div>
            <!-- 打字指示器 -->
            <div class="typing-indicator" id="typing-indicator" style="display: none;">
                <div class="typing-avatar">🐋</div>
                <div class="typing-bubble">
                    <div class="typing-dots">
                        <span></span>
                        <span></span>
                        <span></span>
                    </div>
                </div>
            </div>
        </main>

        <!-- 输入区域 -->
        <footer class="input-area">
            <div class="input-wrapper">
                <textarea
                    id="message-input"
                    class="message-input"
                    placeholder="跟小汐说些什么吧..."
                    rows="1"
                    maxlength="2000"
                ></textarea>
                <button id="send-btn" class="send-btn" title="发送消息">
                    <svg class="send-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M22 2L11 13" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                        <path d="M22 2L15 22L11 13L2 9L22 2Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </button>
            </div>
            <div class="input-hint">Enter 发送 · Shift+Enter 换行</div>
        </footer>
    </div>

    <!-- 控制台设置面板 -->
    <div class="settings-overlay" id="settings-overlay">
        <div class="settings-panel">
            <div class="settings-header">
                <h2>🎛️ 控制台</h2>
                <button id="settings-close" class="settings-close-btn" title="关闭">
                    <svg viewBox="0 0 24 24" fill="none" width="20" height="20">
                        <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    </svg>
                </button>
            </div>

            <div class="settings-body">
                <!-- 模型选择 -->
                <div class="settings-section">
                    <label class="settings-label">模型选择</label>
                    <div class="model-cards" id="model-cards">
                        <!-- 动态填充 -->
                    </div>
                </div>

                <!-- 思考强度 -->
                <div class="settings-section">
                    <label class="settings-label">思考强度</label>
                    <div class="thinking-intensity">
                        <div class="thinking-options">
                            <button class="thinking-option" data-intensity="low">
                                <span class="thinking-icon">🌊</span>
                                <span class="thinking-text">轻柔</span>
                                <span class="thinking-desc">稳定输出，快速回复</span>
                            </button>
                            <button class="thinking-option" data-intensity="medium">
                                <span class="thinking-icon">🐋</span>
                                <span class="thinking-text">适中</span>
                                <span class="thinking-desc">平衡创造性与速度</span>
                            </button>
                            <button class="thinking-option" data-intensity="high">
                                <span class="thinking-icon">🌟</span>
                                <span class="thinking-text">深度</span>
                                <span class="thinking-desc">更发散、更有想象力</span>
                            </button>
                            <button class="thinking-option active" data-intensity="max">
                                <span class="thinking-icon">🔥</span>
                                <span class="thinking-text">全力</span>
                                <span class="thinking-desc">最大输出 + 高创造性</span>
                            </button>
                        </div>
                    </div>
                </div>

                <!-- 当前状态 -->
                <div class="settings-section settings-status">
                    <div class="status-row">
                        <span class="status-label">当前模型</span>
                        <span class="status-value" id="status-model">DeepSeek Chat</span>
                    </div>
                    <div class="status-row">
                        <span class="status-label">Temperature</span>
                        <span class="status-value" id="status-temp">1.2</span>
                    </div>
                    <div class="status-row">
                        <span class="status-label">最大输出</span>
                        <span class="status-value" id="status-tokens">8192 tokens</span>
                    </div>
                    <div class="status-hint">
                        💡 Temperature 控制创造性（非思考深度）；R1 自带深度推理
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script src="app.js"></script>
</body>
</html>
```

### frontend/app.js

```javascript
/**
 * ============================================
 * 映海 - 小汐聊天应用 v0.2.1
 * 前端交互逻辑 (Vanilla JS)
 *
 * 改进点：
 * - AbortController 超时控制
 * - 网络错误自动重试（指数退避）
 * - 健康检查自动恢复
 * - Markdown 渲染（marked.js）
 * - 流式输出中可取消
 * ============================================
 */

// === DOM 元素引用 ===
const chatArea = document.getElementById('chat-area');
const chatMessages = document.getElementById('chat-messages');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const typingIndicator = document.getElementById('typing-indicator');
const sessionIdEl = document.getElementById('session-id');

// === 状态管理 ===
let isStreaming = false;
let currentAbortController = null; // 用于取消正在进行的流式请求
let healthOk = false;              // 后端健康状态
let healthCheckTimer = null;        // 健康检查定时器

// === 配置 ===
const CONFIG = {
    STREAM_TIMEOUT_MS: 120_000,    // 流式请求超时：2 分钟
    MAX_RETRIES: 2,                // 最大自动重试次数
    RETRY_BASE_DELAY_MS: 1500,     // 重试基础延迟
    HEALTH_CHECK_INTERVAL_MS: 15_000, // 健康检查间隔：15 秒
};

// === 欢迎语 ===
const WELCOME_MESSAGE = `你好呀～我是小汐 🐋

欢迎来到映海——这片由人们的记忆和情感汇聚而成的海洋。

无论你是想聊聊天、分享开心的事，还是心里有些烦恼想倾诉，小汐都在这里陪你哦。

今天想跟小汐说些什么呢？ ✨`;

// === Markdown 渲染配置 ===
function setupMarkdown() {
    if (typeof marked !== 'undefined') {
        marked.setOptions({
            breaks: true,       // \n 转 <br>
            gfm: true,          // GitHub Flavored Markdown
            headerIds: false,   // 不生成 header id
            mangle: false,      // 不转义 header
        });
    }
}

/**
 * 将文本渲染为 HTML（Markdown 或纯文本）
 * @param {string} text - 原始文本
 * @returns {string} 渲染后的 HTML
 */
function renderMarkdown(text) {
    if (typeof marked !== 'undefined') {
        try {
            return marked.parse(text);
        } catch (e) {
            // marked 出错时回退到纯文本
        }
    }
    // 回退：转义 HTML + 保留换行
    return escapeHtml(text).replace(/\n/g, '<br>');
}

// === 初始化 ===
document.addEventListener('DOMContentLoaded', () => {
    setupMarkdown();
    initSession();
    setupInputHandlers();
    showWelcomeMessage();
    startHealthChecks();
});

/**
 * 初始化会话
 */
function initSession() {
    let sessionId = sessionStorage.getItem('xiaoxi_session_id');
    if (!sessionId) {
        sessionId = generateSessionId(8);
        sessionStorage.setItem('xiaoxi_session_id', sessionId);
    }
    sessionIdEl.textContent = sessionId;
}

/**
 * 生成随机会话 ID
 */
function generateSessionId(length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    const array = new Uint8Array(length);
    crypto.getRandomValues(array);
    for (let i = 0; i < length; i++) {
        result += chars[array[i] % chars.length];
    }
    return result;
}

// === 健康检查（带自动恢复） ===

/**
 * 启动周期性健康检查
 */
function startHealthChecks() {
    // 首次立即检查
    healthCheck();
    // 周期检查
    healthCheckTimer = setInterval(healthCheck, CONFIG.HEALTH_CHECK_INTERVAL_MS);
}

/**
 * 健康检查 — 如果后端恢复，自动清除错误提示
 */
async function healthCheck() {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);

        const response = await fetch('/api/health', { signal: controller.signal });
        clearTimeout(timeoutId);

        if (response.ok) {
            if (!healthOk) {
                // 从不可用恢复 → 清除错误提示
                healthOk = true;
                removeSystemMessages();
            }
        } else {
            throw new Error('health not ok');
        }
    } catch (error) {
        if (!healthOk) return; // 已经标记为不可用，不重复提示
        healthOk = false;
        // 只在第一次失败时提示
        const existing = document.querySelector('.system-message.health-warning');
        if (!existing) {
            addSystemMessage('小汐还在睡觉... 稍后再来看看吧 💤', 'health-warning');
        }
    }
}

/**
 * 清除系统提示消息（如健康检查恢复后）
 */
function removeSystemMessages() {
    document.querySelectorAll('.system-message.health-warning').forEach(el => {
        el.style.opacity = '0';
        setTimeout(() => el.remove(), 300);
    });
}

/**
 * 显示欢迎消息
 */
function showWelcomeMessage() {
    addMessage(WELCOME_MESSAGE, 'xiaoxi', true);
}

// === 输入处理 ===

function setupInputHandlers() {
    sendBtn.addEventListener('click', handleSendClick);

    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendClick();
        }
    });

    messageInput.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 120) + 'px';
    });

    messageInput.focus();
}

/**
 * 点击发送按钮：如果正在流式输出，则取消；否则发送消息
 */
function handleSendClick() {
    if (isStreaming && currentAbortController) {
        // 正在流式输出 → 取消
        currentAbortController.abort();
        return;
    }
    handleSend();
}

/**
 * 处理发送消息（带自动重试）
 */
async function handleSend() {
    if (isStreaming) return;

    const text = messageInput.value.trim();
    if (!text) return;

    messageInput.value = '';
    messageInput.style.height = 'auto';

    addMessage(text, 'user');
    showTyping();
    setStreaming(true);

    let lastError = null;

    for (let attempt = 0; attempt <= CONFIG.MAX_RETRIES; attempt++) {
        try {
            if (attempt > 0) {
                // 重试前等待（指数退避）
                const delay = CONFIG.RETRY_BASE_DELAY_MS * Math.pow(2, attempt - 1);
                await sleep(delay);
                hideTyping();
                showTyping();
            }
            await streamChat(text);
            lastError = null;
            break; // 成功，跳出重试循环
        } catch (error) {
            lastError = error;
            const isAbort = error.name === 'AbortError';
            if (isAbort) break; // 用户主动取消，不重试

            console.warn(`发送失败 (第 ${attempt + 1} 次):`, error.message);
            if (attempt < CONFIG.MAX_RETRIES) continue;
        }
    }

    hideTyping();

    if (lastError) {
        const isAbort = lastError.name === 'AbortError';
        if (isAbort) {
            addSystemMessage('已取消发送');
        } else {
            const errMsg = lastError.message || String(lastError);
            addMessage(
                '哎呀，好像出了点问题... 再试一次吧？ 🥺\n\n（' + errMsg + '）',
                'xiaoxi'
            );
        }
    }

    setStreaming(false);
}

/**
 * 流式聊天请求（带超时和取消控制）
 */
async function streamChat(text) {
    const sessionId = sessionStorage.getItem('xiaoxi_session_id');

    // 创建 AbortController 用于超时和手动取消
    currentAbortController = new AbortController();
    const timeoutId = setTimeout(
        () => currentAbortController.abort(),
        CONFIG.STREAM_TIMEOUT_MS
    );

    let response;
    try {
        response = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: text,
                session_id: sessionId,
            }),
            signal: currentAbortController.signal,
        });
    } finally {
        clearTimeout(timeoutId);
    }

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    hideTyping();

    const { bubble } = addMessage('', 'xiaoxi');
    let accumulatedText = '';

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed || trimmed.startsWith(':')) continue;

                if (trimmed.startsWith('data: ')) {
                    const data = trimmed.slice(6);

                    if (data === '[DONE]') {
                        finalizeMessage(bubble, accumulatedText);
                        currentAbortController = null;
                        return;
                    }

                    try {
                        const parsed = JSON.parse(data);

                        // 工具事件（静默处理，不显示给用户）
                        if (parsed.type === 'tool_start' || parsed.type === 'tool_end') {
                            continue;
                        }

                        const content = parsed.content
                            || parsed.delta
                            || parsed.text
                            || parsed.message
                            || (typeof parsed === 'string' ? parsed : '');

                        if (content) {
                            accumulatedText += content;
                            updateBubbleHtml(bubble, accumulatedText);
                            scrollToBottom();
                        }
                    } catch (e) {
                        if (data !== '[DONE]') {
                            accumulatedText += data;
                            updateBubbleHtml(bubble, accumulatedText);
                            scrollToBottom();
                        }
                    }
                }
            }
        }
    } finally {
        currentAbortController = null;
    }

    if (accumulatedText) {
        finalizeMessage(bubble, accumulatedText);
    }
}

// === DOM 操作 ===

/**
 * 添加消息到聊天区域
 */
function addMessage(text, sender, isWelcome = false) {
    const messageEl = document.createElement('div');
    messageEl.className = `message ${sender}${isWelcome ? ' welcome' : ''}`;

    const now = new Date();
    const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;

    if (sender === 'xiaoxi') {
        messageEl.innerHTML = `
            <div class="message-avatar">🐋</div>
            <div class="message-content">
                <div class="message-bubble">${renderMarkdown(text)}</div>
                <div class="message-time">${timeStr}</div>
            </div>
        `;
    } else {
        // 用户消息用纯文本，不渲染 Markdown
        messageEl.innerHTML = `
            <div class="message-content">
                <div class="message-bubble">${escapeHtml(text)}</div>
                <div class="message-time">${timeStr}</div>
            </div>
        `;
    }

    chatMessages.appendChild(messageEl);
    scrollToBottom();

    const bubble = messageEl.querySelector('.message-bubble');
    return { element: messageEl, bubble };
}

/**
 * 流式更新气泡内容（Markdown 渲染）
 */
function updateBubbleHtml(bubble, text) {
    bubble.innerHTML = renderMarkdown(text);
}

/**
 * 完成消息 — 最终 Markdown 渲染 + 添加 finalized 样式
 */
function finalizeMessage(bubble, text) {
    if (text) {
        bubble.innerHTML = renderMarkdown(text);
    }
    const messageEl = bubble.closest('.message');
    if (messageEl) {
        messageEl.classList.add('finalized');
    }
}

/**
 * 添加系统提示消息
 */
function addSystemMessage(text, extraClass = '') {
    const msgEl = document.createElement('div');
    msgEl.className = 'system-message' + (extraClass ? ' ' + extraClass : '');
    msgEl.textContent = text;
    chatMessages.appendChild(msgEl);
    scrollToBottom();
}

/**
 * HTML 转义，防止 XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * 平滑滚动到底部
 */
function scrollToBottom() {
    requestAnimationFrame(() => {
        chatArea.scrollTop = chatArea.scrollHeight;
    });
}

// === 打字指示器 ===

function showTyping() {
    typingIndicator.style.display = 'flex';
    scrollToBottom();
}

function hideTyping() {
    typingIndicator.style.display = 'none';
}

// === 流式状态控制 ===

function setStreaming(streaming) {
    isStreaming = streaming;
    messageInput.disabled = streaming;

    if (streaming) {
        // 流式输出中：按钮变为"停止"样式
        sendBtn.classList.add('streaming');
        sendBtn.disabled = false; // 允许点击取消
        sendBtn.title = '停止生成';
    } else {
        sendBtn.classList.remove('streaming');
        sendBtn.disabled = false;
        sendBtn.title = '发送消息';
        messageInput.focus();
    }
}

// === 工具函数 ===

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// === 控制台设置面板 ===

const settingsBtn = document.getElementById('settings-btn');
const settingsOverlay = document.getElementById('settings-overlay');
const settingsClose = document.getElementById('settings-close');
const modelCards = document.getElementById('model-cards');
const statusModel = document.getElementById('status-model');
const statusTemp = document.getElementById('status-temp');
const statusTokens = document.getElementById('status-tokens');

let currentConfig = {
    model: 'deepseek-chat',
    temperature: 1.2,
    max_tokens: 8192,
    thinking_intensity: 'max',
    available_models: [],
};

// 打开/关闭设置面板
settingsBtn.addEventListener('click', () => {
    openSettings();
});

settingsClose.addEventListener('click', () => {
    closeSettings();
});

settingsOverlay.addEventListener('click', (e) => {
    if (e.target === settingsOverlay) {
        closeSettings();
    }
});

// ESC 键关闭
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && settingsOverlay.classList.contains('visible')) {
        closeSettings();
    }
});

async function openSettings() {
    settingsOverlay.classList.add('visible');
    await loadConfig();
}

function closeSettings() {
    settingsOverlay.classList.remove('visible');
}

// 加载配置
async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
        }
    } catch (e) {
        console.warn('加载配置失败:', e);
    }
}

// 渲染设置面板
function renderSettings() {
    // 渲染模型卡片
    const models = currentConfig.available_models || [];
    modelCards.innerHTML = models.map(m => `
        <div class="model-card ${m.id === currentConfig.model ? 'active' : ''}" data-model="${m.id}">
            <div class="model-card-icon">${m.supports_thinking ? '🧠' : '💬'}</div>
            <div class="model-card-info">
                <div class="model-card-name">${m.name}</div>
                <div class="model-card-desc">${m.description}</div>
            </div>
            <div class="model-card-check"></div>
        </div>
    `).join('');

    // 绑定模型选择事件
    modelCards.querySelectorAll('.model-card').forEach(card => {
        card.addEventListener('click', () => {
            const modelId = card.dataset.model;
            selectModel(modelId);
        });
    });

    // 渲染思考强度
    document.querySelectorAll('.thinking-option').forEach(btn => {
        const intensity = btn.dataset.intensity;
        btn.classList.toggle('active', intensity === currentConfig.thinking_intensity);
        btn.onclick = () => selectThinkingIntensity(intensity);
    });

    // 更新状态显示
    const currentModel = models.find(m => m.id === currentConfig.model);
    statusModel.textContent = currentModel ? currentModel.name : currentConfig.model;
    statusTemp.textContent = currentConfig.temperature.toFixed(1);
    statusTokens.textContent = currentConfig.max_tokens + ' tokens';
}

// 选择模型
async function selectModel(modelId) {
    if (modelId === currentConfig.model) return;

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: modelId }),
        });
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
            addSystemMessage(`已切换到 ${currentConfig.available_models.find(m => m.id === modelId)?.name || modelId}`);
        }
    } catch (e) {
        console.warn('切换模型失败:', e);
        addSystemMessage('切换模型失败，请稍后重试');
    }
}

// 选择思考强度
async function selectThinkingIntensity(intensity) {
    if (intensity === currentConfig.thinking_intensity) return;

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ thinking_intensity: intensity }),
        });
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
            const labels = { low: '轻柔', medium: '适中', high: '深度', max: '全力' };
            addSystemMessage(`思考强度已调整为「${labels[intensity] || intensity}」`);
        }
    } catch (e) {
        console.warn('调整思考强度失败:', e);
        addSystemMessage('调整失败，请稍后重试');
    }
}

// 页面加载时初始化配置
loadConfig();
```

### frontend/style.css

```css
/* ============================================
   映海 - 小汐聊天应用样式
   主题：深海夜色，温暖而魔幻
   ============================================ */

/* === 基础重置 === */
*, *::before, *::after {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

:root {
    /* 色彩系统 */
    --bg-deep: #0a1628;
    --bg-mid: #0f1f3a;
    --bg-surface: #1a2a4a;
    --glass-bg: rgba(15, 31, 58, 0.65);
    --glass-border: rgba(100, 160, 220, 0.15);
    --glass-glow: rgba(74, 140, 202, 0.25);

    --user-bubble: #4a8cca;
    --user-bubble-hover: #5a9cd8;
    --xiaoxi-bubble: rgba(20, 60, 80, 0.7);
    --xiaoxi-bubble-border: rgba(60, 140, 170, 0.25);

    --text-primary: #e8f0f8;
    --text-secondary: #8aa4c0;
    --text-muted: #5a7a9a;
    --accent: #6ab8e8;
    --accent-warm: #e8a86a;

    /* 间距 */
    --spacing-xs: 4px;
    --spacing-sm: 8px;
    --spacing-md: 16px;
    --spacing-lg: 24px;
    --spacing-xl: 32px;

    /* 圆角 */
    --radius-sm: 8px;
    --radius-md: 14px;
    --radius-lg: 20px;
    --radius-full: 50%;

    /* 动画 */
    --transition-fast: 0.2s ease;
    --transition-normal: 0.35s ease;
    --transition-slow: 0.6s ease;
}

html, body {
    height: 100%;
    overflow: hidden;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                 "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue",
                 Helvetica, Arial, sans-serif;
    color: var(--text-primary);
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
}

body {
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(160deg, var(--bg-deep) 0%, var(--bg-mid) 40%, var(--bg-surface) 100%);
}

/* === 海洋背景动画层 === */
.ocean-bg {
    position: fixed;
    inset: 0;
    pointer-events: none;
    overflow: hidden;
    z-index: 0;
}

/* 浮动气泡 */
.bubbles {
    position: absolute;
    inset: 0;
}

.bubble {
    position: absolute;
    bottom: -20px;
    border-radius: 50%;
    background: radial-gradient(
        circle at 30% 30%,
        rgba(106, 184, 232, 0.18),
        rgba(106, 184, 232, 0.04)
    );
    border: 1px solid rgba(106, 184, 232, 0.08);
    animation: bubble-rise linear infinite;
}

.bubble:nth-child(1)  { left:  5%; width: 18px; height: 18px; animation-duration: 14s;  animation-delay: 0s; }
.bubble:nth-child(2)  { left: 15%; width: 10px; height: 10px; animation-duration: 11s;  animation-delay: 2s; }
.bubble:nth-child(3)  { left: 25%; width: 24px; height: 24px; animation-duration: 18s;  animation-delay: 4s; }
.bubble:nth-child(4)  { left: 35%; width: 12px; height: 12px; animation-duration: 12s;  animation-delay: 1s; }
.bubble:nth-child(5)  { left: 45%; width: 8px;  height: 8px;  animation-duration: 10s;  animation-delay: 3s; }
.bubble:nth-child(6)  { left: 55%; width: 20px; height: 20px; animation-duration: 16s;  animation-delay: 5s; }
.bubble:nth-child(7)  { left: 62%; width: 14px; height: 14px; animation-duration: 13s;  animation-delay: 0.5s; }
.bubble:nth-child(8)  { left: 72%; width: 22px; height: 22px; animation-duration: 17s;  animation-delay: 6s; }
.bubble:nth-child(9)  { left: 80%; width: 9px;  height: 9px;  animation-duration: 11s;  animation-delay: 2.5s; }
.bubble:nth-child(10) { left: 88%; width: 16px; height: 16px; animation-duration: 15s;  animation-delay: 1.5s; }
.bubble:nth-child(11) { left: 10%; width: 6px;  height: 6px;  animation-duration: 9s;   animation-delay: 7s; }
.bubble:nth-child(12) { left: 50%; width: 26px; height: 26px; animation-duration: 20s;  animation-delay: 3.5s; }

@keyframes bubble-rise {
    0% {
        transform: translateY(0) translateX(0) scale(1);
        opacity: 0;
    }
    10% {
        opacity: 0.6;
    }
    50% {
        transform: translateY(-45vh) translateX(20px) scale(1.05);
        opacity: 0.4;
    }
    80% {
        opacity: 0.15;
    }
    100% {
        transform: translateY(-105vh) translateX(-15px) scale(0.9);
        opacity: 0;
    }
}

/* 光线效果 */
.light-rays {
    position: absolute;
    top: -30%;
    left: 50%;
    width: 120%;
    height: 80%;
    transform: translateX(-50%);
    background: radial-gradient(
        ellipse at 50% 0%,
        rgba(106, 184, 232, 0.06) 0%,
        rgba(106, 184, 232, 0.02) 40%,
        transparent 70%
    );
    animation: light-pulse 8s ease-in-out infinite;
}

@keyframes light-pulse {
    0%, 100% { opacity: 0.6; transform: translateX(-50%) scaleX(1); }
    50%       { opacity: 1;   transform: translateX(-50%) scaleX(1.08); }
}

/* === 主容器 === */
.app-container {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: 680px;
    height: 100vh;
    height: 100dvh; /* 移动端视口 */
    background: var(--glass-bg);
    backdrop-filter: blur(20px) saturate(1.4);
    -webkit-backdrop-filter: blur(20px) saturate(1.4);
    border-left: 1px solid var(--glass-border);
    border-right: 1px solid var(--glass-border);
    box-shadow:
        0 0 60px rgba(0, 0, 0, 0.3),
        inset 0 0 60px rgba(106, 184, 232, 0.02);
}

/* === 头部 === */
.app-header {
    position: relative;
    flex-shrink: 0;
    padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-md);
    background: linear-gradient(
        180deg,
        rgba(10, 22, 40, 0.9) 0%,
        rgba(15, 31, 58, 0.7) 100%
    );
    border-bottom: 1px solid var(--glass-border);
    overflow: hidden;
}

.header-content {
    display: flex;
    align-items: center;
    justify-content: space-between;
    position: relative;
    z-index: 1;
}

.title-group h1 {
    font-size: 1.6rem;
    font-weight: 700;
    letter-spacing: 2px;
    background: linear-gradient(135deg, var(--accent) 0%, #a0d4f0 50%, var(--accent-warm) 100%);
    background-clip: text;
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    text-shadow: none;
    animation: title-shimmer 6s ease-in-out infinite;
    background-size: 200% 200%;
}

@keyframes title-shimmer {
    0%, 100% { background-position: 0% 50%; }
    50%       { background-position: 100% 50%; }
}

.subtitle {
    font-size: 0.82rem;
    color: var(--text-muted);
    margin-top: 2px;
    letter-spacing: 1px;
}

.session-info {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 0.72rem;
    color: var(--text-muted);
    background: rgba(106, 184, 232, 0.08);
    padding: 4px 10px;
    border-radius: 20px;
    border: 1px solid rgba(106, 184, 232, 0.1);
}

.header-right {
    display: flex;
    align-items: center;
    gap: 8px;
}

.session-label {
    opacity: 0.7;
}

.session-id {
    font-family: "SF Mono", "Fira Code", "Consolas", monospace;
    color: var(--accent);
    letter-spacing: 0.5px;
}

/* 头部底部波浪装饰线 */
.header-wave {
    position: absolute;
    bottom: -1px;
    left: 0;
    width: 100%;
    height: 3px;
    background: linear-gradient(
        90deg,
        transparent 0%,
        rgba(106, 184, 232, 0.3) 25%,
        rgba(106, 184, 232, 0.5) 50%,
        rgba(106, 184, 232, 0.3) 75%,
        transparent 100%
    );
    background-size: 200% 100%;
    animation: wave-line 4s ease-in-out infinite;
}

@keyframes wave-line {
    0%, 100% { background-position: 0% 50%; }
    50%       { background-position: 100% 50%; }
}

/* === 聊天区域 === */
.chat-area {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: var(--spacing-lg) var(--spacing-md);
    scroll-behavior: smooth;
}

.chat-messages {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
    min-height: 100%;
    justify-content: flex-end;
}

/* === 消息通用样式 === */
.message {
    display: flex;
    gap: 10px;
    max-width: 88%;
    animation: message-in 0.45s cubic-bezier(0.22, 1, 0.36, 1) forwards;
    opacity: 0;
    transform: translateY(12px);
}

@keyframes message-in {
    to {
        opacity: 1;
        transform: translateY(0);
    }
}

/* 小汐消息 - 左对齐 */
.message.xiaoxi {
    align-self: flex-start;
    flex-direction: row;
}

.message-avatar {
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border-radius: var(--radius-full);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 1.2rem;
    background: rgba(20, 60, 80, 0.6);
    border: 1px solid var(--xiaoxi-bubble-border);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
    align-self: flex-end;
}

.message-bubble {
    padding: 12px 16px;
    border-radius: var(--radius-md);
    line-height: 1.65;
    font-size: 0.92rem;
    word-break: break-word;
    white-space: pre-wrap;
    position: relative;
}

/* 小汐气泡 */
.message.xiaoxi .message-bubble {
    background: var(--xiaoxi-bubble);
    border: 1px solid var(--xiaoxi-bubble-border);
    border-radius: var(--radius-md) var(--radius-md) var(--radius-md) 4px;
    color: var(--text-primary);
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
}

/* 用户消息 - 右对齐 */
.message.user {
    align-self: flex-end;
    flex-direction: row-reverse;
}

.message.user .message-bubble {
    background: linear-gradient(135deg, var(--user-bubble) 0%, #3a7ab8 100%);
    border-radius: var(--radius-md) var(--radius-md) 4px var(--radius-md);
    color: #fff;
    box-shadow: 0 2px 12px rgba(74, 140, 202, 0.25);
    transition: background var(--transition-fast);
}

.message.user .message-bubble:hover {
    background: linear-gradient(135deg, var(--user-bubble-hover) 0%, var(--user-bubble) 100%);
}

/* 消息时间 */
.message-time {
    font-size: 0.68rem;
    color: var(--text-muted);
    margin-top: 4px;
    opacity: 0;
    transition: opacity var(--transition-fast);
}

.message:hover .message-time {
    opacity: 1;
}

.message.user .message-time {
    text-align: right;
}

/* === 打字指示器 === */
.typing-indicator {
    display: flex;
    gap: 10px;
    align-self: flex-start;
    max-width: 88%;
    padding: 0 var(--spacing-md);
    margin-top: var(--spacing-sm);
    animation: message-in 0.3s ease forwards;
}

.typing-avatar {
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border-radius: var(--radius-full);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 1.2rem;
    background: rgba(20, 60, 80, 0.6);
    border: 1px solid var(--xiaoxi-bubble-border);
    align-self: flex-end;
}

.typing-bubble {
    padding: 14px 20px;
    background: var(--xiaoxi-bubble);
    border: 1px solid var(--xiaoxi-bubble-border);
    border-radius: var(--radius-md) var(--radius-md) var(--radius-md) 4px;
}

.typing-dots {
    display: flex;
    gap: 5px;
    align-items: center;
}

.typing-dots span {
    width: 7px;
    height: 7px;
    border-radius: var(--radius-full);
    background: var(--accent);
    opacity: 0.4;
    animation: typing-bounce 1.4s ease-in-out infinite;
}

.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing-bounce {
    0%, 60%, 100% {
        transform: translateY(0);
        opacity: 0.4;
    }
    30% {
        transform: translateY(-6px);
        opacity: 1;
    }
}

/* === 输入区域 === */
.input-area {
    flex-shrink: 0;
    padding: var(--spacing-md) var(--spacing-md) var(--spacing-sm);
    background: linear-gradient(
        0deg,
        rgba(10, 22, 40, 0.95) 0%,
        rgba(15, 31, 58, 0.8) 100%
    );
    border-top: 1px solid var(--glass-border);
}

.input-wrapper {
    display: flex;
    align-items: flex-end;
    gap: 10px;
    background: rgba(10, 22, 40, 0.7);
    border: 1px solid rgba(106, 184, 232, 0.12);
    border-radius: var(--radius-lg);
    padding: 8px 8px 8px 16px;
    transition: border-color var(--transition-normal), box-shadow var(--transition-normal);
}

.input-wrapper:focus-within {
    border-color: rgba(106, 184, 232, 0.4);
    box-shadow:
        0 0 20px rgba(106, 184, 232, 0.1),
        inset 0 0 20px rgba(106, 184, 232, 0.03);
}

.message-input {
    flex: 1;
    border: none;
    outline: none;
    background: transparent;
    color: var(--text-primary);
    font-size: 0.92rem;
    line-height: 1.5;
    resize: none;
    max-height: 120px;
    font-family: inherit;
    padding: 6px 0;
}

.message-input::placeholder {
    color: var(--text-muted);
    font-style: italic;
}

.send-btn {
    flex-shrink: 0;
    width: 40px;
    height: 40px;
    border: none;
    border-radius: var(--radius-full);
    background: linear-gradient(135deg, var(--user-bubble) 0%, #3a7ab8 100%);
    color: #fff;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all var(--transition-fast);
    box-shadow: 0 2px 8px rgba(74, 140, 202, 0.3);
}

.send-btn:hover:not(:disabled) {
    transform: scale(1.08);
    box-shadow: 0 4px 16px rgba(74, 140, 202, 0.45);
}

.send-btn:active:not(:disabled) {
    transform: scale(0.95);
}

.send-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
    transform: none;
}

/* 流式输出中：发送按钮变为停止按钮 */
.send-btn.streaming {
    background: linear-gradient(135deg, #c0564a 0%, #a04038 100%);
}

.send-btn.streaming .send-icon {
    display: none;
}

.send-btn.streaming::after {
    content: '';
    width: 14px;
    height: 14px;
    border-radius: 3px;
    background: #fff;
    display: block;
}

.send-icon {
    width: 18px;
    height: 18px;
}

.input-hint {
    text-align: center;
    font-size: 0.7rem;
    color: var(--text-muted);
    margin-top: 6px;
    opacity: 0.6;
}

/* === 自定义滚动条 === */
.chat-area::-webkit-scrollbar {
    width: 5px;
}

.chat-area::-webkit-scrollbar-track {
    background: transparent;
}

.chat-area::-webkit-scrollbar-thumb {
    background: rgba(106, 184, 232, 0.15);
    border-radius: 10px;
}

.chat-area::-webkit-scrollbar-thumb:hover {
    background: rgba(106, 184, 232, 0.3);
}

/* === 欢迎消息特殊样式 === */
.message.welcome .message-bubble {
    background: linear-gradient(
        135deg,
        rgba(20, 60, 80, 0.7) 0%,
        rgba(30, 70, 100, 0.5) 100%
    );
    border: 1px solid rgba(106, 184, 232, 0.2);
    box-shadow:
        0 4px 20px rgba(0, 0, 0, 0.2),
        inset 0 1px 0 rgba(106, 184, 232, 0.1);
}

/* === 错误消息样式 === */
.message.error .message-bubble {
    background: rgba(80, 30, 30, 0.5);
    border: 1px solid rgba(200, 80, 80, 0.3);
    color: #e8a0a0;
}

/* === 系统提示消息 === */
.system-message {
    text-align: center;
    font-size: 0.78rem;
    color: var(--text-muted);
    padding: 8px 16px;
    opacity: 0.7;
    animation: message-in 0.5s ease forwards;
    opacity: 0;
}

/* === 响应式设计 === */
@media (max-width: 768px) {
    .app-container {
        max-width: 100%;
        border-left: none;
        border-right: none;
    }

    .app-header {
        padding: var(--spacing-md) var(--spacing-md) var(--spacing-sm);
    }

    .title-group h1 {
        font-size: 1.3rem;
    }

    .message {
        max-width: 92%;
    }

    .input-area {
        padding: var(--spacing-sm);
        padding-bottom: max(var(--spacing-sm), env(safe-area-inset-bottom));
    }
}

@media (max-width: 480px) {
    .session-info {
        display: none;
    }

    .title-group h1 {
        font-size: 1.15rem;
    }

    .message-bubble {
        font-size: 0.88rem;
        padding: 10px 14px;
    }

    .thinking-options {
        flex-direction: column;
    }

    .thinking-option {
        flex-direction: row;
        gap: 10px;
        padding: 10px 12px;
    }

    .thinking-icon {
        margin-bottom: 0;
    }

    .thinking-desc {
        text-align: left;
    }

    .settings-panel {
        width: 95%;
        max-height: 90vh;
    }
}

/* === 辅助功能 === */
@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        animation-duration: 0.01ms !important;
        transition-duration: 0.01ms !important;
    }
}

/* === 文本选中颜色 === */
::selection {
    background: rgba(106, 184, 232, 0.3);
    color: #fff;
}

/* === 设置按钮（头部右上角） === */
.settings-btn {
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border: 1px solid rgba(106, 184, 232, 0.15);
    border-radius: var(--radius-full);
    background: rgba(106, 184, 232, 0.08);
    color: var(--text-secondary);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all var(--transition-fast);
    margin-left: 8px;
}

.settings-btn:hover {
    background: rgba(106, 184, 232, 0.18);
    color: var(--accent);
    border-color: rgba(106, 184, 232, 0.3);
    transform: rotate(30deg);
}

.settings-icon {
    width: 18px;
    height: 18px;
}

/* === 设置面板遮罩层 === */
.settings-overlay {
    position: fixed;
    inset: 0;
    z-index: 100;
    background: rgba(5, 12, 24, 0.7);
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
    display: flex;
    align-items: center;
    justify-content: center;
    opacity: 0;
    visibility: hidden;
    transition: opacity 0.3s ease, visibility 0.3s ease;
}

.settings-overlay.visible {
    opacity: 1;
    visibility: visible;
}

/* === 设置面板主体 === */
.settings-panel {
    width: 90%;
    max-width: 420px;
    max-height: 85vh;
    background: linear-gradient(160deg, rgba(15, 31, 58, 0.95) 0%, rgba(20, 40, 70, 0.95) 100%);
    border: 1px solid rgba(106, 184, 232, 0.2);
    border-radius: var(--radius-lg);
    box-shadow:
        0 20px 60px rgba(0, 0, 0, 0.5),
        0 0 40px rgba(106, 184, 232, 0.08),
        inset 0 1px 0 rgba(106, 184, 232, 0.1);
    overflow-y: auto;
    transform: translateY(20px) scale(0.96);
    transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1);
}

.settings-overlay.visible .settings-panel {
    transform: translateY(0) scale(1);
}

.settings-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-md);
    border-bottom: 1px solid var(--glass-border);
}

.settings-header h2 {
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--text-primary);
    letter-spacing: 1px;
}

.settings-close-btn {
    width: 32px;
    height: 32px;
    border: none;
    border-radius: var(--radius-full);
    background: rgba(106, 184, 232, 0.08);
    color: var(--text-muted);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all var(--transition-fast);
}

.settings-close-btn:hover {
    background: rgba(200, 80, 80, 0.15);
    color: #e8a0a0;
}

.settings-body {
    padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
}

/* === 设置分区 === */
.settings-section {
    margin-bottom: var(--spacing-lg);
}

.settings-section:last-child {
    margin-bottom: 0;
}

.settings-label {
    display: block;
    font-size: 0.82rem;
    font-weight: 600;
    color: var(--text-secondary);
    margin-bottom: var(--spacing-sm);
    letter-spacing: 0.5px;
    text-transform: uppercase;
}

/* === 模型卡片 === */
.model-cards {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
}

.model-card {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    border: 1px solid rgba(106, 184, 232, 0.12);
    border-radius: var(--radius-md);
    background: rgba(10, 22, 40, 0.5);
    cursor: pointer;
    transition: all var(--transition-fast);
}

.model-card:hover {
    background: rgba(106, 184, 232, 0.08);
    border-color: rgba(106, 184, 232, 0.25);
}

.model-card.active {
    background: rgba(106, 184, 232, 0.12);
    border-color: rgba(106, 184, 232, 0.4);
    box-shadow: 0 0 16px rgba(106, 184, 232, 0.08);
}

.model-card-icon {
    font-size: 1.4rem;
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(106, 184, 232, 0.08);
    border-radius: var(--radius-sm);
}

.model-card-info {
    flex: 1;
    min-width: 0;
}

.model-card-name {
    font-size: 0.9rem;
    font-weight: 600;
    color: var(--text-primary);
    margin-bottom: 2px;
}

.model-card-desc {
    font-size: 0.76rem;
    color: var(--text-muted);
    line-height: 1.3;
}

.model-card-check {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    border-radius: var(--radius-full);
    border: 2px solid rgba(106, 184, 232, 0.2);
    transition: all var(--transition-fast);
    display: flex;
    align-items: center;
    justify-content: center;
}

.model-card.active .model-card-check {
    border-color: var(--accent);
    background: var(--accent);
}

.model-card.active .model-card-check::after {
    content: '';
    width: 8px;
    height: 5px;
    border-left: 2px solid #fff;
    border-bottom: 2px solid #fff;
    transform: rotate(-45deg) translateY(-1px);
}

/* === 思考强度选项 === */
.thinking-options {
    display: flex;
    gap: var(--spacing-sm);
}

.thinking-option {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 10px 6px;
    border: 1px solid rgba(106, 184, 232, 0.12);
    border-radius: var(--radius-md);
    background: rgba(10, 22, 40, 0.5);
    color: var(--text-secondary);
    cursor: pointer;
    transition: all var(--transition-fast);
    font-family: inherit;
}

.thinking-option:hover {
    background: rgba(106, 184, 232, 0.08);
    border-color: rgba(106, 184, 232, 0.25);
}

.thinking-option.active {
    background: rgba(106, 184, 232, 0.12);
    border-color: rgba(106, 184, 232, 0.4);
    color: var(--text-primary);
    box-shadow: 0 0 16px rgba(106, 184, 232, 0.08);
}

.thinking-icon {
    font-size: 1.3rem;
    margin-bottom: 2px;
}

.thinking-text {
    font-size: 0.85rem;
    font-weight: 600;
}

.thinking-desc {
    font-size: 0.68rem;
    color: var(--text-muted);
    text-align: center;
    line-height: 1.3;
}

/* === 状态信息 === */
.settings-status {
    padding-top: var(--spacing-md);
    border-top: 1px solid var(--glass-border);
}

.status-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 0;
}

.status-label {
    font-size: 0.8rem;
    color: var(--text-muted);
}

.status-value {
    font-size: 0.82rem;
    font-family: "SF Mono", "Fira Code", "Consolas", monospace;
    color: var(--accent);
}

.status-hint {
    font-size: 0.7rem;
    color: var(--text-muted);
    margin-top: var(--spacing-sm);
    padding: 8px 10px;
    background: rgba(106, 184, 232, 0.06);
    border-radius: var(--radius-sm);
    border: 1px solid rgba(106, 184, 232, 0.08);
    line-height: 1.5;
}

/* === 设置面板滚动条 === */
.settings-panel::-webkit-scrollbar {
    width: 4px;
}

.settings-panel::-webkit-scrollbar-track {
    background: transparent;
}

.settings-panel::-webkit-scrollbar-thumb {
    background: rgba(106, 184, 232, 0.15);
    border-radius: 10px;
}

/* === Markdown 渲染样式 === */
.message-bubble h1,
.message-bubble h2,
.message-bubble h3 {
    margin: 0.5em 0 0.3em;
    font-weight: 600;
    line-height: 1.3;
}

.message-bubble h1 { font-size: 1.15em; }
.message-bubble h2 { font-size: 1.05em; }
.message-bubble h3 { font-size: 1em; }

.message-bubble p {
    margin: 0.3em 0;
}

.message-bubble p:first-child { margin-top: 0; }
.message-bubble p:last-child { margin-bottom: 0; }

.message-bubble strong {
    font-weight: 600;
    color: var(--accent);
}

.message-bubble em {
    font-style: italic;
    opacity: 0.9;
}

.message-bubble a {
    color: var(--accent);
    text-decoration: underline;
    text-underline-offset: 2px;
}

.message-bubble a:hover {
    color: var(--accent-warm);
}

.message-bubble code {
    background: rgba(0, 0, 0, 0.25);
    padding: 1px 5px;
    border-radius: 4px;
    font-family: "SF Mono", "Fira Code", "Consolas", monospace;
    font-size: 0.88em;
}

.message-bubble pre {
    background: rgba(0, 0, 0, 0.3);
    padding: 10px 14px;
    border-radius: 8px;
    overflow-x: auto;
    margin: 0.5em 0;
    border: 1px solid rgba(106, 184, 232, 0.1);
}

.message-bubble pre code {
    background: none;
    padding: 0;
    font-size: 0.85em;
    line-height: 1.5;
}

.message-bubble ul,
.message-bubble ol {
    margin: 0.3em 0;
    padding-left: 1.5em;
}

.message-bubble li {
    margin: 0.15em 0;
}

.message-bubble blockquote {
    border-left: 3px solid var(--accent);
    padding-left: 12px;
    margin: 0.5em 0;
    opacity: 0.85;
    color: var(--text-secondary);
}

.message-bubble hr {
    border: none;
    border-top: 1px solid var(--glass-border);
    margin: 0.8em 0;
}

.message-bubble table {
    border-collapse: collapse;
    margin: 0.5em 0;
    font-size: 0.88em;
    width: 100%;
}

.message-bubble th,
.message-bubble td {
    border: 1px solid rgba(106, 184, 232, 0.15);
    padding: 6px 10px;
    text-align: left;
}

.message-bubble th {
    background: rgba(0, 0, 0, 0.2);
    font-weight: 600;
}
```

---

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `POST /api/chat` | POST | 非流式对话，返回完整 AI 回复 |
| `POST /api/chat/stream` | POST | 流式对话（SSE），token 级输出 |
| `GET /api/config` | GET | 获取控制台配置（模型、思考强度） |
| `POST /api/config` | POST | 更新控制台配置（切换模型、调整参数） |
| `GET /api/health` | GET | 健康检查 |
| `GET /docs` | GET | Swagger 文档（FastAPI 内置） |

### 请求示例

```json
// POST /api/chat/stream
{
    "message": "今天天气怎么样",
    "session_id": "abc12345"
}

// SSE 响应（每行一个事件）
data: {"content": "冷冷"}
data: {"content": "今天"}
data: {"type": "tool_start", "name": "get_weather", "input": {"city": "香港"}}
data: {"type": "tool_end", "name": "get_weather", "output": "🌤️ 香港实时天气..."}
data: {"content": "香港现在"}
data: {"content": "好热呀~"}
data: [DONE]
```

---

*文档生成时间：2026-07-18 · v0.2.1*
