"""业务数据库（userdata.db）初始化模块。

维护一个全局的 aiosqlite 长连接，所有模块通过 get_db() 获取。
启动时由 server.py lifespan 调用 init_db_connection() 建立连接并执行 PRAGMA。

⚠️ 单连接约束（架构前提，勿轻易改动）：
整个进程只有一条 aiosqlite 长连接，被所有协程共享。aiosqlite 将操作
串行化到单一后台线程，因此不会损坏数据，但各协程共享同一事务上下文、
彼此没有隔离。这套设计成立的前提是：
- 单用户、低并发的个人陪伴场景；
- uvicorn 单 worker 运行（多 worker 会各自持有连接与内存态，限流/
  缓存/心跳全部失真）。
若未来并发上升或需要多 worker，须引入连接池并将进程内状态外置。
"""
import asyncio
import datetime
import os
from datetime import timezone, timedelta
from pathlib import Path

import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

DB_PATH = Path(__file__).parent.parent / "data" / "userdata.db"

# 冷冷在香港（UTC+8），所有时间戳统一使用此时区
_HKT = timezone(timedelta(hours=8))

# ── 权限收紧 ────────────────────────────────────────────────


def restrict_backup_permissions(path: Path, *, is_dir: bool = False) -> None:
    """收紧备份文件/目录权限：目录 700、文件 600（仅属主可读写）。

    备份含记忆、日记、生理期记录等高敏数据，不应允许同机其他用户读取。
    Windows 下 chmod 语义有限（仅切换只读位），失败时静默忽略，
    不影响备份主流程。
    """
    try:
        os.chmod(path, 0o700 if is_dir else 0o600)
    except Exception as e:
        log.debug("收紧权限失败（%s）：%s", path, e)


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

    # 执行数据库迁移（幂等，检查并应用未执行的迁移）
    # 必须在创建索引之前执行：索引引用的是迁移后的最终 schema
    # （如 memories.status），若在旧版表上先建索引会因列不存在而
    # 直接报错，导致旧数据库永远无法启动新版本（v0.4.3 修复）。
    from database.migrations import ensure_schema, EVENTS_SCHEMA_SQL
    await ensure_schema(conn)

    # 索引（幂等，IF NOT EXISTS）——此时 schema 已保证为最终形态
    # memories(category, content) 为活跃行唯一部分索引（迁移 v5 建立），
    # 与 manager.add 的 ON CONFLICT 推断条件保持一致；此处定义必须同步
    await conn.executescript("""
        CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
            ON memories(category, content) WHERE status = 'active';

        CREATE INDEX IF NOT EXISTS idx_memories_status
            ON memories(status);
    """)
    # events 表（迁移 v6）：老库由迁移建立，新库由下面这段建立，
    # 共享 migrations.EVENTS_SCHEMA_SQL 常量，两处永不漂移
    await conn.executescript(EVENTS_SCHEMA_SQL)
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


# ── Checkpoint 裁剪 ───────────────────────────────────────────

_CHECKPOINTS_DB_PATH = Path(__file__).parent.parent / "data" / "checkpoints.db"


async def prune_checkpoints(
    db_path: Path | None = None,
    keep_per_thread: int = 10,
    drop_inactive_days: int = 30,
) -> int:
    """裁剪旧 checkpoint，每个 thread 只保留最近 keep_per_thread 个。

    另外整体删除 drop_inactive_days 天内无任何活动的僵尸 thread
    （修复 P1-1 后遗症：前端曾用一次性 session id，堆积大量孤儿 thread）。
    置 drop_inactive_days=0 可关闭该策略。

    使用独立连接打开 checkpoints.db（WAL 模式下与 AsyncSqliteSaver 并发安全）。
    返回删除的 checkpoint 数量。
    """
    path = db_path or _CHECKPOINTS_DB_PATH
    if not path.exists():
        return 0

    deleted = 0
    async with aiosqlite.connect(str(path)) as conn:
        await conn.execute("PRAGMA busy_timeout=5000")

        # ── 清理长期不活跃的整个 thread ─────────────────────
        # checkpoint_id 为时间有序的 uuid7，去掉连字符后前 12 位
        # 十六进制 = unix 毫秒时间戳，定宽十六进制可按字典序比较。
        # （与上文 ORDER BY checkpoint_id DESC 的时间序假设一致）
        if drop_inactive_days > 0:
            import time as _time

            cutoff_ms = int((_time.time() - drop_inactive_days * 86400) * 1000)
            threshold = f"{cutoff_ms:012x}"
            cursor = await conn.execute("""
                DELETE FROM checkpoints
                WHERE thread_id IN (
                    SELECT thread_id FROM checkpoints
                    GROUP BY thread_id
                    HAVING MAX(substr(replace(checkpoint_id, '-', ''), 1, 12)) < ?
                )
            """, (threshold,))
            deleted += cursor.rowcount

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

        # 清理孤立的 writes / blobs（对应的 checkpoint 已被删除）。
        # schema 自适应：langgraph-checkpoint-sqlite ≥3.0 将表重命名为
        # writes（blobs 并入 checkpoints 主表），≤2.x 为
        # checkpoint_writes / checkpoint_blobs。线上实测旧名会报
        # "no such table: checkpoint_writes" 导致裁剪整体失败。
        cursor = await conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
        tables = {r[0] for r in await cursor.fetchall()}

        # 表名来自白名单探测，非外部输入
        writes_table = next(
            (t for t in ("writes", "checkpoint_writes") if t in tables), None
        )
        if writes_table:
            await conn.execute(f"""
                DELETE FROM {writes_table}
                WHERE (thread_id, checkpoint_ns, checkpoint_id) NOT IN (
                    SELECT thread_id, checkpoint_ns, checkpoint_id FROM checkpoints
                )
            """)

        if "checkpoint_blobs" in tables:  # 仅旧 schema 存在
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
    restrict_backup_permissions(backup_dir, is_dir=True)

    timestamp = datetime.datetime.now(_HKT).strftime("%Y%m%d_%H%M%S")
    backup_path = backup_dir / f"userdata_backup_{timestamp}.db"

    try:
        async with aiosqlite.connect(str(source_path)) as src:
            async with aiosqlite.connect(str(backup_path)) as dst:
                await src.backup(dst)
        restrict_backup_permissions(backup_path)
    except Exception as e:
        log.error("数据库备份失败：%s", e)
        return None

    # 隐私选项（P3-3）：从备份中剔除生理期记录。
    # 默认关闭（完整备份优先）；开启 BACKUP_SCRUB_SENSITIVE 后，
    # 备份不再含 cycle_records——适合会把备份同步到网盘/他人的场景。
    try:
        from config.settings import get_settings

        if get_settings().backup_scrub_sensitive:
            async with aiosqlite.connect(str(backup_path)) as scrub_conn:
                await scrub_conn.execute("DELETE FROM cycle_records")
                await scrub_conn.commit()
            log.info("备份已脱敏：cycle_records 已剔除")
    except Exception as e:
        log.warning("备份脱敏失败（备份本身仍保留）：%s", e)

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
