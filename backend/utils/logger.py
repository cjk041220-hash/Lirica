"""小汐的日志模块 — 统一日志输出，支持级别过滤、落盘轮转与 GBK 安全输出。"""
import logging
import os
import sys
from logging.handlers import RotatingFileHandler

# ── 日志格式 ──────────────────────────────────────────────────

_FORMAT = "%(asctime)s [%(levelname)-5s] %(name)s | %(message)s"
_DATE_FMT = "%H:%M:%S"          # 控制台：只要时分秒，足够排障
_FILE_DATE_FMT = "%Y-%m-%d %H:%M:%S"  # 文件：跨天需带完整日期

# 文件轮转默认值（仅当设置 LOG_FILE 环境变量时启用落盘）
_LOG_MAX_BYTES = 10 * 1024 * 1024   # 单文件 10MB
_LOG_BACKUP_COUNT = 7                # 保留 7 份


class _GbkSafeStreamHandler(logging.StreamHandler):
    """Windows GBK 控制台安全的 StreamHandler。"""

    def emit(self, record: logging.LogRecord):
        try:
            msg = self.format(record)
            try:
                sys.stderr.write(msg + self.terminator)
            except UnicodeEncodeError:
                # GBK 无法编码时回退到 ASCII 替换模式
                safe = msg.encode("ascii", errors="replace").decode("ascii")
                sys.stderr.write(safe + self.terminator)
            self.flush()
        except Exception:
            self.handleError(record)


def _level_from_env(default: int, override_name: str | None = None) -> int:
    """解析日志级别：显式级别名参数 > LOG_LEVEL 环境变量 > default。

    级别名取 DEBUG/INFO/WARNING/...，无效或未设则回退 default。
    """
    name = (override_name or os.environ.get("LOG_LEVEL") or "").strip().upper()
    if not name:
        return default
    return getattr(logging, name, default)


def setup_logging(level: int | None = None, log_file: str | None = None,
                  log_level_name: str | None = None):
    """配置全局日志。在应用启动时调用一次。

    级别优先取显式 level 参数，其次 log_level_name / LOG_LEVEL 环境变量，
    都未给时为 INFO。默认仅输出 stderr（与旧版行为一致）；给了 log_file
    参数或 LOG_FILE 环境变量后才额外落盘到带轮转的文件（UTF-8，10MB×7）。
    落盘失败仅告警、不阻断启动——因此测试 / 未配置环境绝不会产生日志文件。

    log_file / log_level_name 显式参数的存在意义：.env 里的值经 pydantic
    Settings 加载后不会进 os.environ，server 启动时把 Settings 字段透传
    进来，保证「写在 .env 里」与「设为真实环境变量」等效（体检 9.13）。
    """
    if level is None:
        level = _level_from_env(logging.INFO, log_level_name)

    root = logging.getLogger()
    root.setLevel(level)

    # 控制台：GBK 安全 stderr（避免重复添加）
    if not any(isinstance(h, _GbkSafeStreamHandler) for h in root.handlers):
        handler = _GbkSafeStreamHandler()
        handler.setFormatter(logging.Formatter(_FORMAT, datefmt=_DATE_FMT))
        root.handlers = [h for h in root.handlers if not isinstance(h, logging.StreamHandler)]
        root.addHandler(handler)

    # 文件：显式 log_file 参数 > LOG_FILE 环境变量（默认不启用 → 测试/未配置无副作用）
    log_file = (log_file or os.environ.get("LOG_FILE") or "").strip()
    if log_file and not any(isinstance(h, RotatingFileHandler) for h in root.handlers):
        try:
            os.makedirs(os.path.dirname(os.path.abspath(log_file)), exist_ok=True)
            fh = RotatingFileHandler(
                log_file, maxBytes=_LOG_MAX_BYTES, backupCount=_LOG_BACKUP_COUNT,
                encoding="utf-8",
            )
            fh.setLevel(level)
            fh.setFormatter(logging.Formatter(_FORMAT, datefmt=_FILE_DATE_FMT))
            root.addHandler(fh)
        except Exception as e:  # 落盘失败不得阻断启动
            logging.getLogger(__name__).warning("日志落盘启用失败（%s）：%s", log_file, e)


def get_logger(name: str) -> logging.Logger:
    """获取模块级 logger。"""
    return logging.getLogger(name)
