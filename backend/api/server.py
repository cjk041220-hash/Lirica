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
import heartbeat


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

    # 6. 心跳调度器
    heartbeat.scheduler.add_job(
        heartbeat.check_and_proactive_message,
        "interval",
        hours=2,
        id="proactive_check",
    )
    heartbeat.scheduler.start()
    _p("  [heartbeat] 心跳已启动（每 2 小时）")

    _p("[xixi] 小汐醒啦！")
    _p("  -> http://127.0.0.1:8000")
    _p("  -> http://127.0.0.1:8000/api/health")
    _p()

    yield

    # 清理
    _p("[xixi] 小汐去睡觉了……")
    heartbeat.scheduler.shutdown(wait=False)
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
