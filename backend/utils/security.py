"""启动安全加固 — fail closed 校验。

小汐是单用户私人陪伴服务，绝不允许"忘记设 token 就暴露公网"。
本模块在 lifespan 启动阶段做两道硬性校验，不通过则拒绝启动：

1. 绑定地址非回环 + 未设置 XIAOXI_API_TOKEN → 拒绝启动
   （显式 XIAOXI_ALLOW_INSECURE=1 可覆盖，仅限受控网络）
2. worker 数 > 1 → 拒绝启动
   （APScheduler 心跳、内存限流桶、运行时配置都是进程内状态，
   多 worker 会导致心跳重复、限流失效、配置不同步）
"""
import os
import sys

from utils.logger import get_logger

log = get_logger(__name__)

# 视为回环（仅本机可访问）的绑定地址
_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", ""}


def detect_bind_host(argv: list[str] | None = None) -> str:
    """推断本次启动的绑定地址。

    优先级：命令行 ``--host`` 参数 > 环境变量 ``XIAOXI_BIND_HOST`` > 默认回环。
    uvicorn CLI（含 Dockerfile CMD）显式传 --host，因此 argv 解析能覆盖
    绝大多数暴露场景；uvicorn 未指定 host 时默认就是 127.0.0.1。
    """
    argv = argv if argv is not None else sys.argv
    if "--host" in argv:
        i = argv.index("--host")
        if i + 1 < len(argv):
            return argv[i + 1]
    return os.environ.get("XIAOXI_BIND_HOST", "127.0.0.1")


def detect_workers(argv: list[str] | None = None) -> int:
    """推断 worker 数：命令行 ``--workers`` > WEB_CONCURRENCY 环境变量 > 1。"""
    argv = argv if argv is not None else sys.argv
    if "--workers" in argv:
        i = argv.index("--workers")
        if i + 1 < len(argv):
            try:
                return int(argv[i + 1])
            except ValueError:
                pass
    try:
        return int(os.environ.get("WEB_CONCURRENCY", "1"))
    except ValueError:
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
