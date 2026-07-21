"""记忆管理器 — 基于全局 aiosqlite 连接的异步 CRUD。

v2 重构：
- 精简 schema（删除 source/weight/emotion_tag 三个死字段）
- time → created_at
- 新增 access_count（召回计数）和 status（active/superseded）
- 新增 update_content() 用于矛盾记忆覆盖
- recall() 使用相关性评分排序（时间衰减 × 访问热度）
"""
import datetime
import json
import math
import uuid
from datetime import timezone, timedelta
from pathlib import Path

import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

# 冷冷在香港（UTC+8），所有时间戳统一使用此时区
_HKT = timezone(timedelta(hours=8))
_TIME_FMT = "%Y-%m-%d %H:%M"


def _now() -> str:
    return datetime.datetime.now(_HKT).strftime(_TIME_FMT)


def _parse_time(t: str | None) -> datetime.datetime:
    """安全解析时间字符串，失败时返回 epoch。"""
    if not t:
        return datetime.datetime(2000, 1, 1, tzinfo=_HKT)
    try:
        return datetime.datetime.strptime(t, _TIME_FMT).replace(tzinfo=_HKT)
    except (ValueError, TypeError):
        return datetime.datetime(2000, 1, 1, tzinfo=_HKT)


def _relevance_score(memory: dict, now: datetime.datetime) -> float:
    """计算记忆相关性分数。

    三因子加权：
    - 时间衰减（created_at，半衰期 ≈ 7 天）
    - 访问热度（access_count，对数衰减防止老热门垄断）
    - 最近使用（last_used_at，半衰期 ≈ 3.5 天）
    """
    # 时间衰减
    created = _parse_time(memory.get("created_at"))
    days_old = max(0, (now - created).total_seconds() / 86400)
    recency = math.exp(-0.1 * days_old)

    # 访问热度
    access_count = memory.get("access_count", 0) or 0
    access_boost = math.log1p(access_count) * 0.15

    # 最近使用
    last_used = memory.get("last_used_at")
    if last_used:
        used_days = max(0, (now - _parse_time(last_used)).total_seconds() / 86400)
        usage_recency = math.exp(-0.2 * used_days) * 0.3
    else:
        usage_recency = 0.0

    return recency + access_boost + usage_recency


class MemoryManager:
    """管理小汐的长期记忆（SQLite 持久化）。

    接受外部注入的全局数据库连接，不再自行创建连接。
    """

    def __init__(self, db: aiosqlite.Connection):
        self._db = db

    # ── 内部工具 ────────────────────────────────────────────

    @staticmethod
    def _rows_to_dicts(cursor, rows: list) -> list[dict]:
        """用 cursor.description 手动构造 dict 列表，完全避免 row_factory 共享状态问题。"""
        cols = [desc[0] for desc in (cursor.description or [])]
        return [dict(zip(cols, row)) for row in rows]

    # ── 写入 ────────────────────────────────────────────────

    async def add(
        self,
        category: str,
        content: str,
    ) -> dict:
        """添加一条记忆（带去重）。返回刚添加的记忆条目。

        去重规则：同一 category 下已有完全相同的 content 时，
        不重复添加，只更新 created_at 和 last_used_at。
        """
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        # 去重：同 category + 同 content
        cursor = await self._db.execute(
            "SELECT id FROM memories WHERE category = ? AND content = ? AND status = 'active'",
            (category, content),
        )
        existing = await cursor.fetchone()
        if existing:
            await self._db.execute(
                "UPDATE memories SET created_at = ?, last_used_at = ? WHERE id = ?",
                (now, now, existing[0]),
            )
            await self._db.commit()
            return {
                "id": existing[0],
                "category": category,
                "content": content,
                "created_at": now,
                "last_used_at": now,
                "access_count": 0,
                "status": "active",
            }

        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, last_used_at, access_count, status)
               VALUES (?, ?, ?, ?, ?, 0, 'active')""",
            (mem_id, content, category, now, now),
        )
        await self._db.commit()
        return {
            "id": mem_id,
            "category": category,
            "content": content,
            "created_at": now,
            "last_used_at": now,
            "access_count": 0,
            "status": "active",
        }

    async def update_content(self, memory_id: str, new_content: str) -> bool:
        """覆盖一条记忆的内容，刷新时间戳。返回是否成功。"""
        now = _now()
        cursor = await self._db.execute(
            "UPDATE memories SET content = ?, created_at = ?, last_used_at = ? WHERE id = ? AND status = 'active'",
            (new_content, now, now, memory_id),
        )
        await self._db.commit()
        return cursor.rowcount > 0

    # ── 删除 ────────────────────────────────────────────────

    async def delete(self, memory_id: str) -> bool:
        """删除指定 ID 的记忆。返回是否成功。"""
        cursor = await self._db.execute(
            "DELETE FROM memories WHERE id = ?", (memory_id,)
        )
        await self._db.commit()
        return cursor.rowcount > 0

    async def clear(self) -> int:
        """清空所有记忆。返回清除的条数。"""
        cursor = await self._db.execute(
            "SELECT COUNT(*) FROM memories WHERE status = 'active'"
        )
        count = (await cursor.fetchone())[0]
        await self._db.execute("DELETE FROM memories")
        await self._db.commit()
        return count

    # ── 备份 / 导出 ─────────────────────────────────────────

    async def backup_before_clear(self) -> Path:
        """清空前将所有记忆导出为 JSON 备份文件，返回备份路径。"""
        data = await self.export_json()

        backup_dir = Path(__file__).parent.parent / "data" / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)

        timestamp = datetime.datetime.now(_HKT).strftime("%Y%m%d_%H%M%S")
        backup_path = backup_dir / f"memories_backup_{timestamp}.json"
        backup_path.write_text(data, encoding="utf-8")

        log.info("记忆备份已保存：%s", backup_path)
        return backup_path

    async def export_json(self) -> str:
        """导出所有活跃记忆为 JSON 字符串（用于下载）。"""
        cursor = await self._db.execute(
            "SELECT * FROM memories WHERE status = 'active' ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        mems = self._rows_to_dicts(cursor, rows)
        return json.dumps(
            {"memories": mems, "exported_at": datetime.datetime.now(_HKT).isoformat()},
            ensure_ascii=False,
            indent=2,
        )

    # ── 查询 ────────────────────────────────────────────────

    async def recall(self, category: str = "", limit: int = 20) -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。

        按相关性评分排序（时间衰减 × 访问热度 × 最近使用）。
        同时更新被召回记忆的 last_used_at 和 access_count。
        """
        if category:
            cursor = await self._db.execute(
                """SELECT * FROM memories
                   WHERE category LIKE ? AND status = 'active'
                   ORDER BY created_at DESC LIMIT ?""",
                (f"%{category}%", limit * 2),  # 多取一些，评分后再截断
            )
        else:
            cursor = await self._db.execute(
                """SELECT * FROM memories
                   WHERE status = 'active'
                   ORDER BY created_at DESC LIMIT ?""",
                (limit * 2,),
            )
        rows = await cursor.fetchall()
        matched = self._rows_to_dicts(cursor, rows)

        # 按相关性评分排序
        now = datetime.datetime.now(_HKT)
        matched.sort(key=lambda m: _relevance_score(m, now), reverse=True)
        matched = matched[:limit]

        # 更新 last_used_at 和 access_count
        if matched:
            ids = [m["id"] for m in matched]
            placeholders = ",".join("?" for _ in ids)
            await self._db.execute(
                f"""UPDATE memories
                    SET last_used_at = ?, access_count = access_count + 1
                    WHERE id IN ({placeholders})""",
                (_now(), *ids),
            )
            await self._db.commit()

        return matched

    async def recall_by_category(self, category: str, limit: int = 5) -> list[dict]:
        """按精确类别查询记忆（用于 remember 工具的矛盾检测）。

        与 recall() 不同，这里用 LIKE 模糊匹配且不更新 access_count。
        """
        cursor = await self._db.execute(
            """SELECT * FROM memories
               WHERE category LIKE ? AND status = 'active'
               ORDER BY created_at DESC LIMIT ?""",
            (f"%{category}%", limit),
        )
        rows = await cursor.fetchall()
        return self._rows_to_dicts(cursor, rows)

    async def recent(self, limit: int = 30) -> list[dict]:
        """返回最近 N 条活跃记忆，按时间正序排列（最早的在前）。

        用于叙事构建器注入系统提示词。不更新 last_used_at（被动读取）。
        """
        cursor = await self._db.execute(
            """SELECT * FROM (
                   SELECT * FROM memories
                   WHERE status = 'active'
                   ORDER BY created_at DESC LIMIT ?
               ) ORDER BY created_at ASC""",
            (limit,),
        )
        rows = await cursor.fetchall()
        return self._rows_to_dicts(cursor, rows)

    async def list_categories(self) -> dict[str, int]:
        """列出所有类别及活跃记忆数量。"""
        cursor = await self._db.execute(
            "SELECT category, COUNT(*) as cnt FROM memories WHERE status = 'active' GROUP BY category"
        )
        rows = await cursor.fetchall()
        return {r[0] or "其他": r[1] for r in rows}

    async def count(self) -> int:
        """返回活跃记忆总数。"""
        cursor = await self._db.execute(
            "SELECT COUNT(*) FROM memories WHERE status = 'active'"
        )
        row = await cursor.fetchone()
        return row[0] if row else 0

    async def all(self) -> list[dict]:
        """返回全部活跃记忆列表。"""
        cursor = await self._db.execute(
            "SELECT * FROM memories WHERE status = 'active' ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        return self._rows_to_dicts(cursor, rows)
