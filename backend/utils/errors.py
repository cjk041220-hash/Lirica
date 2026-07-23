"""错误脱敏工具 — 防止敏感信息随错误文本泄漏。

高德/Tavily 等服务的 API Key 以 URL 查询参数传递，httpx 异常的
str(e) 包含完整 URL（含 key=...）。这些文本若原样返回给 LLM，
可能被复述到前端、写入 checkpoint 或日志，造成密钥泄漏。

规则：
- 返回给 LLM / 用户的：固定友好文案，不含任何异常细节
- 记入服务端日志的：经 sanitize_sensitive_text 遮罩敏感参数后的文本
"""
import re

from utils.logger import get_logger

log = get_logger(__name__)

# 匹配 URL/文本中的敏感查询参数：key=xxx、signature=xxx、token=xxx 等
# 值部分匹配到 & 空白 引号 反斜杠 之前为止
_SENSITIVE_PARAM_RE = re.compile(
    r"((?:api_?key|key|signature|token|secret)=)[^&\s'\"\\]+",
    re.IGNORECASE,
)


def sanitize_sensitive_text(text: str) -> str:
    """遮罩文本中的敏感查询参数值（key=*** / token=***）。

    用于服务端日志——既保留排障所需的上下文，又不落盘真实密钥。
    """
    return _SENSITIVE_PARAM_RE.sub(r"\1***", text)


def log_tool_error(tool_name: str, exc: Exception) -> None:
    """在服务端记录工具调用失败（敏感参数已遮罩），供排障使用。

    工具对外只应返回固定友好文案，异常细节仅通过本函数落日志。
    """
    log.warning(
        "工具 %s 调用失败：%s", tool_name, sanitize_sensitive_text(str(exc))
    )
