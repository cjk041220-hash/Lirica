"""记忆管理器 — 基于全局 aiosqlite 连接的异步 CRUD。

v2 重构：
- 精简 schema（删除 source/weight/emotion_tag 三个死字段）
- time → created_at
- 新增 access_count（召回计数）和 status（active/superseded）
- 新增 update_content() 用于矛盾记忆覆盖
- recall() 使用相关性评分排序（时间衰减 × 访问热度）
"""
import asyncio
import datetime
import json
import math
import uuid
from pathlib import Path

import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

# 冷冷在香港（UTC+8），所有时间戳统一使用此时区
from utils.timezone import HKT as _HKT, now_str as _now, _TIME_FMT
from database.core import rows_to_dicts


def _escape_like(s: str) -> str:
    """转义 LIKE 通配符，category 里的 % / _ 只按字面匹配。

    category 来自 LLM 工具参数，含下划线的类别名（如 user_info）
    不转义会让 `_` 当单字符通配，匹配语义失控（不是注入风险——
    已参数化——而是召回串味）。
    """
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _parse_time(t: str | None) -> datetime.datetime:
    """安全解析时间字符串，失败时返回 epoch。"""
    if not t:
        return datetime.datetime(2000, 1, 1, tzinfo=_HKT)
    try:
        return datetime.datetime.strptime(t, _TIME_FMT).replace(tzinfo=_HKT)
    except (ValueError, TypeError):
        return datetime.datetime(2000, 1, 1, tzinfo=_HKT)


def _char_bigrams(text: str) -> set[str]:
    """字符 2-gram 集合（去空白）。中文短文本的话题指纹，无需分词。"""
    t = "".join((text or "").split())
    if len(t) < 2:
        return {t} if t else set()  # 单字兜底，防空集除零
    return {t[i:i + 2] for i in range(len(t) - 1)}


def _bigram_overlap(query: str, content: str) -> float:
    """字符 bigram 集合的 overlap 系数（交集 ÷ min 分母），[0, 1]。

    query 短（当前这句话）、content 长（≤200 字记忆）：Jaccard 的分母
    会被长文稀释到趋零，min 分母让「query 的每个二字词都在内容里」
    拿满分——这正是「话题命中」的定义。
    """
    a, b = _char_bigrams(query), _char_bigrams(content)
    if not a or not b:
        return 0.0
    return len(a & b) / min(len(a), len(b))


def _relevance_score(memory: dict, now: datetime.datetime, query: str = "") -> float:
    """计算记忆相关性分数。

    四因子加权：
    - 时间衰减（created_at，半衰期 ≈ 7 天）
    - 访问热度（access_count，对数衰减防止老热门垄断）
    - 最近使用（last_used_at，半衰期 ≈ 3.5 天）
    - 话题命中（query 与 content+category 的字面重叠，B1 新增，权重 1.0）
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

    # 话题加分（权重 1.0）：层内排序用——同为话题命中时，重叠比例高的
    # 靠前。（层间「命中优先」由 recall 的二段排序保证，不靠这个权重硬扛
    # 新记忆的 recency+usage 双加成。）
    if query:
        haystack = f"{memory.get('content', '')} {memory.get('category', '')}"
        topic = _bigram_overlap(query, haystack)
    else:
        topic = 0.0

    return recency + access_boost + usage_recency + topic


class MemoryManager:
    """管理小汐的长期记忆（SQLite 持久化）。

    接受外部注入的全局数据库连接，不再自行创建连接。
    """

    def __init__(self, db: aiosqlite.Connection):
        self._db = db

    # ── 写入 ────────────────────────────────────────────────

    async def add(
        self,
        category: str,
        content: str,
    ) -> dict:
        """添加一条记忆（原子去重）。返回实际生效的记忆条目。

        去重规则：同一 category 下已有完全相同的 content（活跃行）时，
        不重复添加，只刷新 created_at 和 last_used_at。

        去重由数据库层的唯一部分索引 (category, content)
        WHERE status IN ('active', 'core')
        （迁移 v5）强制保证：INSERT ... ON CONFLICT DO UPDATE 一条语句原子完成，
        彻底消除「先查后写」在并发下产生重复行的 TOCTOU 竞态。
        """
        # 空 category 入库会让 recall_by_category 的 LIKE '%%' 匹配全类别、
        # 矛盾检测上下文串味；由调用方（remember 工具）转友好文案
        category = (category or "").strip()
        if not category:
            raise ValueError("category 不能为空")
        content = (content or "").strip()
        if not content:
            raise ValueError("content 不能为空")
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, last_used_at, access_count, status)
               VALUES (?, ?, ?, ?, ?, 0, 'active')
               ON CONFLICT(category, content) WHERE status IN ('active', 'core')
               DO UPDATE SET
                   created_at = excluded.created_at,
                   last_used_at = excluded.last_used_at""",
            (mem_id, content, category, now, now),
        )
        await self._db.commit()

        # 读回实际生效的行（可能是新插入的，也可能是被刷新的既有行），
        # 返回真实的 id 与 access_count
        cursor = await self._db.execute(
            """SELECT * FROM memories
               WHERE category = ? AND content = ? AND status IN ('active', 'core')""",
            (category, content),
        )
        row = await cursor.fetchone()
        return rows_to_dicts(cursor, [row])[0]

    async def update_content(self, memory_id: str, new_content: str) -> bool:
        """覆盖一条记忆的内容，刷新时间戳。返回是否成功。"""
        now = _now()
        cursor = await self._db.execute(
            "UPDATE memories SET content = ?, created_at = ?, last_used_at = ? WHERE id = ? AND status IN ('active', 'core')",
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
            "SELECT COUNT(*) FROM memories WHERE status IN ('active', 'core')"
        )
        count = (await cursor.fetchone())[0]
        await self._db.execute("DELETE FROM memories")
        await self._db.commit()
        return count

    # ── 备份 / 导出 ─────────────────────────────────────────

    async def backup_before_clear(self) -> Path:
        """清空前将所有记忆导出为 JSON 备份文件，返回备份路径。

        备份目录 700、文件 600（含敏感数据，禁止同机其他用户读取）。
        """
        from database.core import restrict_backup_permissions

        data = await self.export_json()

        backup_dir = Path(__file__).parent.parent / "data" / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        restrict_backup_permissions(backup_dir, is_dir=True)

        timestamp = datetime.datetime.now(_HKT).strftime("%Y%m%d_%H%M%S")
        backup_path = backup_dir / f"memories_backup_{timestamp}.json"
        # 同步文件写入卸载到线程池，避免阻塞事件循环
        await asyncio.to_thread(backup_path.write_text, data, encoding="utf-8")
        restrict_backup_permissions(backup_path)

        log.info("记忆备份已保存：%s", backup_path)
        return backup_path

    async def export_json(self) -> str:
        """导出所有活跃记忆为 JSON 字符串（用于下载）。"""
        cursor = await self._db.execute(
            "SELECT * FROM memories WHERE status IN ('active', 'core') ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        mems = rows_to_dicts(cursor, rows)
        return json.dumps(
            {"memories": mems, "exported_at": datetime.datetime.now(_HKT).isoformat()},
            ensure_ascii=False,
            indent=2,
        )

    # ── 查询 ────────────────────────────────────────────────

    async def recall(
        self, category: str = "", limit: int = 20, passive: bool = False,
        query: str = "",
    ) -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。

        按相关性评分排序（时间衰减 × 访问热度 × 最近使用 × 话题命中）。
        query 传当前对话的最新一句话时，字面重叠的记忆获得话题加分
        （只进排序键，不影响 passive 语义——被动读取仍不刷热度）。

        默认（passive=False）更新被召回记忆的 last_used_at 和 access_count，
        记录真实的「被想起」信号。

        passive=True 时为纯只读，不触碰任何计数器——供 NarrativeBuilder 等
        系统被动读取的场景使用。否则每次 LLM 调用前的叙事注入都会给
        top-K 记忆刷新一轮热度和 last_used_at，形成「越被注入越热门、
        越热门越被注入」的正反馈闭环，把同样的记忆永久钉在榜首。
        """
        if category:
            cursor = await self._db.execute(
                """SELECT * FROM memories
                   WHERE category LIKE ? ESCAPE '\\' AND status IN ('active', 'core')
                   ORDER BY created_at DESC LIMIT ?""",
                (f"%{_escape_like(category)}%", limit * 2),  # 多取一些，评分后再截断
            )
        else:
            # 相关性召回不能用 created_at DESC 预筛：候选池一旦被「最新 N 条」
            # 限死，_relevance_score 的 access 热度因子对旧记忆完全失效——
            # 旧的核心偏好/重要约定无论被想起多少次都进不了候选池，「相关路」
            # 退化成「最新路」（narrative 的 hybrid 注入名存实亡）。
            # 单用户记忆量级（百级～千级）全表评分完全可承受。
            cursor = await self._db.execute(
                "SELECT * FROM memories WHERE status IN ('active', 'core')"
            )
        rows = await cursor.fetchall()
        matched = rows_to_dicts(cursor, rows)

        # 排序（query 非空时为两段式）：
        # 第一段——话题命中层：与 query 共享至少一个二字词（bigram）的记忆
        #   整体优先。自然语言 query 带大量填充词（「好想去澳门啊」），纯权重
        #   会把重叠率稀释到 0.2、压不过带 last_used 0.3 加成的新流水账；
        #   而共享一个 bigram 就是实打实的词命中（中文二字词恰好一个 bigram），
        #   二值分层不会被填充词稀释。
        # 第二段——层内按四因子评分：overlap 大的仍比擦边命中靠前，
        #   同为命中/同为未命中时热度与时近照常生效。
        now = datetime.datetime.now(_HKT)
        if query:
            q_grams = _char_bigrams(query)

            def _sort_key(m):
                haystack = f"{m.get('content', '')} {m.get('category', '')}"
                hit = 1 if q_grams & _char_bigrams(haystack) else 0
                return (hit, _relevance_score(m, now, query))
        else:
            def _sort_key(m):
                return _relevance_score(m, now, query)

        matched.sort(key=_sort_key, reverse=True)
        matched = matched[:limit]

        # 更新 last_used_at 和 access_count（passive 模式为被动读取，跳过）
        if matched and not passive:
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
               WHERE category LIKE ? ESCAPE '\\' AND status IN ('active', 'core')
               ORDER BY created_at DESC LIMIT ?""",
            (f"%{_escape_like(category)}%", limit),
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    async def recent(self, limit: int = 30) -> list[dict]:
        """返回最近 N 条活跃记忆，按时间正序排列（最早的在前）。

        用于叙事构建器注入系统提示词。不更新 last_used_at（被动读取）。
        """
        cursor = await self._db.execute(
            """SELECT * FROM (
                   SELECT * FROM memories
                   WHERE status IN ('active', 'core')
                   ORDER BY created_at DESC LIMIT ?
               ) ORDER BY created_at ASC""",
            (limit,),
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    async def old_memories(self, min_age_days: int = 90, limit: int = 5,
                           exclude_ids: list[str] | None = None) -> list[dict]:
        """随机采样足够久远的活跃记忆（「翻小本本」用）。

        纯被动：不更新 last_used_at / access_count。调用方是记忆回声
        检测器——若刷热度，被 echo 的记忆会在 _relevance_score 里
        突然「复活」霸榜（L220-223 注释记录的正反馈闭环），必须只读。

        全库日期为 TEXT "%Y-%m-%d %H:%M"，字典序即时间序，
        created_at <= cutoff 就是年龄达标；RANDOM() 保证每次采样
        不偏向同一批记忆。
        """
        cutoff = (
            datetime.datetime.now(_HKT) - datetime.timedelta(days=min_age_days)
        ).strftime(_TIME_FMT)
        exclude = exclude_ids or []
        if exclude:
            placeholders = ",".join("?" for _ in exclude)
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE status IN ('active', 'core') AND created_at <= ?
                      AND id NOT IN ({placeholders})
                    ORDER BY RANDOM() LIMIT ?""",
                (cutoff, *exclude, limit),
            )
        else:
            cursor = await self._db.execute(
                """SELECT * FROM memories
                   WHERE status IN ('active', 'core') AND created_at <= ?
                   ORDER BY RANDOM() LIMIT ?""",
                (cutoff, limit),
            )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    async def list_categories(self) -> dict[str, int]:
        """列出所有类别及活跃记忆数量。"""
        cursor = await self._db.execute(
            "SELECT category, COUNT(*) as cnt FROM memories WHERE status IN ('active', 'core') GROUP BY category"
        )
        rows = await cursor.fetchall()
        return {r[0] or "其他": r[1] for r in rows}

    async def count(self) -> int:
        """返回活跃记忆总数。"""
        cursor = await self._db.execute(
            "SELECT COUNT(*) FROM memories WHERE status IN ('active', 'core')"
        )
        row = await cursor.fetchone()
        return row[0] if row else 0

    async def all(self) -> list[dict]:
        """返回全部活跃记忆列表。"""
        cursor = await self._db.execute(
            "SELECT * FROM memories WHERE status IN ('active', 'core') ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    # ── 分层与衰减（B2）─────────────────────────────────────

    async def top_core(self, limit: int = 1) -> list[dict]:
        """按最近使用取 core 记忆（叙事保底席位用）。

        纯只读，不刷热度（同 old_memories 的被动纪律——若刷，core
        会在 _relevance_score 里自我加冕，违背被动读取原则）。
        """
        cursor = await self._db.execute(
            """SELECT * FROM memories WHERE status = 'core'
               ORDER BY COALESCE(last_used_at, created_at) DESC LIMIT ?""",
            (limit,),
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    async def apply_status_decay(self) -> dict:
        """分层衰减：active→core（高频晋升）/ active→archived（低频遗忘）。

        阈值见模块尾部常量。设计纪律：
        - 只从 active 搬——core 不降级、archived 不自动复活；冷冷重提
          旧事时 add() 插新行开始新生命周期（archived 已释放 v10 唯一
          索引槽位）；
        - 晋升先于归档执行（阈值互斥：access≥5 vs access≤1，顺序只是
          为语义清晰）；
        - archived 从一切读面消失（recall/recent/count/… 谓词只含
          active+core），但行仍在库里——遗忘是可回溯的冷藏，不是销毁。

        Returns:
            {"promoted": n, "archived": n}
        """
        now = datetime.datetime.now(_HKT)
        core_cutoff = (
            now - datetime.timedelta(days=_CORE_MIN_AGE_DAYS)
        ).strftime(_TIME_FMT)
        archive_cutoff = (
            now - datetime.timedelta(days=_ARCHIVE_MIN_AGE_DAYS)
        ).strftime(_TIME_FMT)

        cursor = await self._db.execute(
            """UPDATE memories SET status = 'core'
               WHERE status = 'active' AND access_count >= ? AND created_at <= ?""",
            (_CORE_MIN_ACCESSES, core_cutoff),
        )
        promoted = cursor.rowcount

        cursor = await self._db.execute(
            """UPDATE memories SET status = 'archived'
               WHERE status = 'active' AND access_count <= ? AND created_at <= ?""",
            (_ARCHIVE_MAX_ACCESSES, archive_cutoff),
        )
        archived = cursor.rowcount

        await self._db.commit()
        if promoted or archived:
            log.info("记忆分层衰减：晋升 %d 条 core / 归档 %d 条", promoted, archived)
        return {"promoted": promoted, "archived": archived}


# ── 分层衰减阈值 ──────────────────────────────────────────────
# 晋升 core：被主动想起 ≥5 次且存在 ≥30 天——经月反复用到的才算
# 核心画像；归档：≤1 次且 ≥90 天——三个月几乎没被想起就冷藏。
# 衰减 job 挂心跳维护（_periodic_maintenance，2h），日志输出计数便于调参。
_CORE_MIN_ACCESSES = 5
_CORE_MIN_AGE_DAYS = 30
_ARCHIVE_MAX_ACCESSES = 1
_ARCHIVE_MIN_AGE_DAYS = 90


async def run_status_decay() -> dict:
    """分层衰减便捷入口（心跳维护任务用，在全局库上构造管理器）。"""
    from database.core import get_db
    return await MemoryManager(await get_db()).apply_status_decay()
