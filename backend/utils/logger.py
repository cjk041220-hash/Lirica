"""小汐的日志模块 — 统一日志输出，支持级别过滤和 GBK 安全输出。"""
import logging
import sys

# ── 日志格式 ──────────────────────────────────────────────────

_FORMAT = "%(asctime)s [%(levelname)-5s] %(name)s | %(message)s"
_DATE_FMT = "%H:%M:%S"


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


def setup_logging(level: int = logging.INFO):
    """配置全局日志。在应用启动时调用一次。"""
    handler = _GbkSafeStreamHandler()
    handler.setFormatter(logging.Formatter(_FORMAT, datefmt=_DATE_FMT))

    root = logging.getLogger()
    root.setLevel(level)
    # 避免重复添加 handler
    if not any(isinstance(h, _GbkSafeStreamHandler) for h in root.handlers):
        root.handlers = [h for h in root.handlers if not isinstance(h, logging.StreamHandler)]
        root.addHandler(handler)


def get_logger(name: str) -> logging.Logger:
    """获取模块级 logger。"""
    return logging.getLogger(name)
