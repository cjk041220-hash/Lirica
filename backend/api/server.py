"""小汐的后端 — FastAPI 应用工厂 + 生命周期管理。"""
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

import heartbeat
from agent.graph import build_agent, make_pre_model_hook
from agent.soul import build_system_prompt
from api.middleware import (
    AuthAndRateLimitMiddleware,
    RejectWebSocketMiddleware,
    RequestBodyLimitMiddleware,
    SecurityHeadersMiddleware,
)
from api.middleware.request_id import RequestIdMiddleware
from api.routes.chat import router as chat_router
from api.routes.config import create_llm_from_runtime_config
from api.routes.config import router as config_router
from api.routes.events import router as events_router
from api.routes.presence import router as presence_router
from api.routes.push import router as push_router
from api.routes.sessions import router as sessions_router
from api.routes.status import router as status_router
from config.settings import get_settings
from database.core import close_db_connection, get_db, init_db, init_db_connection
from memory.manager import MemoryManager
from memory.narrative import NarrativeBuilder
from tools import (
    get_all_tools,
    set_cycle_prompt_refresh_callback,
    set_diary_db_conn,
    set_http_client,
    set_memory_manager,
)
from utils.logger import get_logger, setup_logging
from version import __version__

log = get_logger(__name__)


# ── 生命周期 ────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时初始化所有组件，关闭时清理。"""
    # 日志配置走 Settings 透传：.env 里的 LOG_FILE/LOG_LEVEL 不会进
    # os.environ（pydantic extra=ignore），只读环境变量会把它们静默吞掉。
    # 显式参数与真实环境变量等效（Docker compose env 路径不受影响）。
    settings = get_settings()
    setup_logging(
        log_file=settings.log_file or None,
        log_level_name=settings.log_level or None,
    )
    log.info("小汐正在醒来……")

    # 0. 启动校验 — 关键 API Key 必须存在，否则直接拒绝启动
    if not settings.deepseek_api_key:
        raise RuntimeError(
            "启动失败：DEEPSEEK_API_KEY 未配置！\n"
            "   请在 backend/config/.env 中填写你的 DeepSeek API Key。\n"
            "   获取地址：https://platform.deepseek.com/"
        )
    log.info("DEEPSEEK_API_KEY 已配置")

    # 0.5 启动加固（fail closed）— 见 utils/security.py：
    #   - 非回环绑定 + 空 token → 拒绝启动（除非显式 XIAOXI_ALLOW_INSECURE=1）
    #   - workers > 1 → 拒绝启动（心跳/限流/配置都是进程内状态）
    from utils.security import assert_safe_startup, detect_bind_host, detect_workers

    assert_safe_startup(
        bind_host=detect_bind_host(),
        api_token=settings.xiaoxi_api_token,
        allow_insecure=settings.xiaoxi_allow_insecure,
        workers=detect_workers(),
    )

    # 认证状态告警 — 空 token 仅适用于本机开发，暴露公网极度危险
    if not settings.xiaoxi_api_token:
        log.warning(
            "XIAOXI_API_TOKEN 未设置，API 处于无认证状态！"
            "仅限本机使用；暴露到公网前必须设置 token 或前置反向代理认证。"
        )

    # Web Push 未配置 — 主动消息无法推送系统通知，只能在页面打开时送达
    if not settings.vapid_public_key or not settings.vapid_private_key:
        log.warning(
            "VAPID 密钥未配置，小汐的主动消息无法发送浏览器系统通知"
            "（页面打开时仍会注入对话）。"
            "生成密钥：npx web-push generate-vapid-keys，"
            "填入 VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY。"
        )

    # 公网部署自检（纯日志，不改行为）——把关键开关在启动时显式打印一遍，
    # 上线当天一眼看出 token / CORS / 代理信任是否配错
    bind_host = detect_bind_host()
    tok = settings.xiaoxi_api_token
    masked = (tok[:4] + "…" + tok[-4:]) if len(tok) > 8 else ("已设置" if tok else "未设置")
    log.info(
        "── 公网部署自检 ── bind=%s | token=%s | allow_insecure=%s | trust_proxy=%s | cors=%s",
        bind_host, masked, settings.xiaoxi_allow_insecure,
        settings.trust_proxy, settings.cors_origins,
    )
    if any(o.strip() == "*" for o in settings.cors_origins.split(",")):
        log.warning("CORS_ORIGINS 含通配符 * —— 公网部署请改为精确域名！")
    if settings.trust_proxy:
        log.info("TRUST_PROXY=True：仅当反向代理正确覆写 X-Forwarded-For 时，限流 IP 才真实")

    # 1. 业务数据库（userdata.db）— 建立全局长连接 + PRAGMA + 建表建索引
    await init_db_connection()
    conn = await get_db()
    await init_db(conn)
    log.info("userdata.db 已初始化（WAL 模式，长连接）")

    # 1.5 孤儿 pending 主动消息清扫（体检 T1-6）：上次进程在「存 pending →
    # 推送 → 改状态」之间崩溃时，消息会永久停在 pending 而对冷冷不可见。
    # 此刻无在途推送，无条件降级 stored 永远正确（详见函数 docstring）
    try:
        await heartbeat.sweep_orphan_proactive_messages()
    except Exception as e:
        log.warning("孤儿 pending 清扫失败（不影响启动）：%s", e)

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

    # 生理期工具写账本后立即刷新提示词（不等每小时看门狗）：
    # 冷冷刚说完「其实是昨天来的」，提示词与日历上的日子就该跟着变。
    # 失败只降级——工具话术照常返回，看门狗下一小时兜底重算。
    async def _refresh_prompt_after_cycle_change():
        from api.routes.config import rebuild_agent_for_app  # 惰性 import 防环
        await rebuild_agent_for_app(app)

    set_cycle_prompt_refresh_callback(_refresh_prompt_after_cycle_change)

    # 5. LLM 模型 — 从运行时配置创建，保证与 GET /api/config 报告的
    #    默认值（medium / 0.7 / 8192 / flash）完全一致（单一数据源）
    model = create_llm_from_runtime_config()
    log.info("DeepSeek 模型已连接")

    # 6. 系统提示词（不含记忆叙事——叙事由 chat.py 按请求动态注入）
    system_prompt = await build_system_prompt()
    app.state.system_prompt = system_prompt

    # 保存模型引用，供心跳刷新 / 配置变更时重建 agent 使用
    # （rebuild_agent_for_app 会在每次重建时同步更新此引用）
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
        # Sqlite 不可用时回退到内存（重启丢失对话，但不会崩溃）。
        # 回退前必须关掉已建立的连接——connect 成功而 setup 失败时
        # db_conn 已打开，不关闭则句柄泄漏（体检 S4）
        if db_conn is not None:
            try:
                await db_conn.close()
            except Exception:
                pass
        from langgraph.checkpoint.memory import MemorySaver
        checkpointer = MemorySaver()
        log.warning("checkpoint: memory (sqlite 不可用: %s，对话重启后丢失)", e)

    # 创建 pre_model_hook（记忆注入 + 消息裁剪）
    # 实测（批① Task 2 Step 0）：langchain 1.3.14 的 create_agent 中
    # llm_input_messages 不是 state channel——注入改经 awrap_model_call
    # 就地改写 request.messages（纯内存、不进 checkpoint，与人格锚同路）
    pre_model_hook = make_pre_model_hook(narrative_builder)

    agent = build_agent(
        model, tools, system_prompt, checkpointer, pre_model_hook,
        anchor_interval=8,  # 人格重锚（批①）：每 8 次模型调用重申人格锚，与 config.py 重建路径一致
    )
    app.state.agent = agent
    app.state.checkpointer = checkpointer

    # 8. 心跳调度器 —— 统一注册入口见 heartbeat.register_jobs。
    #    异步任务必须以协程函数直接注册（lambda 包裹会导致协程被丢弃、
    #    主动消息链路整体静默，见 register_jobs 文档字符串与回归测试）
    heartbeat.register_jobs(app)
    # 防重入：模块级 scheduler 跨 app 实例存活（测试姿势下同进程
    # 可能多次跑 lifespan），二次 start() 会抛 SchedulerAlreadyRunningError
    if not heartbeat.scheduler.running:
        heartbeat.scheduler.start()
    log.info("心跳已启动（每 2 小时 + 每日 8:00 早安）")

    log.info("小汐醒啦！ -> http://%s:%d", settings.xiaoxi_bind_host, settings.xiaoxi_port)

    yield

    # 清理
    log.info("小汐去睡觉了……")
    heartbeat.scheduler.shutdown(wait=False)
    app.state.agent = None
    # 关闭共享 HTTP 客户端
    await shared_http_client.aclose()
    # 关闭 Tavily 搜索客户端单例（内部 httpx 连接池）
    from tools.search import close_tavily_client
    await close_tavily_client()
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

    # 设了 token = 有部署意图 = 生产 → 关闭 docs，避免 API schema
    # （含 /api/memories/clear 等危险端点的参数说明）公网裸奔；
    # 开发模式（无 token、仅本机）保留 /docs 作调试面。
    # 与 assert_safe_startup 的 fail-closed「token=生产意图」语义对称。
    docs_on = not settings.xiaoxi_api_token
    app = FastAPI(
        title="小汐 API",
        description="一只住在网页里的小鲸鱼",
        version=__version__,
        lifespan=lifespan,
        docs_url="/docs" if docs_on else None,
        redoc_url="/redoc" if docs_on else None,
        openapi_url="/openapi.json" if docs_on else None,
    )

    # CORS 来源 — 从 settings 读取
    origins = [
        o.strip()
        for o in settings.cors_origins.split(",")
        if o.strip()
    ]

    # 中间件注册顺序说明：Starlette add_middleware 是头插法，
    # 最后注册的在最外层。执行顺序须为 CORS（最外层）→ 认证限流 →
    # WebSocket 拒绝 → 路由，这样认证/限流短路返回的 401/429
    # 也会带上 CORS 头，跨域前端才能读到错误体并走 401 引导流程。
    # 因此 CORS 放在最后注册。
    # 请求体上限最先注册 = 最内层：413 响应向外穿过 SecurityHeaders /
    # RequestId 时照常拿到安全头与 rid；限流计数在 Auth 层先行，超大
    # 请求一样占桶（防无认证洪泛刷大请求体）。
    app.add_middleware(RequestBodyLimitMiddleware)
    app.add_middleware(RejectWebSocketMiddleware)
    app.add_middleware(
        AuthAndRateLimitMiddleware,
        api_token=settings.xiaoxi_api_token,
        trust_proxy=settings.trust_proxy,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        # PATCH 必须入列：chat 路由的记忆编辑是 @router.patch
        # （routes/chat.py），跨域前端（独立部署 + CORS_ORIGINS）调它
        # 时预检缺 PATCH 会被浏览器直接挡死；同源部署不触发预检故
        # 长期未暴露。events 路由当年为此绕用 POST，此处统一口径放行。
        allow_methods=["GET", "POST", "DELETE", "PATCH"],
        allow_headers=["Content-Type", "Authorization"],
    )
    # 安全响应头：纯 ASGI 实现，不缓冲响应体，SSE 流式不受影响
    app.add_middleware(SecurityHeadersMiddleware)
    # 请求 ID 放最外层（最后注册 = 头插法最外）：先于一切生成 rid 并在响应头
    # 回写 X-Request-Id；并兜底真正未捕获的异常 → 统一 500 JSON + rid
    # （HTTPException 在路由层已转响应、不冒泡到此，故不被误吞；流式中途异常
    # 仅记日志后交外层处理）。纯 ASGI，不缓冲响应体，SSE 不受影响。
    app.add_middleware(RequestIdMiddleware)

    # REST 路由
    app.include_router(chat_router, prefix="/api")
    app.include_router(config_router, prefix="/api")
    app.include_router(events_router, prefix="/api")
    app.include_router(presence_router, prefix="/api")
    app.include_router(push_router, prefix="/api")
    app.include_router(sessions_router, prefix="/api")
    app.include_router(status_router, prefix="/api")

    # 前端静态文件（Vite 生产构建 web/dist；开发模式下前端独立运行在 :3000，
    # 由 vite.config.js 将 /api 代理到 :8000，无需此挂载）
    web_root = Path(__file__).parent.parent.parent / "web"
    frontend_dir = web_root / "dist"
    if frontend_dir.is_dir():
        app.mount("/", StaticFiles(directory=str(frontend_dir), html=True), name="frontend")

    return app


# 模块级 app 实例（兼容 main.py 的直接 import）
app = create_app()
