"""Token 计数遥测 —— 把「提示词多大」从估算变成事实。

用 cl100k_base 近似计数：DeepSeek 没有公开分词器，cl100k 对中文是
高估（约 1.3–2×）——高估正是预算监控要的安全余量，字段语义即
「approx」。不做「字符数 × 系数」式估算：那种数不可证伪、随模型
漂移无感知；cl100k 至少是真实 BPE 词表的确定函数，测试可钉死。

requirements.txt 里的 tiktoken 死依赖由此激活。编码对象惰性加载
并缓存（首次 ~100ms，其后零开销）。
"""
import tiktoken

from utils.logger import get_logger

log = get_logger(__name__)

_encoding = None


def _get_encoding():
    global _encoding
    if _encoding is None:
        _encoding = tiktoken.get_encoding("cl100k_base")
    return _encoding


def count_tokens(text: str) -> int:
    """返回文本的近似 token 数（cl100k_base，对 DeepSeek 偏保守）。"""
    if not text:
        return 0
    try:
        return len(_get_encoding().encode(text))
    except Exception as e:  # 计数失败绝不影响主链路
        log.warning("token 计数失败：%s", e)
        return 0
