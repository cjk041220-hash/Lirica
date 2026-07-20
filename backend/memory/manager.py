"""记忆管理器 — 线程安全的 YAML 文件 CRUD。

支持：增删查、去重、导出、清空、source/last_used_at 字段。
"""
import datetime
import json
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
        self._ensure()
        with open(self._file, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return list(data.get("memories", []))

    def _save(self, mems: list[dict]):
        self._ensure()
        with open(self._file, "w", encoding="utf-8") as f:
            yaml.dump({"memories": mems}, f, allow_unicode=True)

    # ── 公共接口 ────────────────────────────────────────────

    def add(self, category: str, content: str, source: str = "dialogue") -> dict:
        """添加一条记忆（带去重）。返回刚添加的记忆条目。

        去重规则：同一 category 下已有完全相同的 content 时，
        不重复添加，只更新 time 和 last_used_at。
        """
        with _lock:
            mems = self._load()

            # 去重：同 category + 同 content
            for m in mems:
                if m.get("category") == category and m.get("content") == content:
                    m["time"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
                    m["last_used_at"] = m["time"]
                    self._save(mems)
                    return m

            entry = {
                "id": datetime.datetime.now().strftime("%Y%m%d%H%M%S%f"),
                "category": category,
                "content": content,
                "source": source,
                "time": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
                "last_used_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
            }
            mems.append(entry)
            self._save(mems)
        return entry

    def delete(self, memory_id: str) -> bool:
        """删除指定 ID 的记忆。返回是否成功。"""
        with _lock:
            mems = self._load()
            original_len = len(mems)
            mems = [m for m in mems if m.get("id") != memory_id]
            if len(mems) < original_len:
                self._save(mems)
                return True
            return False

    def clear(self) -> int:
        """清空所有记忆。返回清除的条数。"""
        with _lock:
            mems = self._load()
            count = len(mems)
            self._save([])
            return count

    def export_json(self) -> str:
        """导出所有记忆为 JSON 字符串（用于下载）。"""
        with _lock:
            mems = self._load()
            return json.dumps(
                {"memories": mems, "exported_at": datetime.datetime.now().isoformat()},
                ensure_ascii=False, indent=2,
            )

    def recall(self, category: str = "") -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。
        同时更新被召回记忆的 last_used_at。
        """
        with _lock:
            mems = self._load()
            if category:
                matched = [m for m in mems if category in m.get("category", "")]
            else:
                matched = mems

            # 更新 last_used_at
            now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            matched_ids = {m.get("id") for m in matched}
            for m in mems:
                if m.get("id") in matched_ids:
                    m["last_used_at"] = now
            if matched_ids:
                self._save(mems)

            # 按时间倒序（最近的在前）
            return sorted(matched, key=lambda m: m.get("time", ""), reverse=True)

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
