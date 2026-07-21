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
SCHEMA_VERSION = 4


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
    await conn.execute("PRAGMA foreign_keys=OFF")

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

    await conn.execute("PRAGMA foreign_keys=ON")
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


# ── 核心迁移逻辑 ──────────────────────────────────────────────

async def _get_current_version(conn: aiosqlite.Connection) -> int:
    """读取当前 schema 版本。如果 schema_version 表不存在，返回 0。"""
    try:
        cursor = await conn.execute("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1")
        row = await cursor.fetchone()
        return row[0] if row else 0
    except Exception:
        # 表不存在
        return 0


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
