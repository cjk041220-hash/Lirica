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
