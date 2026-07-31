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
#     上下文专用）完全解耦：档案仅供历史列表与回看，永远不会被
#     读进 LLM 上下文（只有 chat.py 写入、sessions.py 读取）
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
SCHEMA_VERSION = 10


# memories 去重唯一部分索引 DDL。三处共享此常量保证永不漂移：
# 全新安装（core.py init_db）/ 测试夹具（conftest）/ 迁移（v10 重建）。
# manager.add 的 INSERT ... ON CONFLICT 推断子句必须与此谓词逐字一致
# （SQLite 要求推断 WHERE 与索引谓词精确匹配，差一词即推断失败）。
MEMORIES_INDEX_SQL = """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
        ON memories(category, content) WHERE status IN ('active', 'core');
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
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS proactive_messages (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            created_at      TEXT NOT NULL,
            delivered_at    TEXT,
            read_at         TEXT,
            status          TEXT DEFAULT 'pending'
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
        dedupe_key  TEXT
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

# life_state 完整 DDL（v7：新增 first_met_on）。init_db / 迁移 / 测试
# 夹具三处共享此常量，保证 schema 永不漂移（同 EVENTS_SCHEMA_SQL 模式）。
LIFE_STATE_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS life_state (
        id              INTEGER PRIMARY KEY DEFAULT 1,
        mood            TEXT,
        daily_event     TEXT,
        updated_at      TEXT,
        first_met_on    TEXT
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
CHAT_ARCHIVE_SCHEMA_SQL = """
    CREATE TABLE IF NOT EXISTS chat_sessions (
        id          TEXT PRIMARY KEY,
        title       TEXT NOT NULL DEFAULT '',
        created_at  TEXT NOT NULL,
        updated_at  TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated
        ON chat_sessions(updated_at DESC);

    CREATE TABLE IF NOT EXISTS chat_messages (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id  TEXT NOT NULL,
        role        TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
        content     TEXT NOT NULL,
        created_at  TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_chat_messages_session
        ON chat_messages(session_id, id);
"""


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
