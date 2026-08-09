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
import re
from pathlib import Path

import aiosqlite

from utils.logger import get_logger

log = get_logger(__name__)

DB_PATH = Path(__file__).parent.parent / "data" / "userdata.db"

# 冷冷在香港（UTC+8），所有时间戳统一使用此时区
from utils.timezone import HKT as _HKT


def rows_to_dicts(cursor, rows: list) -> list[dict]:
    """把 aiosqlite 游标的行列表转成 dict 列表（全项目单一实现）。

    memory/manager、tools/diary、utils/events 共用，取代过去三处逐字复制。
    刻意不用 row_factory，规避共享长连接下 row_factory 的共享状态问题。
    """
    cols = [desc[0] for desc in (cursor.description or [])]
    return [dict(zip(cols, row)) for row in rows]


def escape_like(s: str) -> str:
    """转义 LIKE 通配符，% / _ 只按字面匹配（全项目单一实现）。

    参数来自 LLM 工具入参或用户输入，含通配符时（如下划线类别名
    user_info、标题里的 %）不转义会让匹配语义失控——不是注入风险
    （已参数化），而是召回/去重串味。配合 SQL 的 ESCAPE '\\' 使用。
    原生于 memory/manager._escape_like，events 相似匹配复用而上移。
    """
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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


# ── 逻辑写锁 ────────────────────────────────────────────────
#
# 全局单连接意味着任何协程的 commit() 都会把其他协程进行到一半的
# 多语句序列一并提交（aiosqlite 共享同一事务上下文）。对「多条 SQL
# 才构成一个逻辑写」的方法（如 superseded 链：SELECT→INSERT→UPDATE
# →commit），SSE 归档与心跳写交叉时可能半截落库。多语句写路径的
# 对外入口统一在此锁内执行；锁内被调用的内层方法（如
# compress_history）不得再次加锁——asyncio.Lock 不可重入。
#
# 按事件循环缓存：生产只有一个 loop，等价模块级单例；测试下
# pytest-asyncio 每个用例一个新 loop，而 asyncio.Lock 一旦发生
# 真正的阻塞等待就会永久绑定该 loop——跨用例复用会在下一次竞争时
# 抛 "bound to a different event loop"（2026-08-08 events.py 扩大
# 锁覆盖面后实锤）。loop 变了就重建一把，语义不变。
_write_lock: asyncio.Lock | None = None
_write_lock_loop: asyncio.AbstractEventLoop | None = None


def get_write_lock() -> asyncio.Lock:
    """获取全局逻辑写锁（按当前事件循环缓存的单例）。"""
    global _write_lock, _write_lock_loop
    loop = asyncio.get_running_loop()
    if _write_lock is None or _write_lock_loop is not loop:
        _write_lock = asyncio.Lock()
        _write_lock_loop = loop
    return _write_lock


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
            hit_count       INTEGER DEFAULT 0,
            last_hit_date   TEXT,
            status          TEXT DEFAULT 'active',
            updated_at      TEXT,
            source          TEXT,
            prev_content    TEXT,
            superseded_by   TEXT,
            last_mention_ts TEXT,
            importance      INTEGER DEFAULT 5,
            valence         REAL DEFAULT 0,
            arousal         REAL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS diaries (
            date            TEXT PRIMARY KEY,
            title           TEXT,
            content         TEXT
        );

        CREATE TABLE IF NOT EXISTS proactive_messages (
            id              TEXT PRIMARY KEY,
            content         TEXT NOT NULL,
            created_at      TEXT NOT NULL,
            delivered_at    TEXT,
            read_at         TEXT,
            status          TEXT DEFAULT 'pending',
            session_id      TEXT
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
    from database.migrations import (
        CARE_LEDGER_SCHEMA_SQL,
        CHAT_ARCHIVE_SCHEMA_SQL,
        EVENTS_SCHEMA_SQL,
        LIFE_STATE_SCHEMA_SQL,
        MEMORIES_INDEX_SQL,
        MEMORY_EXTRACT_STATE_SQL,
        ensure_schema,
    )
    # life_state 表（v7：含 first_met_on）：老库由迁移补列，新库由共享
    # DDL 常量建立，与 migrations / conftest 三处永不漂移
    await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    await ensure_schema(conn)

    # 索引（幂等，IF NOT EXISTS）——此时 schema 已保证为最终形态
    # memories(category, content) 为活跃+core 行唯一部分索引（迁移 v5
    # 建立、v10 扩谓词），与 manager.add 的 ON CONFLICT 推断条件保持
    # 一致；定义走 MEMORIES_INDEX_SQL 共享常量，三源永不漂移
    await conn.executescript(MEMORIES_INDEX_SQL)
    await conn.executescript("""
        CREATE INDEX IF NOT EXISTS idx_memories_status
            ON memories(status);
    """)
    # events 表（迁移 v6）：老库由迁移建立，新库由下面这段建立，
    # 共享 migrations.EVENTS_SCHEMA_SQL 常量，两处永不漂移
    await conn.executescript(EVENTS_SCHEMA_SQL)
    # chat_sessions / chat_messages（迁移 v8）：同共享 DDL 常量模式
    await conn.executescript(CHAT_ARCHIVE_SCHEMA_SQL)
    # memory_extract_state（迁移 v11）：后台提取水位线 + 健康检查节流
    await conn.executescript(MEMORY_EXTRACT_STATE_SQL)
    # care_ledger（迁移 v19）：异常感知关心冷却台账（Spec B）
    await conn.executescript(CARE_LEDGER_SCHEMA_SQL)
    await conn.commit()

    # 确保 life_state 有一条初始数据，防止后续读取空指针
    # v16：mood_changed_at 初始为 now（首次插入即视为「刚切换」，最小
    # 停留判定从创建时刻起算）
    cursor = await conn.execute("SELECT id FROM life_state WHERE id = 1")
    if not await cursor.fetchone():
        now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
        await conn.execute(
            "INSERT INTO life_state (id, mood, daily_event, mood_changed_at, updated_at) "
            "VALUES (1, ?, ?, ?, ?)",
            ("开心", "刚刚醒来，映海还很安静", now, now),
        )
        await conn.commit()

    # 相识日种子兜底：全新安装时 v7 迁移跑在初始行 INSERT 之前，
    # 那里的种子 UPDATE 命中空表，这里对已有行补一次（同样幂等）
    from database.migrations import _seed_first_met
    await _seed_first_met(conn)
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
        # checkpoint_id 为 langgraph 生成的时间有序 uuid6（见
        # langgraph/checkpoint/base/id.py:uuid6）：时间戳 = unix_ns//100
        # + 格里高利 epoch 偏移 0x01B21DD213814000，UUID 高 48 位
        # （去连字符后前 12 位十六进制）= (timestamp >> 12) & 0xFFFFFFFFFFFF，
        # 定宽十六进制可按字典序比较。阈值必须在同一时间域计算——
        # 旧实现按 unix 毫秒直接格式化，对真实 uuid6 恒小于任何阈值，
        # 导致僵尸清理从未删除过任何行（测试还伪造 uuid7 数据给了假绿灯）。
        if drop_inactive_days > 0:
            import time as _time

            cutoff_ms = int((_time.time() - drop_inactive_days * 86400) * 1000)
            ts = cutoff_ms * 10_000 + 0x01B21DD213814000  # 毫秒 → uuid6 时间域
            threshold = f"{(ts >> 12) & 0xFFFFFFFFFFFF:012x}"
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
# 备份文件名严格模式（体检 P2-17）：glob "userdata_backup_*.db" 会把
# 非时间戳命名的同名文件卷进轮换/清扫，改用正则 fullmatch
_BACKUP_NAME_RE = re.compile(r"userdata_backup_\d{8}_\d{6}\.db")
_BACKUP_SIDECAR_RE = re.compile(r"(userdata_backup_\d{8}_\d{6}\.db)-(?:wal|shm)")


def _unlink_sidecars(db_path: Path) -> None:
    """删除备份文件的 -wal/-shm 兄弟文件（存在才删，缺失静默）。"""
    for sibling in (
        db_path.parent / (db_path.name + "-wal"),
        db_path.parent / (db_path.name + "-shm"),
    ):
        sibling.unlink(missing_ok=True)


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

    # 隐私选项（P3-3）：从备份中剔除高敏数据。
    # 默认关闭（完整备份优先）；开启 BACKUP_SCRUB_SENSITIVE 后，
    # 备份不再含 cycle_records、由它派生的周期推算事件
    # （events.source='cycle'）与对话档案（chat_sessions /
    # chat_messages）——对话档案是最大体量的亲密数据，适合会把
    # 备份同步到网盘/他人的场景。周期事件虽只存预测日，仍是
    # 生理期的衍生物，与 cycle_records 同进同出。
    # DELETE 之后必须 VACUUM（体检 T1-4）：SQLite 的 DELETE 只把页
    # 标记为可复用，数据页原文仍留在备份文件里，十六进制编辑器可
    # 直接捞回——对一个以隐私为卖点的脱敏开关，那是语义级漏洞。
    # VACUUM 重写整库文件彻底清除空闲页（需要约一倍备份大小的临时
    # 磁盘空间，个人库量级无碍；同文件 prune_checkpoints 已有先例）。
    try:
        from config.settings import get_settings

        if get_settings().backup_scrub_sensitive:
            async with aiosqlite.connect(str(backup_path)) as scrub_conn:
                await scrub_conn.execute("DELETE FROM cycle_records")
                await scrub_conn.execute("DELETE FROM events WHERE source = 'cycle'")
                await scrub_conn.execute("DELETE FROM chat_messages")
                await scrub_conn.execute("DELETE FROM chat_sessions")
                await scrub_conn.commit()
                await scrub_conn.execute("VACUUM")
            log.info("备份已脱敏：cycle_records + 周期推算事件 + 对话档案已剔除（含 VACUUM 清页）")
    except Exception as e:
        # P0-04 回归：脱敏是隐私承诺，必须 fail-closed——修复前只记
        # warning，未脱敏的完整库照常保留并宣称「备份已保存」，与
        # 开启脱敏的语义直接冲突（完整对话档案被同步去网盘/他人）。
        # 失败即删掉本次备份（含可能残留的 sidecar），返回 None。
        backup_path.unlink(missing_ok=True)
        _unlink_sidecars(backup_path)
        log.error("备份脱敏失败，已删除未脱敏备份：%s", e)
        return None

    # 成功路径同样清理本次备份的 sidecar（体检 S6）：Online Backup 拷出的
    # 文件继承 WAL 模式位，脱敏/读取开连接会伴生 -wal/-shm，非干净关闭
    # 即残留——此前只有失败分支清理，成功路径的孤儿只能靠下方轮换/
    # 清扫碰运气（生产备份目录实测 14 个）
    _unlink_sidecars(backup_path)

    # 只保留最近 _MAX_BACKUPS 份。淘汰时同删 -wal/-shm 兄弟文件
    # （体检 D-1）：Online Backup 拷出的文件继承 WAL 模式位，脱敏/
    # 读取开连接会伴生 sidecar，非干净关闭即残留——轮换只匹配严格
    # 时间戳命名的 *.db，碰不到它们，孤儿会无限累积。
    backups = sorted(
        (p for p in backup_dir.iterdir() if _BACKUP_NAME_RE.fullmatch(p.name)),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    for old_backup in backups[_MAX_BACKUPS:]:
        old_backup.unlink()
        _unlink_sidecars(old_backup)

    # P0-05 回归：孤儿 sidecar 清扫。上面的轮换只删「被淘汰 .db」的
    # 兄弟文件，早已失去基础 .db 的 -wal/-shm（生产备份目录实测 14 个）
    # 不会再次进入轮换列表，永久累积。每次备份后全量扫一遍：基础 .db
    # 已不存在则删除（对象限定为严格时间戳命名备份的 -wal/-shm 且无主，
    # 不会误伤仍被引用的 sidecar）。
    for sidecar in backup_dir.iterdir():
        m = _BACKUP_SIDECAR_RE.fullmatch(sidecar.name)
        if not m:
            continue
        if not (backup_dir / m.group(1)).exists():
            sidecar.unlink(missing_ok=True)

    log.info("数据库备份已保存：%s", backup_path)
    return backup_path
