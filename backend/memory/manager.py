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

# hit_count 按日去重的守卫格式（v14）：HKT 日历日，"%Y-%m-%d"。
# **不能复用 _TIME_FMT**——那是分钟精度 "%Y-%m-%d %H:%M"，分钟级守卫
# 几乎永远放行，按日计数等于没修。TEXT 字典序即日期序，比较安全。
_DATE_FMT = "%Y-%m-%d"


def _today_str() -> str:
    """HKT 今日日期字符串（hit_count 按日计数守卫用）。"""
    return datetime.datetime.now(_HKT).strftime(_DATE_FMT)


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


# 单条记忆内容长度上限（字符）。narrative 注入展示上限是 200 字，
# 库里留 500 的余量（LLM 手滑写长文时截断保底，不拒绝——对话中不能
# 卡死写入）。截断带 log，便于发现 LLM 长文倾向（2026-08-01 评审 D4）。
_MAX_CONTENT_LEN = 500


def _cap_content(content: str, context: str) -> str:
    """截断超长记忆内容（500 字），超限部分替换为省略号并记日志。"""
    if len(content) > _MAX_CONTENT_LEN:
        log.warning("记忆内容超长截断（%s）：%d → %d 字", context, len(content), _MAX_CONTENT_LEN)
        return content[:_MAX_CONTENT_LEN] + "……"
    return content


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

    # ── 内部工具 ────────────────────────────────────────────

    async def _count_hits(self, ids: list[str]) -> None:
        """hit_count 按日 +1（v13 弱信号，v14 改按「不同天数」计数）。空列表无操作。

        hit_count 记录「这条记忆被系统浮现在多少个不同日子」（HKT 日历
        日去重，守卫见 SQL）——被动读取（叙事注入、小本本翻旧事、core
        保底席位）也计数，但它只作 apply_status_decay 的衰减燃料，
        **永不进入** _relevance_score 排序。排序仍只认 access_count
        （主动想起），那条 passive 纪律防的是「越注入越热门」正反馈
        闭环（见 recall() 注释）；衰减是另一件事——一条天天被注入的
        记忆不该因为「不是被主动 recall 的」就在 90 天后被冷藏。

        v14 语义修正（体检 T1-1）：v13 的原始次数计数被叙事注入的频率
        带崩——每次 LLM 模型调用前都浮现一遍（活跃日 25-70 次/天），
        阈值校准差一个数量级。按「不同天数」计后与聊天频率解耦：
        重度用户一天聊 50 轮，某记忆也只记 1 天。守卫放在 WHERE 里，
        同日重复浮现命中 0 行（还省写入）。

        已知轻微过计（不修，记录在案）：叙事保底席位替换末位时，被换下
        的 recent 记忆当天已吃过 +1 却未真正注入——守卫后噪音封顶
        1 天/日，远小于衰减阈值量级。
        """
        if not ids:
            return
        today = _today_str()
        placeholders = ",".join("?" for _ in ids)
        await self._db.execute(
            f"""UPDATE memories
                SET hit_count = hit_count + 1, last_hit_date = ?
                WHERE id IN ({placeholders})
                  AND COALESCE(last_hit_date, '') != ?""",
            (today, *ids, today),
        )
        await self._db.commit()

    # ── 写入 ────────────────────────────────────────────────

    async def add(
        self,
        category: str,
        content: str,
        source: str | None = None,
    ) -> dict:
        """添加一条记忆（原子去重）。返回实际生效的记忆条目。

        去重规则：同一 category 下已有完全相同的 content（活跃行）时，
        不重复添加，只刷新 created_at 和 last_used_at（以及 updated_at，
        v12 语义：再次想起也视为一次内容刷新；source 保留首次来源）。

        source（v12）：来源追踪——后台提取传 ``chat:{session_id}``，
        agentic remember 不传（NULL）。「小汐为什么记得这个」可追溯。

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
        content = _cap_content(content, "add")
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, last_used_at, access_count,
                status, updated_at, source)
               VALUES (?, ?, ?, ?, ?, 0, 'active', ?, ?)
               ON CONFLICT(category, content) WHERE status IN ('active', 'core')
               DO UPDATE SET
                   created_at = excluded.created_at,
                   last_used_at = excluded.last_used_at,
                   updated_at = excluded.updated_at""",
            (mem_id, content, category, now, now, now, source),
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
        """覆盖一条记忆的内容，保留首次时间、记录修改时间与旧文本。

        v12 语义（评审 D5）：created_at 是「第一次记住」的时间，覆盖
        只刷 updated_at——否则时间衰减被重置，被纠正的旧记忆反而
        变成「新记忆」；旧文本存进 prev_content（单层快照），矛盾
        纠正后仍可回看旧说法。
        """
        new_content = _cap_content((new_content or "").strip(), "update_content")
        if not new_content:
            return False
        # 先读旧文本（同一事务内完成：取旧值 → 覆盖），
        # 找不到目标行直接返回 False（不写空快照）
        cursor = await self._db.execute(
            "SELECT content FROM memories WHERE id = ? AND status IN ('active', 'core')",
            (memory_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return False
        old_content = row[0]

        now = _now()
        cursor = await self._db.execute(
            """UPDATE memories
               SET content = ?, updated_at = ?, prev_content = ?, last_used_at = ?
               WHERE id = ? AND status IN ('active', 'core')""",
            (new_content, now, old_content, now, memory_id),
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
        """清空所有记忆（含 archived 冷藏行）。返回清除的条数。

        计数含 archived（2026-08-01 体检修复）：旧版只数 active+core，
        而删除删的是全表——冷藏记忆被删却不计入返回数字，用户看到的
        清除量比真实删除量少。冷藏也是记忆。调用前应经过
        backup_before_clear()（其备份同样含 archived，否则冷藏行在
        清空时被永久销毁）。
        """
        cursor = await self._db.execute("SELECT COUNT(*) FROM memories")
        count = (await cursor.fetchone())[0]
        await self._db.execute("DELETE FROM memories")
        await self._db.commit()
        return count

    # ── 备份 / 导出 ─────────────────────────────────────────

    async def backup_before_clear(self, backup_dir: Path | None = None) -> Path:
        """清空前将全部记忆（含 archived）导出为 JSON 备份文件，返回备份路径。

        备份含冷藏记忆（include_archived=True，2026-08-01 体检阻塞项
        修复）：clear() 删的是全表，若备份只导 active+core，archived
        会在「清空」时被永久销毁且无备份——直接违反 apply_status_decay
        立下的「遗忘是可回溯的冷藏，不是销毁」不变量。

        备份目录 700、文件 600（含敏感数据，禁止同机其他用户读取）。
        backup_dir 供测试注入，生产默认 data/backups/。
        """
        from database.core import restrict_backup_permissions

        data = await self.export_json(include_archived=True)

        if backup_dir is None:
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

    async def export_json(self, include_archived: bool = False) -> str:
        """导出记忆为 JSON 字符串（用于下载）。

        include_archived=False 时只含活跃+core（默认导出语义）；
        True 时连同冷藏的 archived 一并导出（D8：冷库可回看——
        否则「冷藏」对用户就是永久丢失）。
        """
        status_filter = "IN ('active', 'core')" if not include_archived \
            else "IN ('active', 'core', 'archived')"
        cursor = await self._db.execute(
            f"SELECT * FROM memories WHERE status {status_filter} ORDER BY created_at DESC"
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

        passive=True 时不触碰 last_used_at / access_count——供 NarrativeBuilder
        等系统被动读取的场景使用。否则每次 LLM 调用前的叙事注入都会给
        top-K 记忆刷新一轮热度和 last_used_at，形成「越被注入越热门、
        越热门越被注入」的正反馈闭环，把同样的记忆永久钉在榜首。

        计数器双信号（v13 引入、v14 按日计数）：无论 passive 与否都会
        按「不同天数」+1 hit_count——它只作衰减燃料（apply_status_decay），
        不进上面的排序键，因此不破坏被动纪律。详见 _count_hits。
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

        # 计数器（双信号，v13 引入、v14 按日计数）：
        # - access_count / last_used_at：仅主动召回（passive=False）更新，
        #   参与 _relevance_score 排序——被动注入刷这两个会形成正反馈
        #   闭环，铁律禁止；
        # - hit_count：任何读取（含 passive）都按「不同天数」+1，只做
        #   衰减燃料，不进排序（见 _count_hits 的设计说明）。
        if matched:
            ids = [m["id"] for m in matched]
            placeholders = ",".join("?" for _ in ids)
            if passive:
                await self._count_hits(ids)
                return matched
            today = _today_str()
            # 主动分支不能用 _count_hits 的 WHERE 守卫版——守卫会把
            # 「今天已被浮现过」的行整行排除，连坐 access_count 不加。
            # access_count 是「被主动想起」的真实信号（排序热度 + core
            # 晋升条件），必须无条件计数；hit 两列走 CASE 条件自增。
            await self._db.execute(
                f"""UPDATE memories
                    SET last_used_at = ?,
                        access_count = access_count + 1,
                        hit_count = hit_count + CASE
                            WHEN COALESCE(last_hit_date, '') != ? THEN 1 ELSE 0 END,
                        last_hit_date = CASE
                            WHEN COALESCE(last_hit_date, '') != ? THEN ?
                            ELSE last_hit_date END
                    WHERE id IN ({placeholders})""",
                (_now(), today, today, today, *ids),
            )
            await self._db.commit()

        return matched

    async def recall_by_category(self, category: str, limit: int = 5) -> list[dict]:
        """按精确类别查询记忆（用于 remember 工具的矛盾检测）。

        与 recall() 不同，这里用 LIKE 模糊匹配且不更新任何计数器
        （access_count / hit_count 都不碰）——这是写入路径的内部查找
        （「这条新事实有没有和旧记忆矛盾」），不是把记忆浮现到小汐
        眼前，不应计入浮现次数。
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

        用于叙事构建器注入系统提示词。被动读取：不更新 last_used_at /
        access_count，但按日计 hit_count（v13 衰减弱信号、v14 按不同
        天数——被叙事浮现过的记忆不该到期冷藏）。
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
        mems = rows_to_dicts(cursor, rows)
        await self._count_hits([m["id"] for m in mems])
        return mems

    async def old_memories(self, min_age_days: int = 90, limit: int = 5,
                           exclude_ids: list[str] | None = None) -> list[dict]:
        """随机采样足够久远的活跃记忆（「翻小本本」用）。

        纯被动：不更新 last_used_at / access_count。调用方是记忆回声
        检测器——若刷热度，被 echo 的记忆会在 _relevance_score 里
        突然「复活」霸榜（L220-223 注释记录的正反馈闭环），必须只读。
        但按日计 hit_count（v13 引入、v14 按不同天数）：被翻出来回声
        的记忆正是「系统浮现过」的典型，衰减燃料该收下这个信号。

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
        mems = rows_to_dicts(cursor, rows)
        await self._count_hits([m["id"] for m in mems])
        return mems

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

    async def all(self, include_archived: bool = False) -> list[dict]:
        """返回全部活跃记忆列表。

        include_archived=True 时含冷藏的 archived（面板回看用），
        与 export_json 的语义一致。
        """
        status_filter = "IN ('active', 'core')" if not include_archived \
            else "IN ('active', 'core', 'archived')"
        cursor = await self._db.execute(
            f"SELECT * FROM memories WHERE status {status_filter} ORDER BY created_at DESC"
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    # ── 分层与衰减（B2）─────────────────────────────────────

    async def top_core(self, limit: int = 1) -> list[dict]:
        """按最近使用取 core 记忆（叙事保底席位用）。

        不刷热度（同 old_memories 的被动纪律——若刷 access_count，
        core 会在 _relevance_score 里自我加冕，违背被动读取原则）；
        但按日计 hit_count（v13 引入、v14 按不同天数）：保底席位的
        浮现是衰减燃料，不是排名信号。
        """
        cursor = await self._db.execute(
            """SELECT * FROM memories WHERE status = 'core'
               ORDER BY COALESCE(last_used_at, created_at) DESC LIMIT ?""",
            (limit,),
        )
        rows = await cursor.fetchall()
        mems = rows_to_dicts(cursor, rows)
        await self._count_hits([m["id"] for m in mems])
        return mems

    async def apply_status_decay(self) -> dict:
        """分层衰减：active→core（高频晋升）/ active→archived（低频遗忘）。

        双信号燃料（v13）：access_count（被主动想起）+ hit_count（被
        系统浮现，含被动注入）。修复前衰减只烧 access_count，而它唯一
        的增量点在主动 recall——主消费路径（叙事注入）按铁律 passive
        → 稳态下「芒果过敏」这类天天被注入的高重要事实也会在 90 天后
        被冷藏，core 晋升实际不可达、保底席位是死代码。hit_count 补上
        了衰减燃料，且不参与排序（见 _count_hits）。

        阈值见模块尾部常量。设计纪律：
        - 只从 active 搬——core 不降级、archived 不自动复活；冷冷重提
          旧事时 add() 插新行开始新生命周期（archived 已释放 v10 唯一
          索引槽位）；
        - 晋升先于归档执行（顺序为语义清晰，两条 SQL 互不干涉）；
        - 「重要的事」类别豁免归档——高重要事实永不自动冷藏，只允许
          手动删除/覆盖（类别名与提取 prompt 的类别表一致）；
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
               WHERE status = 'active'
                 AND (access_count >= ? OR hit_count >= ?)
                 AND created_at <= ?""",
            (_CORE_MIN_ACCESSES, _CORE_MIN_HITS, core_cutoff),
        )
        promoted = cursor.rowcount

        exempt = ",".join("?" for _ in _ARCHIVE_EXEMPT_CATEGORIES)
        cursor = await self._db.execute(
            f"""UPDATE memories SET status = 'archived'
                WHERE status = 'active'
                  AND (access_count + hit_count) <= ?
                  AND created_at <= ?
                  AND category NOT IN ({exempt})""",
            (_ARCHIVE_MAX_ACCESSES, archive_cutoff, *_ARCHIVE_EXEMPT_CATEGORIES),
        )
        archived = cursor.rowcount

        await self._db.commit()
        if promoted or archived:
            log.info("记忆分层衰减：晋升 %d 条 core / 归档 %d 条", promoted, archived)
        return {"promoted": promoted, "archived": archived}

    async def check_memory_health(self) -> dict:
        """记忆系统健康指标（纯只读，无副作用、不写日志）。

        实盘教训（2026-08-01 评审 D3）：写入完全依赖 LLM 自觉调用工具，
        曾出现「31 条真实对话、0 条记忆」跑一周无人察觉。本方法产出
        abnormal 信号，由调用方（memory/extract.py 的
        check_and_report_memory_health）负责节流告警：
        - 有相当对话量（>= _HEALTH_MIN_CHATS）却零记忆 → 写路径异常；
        - 距上次写入超过 _HEALTH_WARN_DAYS 天 → 记忆已停滞。
        """
        cursor = await self._db.execute(
            "SELECT COUNT(*), MAX(created_at) FROM memories WHERE status IN ('active', 'core')"
        )
        row = await cursor.fetchone()
        count, newest = (row[0], row[1]) if row else (0, None)

        now = datetime.datetime.now(_HKT)
        days_since_write = None
        if newest:
            days_since_write = max(0.0, (now - _parse_time(newest)).total_seconds() / 86400)

        chats = 0
        if count == 0:
            # 只在零记忆时才查对话量（异常信号所需的唯一场景）
            cursor = await self._db.execute("SELECT COUNT(*) FROM chat_messages")
            chats = (await cursor.fetchone())[0]

        abnormal = (
            (count == 0 and chats >= _HEALTH_MIN_CHATS)
            or (days_since_write is not None and days_since_write >= _HEALTH_WARN_DAYS)
        )
        return {
            "active_count": count,
            "days_since_write": days_since_write,
            "chat_count": chats,
            "abnormal": abnormal,
        }


# ── 分层衰减阈值 ──────────────────────────────────────────────
# 晋升 core：被主动想起 ≥5 次，或在 ≥20 个不同日子被系统浮现
#   （v14 hit_count 按 HKT 日历日去重，与聊天频率解耦：20 ≈ 重度用户
#   3-4 周 / 轻度用户 6-8 周的「经月活跃在场」），且存在 ≥30 天
#   （年龄闸防「爆发一周即晋升」）。
# 归档：主动想起 + 浮现天数合计 ≤1 且 ≥90 天——三个月里至多一天
#   被想起或被注入过才冷藏。「重要的事」类别豁免（高重要事实永不
#   自动冷藏）。v13 时代 hit 是原始次数（活跃日 25-70 次/天通胀），
#   v14 改按日计数后阈值 90 → 20（体检 T1-1 校准）。
# 衰减 job 挂心跳维护（_periodic_maintenance，2h），日志输出计数便于调参。
_CORE_MIN_ACCESSES = 5
_CORE_MIN_HITS = 20
_CORE_MIN_AGE_DAYS = 30
_ARCHIVE_MAX_ACCESSES = 1
_ARCHIVE_MIN_AGE_DAYS = 90
_ARCHIVE_EXEMPT_CATEGORIES = frozenset({"重要的事"})


# ── 记忆健康检查阈值 ─────────────────────────────────────────────
# 有 ≥10 条对话消息却零记忆 → 写路径疑似失灵；距上次写入 ≥7 天 → 停滞。
# 阈值偏保守（宁可错报一次，不可再静默一周）。
_HEALTH_MIN_CHATS = 10
_HEALTH_WARN_DAYS = 7


async def run_status_decay() -> dict:
    """分层衰减便捷入口（心跳维护任务用，在全局库上构造管理器）。"""
    from database.core import get_db
    return await MemoryManager(await get_db()).apply_status_decay()
