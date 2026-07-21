"""小汐的后端 — FastAPI 应用工厂 + 生命周期管理。"""
import datetime
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from version import __version__
from providers.deepseek import create_deepseek_llm
from agent.graph import build_agent, make_pre_model_hook
from agent.soul import build_system_prompt
from tools import get_all_tools, set_memory_manager, set_diary_db_conn, set_http_client
from memory.manager import MemoryManager
from memory.narrative import NarrativeBuilder
from api.routes.chat import router as chat_router
from api.routes.config import router as config_router
from api.routes.push import router as push_router
from api.middleware import AuthAndRateLimitMiddleware, RejectWebSocketMiddleware
from database.core import init_db_connection, get_db, close_db_connection, init_db
from config.settings import get_settings
from utils.logger import setup_logging, get_logger
import heartbeat

log = get_logger(__name__)


# ── 生命周期 ────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时初始化所有组件，关闭时清理。"""
    setup_logging()
    log.info("小汐正在醒来……")

    # 0. 启动校验 — 关键 API Key 必须存在，否则直接拒绝启动
    settings = get_settings()
    if not settings.deepseek_api_key:
        raise RuntimeError(
            "启动失败：DEEPSEEK_API_KEY 未配置！\n"
            "   请在 backend/config/.env 中填写你的 DeepSeek API Key。\n"
            "   获取地址：https://platform.deepseek.com/"
        )
    log.info("DEEPSEEK_API_KEY 已配置")

    # 1. 业务数据库（userdata.db）— 建立全局长连接 + PRAGMA + 建表建索引
    await init_db_connection()
    conn = await get_db()
    await init_db(conn)
    log.info("userdata.db 已初始化（WAL 模式，长连接）")

    # 2. 记忆系统（注入全局连接）
    memory_mgr = MemoryManager(conn)
    narrative_builder = NarrativeBuilder(memory_mgr)
    set_memory_manager(memory_mgr)
    set_diary_db_conn(conn)
    app.state.memory_manager = memory_mgr
    app.state.narrative_builder = narrative_builder
    log.info("%s 条记忆已加载", await memory_mgr.count())

    # 3. 共享 HTTP 客户端（工具复用，由 lifespan 管理生命周期）
    shared_http_client = httpx.AsyncClient(timeout=10)
    app.state.http_client = shared_http_client
    set_http_client(shared_http_client)
    log.info("共享 HTTP 客户端已创建")

    # 4. 工具集
    tools = get_all_tools()
    app.state.tools = tools
    log.info("%s 个工具已加载", len(tools))

    # 5. LLM 模型
    model = create_deepseek_llm()
    log.info("DeepSeek 模型已连接")

    # 6. 系统提示词（不含记忆叙事——叙事由 chat.py 按请求动态注入）
    system_prompt = await build_system_prompt()
    app.state.system_prompt = system_prompt

    # 保存模型引用，供心跳刷新 / 配置变更时重建 agent 使用
    app.state.model = model

    # 7. Agent 图 + Checkpointer + pre_model_hook
    db_conn = None
    try:
        import aiosqlite
        from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
        db_path = Path(__file__).parent.parent / "data" / "checkpoints.db"
        db_path.parent.mkdir(parents=True, exist_ok=True)
        db_conn = await aiosqlite.connect(str(db_path))
        checkpointer = AsyncSqliteSaver(db_conn)
        await checkpointer.setup()
        log.info("checkpoint: sqlite (data/checkpoints.db)")
    except Exception as e:
        # Sqlite 不可用时回退到内存（重启丢失对话，但不会崩溃）
        from langgraph.checkpoint.memory import MemorySaver
        checkpointer = MemorySaver()
        log.warning("checkpoint: memory (sqlite 不可用: %s，对话重启后丢失)", e)

    # 创建 pre_model_hook（记忆注入 + 消息裁剪）
    # 记忆通过 llm_input_messages 注入，不进入 checkpoint
    pre_model_hook = make_pre_model_hook(narrative_builder)

    agent = build_agent(model, tools, system_prompt, checkpointer, pre_model_hook)
    app.state.agent = agent
    app.state.checkpointer = checkpointer

    # 8. 心跳调度器（传入 app 引用，使心跳可以刷新 system prompt）
    heartbeat.scheduler.add_job(
        lambda: heartbeat.check_and_proactive_message(app),
        "interval",
        hours=2,
        id="proactive_check",
        next_run_time=datetime.datetime.now(heartbeat._HKT),
    )
    heartbeat.scheduler.start()
    log.info("心跳已启动（每 2 小时，首次立即触发）")

    log.info("小汐醒啦！ -> http://127.0.0.1:8000")

    yield

    # 清理
    log.info("小汐去睡觉了……")
    heartbeat.scheduler.shutdown(wait=False)
    app.state.agent = None
    # 关闭共享 HTTP 客户端
    await shared_http_client.aclose()
    # 关闭 LangGraph checkpoint 连接
    if db_conn is not None:
        try:
            await db_conn.close()
        except Exception as e:
            log.warning("checkpoint 连接关闭失败：%s", e)
    # 关闭业务数据库全局连接
    await close_db_connection()


# ── 应用工厂 ────────────────────────────────────────────────

def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="小汐 API",
        description="一只住在网页里的小鲸鱼",
        version=__version__,
        lifespan=lifespan,
    )

    # CORS — 从 settings 读取允许的前端来源
    origins = [
        o.strip()
        for o in settings.cors_origins.split(",")
        if o.strip()
    ]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Content-Type", "Authorization"],
    )

    # WebSocket 拒绝中间件
    app.add_middleware(RejectWebSocketMiddleware)

    # 认证 + 限流中间件（在 CORS 之后、路由之前）
    app.add_middleware(AuthAndRateLimitMiddleware, api_token=settings.xiaoxi_api_token)

    # REST 路由
    app.include_router(chat_router, prefix="/api")
    app.include_router(config_router, prefix="/api")
    app.include_router(push_router, prefix="/api")

    # 前端静态文件（Next.js 生产导出；开发模式下前端独立运行在 :3000，无需此挂载）
    frontend_dir = Path(__file__).parent.parent.parent / "web" / "out"
    if frontend_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(frontend_dir), html=True), name="frontend")

    return app


# 模块级 app 实例（兼容 main.py 的直接 import）
app = create_app()
