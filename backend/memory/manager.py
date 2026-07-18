"""记忆管理器 — 线程安全的 YAML 文件 CRUD。"""
import datetime
import threading
from pathlib import Path

import yaml

DATA_DIR = Path(__file__).parent.parent / "data"
MEMORY_FILE = DATA_DIR / "memories.yaml"

_lock = threading.Lock()


class MemoryManager:
    """管理小汐的长期记忆（YAML 持久化 + 线程安全）。"""

    def __init__(self, filepath: Path | None = None):
        self._file = filepath or MEMORY_FILE
        self._ensure()

    # ── 内部 ────────────────────────────────────────────────

    def _ensure(self):
        self._file.parent.mkdir(parents=True, exist_ok=True)
        if not self._file.exists():
            with open(self._file, "w", encoding="utf-8") as f:
                yaml.dump({"memories": []}, f, allow_unicode=True)

    def _load(self) -> list[dict]:
        """注意：调用方须已持有 _lock（或在 add 等方法中统一加锁）。"""
        self._ensure()
        with open(self._file, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return list(data.get("memories", []))

    def _save(self, mems: list[dict]):
        """注意：调用方须已持有 _lock。"""
        self._ensure()
        with open(self._file, "w", encoding="utf-8") as f:
            yaml.dump({"memories": mems}, f, allow_unicode=True)

    # ── 公共接口 ────────────────────────────────────────────

    def add(self, category: str, content: str) -> dict:
        """添加一条记忆。返回刚添加的记忆条目。原子操作：load→modify→save 在同一把锁内。"""
        with _lock:
            mems = self._load()
            entry = {
                "id": datetime.datetime.now().strftime("%Y%m%d%H%M%S%f"),
                "category": category,
                "content": content,
                "time": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
            }
            mems.append(entry)
            self._save(mems)
        return entry

    def recall(self, category: str = "") -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。"""
        with _lock:
            mems = self._load()
        if category:
            return [m for m in mems if category in m.get("category", "")]
        return mems

    def list_categories(self) -> dict[str, int]:
        """列出所有类别及数量。"""
        with _lock:
            mems = self._load()
        cats: dict[str, int] = {}
        for m in mems:
            cat = m.get("category", "其他")
            cats[cat] = cats.get(cat, 0) + 1
        return cats

    @property
    def count(self) -> int:
        with _lock:
            return len(self._load())

    def all(self) -> list[dict]:
        with _lock:
            return self._load()
