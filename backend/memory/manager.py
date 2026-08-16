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
from database.core import get_write_lock, rows_to_dicts

# Russell 情绪空间坐标（heartbeat_moods 单持有，本模块只读引用）。
# 无循环导入：heartbeat_moods 只 import stdlib + utils.logger。
from heartbeat_moods import MOOD_VECTORS
from utils.timezone import _TIME_FMT
from utils.timezone import HKT as _HKT
from utils.timezone import now_str as _now

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
    实现已上移 database.core.escape_like（events 相似匹配复用），
    本壳保留模块内既有调用与测试 import 兼容。
    """
    from database.core import escape_like
    return escape_like(s)


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

# 清空前 JSON 备份保留份数（体检 D-1：原来无轮换，与 userdata 备份的
# 5 份策略对齐）。
_MAX_JSON_BACKUPS = 5

# superseded 历史链上限（O3）：单条记忆保留最新 5 代，更旧合并为一行
# digest——链无上限时历史行与活跃行同量级增长，且导出/备份无谓携带。
_MAX_SUPERSEDED_GENERATIONS = 5

# ── 情绪核心记忆（schema v18 情绪联动）────────────────────────
# 「当前情绪」固定行：status='core'，**只存不浮现**——一切浮现/计数
# 读面按 id 排除（_MOOD_ROW_FILTER），仅作回放（history / 导出账本）
# 与调制信号来源。固定确定性 id 的好处：幂等 upsert、最便宜的 SQL
# 排除谓词、测试可直接断言（uuid 动态 id 每次写前还得先查）。
MOOD_MEMORY_ID = "mood_current"
MOOD_MEMORY_CATEGORY = "小汐的心情"
MOOD_MEMORY_SOURCE = "system:mood"

# 排除谓词（id 排除而非 category/source：确定性单行、单写入方，谓词
# 最便宜且零歧义；superseded 链行已因 status='superseded' 天然跌出
# 读面，无需本谓词照顾）。挂在所有浮现/计数读面的 WHERE 后。
_MOOD_ROW_FILTER = f"AND id != '{MOOD_MEMORY_ID}'"

# 画像块（批次②）：固定 id 的 core 行，只走叙事专属通道（narrative.py
# 单独查它做「画像优先注入」），不进常规召回/热度/衰减候选。
# PROFILE_MEMORY_ID 与 memory/profile.py 蒸馏侧双定义同值（本侧只做读面
# 过滤用），两处必须一致。
PROFILE_MEMORY_ID = "profile_cold"
PROFILE_ROW_FILTER = f"AND id != '{PROFILE_MEMORY_ID}'"

# 读面排除组合（批② 后 13 处拼接收拢）：mood 固定行 + 画像固定行
# 全部读面统一排除——两个基础常量保留（v18/mood 测试钉死）。
_READ_EXCLUSIONS = f"{_MOOD_ROW_FILTER} {PROFILE_ROW_FILTER}"


def _cap_content(content: str, context: str) -> str:
    """截断超长记忆内容（500 字），超限部分替换为省略号并记日志。"""
    if len(content) > _MAX_CONTENT_LEN:
        log.warning("记忆内容超长截断（%s）：%d → %d 字", context, len(content), _MAX_CONTENT_LEN)
        return content[:_MAX_CONTENT_LEN] + "……"
    return content


def _coerce_importance(value) -> int:
    """importance 归一化到 1-10（LLM 手滑/缺失兜底为 5，I1）。"""
    try:
        n = int(value)
    except (TypeError, ValueError):
        return 5
    return max(1, min(10, n))


def _coerce_unit(value) -> float:
    """valence/arousal 归一化到 [-1, 1]（schema v18 情绪联动）。

    LLM 手滑/缺失兜底为 0.0（中性）；NaN 显式归 0——json.loads 接受
    NaN 字面量，float() 不拦它，入库会污染亲和度计算。
    """
    try:
        x = float(value)
    except (TypeError, ValueError):
        return 0.0
    if x != x:  # NaN
        return 0.0
    return max(-1.0, min(1.0, x))


def _coerce_valence(value) -> float:
    """valence（情绪效价）钳制：缺失/非法 → 0.0 中性。"""
    return _coerce_unit(value)


def _coerce_arousal(value) -> float:
    """arousal（唤醒度）钳制：缺失/非法 → 0.0 中性。"""
    return _coerce_unit(value)


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


# mood-congruent 因子权重（schema v18 情绪联动）：0.15 = importance 档
# （0.3）的一半，远低于话题档（1.0）与二段硬分层——压不过话题命中，
# 满幅差 ≈1.5 天时近差，只在近持平时换位：软调制，不是硬过滤。
_MOOD_AFFINITY_WEIGHT = 0.15


def _mood_affinity(memory: dict, mood_valence: float | None) -> float:
    """mood-congruent 亲和度 [0,1]（schema v18 情绪联动第六因子）。

    亲和度 = 1 - |v_mem - v_mood| / 2：记忆效价与当前心情效价越接近分越高
    （Bower 1981 mood-congruent memory）。**加权而非硬过滤**——异效价记忆
    仍可凭话题/时近/热度浮现，防「只想起同色记忆 → 情绪更固化」回路。

    中性免疫：valence 缺失（存量/未打分）或显式 0 → 亲和度恒定 0.5，
    与 mood_valence 取值无关——中性记忆不吃任何心情的偏置，也不被系统性
    惩罚（回归测试用严格等式钉死）。

    mood_valence=None（调用方未提供当前心情）→ 返回 0.0，因子整体关闭，
    评分与 v17 逐字节一致（extract 冲突上下文 / agentic recall 走此路径）。
    """
    if mood_valence is None:
        return 0.0
    raw = memory.get("valence")
    if raw is None or _coerce_valence(raw) == 0.0:
        return 0.5
    return 1.0 - abs(_coerce_valence(raw) - mood_valence) / 2.0


def _relevance_score(memory: dict, now: datetime.datetime, query: str = "",
                     mood_valence: float | None = None) -> float:
    """计算记忆相关性分数。

    六因子加权（O1/I1 schema v17；mood-congruent schema v18）：
    - 时间衰减（last_mention_ts，半衰期 ≈ 7 天——O1：统一提及时钟，
      被 add/update/hit 过的记忆续命，created_at 不再兼职衰减）
    - 访问热度（access_count，对数衰减防止老热门垄断）
    - 最近使用（last_used_at，半衰期 ≈ 3.5 天）
    - 重要性（importance 1-10，LLM 打分，权重 0.3 档——I1）
    - 话题命中（query 与 content+category 的字面重叠，B1 新增，权重 1.0）
    - 情绪亲和（当前心情效价与记忆 valence 的接近度——schema v18，
      权重 0.15 软调制；mood_valence=None 时因子关闭，等同 v17）
    """
    # 时间衰减（O1：基于 last_mention_ts，缺失回退 created_at——
    # 被 add/update/hit 过的记忆续命，created_at 不再兼职衰减时钟）
    last_mention = memory.get("last_mention_ts") or memory.get("created_at")
    days_old = max(0, (now - _parse_time(last_mention)).total_seconds() / 86400)
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

    # 重要性（I1：LLM 提取时打分 1-10，权重 0.3 档——与 usage_recency 同档）
    importance_boost = _coerce_importance(memory.get("importance")) * 0.03

    # 话题加分（权重 1.0）：层内排序用——同为话题命中时，重叠比例高的
    # 靠前。（层间「命中优先」由 recall 的二段排序保证，不靠这个权重硬扛
    # 新记忆的 recency+usage 双加成。）
    if query:
        haystack = f"{memory.get('content', '')} {memory.get('category', '')}"
        topic = _bigram_overlap(query, haystack)
    else:
        topic = 0.0

    # 情绪亲和（schema v18）：mood-congruent 软调制，加权而非硬过滤
    mood_boost = _MOOD_AFFINITY_WEIGHT * _mood_affinity(memory, mood_valence)

    return recency + access_boost + usage_recency + importance_boost + topic + mood_boost


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
        # 单语句写也挂逻辑写锁：本连接的 commit 会提交一切进行中的
        # 序列，持锁保证不与多语句写（superseded 链等）交错
        async with get_write_lock():
            await self._db.execute(
                f"""UPDATE memories
                    SET hit_count = hit_count + 1, last_hit_date = ?,
                        last_mention_ts = ?   -- O1：被浮现也是一次提及，续命衰减钟
                    WHERE id IN ({placeholders})
                      AND COALESCE(last_hit_date, '') != ?""",
                (today, _now(), *ids, today),
            )
            await self._db.commit()

    # ── 写入 ────────────────────────────────────────────────

    async def add(
        self,
        category: str,
        content: str,
        source: str | None = None,
        importance: int = 5,
        valence=None,
        arousal=None,
    ) -> dict:
        """添加一条记忆（原子去重）。返回实际生效的记忆条目。

        去重规则：同一 category 下已有完全相同的 content（活跃行）时，
        不重复添加，只刷新 last_mention_ts 和 last_used_at（以及 updated_at，
        v12 语义：再次想起也视为一次内容刷新；source 保留首次来源）。
        **created_at 严格不可变**（O1，schema v17）：它是「第一次记住」
        的时间，去重刷新不再触碰——否则被反复 add 的记忆衰减时钟被重置，
        与被反复 update 的记忆老化形成两套矛盾时钟。

        importance（I1，schema v17）：提取时 LLM 打 1-10 分，进相关性
        排序权重 0.3 档；agentic remember 不传默认 5（中性）。
        **importance 去重时保持首写**（第一次记住时的重要性判断稳定）。

        valence/arousal（情绪联动，schema v18）：提取时 LLM 打 Russell
        情绪空间分（各 -1..1，缺失/非法 → 0.0 中性，见 _coerce_unit）。
        与 importance 刻意不同的纪律：**去重时随最新打分刷新**——情绪是
        状态量，同一事实重提时最新的情绪色彩胜出（提取重试也自愈：
        首跑漏打分落 0，次跑补上即刷新）。agentic remember 不传默认 0。

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
        # 存储侧时间词归一化（2026-08-05 用户反馈）：相对时间词落库前
        # 改写为绝对日期，防「后天考试」过了两天读不懂（提示词约束在
        # soul.py「时间的写法」+ 提取提示词，这里是硬兜底）。
        from utils.time_words import normalize_time_words

        content = normalize_time_words(content)
        content = _cap_content(content, "add")
        importance = _coerce_importance(importance)
        valence = _coerce_valence(valence)
        arousal = _coerce_arousal(arousal)
        now = _now()
        mem_id = uuid.uuid4().hex[:16]

        # 逻辑写锁：INSERT+commit+读回是一个完整序列，不能与其他写交错
        async with get_write_lock():
            await self._db.execute(
                """INSERT INTO memories
                   (id, content, category, created_at, last_used_at, access_count,
                    status, updated_at, source, last_mention_ts, importance,
                    valence, arousal)
                   VALUES (?, ?, ?, ?, ?, 0, 'active', ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(category, content) WHERE status IN ('active', 'core')
                   DO UPDATE SET
                       last_used_at = excluded.last_used_at,
                       updated_at = excluded.updated_at,
                       last_mention_ts = excluded.last_mention_ts,
                       valence = excluded.valence,
                       arousal = excluded.arousal""",
                (mem_id, content, category, now, now, now, source, now, importance,
                 valence, arousal),
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
        """覆盖一条记忆的内容，保留首次时间、记录修改时间，旧文本落历史链。

        v12 语义（评审 D5）：created_at 是「第一次记住」的时间，覆盖
        只刷 updated_at——否则时间衰减被重置，被纠正的旧记忆反而
        变成「新记忆」。

        v15 语义（体检 R3，Zep/Graphiti 边失效化思想）：被覆盖的旧
        内容另落一行 status='superseded'（superseded_by 指向本记忆），
        形成完整历史链——prev_content 是单层快查（二次覆盖即丢最初
        版本），superseded 行链则无论覆盖多少次、每一代旧说法都可
        追溯。读面谓词统一 IN('active','core')，superseded 行在日常
        读面自然消失；v10 去重索引同谓词，故重提同内容不被历史行
        阻挡（「忘了又想起」语义不受影响）。
        """
        # 逻辑写锁在公共入口获取；upsert_mood_memory 等持锁调用方走内层
        async with get_write_lock():
            return await self._update_content_inner(memory_id, new_content)

    async def _update_content_inner(self, memory_id: str, new_content: str) -> bool:
        """update_content 的锁内实现（调用方必须已持有 get_write_lock）。

        asyncio.Lock 不可重入：upsert_mood_memory 持锁后只能调本方法，
        调公共入口会死锁。
        """
        # 存储侧时间词归一化（同 add：相对时间词改写为绝对日期）
        from utils.time_words import normalize_time_words

        new_content = (new_content or "").strip()
        if not new_content:
            return False
        new_content = normalize_time_words(new_content)
        new_content = _cap_content(new_content, "update_content")
        if not new_content:
            return False
        # 先读旧文本与历史行所需字段（同一事务内完成：取旧值 → 落
        # 历史行 → 覆盖），找不到目标行直接返回 False（不写空快照）
        cursor = await self._db.execute(
            """SELECT content, category, created_at, source
               FROM memories WHERE id = ? AND status IN ('active', 'core')""",
            (memory_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return False
        old_content, category, original_created_at, source = row

        now = _now()
        # 历史行：created_at 用本记忆的**最初**创建时间（时序诚实——
        # 这条旧说法确实是那时说出的），状态 superseded 使其跌出
        # 一切读面与去重谓词
        await self._db.execute(
            """INSERT INTO memories
               (id, content, category, created_at, status, superseded_by, source)
               VALUES (?, ?, ?, ?, 'superseded', ?, ?)""",
            (uuid.uuid4().hex[:16], old_content, category,
             original_created_at, memory_id, source),
        )
        cursor = await self._db.execute(
            """UPDATE memories
               SET content = ?, updated_at = ?, prev_content = ?, last_used_at = ?,
                   last_mention_ts = ?   -- O1：覆盖也是一次提及，续命衰减钟
               WHERE id = ? AND status IN ('active', 'core')""",
            (new_content, now, old_content, now, now, memory_id),
        )
        await self.compress_history(memory_id)  # O3：链上限压缩（锁内被调，不独立 commit/加锁）
        await self._db.commit()
        return cursor.rowcount > 0

    async def history(self, memory_id: str) -> list[dict]:
        """一条记忆的历代旧说法（superseded 行，按时间正序）。

        供「我以前是不是说过……」类回看（体检 R3）。不含当前内容
        （当前内容在记忆本行）。
        """
        cursor = await self._db.execute(
            """SELECT * FROM memories
               WHERE superseded_by = ? AND status = 'superseded'
               ORDER BY created_at ASC, rowid ASC""",  # rowid：同 created_at 时按插入序
            (memory_id,),
        )
        rows = await cursor.fetchall()
        return rows_to_dicts(cursor, rows)

    async def compress_history(self, memory_id: str) -> None:
        """superseded 链上限：每记忆保留最新 N 代，更旧合并为一行 digest。

        O3（2026-08-03 记忆库诊断）：单条记忆被覆盖几十次时，历史行
        与活跃行同量级增长，导出/备份无谓携带失控。上限 5 代：最新 4 代
        保留逐行，更旧的合并为一行 digest（内容以「；」连接，超长从旧端
        截断、起始补「…」），链恒 ≤5 行。digest 是概念摘要——「这条
        记忆改过很多次」仍可追溯，逐代细节让位于上限。

        合并按 rowid ASC（插入序）：superseded 行的 created_at 全是本
        记忆最初创建时间（时序诚实），同值无法排序，必须用插入序。

        本方法不 commit、不加锁（调用方统一提交）：update_content 与
        consolidate._apply_merge 都在各自持锁事务内调用——asyncio.Lock
        不可重入，此处再加锁即死锁。
        """
        cursor = await self._db.execute(
            """SELECT id, content, rowid FROM memories
               WHERE superseded_by = ? AND status = 'superseded'
               ORDER BY rowid ASC""",
            (memory_id,),
        )
        rows = await cursor.fetchall()
        if len(rows) <= _MAX_SUPERSEDED_GENERATIONS:
            return
        keep = _MAX_SUPERSEDED_GENERATIONS - 1  # 末位留给 digest
        to_merge = rows[:-keep]
        digest = "；".join(r[1] for r in to_merge)
        if len(digest) > _MAX_CONTENT_LEN:
            # 超长从旧端截断（保留最新端），起始补省略号标记不完整
            digest = "…" + digest[-(_MAX_CONTENT_LEN - 1):]
        keep_id = to_merge[0][0]  # 合并进最旧行（digest 在那行）
        drop_ids = [r[0] for r in to_merge[1:]]
        await self._db.execute(
            "UPDATE memories SET content = ? WHERE id = ?", (digest, keep_id)
        )
        if drop_ids:
            placeholders = ",".join("?" for _ in drop_ids)
            await self._db.execute(
                f"DELETE FROM memories WHERE id IN ({placeholders})", drop_ids
            )

    # ── 删除 ────────────────────────────────────────────────

    async def delete(self, memory_id: str) -> bool:
        """删除指定 ID 的记忆。返回是否成功。"""
        async with get_write_lock():
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
        async with get_write_lock():
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

        # include_superseded=True（O3）：完整快照——历史链与冷藏行一样
        # 属于记忆账本，清空后应从备份可回看
        data = await self.export_json(include_archived=True, include_superseded=True)

        if backup_dir is None:
            backup_dir = Path(__file__).parent.parent / "data" / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        restrict_backup_permissions(backup_dir, is_dir=True)

        timestamp = datetime.datetime.now(_HKT).strftime("%Y%m%d_%H%M%S")
        backup_path = backup_dir / f"memories_backup_{timestamp}.json"
        # 同步文件写入卸载到线程池，避免阻塞事件循环
        await asyncio.to_thread(backup_path.write_text, data, encoding="utf-8")
        restrict_backup_permissions(backup_path)

        # 轮换（体检 D-1）：memories_backup_*.json 与 userdata 备份同目录，
        # 但不在 database.core 的 db 轮换 glob 内——清空是手动低频操作，
        # 不轮换也会缓慢无限累积。只保留最近 _MAX_JSON_BACKUPS 份。
        json_backups = sorted(
            backup_dir.glob("memories_backup_*.json"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        for old in json_backups[_MAX_JSON_BACKUPS:]:
            old.unlink(missing_ok=True)

        log.info("记忆备份已保存：%s", backup_path)
        return backup_path

    async def export_json(self, include_archived: bool = False,
                          include_superseded: bool = False) -> str:
        """导出记忆为 JSON 字符串（用于下载）。

        include_archived=False 时只含活跃+core（默认导出语义）；
        True 时连同冷藏的 archived 一并导出（D8：冷库可回看——
        否则「冷藏」对用户就是永久丢失）。
        include_superseded=True 时连同 superseded 历史链一并导出
        （O3：历史链此前只存于整库快照备份，加开关后用户可完整回看
        「这条记忆改过哪些说法」）。
        """
        statuses = ["'active'", "'core'"]
        if include_archived:
            statuses.append("'archived'")
        if include_superseded:
            statuses.append("'superseded'")
        status_filter = f"IN ({', '.join(statuses)})"
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
        query: str = "", mood_valence: float | None = None,
    ) -> list[dict]:
        """回忆记忆。可按 category 筛选，不传则返回全部。

        按相关性评分排序（时间衰减 × 访问热度 × 最近使用 × 话题命中）。
        query 传当前对话的最新一句话时，字面重叠的记忆获得话题加分
        （只进排序键，不影响 passive 语义——被动读取仍不刷热度）。

        mood_valence（schema v18 情绪联动）：当前心情的效价（-1..1）。
        传入时进 _relevance_score 第六因子 mood-congruent 加权（软调制，
        不硬过滤）；None（agentic recall 工具 / extract 冲突上下文）→
        因子关闭，行为与 v17 一致。叙事注入路径由 NarrativeBuilder 查
        life_state 后传入。

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
            # 类别路径同样不能 created_at DESC 预筛（体检 D2：无类别路径
            # 早已修复的同款缺陷在类别路径原样保留——类别内「旧但高频/
            # 高相关」的记忆永远进不了候选池，评分只能在最新 N 条里选）。
            # LIKE 过滤后全量评分，单用户量级（百级～千级）可承受。
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE category LIKE ? ESCAPE '\\' AND status IN ('active', 'core')
                    {_READ_EXCLUSIONS}""",
                (f"%{_escape_like(category)}%",),
            )
        else:
            # 相关性召回不能用 created_at DESC 预筛：候选池一旦被「最新 N 条」
            # 限死，_relevance_score 的 access 热度因子对旧记忆完全失效——
            # 旧的核心偏好/重要约定无论被想起多少次都进不了候选池，「相关路」
            # 退化成「最新路」（narrative 的 hybrid 注入名存实亡）。
            # 单用户记忆量级（百级～千级）全表评分完全可承受。
            cursor = await self._db.execute(
                f"SELECT * FROM memories WHERE status IN ('active', 'core') {_READ_EXCLUSIONS}"
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
                return (hit, _relevance_score(m, now, query, mood_valence))
        else:
            def _sort_key(m):
                return _relevance_score(m, now, query, mood_valence)

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
            async with get_write_lock():
                await self._db.execute(
                    f"""UPDATE memories
                        SET last_used_at = ?,
                            access_count = access_count + 1,
                            hit_count = hit_count + CASE
                                WHEN COALESCE(last_hit_date, '') != ? THEN 1 ELSE 0 END,
                            last_hit_date = CASE
                                WHEN COALESCE(last_hit_date, '') != ? THEN ?
                                ELSE last_hit_date END,
                            last_mention_ts = ?   -- O1：主动想起也是提及
                        WHERE id IN ({placeholders})""",
                    (_now(), today, today, today, _now(), *ids),
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
            f"""SELECT * FROM memories
                WHERE category LIKE ? ESCAPE '\\' AND status IN ('active', 'core')
                {_READ_EXCLUSIONS}
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
            f"""SELECT * FROM (
                    SELECT * FROM memories
                    WHERE status IN ('active', 'core') {_READ_EXCLUSIONS}
                    ORDER BY COALESCE(last_mention_ts, created_at) DESC LIMIT ?   -- O1
                ) ORDER BY COALESCE(last_mention_ts, created_at) ASC""",
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
                    WHERE status IN ('active', 'core')
                      AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
                      AND id NOT IN ({placeholders})
                      {_READ_EXCLUSIONS}
                    ORDER BY RANDOM() LIMIT ?""",
                (cutoff, *exclude, limit),
            )
        else:
            cursor = await self._db.execute(
                f"""SELECT * FROM memories
                    WHERE status IN ('active', 'core')
                      AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
                      {_READ_EXCLUSIONS}
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
            f"""SELECT category, COUNT(*) as cnt FROM memories
                WHERE status IN ('active', 'core') {_READ_EXCLUSIONS}
                GROUP BY category"""
        )
        rows = await cursor.fetchall()
        return {r[0] or "其他": r[1] for r in rows}

    async def count(self) -> int:
        """返回活跃记忆总数。"""
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE status IN ('active', 'core') {_READ_EXCLUSIONS}"
        )
        row = await cursor.fetchone()
        return row[0] if row else 0

    async def has_recent_chat(self, hours: int = 24,
                              now: datetime.datetime | None = None) -> bool:
        """近 hours 小时内是否有真实对话（chat_messages 里 user 消息）。

        O2（2026-08-03 心情重构后评估）：_choose_mood 的 talked 信号
        先前读 recent(10) 记忆——「近 24h 有记忆活动」≈「近 24h 有值得
        记住的对话」，纯闲聊（无记忆产生）不触发。改从 chat_messages
        按 created_at 判断真实对话时间。纯只读，不碰任何计数器。
        """
        now = now or datetime.datetime.now(_HKT)
        cutoff = (now - datetime.timedelta(hours=hours)).strftime(_TIME_FMT)
        cursor = await self._db.execute(
            "SELECT 1 FROM chat_messages "
            "WHERE role = 'user' AND created_at >= ? LIMIT 1",
            (cutoff,),
        )
        return await cursor.fetchone() is not None

    async def has_sad_carryover(self, now: datetime.datetime | None = None) -> bool:
        """上一段会话是否有「还没散去的难过」（P1-1 carryover 信号）。

        读上一段对话的尾部 user 消息，命中难过词表（确定性可算，禁
        LLM 判定——路线图纪律）且落在 24h 窗口内 → True。作为
        _choose_mood 的第五信号，把 mood 向低效价方向轻推。

        定位上一段会话：life_state.active_session_id（presence 上报的
        当前活跃会话）排除后，取 updated_at 最近的一个**非 era** 会话
        （era=小汐的独处时间线，那里只有小汐自己在说话，没有冷冷的
        情绪）。任何读取失败 / 无上一段会话 → False（失败语义：信号
        缺失不推偏，同 talked 的降级纪律）。
        """
        from heartbeat_moods import (
            _CARRYOVER_SAD_LEXICON,
            _CARRYOVER_TAIL_LIMIT,
            _CARRYOVER_WINDOW_HOURS,
        )

        now = now or datetime.datetime.now(_HKT)
        cutoff = (now - datetime.timedelta(hours=_CARRYOVER_WINDOW_HOURS)).strftime(
            _TIME_FMT
        )
        try:
            cursor = await self._db.execute(
                "SELECT active_session_id FROM life_state WHERE id = 1"
            )
            row = await cursor.fetchone()
            active_sid = row[0] if row else None
            cursor = await self._db.execute(
                """SELECT m.content FROM chat_messages m
                   JOIN chat_sessions s ON s.id = m.session_id
                   WHERE m.role = 'user'
                     AND m.created_at >= ?
                     AND s.is_era = 0
                     AND m.session_id != ?
                   ORDER BY m.id DESC LIMIT ?""",
                (cutoff, active_sid or "", _CARRYOVER_TAIL_LIMIT),
            )
            rows = await cursor.fetchall()
        except Exception:
            log.exception("跨会话情绪基调检测失败，信号缺失（不推偏）")
            return False
        text = "\n".join(r[0] or "" for r in rows)
        return any(word in text for word in _CARRYOVER_SAD_LEXICON)

    async def get_mood_valence(self) -> float | None:
        """当前心情效价（schema v18 情绪联动的调制信号源）。

        读 life_state 单行 mood → MOOD_VECTORS 的 Russell valence 分量。
        无行 / mood 为空 / 未知词 → None（调用方关闭 mood 因子，浮现
        退回 v17 行为）。mood 词表与心跳 _choose_mood 的抽样空间同源
        （heartbeat_moods.MOOD_VECTORS），永不漂移。
        """
        cursor = await self._db.execute(
            "SELECT mood FROM life_state WHERE id = 1"
        )
        row = await cursor.fetchone()
        if not row or not row[0]:
            return None
        vec = MOOD_VECTORS.get(row[0])
        return vec[0] if vec is not None else None

    async def all(self, include_archived: bool = False) -> list[dict]:
        """返回全部活跃记忆列表。

        include_archived=True 时含冷藏的 archived（面板回看用），
        与 export_json 的语义一致。
        """
        status_filter = "IN ('active', 'core')" if not include_archived \
            else "IN ('active', 'core', 'archived')"
        cursor = await self._db.execute(
            f"SELECT * FROM memories WHERE status {status_filter} {_READ_EXCLUSIONS} "
            "ORDER BY created_at DESC"
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

        情绪核心记忆不占保底席位（_MOOD_ROW_FILTER）：它是小汐自己的
        心情账本，若计入会永久霸占 core 保底席——叙事里永远多一条
        「心情」，且高频心跳 update 刷它的 last_used_at 钉死榜首。
        """
        cursor = await self._db.execute(
            f"""SELECT * FROM memories WHERE status = 'core' {_READ_EXCLUSIONS}
                ORDER BY COALESCE(last_used_at, created_at) DESC LIMIT ?""",
            (limit,),
        )
        rows = await cursor.fetchall()
        mems = rows_to_dicts(cursor, rows)
        await self._count_hits([m["id"] for m in mems])
        return mems

    async def upsert_mood_memory(self, mood: str, now: str | None = None) -> dict | None:
        """把一次心情变化落进情绪核心记忆（回放账本，schema v18 情绪联动）。

        首次调用 INSERT core 行（add() 只会落 active，且其同内容去重语义
        与「每次换词留一代历史」冲突，故情绪行走裸 SQL）；此后走
        update_content——旧说法落 superseded 历史链（v15）并继承链上限
        5 代压缩（compress_history），created_at 保留首次创建时间——
        情绪轨迹可逐代回放（history() / export_json）。

        content 内嵌落库时间：superseded 行的 created_at 一律是本记忆
        最初创建时间（时序诚实设计），列上拿不到每代生效时间，只有文案
        能携带；副作用是每代 content 必不同，天然规避去重索引与幂等误判。

        行上 valence/arousal 存 MOOD_VECTORS 坐标——调制信号也落账本
        （导出/回放可读数值）。update_content 不动这两列（不改共享方法），
        本方法在链更新后补一条 UPDATE。

        只存不浮现：本行被一切浮现/计数读面排除（_MOOD_ROW_FILTER），
        不进叙事注入、不参与 recall/top_core/old_memories 候选。
        同分钟同心情重复调用 → no-op 返回 None（不空落历史行）。
        """
        mood = (mood or "").strip()
        if not mood:
            return None
        now = now or _now()
        content = f"{now} 起，小汐的心情是「{mood}」"
        vec = MOOD_VECTORS.get(mood, (0.0, 0.0))

        # 逻辑写锁整段持有：SELECT→INSERT/UPDATE→commit 是完整序列；
        # 内层调 _update_content_inner（公共入口会重入死锁）
        async with get_write_lock():
            cursor = await self._db.execute(
                "SELECT content FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
            )
            row = await cursor.fetchone()

            if row is None:
                await self._db.execute(
                    """INSERT INTO memories
                       (id, content, category, created_at, last_used_at, access_count,
                        status, updated_at, source, last_mention_ts, importance,
                        valence, arousal)
                       VALUES (?, ?, ?, ?, ?, 0, 'core', ?, ?, ?, 5, ?, ?)""",
                    (MOOD_MEMORY_ID, content, MOOD_MEMORY_CATEGORY, now, now,
                     now, MOOD_MEMORY_SOURCE, now, vec[0], vec[1]),
                )
                await self._db.commit()
            else:
                if row[0] == content:
                    return None  # 同分钟同心情：no-op
                await self._update_content_inner(MOOD_MEMORY_ID, content)
                await self._db.execute(
                    "UPDATE memories SET valence = ?, arousal = ? WHERE id = ?",
                    (vec[0], vec[1], MOOD_MEMORY_ID),
                )
                await self._db.commit()

            cursor = await self._db.execute(
                "SELECT * FROM memories WHERE id = ?", (MOOD_MEMORY_ID,)
            )
            return rows_to_dicts(cursor, [await cursor.fetchone()])[0]

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
        - 晋升年龄闸看 created_at（存在 ≥30 天——「爆发一周即晋升」的
          防线是「存在多久」，不是「多久没提」）；归档年龄闸看
          last_mention_ts（O1：多久没被提及——被反复 update 但未重复
          add 的记忆不再因 created_at 老而误归档）；
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

        exempt = ",".join("?" for _ in _ARCHIVE_EXEMPT_CATEGORIES)
        # 「晋升+归档」作为一个维护批次整段持锁：两条 UPDATE 虽各自
        # 原子，但中途被其他写的 commit 截断会让晋升单独落盘——
        # 见 database.core 逻辑写锁说明
        async with get_write_lock():
            cursor = await self._db.execute(
                """UPDATE memories SET status = 'core'
                   WHERE status = 'active'
                     AND (access_count >= ? OR hit_count >= ?)
                     AND created_at <= ?""",
                (_CORE_MIN_ACCESSES, _CORE_MIN_HITS, core_cutoff),
            )
            promoted = cursor.rowcount

            cursor = await self._db.execute(
                f"""UPDATE memories SET status = 'archived'
                    WHERE status = 'active'
                      AND (access_count + hit_count) <= ?
                      AND COALESCE(last_mention_ts, created_at) <= ?   -- O1
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

        - 写路径异常（近 _HEALTH_WINDOW_DAYS 天窗口）：窗口内有相当
          对话量（>= _HEALTH_MIN_CHATS）却零新增记忆——覆盖「从未写入」
          与「曾经写过、最近停摆」两种失灵（2026-08-04 多指标批）；
        - 记忆停滞：距上次写入超过 _HEALTH_WARN_DAYS 天且窗口内仍有
          对话——「7 天没打开小汐」是正常闲置，不该报（原实现无对话
          也报，误报；2026-08-04 修正）。

        多指标（2026-08-04，MEMORY_GAPS 健康检查收尾）：提取率 /
        归档占比等只做观测信息（进返回体与消费方 INFO 日志），不参与
        abnormal——默认动作「今天没事发生」会天然压低提取率，告警阈值
        需实盘基线（每 2h INFO 日志时间序列）校准后再考虑。
        """
        cursor = await self._db.execute(
            # O1：created_at 不可变后，写时钟看 MAX(COALESCE(updated_at,
            # created_at))——add/update 刷新 updated_at，hit 不刷，「距上次
            # 写入」该看它；只 update 不新增的记忆系统不该误报停滞
            # v18：排除情绪核心记忆——它每小时可能被心跳 update，若计入，
            # 「0 记忆」与「记忆停滞」两条告警恒假，健康检查全废
            "SELECT COUNT(*), MAX(COALESCE(updated_at, created_at)) "
            f"FROM memories WHERE status IN ('active', 'core') {_READ_EXCLUSIONS}"
        )
        row = await cursor.fetchone()
        count, newest = (row[0], row[1]) if row else (0, None)

        now = datetime.datetime.now(_HKT)
        days_since_write = None
        if newest:
            days_since_write = max(0.0, (now - _parse_time(newest)).total_seconds() / 86400)

        # ── 多指标（2026-08-04）：近窗口活动 + 库结构观测 ──────────
        # 窗口统计全部基于 created_at（v17 后不可变 = 首次记住时间戳）：
        # 「近窗口新增」无需差分快照。新增数按读面谓词过滤（superseded
        # 历史行与情绪账本行都不算——v18 _MOOD_ROW_FILTER 纪律贯穿）。
        # 归档占比是存量而非窗口差：SQLite 无归档时间戳（apply_status_decay
        # 不刷 updated_at），占比反映「库是否在膨胀/积压」的慢信号。
        cutoff = (now - datetime.timedelta(days=_HEALTH_WINDOW_DAYS)).strftime(_TIME_FMT)
        # 与提取口径一致（extract._fetch_pending）：solo 是小汐的自言自语，
        # 不是与冷冷的对话——既不进提取，也不该计为「有对话」。否则
        # 冷冷缺席期间 solo 独白会撑起 chats_7d，把「写路径停摆/停滞」
        # 两条告警变成误报（2026-08-17 solo 不进提取的姐妹修复）
        cursor = await self._db.execute(
            "SELECT COUNT(*) FROM chat_messages "
            "WHERE created_at >= ? AND msg_type != 'solo'",
            (cutoff,),
        )
        chats_7d = (await cursor.fetchone())[0]
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE created_at >= ? "
            f"AND status IN ('active', 'core') {_READ_EXCLUSIONS}",
            (cutoff,),
        )
        added_7d = (await cursor.fetchone())[0]
        cursor = await self._db.execute(
            f"SELECT COUNT(*) FROM memories WHERE status = 'archived' {_READ_EXCLUSIONS}"
        )
        archived_count = (await cursor.fetchone())[0]
        total = count + archived_count
        archive_ratio = archived_count / total if total else 0.0

        chats = 0
        if count == 0:
            # 只在零记忆时才查全历史对话量（兼容旧返回体语义）。
            # 同窗口口径排除 solo——只有 solo 独白（冷冷从未来过）不算
            # 「有对话」，否则「从未写入」告警误报
            cursor = await self._db.execute(
                "SELECT COUNT(*) FROM chat_messages WHERE msg_type != 'solo'"
            )
            chats = (await cursor.fetchone())[0]

        abnormal = (
            (chats_7d >= _HEALTH_MIN_CHATS and added_7d == 0)
            or (
                days_since_write is not None
                and days_since_write >= _HEALTH_WARN_DAYS
                and chats_7d >= 1
            )
        )
        return {
            "active_count": count,
            "days_since_write": days_since_write,
            "chat_count": chats,
            "abnormal": abnormal,
            # 多指标（2026-08-04，基线积累期——告警阈值待实盘数据校准）
            "chats_7d": chats_7d,
            "added_7d": added_7d,
            "extract_rate_7d": (added_7d / chats_7d) if chats_7d else None,
            "archived_count": archived_count,
            "archive_ratio": archive_ratio,
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
# 写路径异常（近 _HEALTH_WINDOW_DAYS 天窗口）：窗口内 ≥_HEALTH_MIN_CHATS
# 条对话却零新增记忆——覆盖「从未写入」与「曾经写过、最近停摆」两种失灵；
# 记忆停滞：距上次写入 ≥_HEALTH_WARN_DAYS 天且窗口内仍有对话——「7 天没
# 打开小汐」是正常闲置，不该报（2026-08-04 多指标批修误报）。
# 阈值偏保守（宁可错报一次，不可再静默一周）。
# 提取率/归档占比不做告警阈值（基线积累期）：默认动作「今天没事发生」
# 天然压低提取率，告警下限需 2-4 周实盘 INFO 日志时间序列校准后再定。
_HEALTH_MIN_CHATS = 10
_HEALTH_WARN_DAYS = 7
_HEALTH_WINDOW_DAYS = 7


async def run_status_decay() -> dict:
    """分层衰减便捷入口（心跳维护任务用，在全局库上构造管理器）。"""
    from database.core import get_db
    return await MemoryManager(await get_db()).apply_status_decay()
