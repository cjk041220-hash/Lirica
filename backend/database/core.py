"""业务数据库（userdata.db）初始化模块。

维护一个全局的 aiosqlite 长连接，所有模块通过 get_db() 获取。
启动时由 server.py lifespan 调用 init_db_connection() 建立连接并执行 PRAGMA。
"""
import asyncio
import datetime
from datetime import timezone, timedelta
from pathlib import Path

import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

DB_PATH = Path(__file__).parent.parent / "data" / "userdata.db"

# 冷冷在香港（UTC+8），所有时间戳统一使用此时区
_HKT = timezone(timedelta(hours=8))

# ── 全局长连接 ──────────────────────────────────────────────

_db: aiosqlite.Connection | None = None
_init_lock = asyncio.Lock()


async def init_db_connection() -> None:
    """建立全局数据库长连接，并执行并发优化 PRAGMA。

    必须在 init_db() 之前调用。由 server.py lifespan 在启动时调用。
    """
    global _db
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    _db = await aiosqlite.connect(str(DB_PATH))
    # WAL 模式：允许读写并发，大幅减少 database is locked
    await _db.execute("PRAGMA journal_mode=WAL")
    # NORMAL 同步：WAL 模式下的推荐配置，兼顾性能与安全
    await _db.execute("PRAGMA synchronous=NORMAL")
    # 5 秒忙等待：遇到锁时不立即报错，而是等待最多 5 秒
    await _db.execute("PRAGMA busy_timeout=5000")
    await _db.commit()


async def get_db() -> aiosqlite.Connection:
    """获取全局数据库连接。

    正常流程下由 init_db_connection() 提前建好。
    作为保底，若连接尚未建立则惰性初始化（防御性编程）。
    使用双重检查锁防止并发初始化竞态。
    """
    global _db
    if _db is None:
        async with _init_lock:
            if _db is None:
                await init_db_connection()
    return _db  # type: ignore[return-value]


async def close_db_connection() -> None:
    """优雅关闭全局数据库连接。由 server.py lifespan 在关闭时调用。"""
    global _db
    if _db is not None:
        try:
            await _db.close()
        except Exception as e:
            log.warning("数据库连接关闭失败：%s", e)
        _db = None


# ── 建表 + 建索引 ────────────────────────────────────────────

async def init_db(conn: aiosqlite.Connection) -> None:
    """异步初始化业务数据库，创建所需表和索引（如不存在），确保 life_state 有初始行。

    Args:
        conn: 由外部传入的数据库连接（通常是 get_db() 返回的全局连接）。
    """
    # 创建表（IF NOT EXISTS，幂等安全）
    await conn.executescript("""
        CREATE TABLE IF NOT EXISTS memories (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            category        TEXT DEFAULT '其他',
            created_at      TEXT NOT NULL,
            last_used_at    TEXT,
            access_count    INTEGER DEFAULT 0,
            status          TEXT DEFAULT 'active'
        );

        CREATE TABLE IF NOT EXISTS diaries (
            date            TEXT PRIMARY KEY,
            title           TEXT,
            content         TEXT
        );

        CREATE TABLE IF NOT EXISTS life_state (
            id              INTEGER PRIMARY KEY DEFAULT 1,
            mood            TEXT,
            daily_event     TEXT,
            updated_at      TEXT
        );

        CREATE TABLE IF NOT EXISTS proactive_messages (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            created_at      TEXT NOT NULL,
            delivered_at    TEXT,
            read_at         TEXT,
            status          TEXT DEFAULT 'pending'
        );

        CREATE TABLE IF NOT EXISTS push_subscriptions (
            id              TEXT PRIMARY KEY,
            endpoint        TEXT NOT NULL UNIQUE,
            p256dh          TEXT NOT NULL,
            auth            TEXT NOT NULL,
            user_agent      TEXT,
            created_at      TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS cycle_records (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            start_date      TEXT NOT NULL UNIQUE,
            note            TEXT
        );
    """)
    await conn.commit()

    # 索引（幂等，IF NOT EXISTS）
    # 只需一个复合索引，最左列覆盖 category-only 查询
    await conn.executescript("""
        CREATE INDEX IF NOT EXISTS idx_memories_cat_content
            ON memories(category, content);

        CREATE INDEX IF NOT EXISTS idx_memories_status
            ON memories(status);
    """)
    await conn.commit()

    # 确保 life_state 有一条初始数据，防止后续读取空指针
    cursor = await conn.execute("SELECT id FROM life_state WHERE id = 1")
    if not await cursor.fetchone():
        now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
        await conn.execute(
            "INSERT INTO life_state (id, mood, daily_event, updated_at) VALUES (1, ?, ?, ?)",
            ("开心", "刚刚醒来，映海还很安静", now),
        )
        await conn.commit()

    # 执行数据库迁移（幂等，检查并应用未执行的迁移）
    from database.migrations import ensure_schema
    await ensure_schema(conn)


# ── Checkpoint 裁剪 ───────────────────────────────────────────

_CHECKPOINTS_DB_PATH = Path(__file__).parent.parent / "data" / "checkpoints.db"


async def prune_checkpoints(
    db_path: Path | None = None,
    keep_per_thread: int = 10,
) -> int:
    """裁剪旧 checkpoint，每个 thread 只保留最近 keep_per_thread 个。

    使用独立连接打开 checkpoints.db（WAL 模式下与 AsyncSqliteSaver 并发安全）。
    返回删除的 checkpoint 数量。
    """
    path = db_path or _CHECKPOINTS_DB_PATH
    if not path.exists():
        return 0

    deleted = 0
    async with aiosqlite.connect(str(path)) as conn:
        await conn.execute("PRAGMA busy_timeout=5000")

        # 每个 thread 只保留最近 N 个 checkpoint（按 checkpoint_id DESC 排序）
        cursor = await conn.execute("""
            DELETE FROM checkpoints
            WHERE rowid NOT IN (
                SELECT rowid FROM (
                    SELECT rowid, thread_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY thread_id
                               ORDER BY checkpoint_id DESC
                           ) as rn
                    FROM checkpoints
                ) WHERE rn <= ?
            )
        """, (keep_per_thread,))
        deleted += cursor.rowcount

        # 清理孤立的 writes（对应的 checkpoint 已被删除）
        await conn.execute("""
            DELETE FROM checkpoint_writes
            WHERE (thread_id, checkpoint_ns, checkpoint_id) NOT IN (
                SELECT thread_id, checkpoint_ns, checkpoint_id FROM checkpoints
            )
        """)

        # 清理孤立的 blobs
        await conn.execute("""
            DELETE FROM checkpoint_blobs
            WHERE (thread_id, checkpoint_ns, checkpoint_id) NOT IN (
                SELECT thread_id, checkpoint_ns, checkpoint_id FROM checkpoints
            )
        """)

        await conn.commit()

        # 大量删除后回收磁盘空间
        if deleted > 50:
            await conn.execute("VACUUM")

    if deleted:
        log.info("已裁剪 %d 个旧 checkpoint", deleted)
    return deleted


# ── 数据库备份 ────────────────────────────────────────────────

_BACKUP_DIR = Path(__file__).parent.parent / "data" / "backups"
_MAX_BACKUPS = 5


async def backup_database(
    source_path: Path | None = None,
    backup_dir: Path | None = None,
) -> Path | None:
    """使用 SQLite Online Backup API 创建 userdata.db 的一致性快照。

    比 shutil.copy 更安全——即使源数据库正在写入也能得到一致备份。
    自动保留最近 _MAX_BACKUPS 份，删除更旧的备份。
    返回备份文件路径，失败返回 None。
    """
    source_path = source_path or DB_PATH
    backup_dir = backup_dir or _BACKUP_DIR

    if not source_path.exists():
        return None

    backup_dir.mkdir(parents=True, exist_ok=True)

    timestamp = datetime.datetime.now(_HKT).strftime("%Y%m%d_%H%M%S")
    backup_path = backup_dir / f"userdata_backup_{timestamp}.db"

    try:
        async with aiosqlite.connect(str(source_path)) as src:
            async with aiosqlite.connect(str(backup_path)) as dst:
                await src.backup(dst)
    except Exception as e:
        log.error("数据库备份失败：%s", e)
        return None

    # 只保留最近 _MAX_BACKUPS 份
    backups = sorted(
        backup_dir.glob("userdata_backup_*.db"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    for old_backup in backups[_MAX_BACKUPS:]:
        old_backup.unlink()

    log.info("数据库备份已保存：%s", backup_path)
    return backup_path
