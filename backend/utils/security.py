"""启动安全加固 — fail closed 校验。

小汐是单用户私人陪伴服务，绝不允许"忘记设 token 就暴露公网"。
本模块在 lifespan 启动阶段做两道硬性校验，不通过则拒绝启动：

1. 绑定地址非回环 + 未设置 XIAOXI_API_TOKEN → 拒绝启动
   （显式 XIAOXI_ALLOW_INSECURE=1 可覆盖，仅限受控网络）
2. worker 数 > 1 → 拒绝启动
   （APScheduler 心跳、内存限流桶、运行时配置都是进程内状态，
   多 worker 会导致心跳重复、限流失效、配置不同步）
"""
import hmac
import os
import sys

from utils.logger import get_logger

log = get_logger(__name__)


def bearer_token_matches(auth_header: str, expected_token: str) -> bool:
    """常量时间比较 Bearer Token，防时序侧信道（全项目单一实现）。

    api/middleware/auth 与 /api/health 共用，避免内联复刻漂移。
    不含「未配置 token=开发模式」规则——该规则由各调用方自行决定。
    """
    if not auth_header.startswith("Bearer "):
        return False
    provided = auth_header[len("Bearer "):]
    if not provided:
        return False
    return hmac.compare_digest(provided.encode("utf-8"), expected_token.encode("utf-8"))

# 视为回环（仅本机可访问）的绑定地址
_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", ""}


def _argv_value(argv: list[str], flag: str) -> str | None:
    """取命令行参数值，同时支持 ``--flag V`` 与 ``--flag=V`` 两种写法。

    uvicorn/click 两种写法都接受，只认空格写法会让 ``--host=0.0.0.0``
    绕过启动安全校验（曾审计发现的 fail-open 路径）。
    """
    prefix = f"{flag}="
    for i, token in enumerate(argv):
        if token == flag:
            return argv[i + 1] if i + 1 < len(argv) else None
        if token.startswith(prefix):
            return token[len(prefix):]
    return None


def detect_bind_host(argv: list[str] | None = None) -> str:
    """推断本次启动的绑定地址。

    优先级：命令行 ``--host``（``--host V`` / ``--host=V`` 两种写法）
    > ``UVICORN_HOST`` 环境变量（uvicorn 实际生效的 env）
    > ``XIAOXI_BIND_HOST`` 环境变量 > ``settings.xiaoxi_bind_host``
    （兜底 .env 文件配置——pydantic 读 .env 不写 os.environ，缺了这层
    会出现「安检以为回环、实际绑 0.0.0.0」的 fail-open，体检 S9）。
    检测必须对齐 uvicorn 真实会绑定的地址；uvicorn 未指定 host 时
    默认就是 127.0.0.1。
    """
    argv = argv if argv is not None else sys.argv
    host = _argv_value(argv, "--host")
    if host:
        return host
    host = os.environ.get("UVICORN_HOST") or os.environ.get("XIAOXI_BIND_HOST")
    if host:
        return host
    from config.settings import get_settings  # 函数级 import 防环
    return get_settings().xiaoxi_bind_host


def detect_workers(argv: list[str] | None = None) -> int:
    """推断 worker 数：命令行 ``--workers``（两种写法）> UVICORN_WORKERS > WEB_CONCURRENCY > 1。"""
    argv = argv if argv is not None else sys.argv
    raw = _argv_value(argv, "--workers")
    if raw is not None:
        try:
            return int(raw)
        except ValueError:
            pass
    for env_key in ("UVICORN_WORKERS", "WEB_CONCURRENCY"):
        try:
            return int(os.environ[env_key])
        except (KeyError, ValueError):
            continue
    return 1


def assert_safe_startup(
    bind_host: str,
    api_token: str,
    allow_insecure: bool,
    workers: int,
) -> None:
    """启动前安全校验。不满足安全条件时抛 RuntimeError 拒绝启动。"""
    # ── 单 worker 约束 ──────────────────────────────────────
    if workers > 1:
        raise RuntimeError(
            f"启动失败：检测到 workers={workers}，小汐只能以单 worker 运行！\n"
            "   心跳调度器、内存限流桶、运行时配置都是进程内状态，\n"
            "   多 worker 会导致主动消息重复发送、限流失效、配置不同步。\n"
            "   请改为 --workers 1（或删除 WEB_CONCURRENCY 环境变量）。"
        )

    # ── 认证 fail closed ────────────────────────────────────
    if bind_host in _LOOPBACK_HOSTS:
        return  # 仅本机可访问，允许开发模式
    if api_token:
        return  # 已启用 Bearer 认证
    if allow_insecure:
        log.warning(
            "XIAOXI_ALLOW_INSECURE 已开启：绑定 %s 但无 API Token，"
            "API 处于无认证状态！仅限受信任的内网环境。",
            bind_host,
        )
        return
    raise RuntimeError(
        f"启动失败：绑定地址 {bind_host} 非回环，但未设置 XIAOXI_API_TOKEN！\n"
        "   无认证暴露到网络 = 记忆/日记/对话任人读取、API 账单任人消耗。\n"
        "   解决方法（任选其一）：\n"
        "   1. 在 backend/config/.env 中设置 XIAOXI_API_TOKEN（推荐）；\n"
        "   2. 改为仅本机访问：--host 127.0.0.1，前置反向代理做认证；\n"
        "   3. 确认处于受控内网后，设置 XIAOXI_ALLOW_INSECURE=1 显式承担风险。"
    )
