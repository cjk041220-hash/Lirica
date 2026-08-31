"""轻量级数据库迁移框架。

使用 schema_version 表追踪当前版本。
每个迁移是一个 async def 函数，接收 aiosqlite.Connection，按版本号顺序执行。

添加新迁移的步骤：
    1. 递增 SCHEMA_VERSION
    2. 在 _migrations 字典中添加 {新版本号: 迁移函数}
    3. 迁移函数中执行 ALTER TABLE / CREATE TABLE 等 DDL
"""
import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

# ── 当前 schema 版本 ──────────────────────────────────────────
# v1: 初始 schema（memories / diaries / life_state 三张表）
# v2: 记忆系统重构 — 删除死字段(source/weight/emotion_tag)，
#     time→created_at，新增 access_count/status，删除冗余索引
# v3: 主动消息系统 — 新增 proactive_messages + push_subscriptions 表
# v4: 生理期追踪 — 新增 cycle_records 表
# v5: 记忆去重原子化 — 清理存量重复 + (category, content) 活跃行
#     唯一部分索引，配合 manager.add 的 INSERT ... ON CONFLICT
# v6: 事件系统 — 新增 events 表（小汐的「惦记」：台风/考试/生日等
#     未来事件的登记、到期关心、过期自动归档），dedupe_key 活跃行
#     唯一部分索引防止检测器重复生成
# v7: 相识纪念日 — life_state 新增 first_met_on（小汐与冷冷第一次
#     见面的日期），灵魂提示词注入「重要的日子」、事件检测器派生
#     周年事件。种子来自环境变量 XIAOXI_FIRST_MEET（YYYY-MM-DD）
# v8: 对话档案 — 新增 chat_sessions + chat_messages 表。与 checkpoint
#     （prune_checkpoints 每 2h 裁剪、30 天清线程）和 memories（LLM
#     上下文专用）解耦：档案供历史列表与回看；除 Spec A 例外外不被
#     读进 LLM 上下文（chat.py 写入、sessions.py 读取；2026-08-05
#     Spec A 起 heartbeat 经 recent_messages 注入历史尾巴，是唯一例外）
# v9: 事件软删墓碑 — dedupe 部分唯一索引谓词从 status='active' 扩为
#     status IN ('active','deleted')。日历面板的「删除」从此是墓碑：
#     同 key 重建（LLM 重新提取 / 检测器再生成）会被索引挡下，不再
#     「删了又偷偷长回来」。archived 行仍不在谓词内——「翻篇了明年
#     再来」（生日/周年/台风的年度再生成）语义不变
# v10: 记忆分层 — 去重唯一部分索引谓词从 status='active' 扩为
#     status IN ('active','core')。分层衰减（B2）引入 core（高频核心
#     记忆）/ archived（冷藏遗忘）两个新取值；core 必须与 active 一样
#     参与去重，否则同内容会出现 active+core 双行。archived 不在谓词
#     内——忘了的事重提时 add() 开始新生命周期
# v11: 记忆后台提取状态 — memory_extract_state 单行表（id=1 固定）：
#     last_message_id 水位线（已处理到的 chat_messages.id，后台提取
#     断点，跨重启持久）+ health_warned_at（健康检查 24h 告警节流）。
#     解决实盘「纯 agentic 写路径零触发、0 记忆跑一周无告警」的教训
#     （2026-08-01 架构评审 D1/D2/D3）
# v12: 记忆溯源与覆盖留痕 — memories 新增三列：
#     updated_at —— 最近一次内容被修改的时间；时间衰减仍按 created_at
#       （第一次记住的时间），覆盖不再把旧记忆重置成「新」；
#     source —— 来源追踪（后台提取传 chat:{session_id}，agentic
#       remember 为 NULL）：「小汐为什么记得这个」可追溯（D6）；
#     prev_content —— 覆盖前的旧文本（单层快照）：矛盾纠正后能回看
#       旧说法（D5 的轻量版本，不做完整版本链）。
# v16: 心情节奏 — life_state 新增 mood_changed_at（上次心情切换时间）。
#     心情数值化重构（2026-08-03 系统性优化）引入多时间尺度：早晨锚定
#     （每日 6-8 点首次心跳重置当日心情）+ 最小停留（日内换词需距上次
#     切换 ≥6h）。mood_changed_at 支撑这两条节奏的判定，跨重启持久。
# v17: 记忆双时钟 + 重要性 — memories 新增 last_mention_ts（O1：统一
#     提及时钟，add/update/hit 刷新；created_at 自此严格不可变）与
#     importance（I1：提取时 LLM 打 1-10 分，进相关性排序权重 0.3 档）。
#     存量回填 last_mention_ts = MAX(created_at, updated_at, last_used_at)，
#     importance 默认 5（中性）。
# v18: 情绪联动 — memories 新增 valence / arousal（Russell 情绪空间标签，
#     REAL DEFAULT 0，范围 -1..1 由写入侧钳制）。提取时 LLM 顺带打分；
#     进 _relevance_score 第六因子 mood-congruent 加权（manager.py，
#     加权而非硬过滤——防只浮现同色记忆的情绪固化回路）。存量回填
#     0 = 中性：老记忆无情绪打分，亲和度取 0.5 常量，不被系统性偏置。
# v19: 异常感知关心冷却台账 — 新增 care_ledger 表（type 主键 + last_at）。
#     Spec B：late_night 72h / event_combo 24h 各自独立冷却，「注入即
#     记账」——LLM 最后没提这茬也算冷却开始；防唠叨由确定性状态保证，
#     不押在模型自觉上（记忆写路径 31 条消息零触发的前科）。
# v21: 主动消息独立会话（era 时间线）— 四表补列：
#     proactive_messages.session_id（主动消息归属哪个会话；旧积压全部
#       标已读清账——不这么做，升级后旧前端/旧逻辑会把无归属积压再次
#       注入当前对话，原样复现「问候轰炸」bug）；
#     chat_sessions.is_era（小汐的独处时间线会话标记：离线期间主动消息
#       新开一个 era 窗口，冷冷上线自动切到它看到小汐的活动与话语）；
#     chat_messages.msg_type（'chat' | 'solo'——独处消息在回放里渲染为
#       日记风 + 时间戳，区别于普通回复；role 仍受 CHECK 约束只能是
#       user/assistant，solo 消息落 role='assistant'）；
#     life_state.last_seen_at / active_session_id（presence 上报：冷冷
#       在线状态 + 当前活跃会话，era 路由判定的数据源）。
# v22: chat_messages (role, created_at) 组合索引 — 批③关系密度查询
#     （agent/soul.py 近7天互动密度）与批②画像蒸馏查询（memory/profile.py
#     近30天画像素材）都按 (role='user', created_at) 谓词扫 chat_messages，
#     无索引时全表扫。组合索引一次覆盖两处；纯索引迁移，无数据语义变更。
# v24: 会话「类型」与「来源」分家 — chat_sessions 新增 kind
#     （'chat' 普通对话 / 'solo' 小汐的碎碎念，可变）+ era 会话标题统一。
#     此前只有 is_era 一个字段，它本质是**来源**标记（这段会话诞生于
#     冷冷离线的独处时间线，创建后不可变），却被当成**内容类型**在读
#     ——于是「小汐的碎碎念」被冷冷接话变成真正的对话后，列表标签仍
#     然写着碎碎念，永远不更新。kind 补上缺失的可变类型层：
#       is_era=1, kind='solo'  小汐独处、还没人接话 → 列表标「碎碎念」
#       is_era=1, kind='chat'  被冷冷接话了 → 它就是一段对话，标签消失
#       is_era=0, kind='chat'  冷冷自己开的对话（kind 不可能是 solo）
#     冷冷的第一条消息到达即把 kind 从 'solo' 提升为 'chat'（chat_archive
#     ._record_message 内与「摘要作废」同一分支）。
#     存量回填：is_era=1 且尚无 user 消息 → kind='solo'；era 会话标题
#     统一为「小汐的碎碎念」（旧标题带日期「小汐的日记 · 08月09日」，
#     与新的时间分组列表重复表达日期，也和 diaries 表的真日记撞名）。
SCHEMA_VERSION = 24


# memories 去重唯一部分索引 DDL。三处共享此常量保证永不漂移：
# 全新安装（core.py init_db）/ 测试夹具（conftest）/ 迁移（v10 重建）。
# manager.add 的 INSERT ... ON CONFLICT 推断子句必须与此谓词逐字一致
# （SQLite 要求推断 WHERE 与索引谓词精确匹配，差一词即推断失败）。
MEMORIES_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
        ON memories(category, content) WHERE status IN ('active', 'core');
"""


# 异常感知关心冷却台账（迁移 v19 / Spec B）：各提示类型独立冷却，
# 注入即记账。三处共享此常量（同 EVENTS_SCHEMA_SQL 模式）：
# 全新安装（core.py init_db）/ 测试夹具（conftest）/ 迁移（v19）。
CARE_LEDGER_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS care_ledger (
        type     TEXT PRIMARY KEY,
        last_at  TEXT NOT NULL
    );
"""


# ── 迁移函数注册表 ────────────────────────────────────────────
# 格式：{目标版本号: 迁移函数}
# 迁移函数签名：async def migrate_vN(conn: aiosqlite.Connection) -> None

_migrations: dict[int, callable] = {
    2: None,  # 下方定义后赋值
}


# ── v2 迁移：记忆系统重构 ────────────────────────────────────

async def _migrate_v2(conn: aiosqlite.Connection) -> None:
    """v2: 重构 memories 表 — 删除死字段，重命名 time→created_at，新增 access_count/status。

    SQLite 不支持 DROP COLUMN（3.35.0+才支持），使用安全的重建表方式。
    """
    # 1. 检查旧表是否存在（全新安装不需要迁移）
    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='memories'"
    )
    if not await cursor.fetchone():
        log.info("迁移 v2：memories 表不存在，跳过")
        return

    # 2. 检查是否已经是新 schema（幂等）
    cursor = await conn.execute("PRAGMA table_info(memories)")
    columns = {row[1] for row in await cursor.fetchall()}
    if "created_at" in columns and "access_count" in columns:
        log.info("迁移 v2：memories 已是新 schema，跳过")
        return

    # 3. 重建表（保留数据）
    # 注：不写 PRAGMA foreign_keys=OFF/ON —— SQLite 规定该 pragma 在
    # 事务内是 no-op（aiosqlite 默认隐式开事务），而本项目 schema
    # 未定义任何 FK 约束，重建表无需关闭外键。

    await conn.execute("""
        CREATE TABLE memories_new (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            category        TEXT DEFAULT '其他',
            created_at      TEXT NOT NULL,
            last_used_at    TEXT,
            access_count    INTEGER DEFAULT 0,
            status          TEXT DEFAULT 'active'
        )
    """)

    # 迁移数据：time → created_at，丢弃 source/weight/emotion_tag
    if "time" in columns:
        await conn.execute("""
            INSERT INTO memories_new (id, content, category, created_at, last_used_at)
            SELECT id, content,
                   COALESCE(category, '其他'),
                   COALESCE(time, datetime('now')),
                   last_used_at
            FROM memories
        """)
    else:
        # 极端情况：旧表没有 time 字段
        await conn.execute("""
            INSERT INTO memories_new (id, content, category, created_at)
            SELECT id, content,
                   COALESCE(category, '其他'),
                   datetime('now')
            FROM memories
        """)

    await conn.execute("DROP TABLE memories")
    await conn.execute("ALTER TABLE memories_new RENAME TO memories")

    # 4. 重建索引（只保留复合索引 + status 索引）
    await conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_memories_cat_content
            ON memories(category, content)
    """)
    await conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_memories_status
            ON memories(status)
    """)

    # 5. 清理旧的冗余索引（如果存在）
    try:
        await conn.execute("DROP INDEX IF EXISTS idx_memories_category")
    except Exception:
        pass

    await conn.commit()

    cursor = await conn.execute("SELECT COUNT(*) FROM memories")
    count = (await cursor.fetchone())[0]
    log.info("迁移 v2 完成：memories 表重构，保留 %d 条记忆", count)


# 注册迁移函数
_migrations[2] = _migrate_v2


# ── v3 迁移：主动消息系统 ────────────────────────────────────

async def _migrate_v3(conn: aiosqlite.Connection) -> None:
    """v3: 新增 proactive_messages 和 push_subscriptions 表。"""

    # 创建主动消息表
    # session_id 列由 v21 迁移补列（v3 建表时还没有 era 概念；此处写
    # 入保持全新安装与升级路径终态一致——v21 的 PRAGMA 守卫会跳过）
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS proactive_messages (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            created_at      TEXT NOT NULL,
            delivered_at    TEXT,
            read_at         TEXT,
            status          TEXT DEFAULT 'pending',
            session_id      TEXT
        )
    """)

    # 创建推送订阅表
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS push_subscriptions (
            id              TEXT PRIMARY KEY,
            endpoint        TEXT NOT NULL UNIQUE,
            p256dh          TEXT NOT NULL,
            auth            TEXT NOT NULL,
            user_agent      TEXT,
            created_at      TEXT NOT NULL
        )
    """)

    await conn.commit()
    log.info("迁移 v3 完成：proactive_messages + push_subscriptions 表已创建")


_migrations[3] = _migrate_v3


# ── v4 迁移：生理期追踪 ─────────────────────────────────────

async def _migrate_v4(conn: aiosqlite.Connection) -> None:
    """v4: 新增 cycle_records 表 + 写入初始记录。"""
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS cycle_records (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            start_date      TEXT NOT NULL UNIQUE,
            note            TEXT
        )
    """)

    # 写入冷冷的初始生理期记录
    initial_records = [
        ("2026-04-22", ""),
        ("2026-05-20", ""),
        ("2026-06-15", ""),
        ("2026-07-18", "近期周期波动较大，可能和熬夜有关"),
    ]
    for date_str, note in initial_records:
        await conn.execute(
            "INSERT OR IGNORE INTO cycle_records (start_date, note) VALUES (?, ?)",
            (date_str, note),
        )

    await conn.commit()
    log.info("迁移 v4 完成：cycle_records 表已创建，写入 %d 条初始记录", len(initial_records))


_migrations[4] = _migrate_v4


# ── v5 迁移：记忆去重原子化 ──────────────────────────────────

async def _migrate_v5(conn: aiosqlite.Connection) -> None:
    """v5: 记忆去重下沉到数据库层。

    此前去重靠 manager.add「先查后写」，且 idx_memories_cat_content 是
    普通索引——心跳与聊天并发写入同一 (category, content) 时，两个协程
    都能通过 SELECT 检查并各自 INSERT，产生重复行。

    本迁移：
    1. 清理存量重复：同一 (category, content) 的活跃行只保留最近使用的
       一条（重复行内容完全相同，删除无信息损失）；
    2. 将 idx_memories_cat_content 重建为唯一部分索引（仅约束 active 行）。
       选择部分索引是因为已删除/被覆盖的记忆（status != 'active'）不应
       阻止重新记住相同内容。
    3. 此后 manager.add 用 INSERT ... ON CONFLICT(category, content)
       WHERE status = 'active' DO UPDATE 原子去重，彻底消除 TOCTOU。
    """
    # 0. SQLite 版本守卫：ROW_NUMBER() 窗口函数需要 >= 3.25.0
    cursor = await conn.execute("SELECT sqlite_version()")
    row = await cursor.fetchone()
    if row:
        version = tuple(int(x) for x in row[0].split("."))
        if version < (3, 25, 0):
            log.warning(
                "迁移 v5 需要 SQLite >= 3.25.0（当前 %s），跳过。"
                "记忆去重将继续使用应用层「先查后写」，无数据丢失。",
                row[0],
            )
            return

    # 1. 清理存量重复活跃行（保留 last_used_at/created_at 最新的一条）
    cursor = await conn.execute("""
        DELETE FROM memories
        WHERE status = 'active'
          AND id NOT IN (
              SELECT id FROM (
                  SELECT id,
                         ROW_NUMBER() OVER (
                             PARTITION BY category, content
                             ORDER BY COALESCE(last_used_at, created_at) DESC,
                                      created_at DESC
                         ) AS rn
                  FROM memories
                  WHERE status = 'active'
              )
              WHERE rn = 1
          )
    """)
    removed = cursor.rowcount

    # 2. 重建为唯一部分索引（与 manager.add 的 ON CONFLICT 推断条件一致）
    await conn.execute("DROP INDEX IF EXISTS idx_memories_cat_content")
    await conn.execute("""
        CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
            ON memories(category, content) WHERE status = 'active'
    """)

    await conn.commit()
    if removed:
        log.info("迁移 v5：清理了 %d 条重复记忆", removed)
    log.info("迁移 v5 完成：记忆去重唯一索引已建立")


_migrations[5] = _migrate_v5


# ── v6 迁移：事件系统 ────────────────────────────────────────

# events 表完整 DDL（建表 + 索引）。迁移 / 全新安装 / 测试夹具三处
# 共享此常量，保证三处 schema 永不漂移。
EVENTS_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS events (
        id          TEXT PRIMARY KEY,
        title       TEXT NOT NULL,
        event_type  TEXT NOT NULL,
        event_date  TEXT NOT NULL,
        end_date    TEXT,
        severity    TEXT NOT NULL DEFAULT 'normal',
        source      TEXT NOT NULL DEFAULT 'llm',
        memo        TEXT,
        status      TEXT NOT NULL DEFAULT 'active',
        created_at  TEXT NOT NULL,
        dedupe_key  TEXT,
        completed_at TEXT
    );

    CREATE INDEX IF NOT EXISTS idx_events_status_date
        ON events(status, event_date);

    -- dedupe_key 部分唯一索引：防止检测器重复生成同一事件
    -- （天气 7 日预报每天扫一遍、生日每天扫一遍、LLM 反复提取）；
    -- 覆盖 active + deleted（v9 墓碑）：用户在日历删掉的事件，
    -- LLM/检测器用同 key 重建会被挡下（「删了就是删了」）；
    -- 归档行不在谓词内 → 不阻塞明年/下次重新生成。
    -- 与 utils.events.create_event 的 ON CONFLICT 推断条件必须一致。
    CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedupe
        ON events(dedupe_key) WHERE dedupe_key IS NOT NULL AND status IN ('active', 'deleted');
"""


async def _migrate_v6(conn: aiosqlite.Connection) -> None:
    """v6: 事件系统 — 新增 events 表（小汐的「惦记」：登记台风/考试/生日等
    未来事件，到期自然关心，过期自动归档）。"""
    await conn.executescript(EVENTS_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v6 完成：events 表已建立")


_migrations[6] = _migrate_v6


# ── v7 迁移：相识纪念日 ──────────────────────────────────────

# life_state 完整 DDL（v7：新增 first_met_on；v16：新增 mood_changed_at；
# v21：新增 last_seen_at + active_session_id——presence 上报，era 路由的
# 在线判定数据源）。
# init_db / 迁移 / 测试夹具三处共享此常量，保证 schema 永不漂移
# （同 EVENTS_SCHEMA_SQL 模式）。
LIFE_STATE_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS life_state (
        id                INTEGER PRIMARY KEY DEFAULT 1,
        mood              TEXT,
        daily_event       TEXT,
        updated_at        TEXT,
        first_met_on      TEXT,
        mood_changed_at   TEXT,
        last_seen_at      TEXT,
        active_session_id TEXT
    );
"""


def _valid_iso_date(s: str) -> bool:
    """校验 YYYY-MM-DD 且是真实存在的日期（挡住 2026-02-30）。"""
    import datetime as _dt
    import re

    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", s):
        return False
    try:
        _dt.date.fromisoformat(s)
        return True
    except ValueError:
        return False


async def _seed_first_met(conn: aiosqlite.Connection) -> None:
    """把环境变量 XIAOXI_FIRST_MEET 种进 life_state.first_met_on。

    幂等且单向：只在列为空时写入。相识日只应被设定一次——日后改
    env 不会覆盖既有值（想改需直接 UPDATE 数据库），避免小汐的
    「我们认识多少天」因为运维操作悄悄漂移。
    """
    import os

    env_val = (os.environ.get("XIAOXI_FIRST_MEET") or "").strip()
    if not env_val:
        return
    if not _valid_iso_date(env_val):
        log.warning("XIAOXI_FIRST_MEET 应为合法日期 YYYY-MM-DD，已忽略：%s", env_val)
        return
    await conn.execute(
        "UPDATE life_state SET first_met_on = ? "
        "WHERE id = 1 AND (first_met_on IS NULL OR first_met_on = '')",
        (env_val,),
    )


async def _migrate_v7(conn: aiosqlite.Connection) -> None:
    """v7: life_state 新增 first_met_on（相识纪念日）。"""
    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='life_state'"
    )
    if not await cursor.fetchone():
        # 表不存在（极老的部分 schema 旧库 / 直接调 ensure_schema 的
        # 升级测试夹具）→ 直接用完整 DDL 建出含新列的表
        await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    else:
        cursor = await conn.execute("PRAGMA table_info(life_state)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "first_met_on" not in columns:
            await conn.execute("ALTER TABLE life_state ADD COLUMN first_met_on TEXT")

    await _seed_first_met(conn)
    await conn.commit()
    log.info("迁移 v7 完成：life_state.first_met_on 已就绪")


_migrations[7] = _migrate_v7


# ── v8 迁移：对话档案 ────────────────────────────────────────

# chat_sessions / chat_messages 完整 DDL。init_db / 迁移 / 测试夹具
# 三处共享此常量（同 EVENTS_SCHEMA_SQL 模式），保证 schema 永不漂移。
# 故意不定义 FK 约束：全项目 schema 无 FK、PRAGMA foreign_keys 未开启，
# ON DELETE CASCADE 不会生效——级联删除由 chat_archive.delete_session
# 显式执行（先删消息、后删会话）。
# v21：chat_sessions 加 is_era（小汐的独处时间线会话，0=普通/1=era）；
#       chat_messages 加 msg_type（'chat' 普通对话 / 'solo' 主动消息的
#       独处话语——回放时渲染为日记风 + 时间戳）。两列都在共享常量里，
#       老库由 v21 迁移补列，全新安装直接带列，终态一致。
# v24：chat_sessions 加 kind（'chat' 对话 / 'solo' 小汐的碎碎念）。
#       is_era 是来源（不可变），kind 是当前类型（可变）——两者正交。
CHAT_ARCHIVE_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS chat_sessions (
        id          TEXT PRIMARY KEY,
        title       TEXT NOT NULL DEFAULT '',
        created_at  TEXT NOT NULL,
        updated_at  TEXT NOT NULL,
        is_era      INTEGER NOT NULL DEFAULT 0,
        kind        TEXT NOT NULL DEFAULT 'chat'
    );

    CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated
        ON chat_sessions(updated_at DESC);

    CREATE TABLE IF NOT EXISTS chat_messages (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id  TEXT NOT NULL,
        role        TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
        content     TEXT NOT NULL,
        created_at  TEXT NOT NULL,
        msg_type    TEXT NOT NULL DEFAULT 'chat'
    );

    CREATE INDEX IF NOT EXISTS idx_chat_messages_session
        ON chat_messages(session_id, id);
"""

# era 会话（小汐的碎碎念）的显示名。三处共用同一个常量，避免漂移：
# chat_archive 建会话行、era.py 预插行（C-1 竞态防护）、v24 存量回填。
# 固定文案不带日期——日期由会话列表的时间分组与会话内的时间分隔条
# 表达，标题再写一遍「08月09日」是重复；且旧格式「小汐的日记 · 08月
# 09日」与 diaries 表每晚自动生成的真日记几乎同名，两者实为互不相通
# 的三种东西（真日记 / era 会话 / solo 消息），命名必须收口区分开。
ERA_SESSION_TITLE = "小汐的碎碎念"


async def _migrate_v8(conn: aiosqlite.Connection) -> None:
    """v8: 对话档案 — 会话与消息的持久化存档。

    纯增量建表，幂等。存档与 checkpoint（定期裁剪）和 memories
    （LLM 上下文专用）完全解耦：重要信息由小汐的记忆系统自己
    提取，历史只是档案，永远不会回喂 LLM 上下文。
    """
    await conn.executescript(CHAT_ARCHIVE_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v8 完成：chat_sessions + chat_messages 表已建立")


_migrations[8] = _migrate_v8


# ── v9 迁移：事件软删墓碑 ─────────────────────────────────────

async def _migrate_v9(conn: aiosqlite.Connection) -> None:
    """v9: dedupe 部分唯一索引谓词扩至 status IN ('active','deleted')。

    背景：日历面板引入软删（status='deleted'）。旧谓词只覆盖 active，
    被删事件的 dedupe_key 失去约束 → LLM 重新提取 / 检测器再生成会
    把删掉的事原样建回来（「删了又偷偷长回来」，用户体验上的打架）。
    扩谓词后墓碑行继续占位挡重建；archived 仍不在谓词内，生日/周年
    /台风的「明年再来」语义不变（events.py 注释与 test_events 钉死）。

    SQLite 不支持 ALTER INDEX：drop + recreate。幂等——新库走
    EVENTS_SCHEMA_SQL（已同步新谓词），老库走本迁移，两者终态一致。
    """
    await conn.execute("DROP INDEX IF EXISTS idx_events_dedupe")
    await conn.execute(
        """CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedupe
           ON events(dedupe_key)
           WHERE dedupe_key IS NOT NULL AND status IN ('active', 'deleted')"""
    )
    await conn.commit()
    log.info("迁移 v9 完成：事件 dedupe 索引已覆盖墓碑行（active + deleted）")


_migrations[9] = _migrate_v9


# ── v10 迁移：记忆分层 ────────────────────────────────────────

async def _migrate_v10(conn: aiosqlite.Connection) -> None:
    """v10: 记忆去重唯一部分索引谓词扩至 status IN ('active','core')。

    背景：分层衰减（B2）给 memories.status 引入两个新取值——core
    （access_count≥5 且 ≥30 天的高频核心记忆，叙事保底席位）与
    archived（≤1 次且 ≥90 天的冷藏记忆，读面排除）。旧谓词只覆盖
    active：core 行跌出约束后，add() 的 ON CONFLICT 推断命中不到它
    → 同 (category, content) 出现 active + core 双行。扩谓词后 core
    与 active 一样参与去重；archived 仍不在谓词内——遗忘后重提同内容
    时 add() 插入新行开始新生命周期（正是「忘了又想起」的语义）。

    SQLite 不支持 ALTER INDEX：drop + recreate。幂等——新库走
    MEMORIES_INDEX_SQL（已同步新谓词），老库走本迁移，终态一致。
    manager.add 的 ON CONFLICT 推断子句与读回 SELECT 已同步扩谓词。
    """
    await conn.execute("DROP INDEX IF EXISTS idx_memories_cat_content")
    await conn.executescript(MEMORIES_INDEX_SQL)
    await conn.commit()
    log.info("迁移 v10 完成：记忆去重索引已覆盖 core 行（active + core）")


_migrations[10] = _migrate_v10


# ── v11 迁移：记忆后台提取状态 ────────────────────────────────

# 状态表完整 DDL。迁移 / 全新安装（init_db）/ 测试夹具三处共享此常量，
# 保证 schema 永不漂移（同 EVENTS_SCHEMA_SQL 模式）。
# 单行表（id=1 固定，CHECK 约束）：任何时刻只有一行，UPSERT 更新。
MEMORY_EXTRACT_STATE_SQL = """
    CREATE TABLE IF NOT EXISTS memory_extract_state (
        id               INTEGER PRIMARY KEY CHECK (id = 1),
        last_message_id  INTEGER NOT NULL DEFAULT 0,
        last_run_at      TEXT,
        health_warned_at TEXT,
        fail_count       INTEGER NOT NULL DEFAULT 0,
        consolidate_ran_at TEXT
    );
"""


async def _migrate_v11(conn: aiosqlite.Connection) -> None:
    """v11: 记忆后台提取状态表 — 水位线 + 健康检查节流。

    纯增量建表，幂等。背景（2026-08-01 架构评审 D1/D2/D3）：
    - 主对话的记忆写入纯 agentic（LLM 自觉调 remember），实盘 31 条
      消息零触发 → 引入对话后异步提取通道，需要水位线断点；
    - 0 记忆跑一周无告警 → 健康检查需要告警节流时间戳。
    两者合并为一张单行状态表，跨重启持久。
    """
    await conn.executescript(MEMORY_EXTRACT_STATE_SQL)
    await conn.commit()
    log.info("迁移 v11 完成：memory_extract_state 状态表已建立")


_migrations[11] = _migrate_v11


# ── v12 迁移：记忆溯源与覆盖留痕 ─────────────────────────────

async def _migrate_v12(conn: aiosqlite.Connection) -> None:
    """v12: memories 新增 updated_at / source / prev_content 三列。

    ALTER TABLE ADD COLUMN 逐列守卫（幂等——PRAGMA table_info 已存在
    则跳过）。v1 时代曾有过一个死字段 source（v2 迁移时删除），v12
    以新语义加回：来源追踪与 v1 的 source 无继承关系，旧值早已随
    v2 重建表丢弃，无数据衔接问题（test_init_db_upgrade 钉死全链路）。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    columns = {row[1] for row in await cursor.fetchall()}

    if "updated_at" not in columns:
        await conn.execute("ALTER TABLE memories ADD COLUMN updated_at TEXT")
    if "source" not in columns:
        await conn.execute("ALTER TABLE memories ADD COLUMN source TEXT")
    if "prev_content" not in columns:
        await conn.execute("ALTER TABLE memories ADD COLUMN prev_content TEXT")

    await conn.commit()
    log.info("迁移 v12 完成：memories 新增 updated_at / source / prev_content")


_migrations[12] = _migrate_v12


# ── v13 迁移：衰减弱信号 + 提取卡死死信 ──────────────────────

async def _migrate_v13(conn: aiosqlite.Connection) -> None:
    """v13: memories.hit_count（衰减弱信号）+ memory_extract_state.fail_count（解析死信）。

    ALTER TABLE ADD COLUMN 逐列守卫（幂等）。背景（2026-08-01 第三轮
    体检阻塞项）：
    - 分层衰减的唯一燃料是 access_count，而它唯一的增量点在主动
      recall——主消费路径（叙事注入）按铁律 passive → 稳态下几乎所有
      记忆 access_count 永远为 0，90 天后连同「芒果过敏」「重要约定」
      这类天天被注入的高重要事实一起被归档，core 晋升实际不可达。
      hit_count 计「被系统浮现」次数（含被动读取），只做衰减燃料，
      不参与相关性排序（排序的 passive 纪律保持不动）；
    - 后台提取 LLM 输出不可解析时无限重试同一批：输出确定性损坏的
      批次（如满载 8 动作 JSON 撞上 max_tokens 截断）会让水位线永远
      不推进、提取通道永久空转。fail_count 计连续解析失败次数，达
      3 次死信该批并推进水位线。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "hit_count" not in mem_cols:
        await conn.execute(
            "ALTER TABLE memories ADD COLUMN hit_count INTEGER DEFAULT 0"
        )

    cursor = await conn.execute("PRAGMA table_info(memory_extract_state)")
    state_cols = {row[1] for row in await cursor.fetchall()}
    if "fail_count" not in state_cols:
        # NOT NULL 与共享常量 MEMORY_EXTRACT_STATE_SQL 终态对齐（体检 T1-7：
        # 原文漏写 NOT NULL，全新安装与升级路径终态漂移）。已上过原版 v13
        # 的库列已存在、幂等守卫跳过，留存可空列——读面 get_fail_count 的
        # (row[0] or 0) 兜住 NULL，无重建必要。
        await conn.execute(
            "ALTER TABLE memory_extract_state ADD COLUMN fail_count INTEGER NOT NULL DEFAULT 0"
        )

    await conn.commit()
    log.info("迁移 v13 完成：memories.hit_count + memory_extract_state.fail_count")


_migrations[13] = _migrate_v13


# ── v14 迁移：hit_count 按日计数语义修正 + 存量回填 ──────────

async def _migrate_v14(conn: aiosqlite.Connection) -> None:
    """v14: memories.last_hit_date（hit_count 改按「不同天数」计数）+ 存量回填。

    背景（2026-08-01 全面复核 T1-1）：v13 的 hit_count 是原始浮现次数，
    而叙事注入在每次 LLM 模型调用前都跑（recall passive + recent 两路
    独立计数、重叠记忆双计），活跃日单记忆增速 25-70/天——_CORE_MIN_HITS
    阈值校准与实际速率差一个数量级，小库活跃用户 30 天后全体晋升 core、
    归档机制灭绝。修正把 hit_count 语义改为「被浮现的不同天数」（HKT
    日历日去重，计数守卫在 manager._count_hits 的 SQL 里），与聊天频率
    解耦；阈值相应重校准（_CORE_MIN_HITS 90 → 20 个不同日子）。

    存量回填是本迁移的必需部分，不是可选项：v13 通胀期积累的 hit_count
    （普遍 ≥20）撞上校准后阈值，会让升级后首次衰减运行（2h 内）把所有
    ≥30 天记忆一次性全体晋升 core——症状延迟复发。clamp 到 19 同时防
    两个方向的误伤：<20 无意外晋升；>1（归档阈值）无意外归档扫荡
    （清零会让「90 天老、access=0、实际天天被注入」的诚实热记忆被错杀）。
    last_hit_date 置 NULL 让下次浮现记为全新一天，从升级日起诚实累积。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "last_hit_date" not in mem_cols:
        await conn.execute(
            "ALTER TABLE memories ADD COLUMN last_hit_date TEXT"
        )

    # 存量回填：MIN(hit_count, 19) 是 SQLite 双参标量函数（非聚合），
    # 幂等——重跑时已 ≤19 的行不受影响
    await conn.execute(
        "UPDATE memories SET hit_count = MIN(hit_count, 19), last_hit_date = NULL"
    )

    await conn.commit()
    log.info("迁移 v14 完成：memories.last_hit_date + hit_count 存量回填（clamp ≤19）")


_migrations[14] = _migrate_v14


# ── v15 迁移：覆盖历史链 + 整合节流时间戳 ────────────────────

async def _migrate_v15(conn: aiosqlite.Connection) -> None:
    """v15: memories.superseded_by（覆盖历史链）+ memory_extract_state.consolidate_ran_at。

    背景（2026-08-01 全面复核 R3/R1）：
    - prev_content 是单层快照，二次覆盖即丢最初版本。superseded_by
      列支撑「失效行」：被覆盖的旧内容另落一行 status='superseded'、
      superseded_by 指向现记忆，形成无论覆盖多少次都可追溯的历史链
      （Zep/Graphiti 双时态边失效化思想的 5% 移植——失效保留而非
      删除；读面谓词统一 IN('active','core') 使 superseded 行天然
      消失在一切日常读面，v10 去重索引同谓词故不挡同内容重提）；
    - consolidate_ran_at 供 dreaming-lite 周期整合 pass 做 24h 节流
      （心跳维护段调用，复用本状态表避免再建表）。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "superseded_by" not in mem_cols:
        await conn.execute(
            "ALTER TABLE memories ADD COLUMN superseded_by TEXT"
        )

    cursor = await conn.execute("PRAGMA table_info(memory_extract_state)")
    state_cols = {row[1] for row in await cursor.fetchall()}
    if "consolidate_ran_at" not in state_cols:
        await conn.execute(
            "ALTER TABLE memory_extract_state ADD COLUMN consolidate_ran_at TEXT"
        )

    await conn.commit()
    log.info("迁移 v15 完成：memories.superseded_by + memory_extract_state.consolidate_ran_at")


_migrations[15] = _migrate_v15


# ── v16 迁移：心情节奏 ────────────────────────────────────────

async def _migrate_v16(conn: aiosqlite.Connection) -> None:
    """v16: life_state 新增 mood_changed_at（上次心情切换时间）。

    背景（2026-08-03 系统性优化）：心情数值化重构引入多时间尺度——
    早晨锚定（每日 6-8 点首次心跳重置当日心情，允许「新的一天新心情」）
    与最小停留（日内换词需距上次切换 ≥6h，mood 是弥漫几小时的背景，
    不该被 2h 心跳频率带着跳）。mood_changed_at 支撑这两条节奏的判定，
    跨重启持久。

    ALTER TABLE ADD COLUMN 幂等守卫（表不存在则用完整 DDL 建出含新列的
    表，同 v7 模式）。不回填旧值——老库 mood 无历史切换时间，首次心跳
    自然重置（mood_changed_at 为 NULL 时 `_choose_mood` 从基线出发），
    无需迁移数据。
    """
    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='life_state'"
    )
    if not await cursor.fetchone():
        await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    else:
        cursor = await conn.execute("PRAGMA table_info(life_state)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "mood_changed_at" not in columns:
            await conn.execute("ALTER TABLE life_state ADD COLUMN mood_changed_at TEXT")

    await conn.commit()
    log.info("迁移 v16 完成：life_state.mood_changed_at 已就绪")


_migrations[16] = _migrate_v16


# ── v17 迁移：双时钟 + 重要性 ─────────────────────────────────


async def _migrate_v17(conn: aiosqlite.Connection) -> None:
    """v17: memories.last_mention_ts（统一提及时钟）+ importance（重要性打分）。

    背景（2026-08-03 记忆库诊断 O1/I1）：
    - O1：add 去重刷新 created_at、update_content 保留——同一衰减体系
      两套矛盾时钟。被反复 add 的记忆续命、被反复 update 但未重复 add
      的记忆老化。拆为 created_at 严格不可变（add 不再刷新）+ 新列
      last_mention_ts（add/update/active-recall/hit 统一刷新），衰减与
      90 天归档统一基于 last_mention_ts（mem0 三时间戳范式）；
    - I1：排序缺 importance 因子。提取时 LLM 顺带打 1-10 分存本列，
      进 _relevance_score（权重 0.3 档）。

    存量回填：last_mention_ts 取 MAX(created_at, updated_at, last_used_at)
    ——三者任一都是「提及/写入」信号，取最新即最接近真实最近提及的估计
    （幂等：重跑时 MAX 结果不变）。importance 默认 5——老记忆无打分
    记录，按中性处理。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "last_mention_ts" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN last_mention_ts TEXT")
    if "importance" not in mem_cols:
        await conn.execute(
            "ALTER TABLE memories ADD COLUMN importance INTEGER DEFAULT 5"
        )

    await conn.execute(
        """UPDATE memories SET last_mention_ts = MAX(
               created_at,
               COALESCE(updated_at, created_at),
               COALESCE(last_used_at, created_at))"""
    )
    await conn.execute("UPDATE memories SET importance = 5 WHERE importance IS NULL")

    await conn.commit()
    log.info("迁移 v17 完成：memories.last_mention_ts + importance（双时钟 + 重要性）")


_migrations[17] = _migrate_v17


# ── v18 迁移：情绪联动情绪标签 ────────────────────────────────


async def _migrate_v18(conn: aiosqlite.Connection) -> None:
    """v18: memories.valence + arousal（情绪联动：记忆的情绪空间标签）。

    背景（2026-08-03 记忆库诊断「情绪联动」，schema v18 批）：
    - 记忆带 (valence, arousal) Russell 空间标签，提取时 LLM 打分写入，
      供 _relevance_score 第六因子 mood-congruent 加权（加权而非硬过滤——
      防「只想起同色记忆 → 情绪更固化」回路）；
    - 标签是纯增量列：不影响任何既有读面谓词、去重索引与衰减。

    存量回填 0 = 中性：老记忆无情绪打分，manager._mood_affinity 对
    NULL/0 一律取亲和度 0.5 常量——不被 mood 因子偏向也不被系统性惩罚。
    ALTER TABLE ADD COLUMN ... DEFAULT 0 已把默认值写入存量行，UPDATE
    只是 NULL 兜底（幂等；重跑时已有合法值的行不受影响）。
    """
    cursor = await conn.execute("PRAGMA table_info(memories)")
    mem_cols = {row[1] for row in await cursor.fetchall()}
    if "valence" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN valence REAL DEFAULT 0")
    if "arousal" not in mem_cols:
        await conn.execute("ALTER TABLE memories ADD COLUMN arousal REAL DEFAULT 0")

    await conn.execute("UPDATE memories SET valence = 0 WHERE valence IS NULL")
    await conn.execute("UPDATE memories SET arousal = 0 WHERE arousal IS NULL")

    await conn.commit()
    log.info("迁移 v18 完成：memories.valence + arousal（情绪联动情绪标签）")


_migrations[18] = _migrate_v18


# ── v19 迁移：异常感知关心冷却台账 ────────────────────────────


async def _migrate_v19(conn: aiosqlite.Connection) -> None:
    """v19: care_ledger — 异常感知关心的硬冷却台账（Spec B）。

    纯新表，无存量回填。全新安装由 core.init_db 执行共享 DDL 常量，
    老库走本迁移，两者终态一致（同 v6/v8 模式）。
    """
    await conn.executescript(CARE_LEDGER_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v19 完成：care_ledger 表已建立")


_migrations[19] = _migrate_v19


# ── v20 迁移：事件完成态 ──────────────────────────────────────


async def _migrate_v20(conn: aiosqlite.Connection) -> None:
    """v20: events.completed_at（事件完成态——「办完了，别再惦记」）。

    背景（2026-08-06 体检产品决策 2）：日历只有删除（墓碑），没有
    「完成」。冷冷 8/5 考完试，只能等看门狗翻篇或删掉，不能打个勾。
    完成 ≠ 删除：删除是否决（墓碑挡重建），完成是「这件事办完了，
    小汐别再惦记」——LLM 读侧（get_active_events）与提醒（早安/前夜
    提醒）都排除已完成，日历上仍可见（划线 + ✓）。

    ALTER TABLE ADD COLUMN 幂等守卫（同 v16 模式）；不回填——老库
    无完成历史，全部视为未完成（completed_at IS NULL）。
    """
    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='events'"
    )
    if not await cursor.fetchone():
        await conn.executescript(EVENTS_SCHEMA_SQL)
    else:
        cursor = await conn.execute("PRAGMA table_info(events)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "completed_at" not in columns:
            await conn.execute("ALTER TABLE events ADD COLUMN completed_at TEXT")

    await conn.commit()
    log.info("迁移 v20 完成：events.completed_at 已就绪")


_migrations[20] = _migrate_v20


# ── v21 迁移：主动消息独立会话（era 时间线）────────────────────

async def _migrate_v21(conn: aiosqlite.Connection) -> None:
    """v21: 主动消息独立会话 — 四表补列 + 旧积压清账。

    背景（2026-08-09 用户需求）：主动消息此前无任何会话归属，前端把
    积压一股脑注入当前打开的对话（「早安/午安/晚上好」问候轰炸）。
    方案：离线期间的主动消息新开 era 会话（chat_sessions.is_era=1）并
    追加进它，冷冷上线自动切到该窗口看到小汐的活动与话语；主动消息
    落库带 session_id；presence 上报在线状态供 era 路由判定。

    ALTER TABLE ADD COLUMN 逐列守卫（幂等，同 v12/v16 模式）；全新安装
    时各列已由 v3/v8 建表 DDL 与共享常量携带，本迁移自动跳过。
    旧积压清账是必需步骤：升级前 delivered/stored 的未读行没有
    session_id，不标已读的话新前端会按「legacy 无归属」路径把它们再次
    注入当前会话，原样复现本批要修的 bug。一次性清账损失的是几条
    已过期消息，换的是升级后不再问候轰炸。
    """
    import datetime

    from utils.timezone import HKT as _HKT

    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")

    # 表不存在守卫（v7/v16/v20 同款）：极老库 / 直接调 ensure_schema 的
    # 升级测试夹具可能跳过 v3（schema_version 伪造已应用），proactive_
    # messages 尚未建立——此时用含新列的完整 DDL 建表，终态一致
    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='proactive_messages'"
    )
    if not await cursor.fetchone():
        await conn.execute(
            """CREATE TABLE IF NOT EXISTS proactive_messages (
                id              TEXT PRIMARY KEY,
                content         TEXT NOT NULL,
                created_at      TEXT NOT NULL,
                delivered_at    TEXT,
                read_at         TEXT,
                status          TEXT DEFAULT 'pending',
                session_id      TEXT
            )"""
        )
    else:
        cursor = await conn.execute("PRAGMA table_info(proactive_messages)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "session_id" not in columns:
            await conn.execute("ALTER TABLE proactive_messages ADD COLUMN session_id TEXT")

    # 旧积压清账：升级前的无归属未读全部标已读（见 docstring）
    await conn.execute(
        "UPDATE proactive_messages SET status = 'read', read_at = ? "
        "WHERE status IN ('delivered', 'stored') AND read_at IS NULL",
        (now,),
    )
    await conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_proactive_session "
        "ON proactive_messages(session_id)"
    )

    cursor = await conn.execute("PRAGMA table_info(chat_sessions)")
    columns = {row[1] for row in await cursor.fetchall()}
    if "is_era" not in columns:
        await conn.execute(
            "ALTER TABLE chat_sessions ADD COLUMN is_era INTEGER NOT NULL DEFAULT 0"
        )

    cursor = await conn.execute("PRAGMA table_info(chat_messages)")
    columns = {row[1] for row in await cursor.fetchall()}
    if "msg_type" not in columns:
        await conn.execute(
            "ALTER TABLE chat_messages ADD COLUMN msg_type TEXT NOT NULL DEFAULT 'chat'"
        )

    cursor = await conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='life_state'"
    )
    if not await cursor.fetchone():
        await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    else:
        cursor = await conn.execute("PRAGMA table_info(life_state)")
        columns = {row[1] for row in await cursor.fetchall()}
        if "last_seen_at" not in columns:
            await conn.execute("ALTER TABLE life_state ADD COLUMN last_seen_at TEXT")
        if "active_session_id" not in columns:
            await conn.execute("ALTER TABLE life_state ADD COLUMN active_session_id TEXT")

    await conn.commit()
    log.info("迁移 v21 完成：主动消息会话归属（session_id/is_era/msg_type/presence）")


_migrations[21] = _migrate_v21


# ── v22 迁移：chat_messages 关系查询组合索引 ───────────────────

async def _migrate_v22(conn: aiosqlite.Connection) -> None:
    """v22: chat_messages (role, created_at) 索引 — 批③关系密度与批②画像蒸馏查询去全表扫。

    两处新查询（agent/soul.py 近7天互动密度、memory/profile.py 近30天
    画像素材）都按 (role='user', created_at) 谓词扫描 chat_messages，
    无索引时全表扫。加组合索引一次覆盖两处。

    纯增量建索引（IF NOT EXISTS 幂等），无数据语义变更；全新安装经
    ensure_schema 全量迁移同样获得本索引，与升级路径终态一致。
    """
    await conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_chat_messages_role_created "
        "ON chat_messages (role, created_at)"
    )
    await conn.commit()
    log.info("迁移 v22 完成：chat_messages (role, created_at) 索引已建立")


_migrations[22] = _migrate_v22


# ── 迁移 v23：session_summaries（P1-a session summary 层）────────

# 会话脉络摘要（P1-a，2026-08-13）：长对话的一句话语义压缩。
# session_id 对应 chat_sessions.id；ended_at 是会话最后消息时间快照
# （叙事按谈话新鲜度排序，非摘要生成时间）；独立表 → 天然不落
# memories 任何读面（面板/recall/健康检查），零 _READ_EXCLUSIONS 改动。
SESSION_SUMMARIES_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS session_summaries (
    session_id  TEXT PRIMARY KEY,
    summary     TEXT NOT NULL,
    msg_count   INTEGER NOT NULL,
    ended_at    TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
"""


async def _migrate_v23(conn: aiosqlite.Connection) -> None:
    """v23: session_summaries 表 — session summary 层（P1-a）的存储。"""
    await conn.execute(SESSION_SUMMARIES_SCHEMA_SQL)
    await conn.commit()
    log.info("迁移 v23 完成：session_summaries 表已建立")


_migrations[23] = _migrate_v23


# ── 迁移 v24：会话类型与来源分家（kind 列）─────────────────────

async def _migrate_v24(conn: aiosqlite.Connection) -> None:
    """v24: chat_sessions.kind — 把「当前类型」从「来源」里拆出来。

    is_era 是会话的出生证明（创建时写入、永不改写），kind 是会话现在
    是什么（冷冷接话即提升为 'chat'）。此前只有前者，于是列表把
    is_era 当类型用 → 被接话的碎碎念永远顶着「碎碎念」标签。

    三步，全部幂等：
    1. 补列 kind（老库 ALTER，新库已由 CHAT_ARCHIVE_SCHEMA_SQL 带列）；
    2. 回填：is_era=1 且还没有 user 消息的 → kind='solo'（其余保持
       默认 'chat'，含「已被接话的 era」与冷冷自己开的会话）；
    3. era 会话标题统一为 ERA_SESSION_TITLE（旧日记式标题带日期，与
       新的时间分组列表重复，也与 diaries 表的真日记撞名）。

    第 2 步的「还没有 user 消息」与旧 get_open_era_id 的判定逐字一致，
    保证迁移前后「哪些 era 算开放」的答案不变——era 路由不受影响。
    """
    cursor = await conn.execute("PRAGMA table_info(chat_sessions)")
    columns = {row[1] for row in await cursor.fetchall()}
    if "kind" not in columns:
        await conn.execute(
            "ALTER TABLE chat_sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'"
        )

    await conn.execute(
        """UPDATE chat_sessions
              SET kind = 'solo'
            WHERE is_era = 1
              AND NOT EXISTS (
                  SELECT 1 FROM chat_messages m
                   WHERE m.session_id = chat_sessions.id AND m.role = 'user'
              )"""
    )

    await conn.execute(
        "UPDATE chat_sessions SET title = ? WHERE is_era = 1 AND title <> ?",
        (ERA_SESSION_TITLE, ERA_SESSION_TITLE),
    )

    await conn.commit()
    log.info("迁移 v24 完成：chat_sessions.kind 已就绪，era 会话标题已统一")


_migrations[24] = _migrate_v24


# ── 核心迁移逻辑 ──────────────────────────────────────────────

async def _get_current_version(conn: aiosqlite.Connection) -> int:
    """读取当前 schema 版本。schema_version 表不存在时返回 0。

    只把「表不存在」当作全新安装处理：旧写法吞掉一切异常回落为 0，
    磁盘错误 / 表损坏也会被当成 v0 → 触发全量迁移重放。重放目前
    恰好幂等，但这份安全性依赖每个迁移函数的自觉，框架层必须有兜底：
    其他异常如实上抛、拒绝启动，而不是赌迁移幂等。
    """
    cursor = await conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_version'"
    )
    if await cursor.fetchone() is None:
        return 0
    cursor = await conn.execute("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1")
    row = await cursor.fetchone()
    return row[0] if row else 0


async def _create_version_table(conn: aiosqlite.Connection) -> None:
    """创建 schema_version 表（如果不存在）。"""
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS schema_version (
            version     INTEGER PRIMARY KEY,
            applied_at  TEXT DEFAULT (datetime('now'))
        )
    """)
    await conn.commit()


async def ensure_schema(conn: aiosqlite.Connection) -> None:
    """检查并执行所有未应用的迁移。

    在 init_db() 末尾调用。幂等——多次执行不会重复迁移。
    """
    await _create_version_table(conn)

    current = await _get_current_version(conn)

    if current >= SCHEMA_VERSION:
        return  # 已是最新版本

    # 按版本号顺序执行每个未应用的迁移
    for target_version in range(current + 1, SCHEMA_VERSION + 1):
        migrate_fn = _migrations.get(target_version)
        if migrate_fn is None:
            # 没有注册的迁移函数——只记录版本号（可能是全新安装）
            log.info("schema v%d：无迁移函数，直接标记", target_version)
        else:
            log.info("正在迁移到 schema v%d ……", target_version)
            await migrate_fn(conn)
            log.info("迁移到 schema v%d 完成", target_version)

        # 记录已应用的版本
        await conn.execute(
            "INSERT INTO schema_version (version) VALUES (?)",
            (target_version,),
        )
        await conn.commit()

    log.info("schema 版本：%d（最新：%d）", SCHEMA_VERSION, SCHEMA_VERSION)
