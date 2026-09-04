"""Shared test fixtures for the XiaoXi backend test suite."""

import aiosqlite
import pytest


@pytest.fixture
async def tmp_db(tmp_path):
    """Create a temporary aiosqlite database with all tables initialized."""
    db_path = tmp_path / "test.db"
    conn = await aiosqlite.connect(str(db_path))
    await conn.executescript("""
        CREATE TABLE IF NOT EXISTS memories (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            category TEXT DEFAULT '其他', created_at TEXT NOT NULL,
            last_used_at TEXT, access_count INTEGER DEFAULT 0,
            hit_count INTEGER DEFAULT 0, last_hit_date TEXT,
            status TEXT DEFAULT 'active',
            updated_at TEXT, source TEXT, prev_content TEXT,
            superseded_by TEXT,
            last_mention_ts TEXT,
            importance INTEGER DEFAULT 5,
            valence REAL DEFAULT 0,
            arousal REAL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS diaries (
            date TEXT PRIMARY KEY, title TEXT, content TEXT
        );
        CREATE TABLE IF NOT EXISTS proactive_messages (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            created_at TEXT NOT NULL, delivered_at TEXT,
            read_at TEXT, status TEXT DEFAULT 'pending',
            session_id TEXT
        );
        CREATE TABLE IF NOT EXISTS push_subscriptions (
            id TEXT PRIMARY KEY, endpoint TEXT NOT NULL UNIQUE,
            p256dh TEXT NOT NULL, auth TEXT NOT NULL,
            user_agent TEXT, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS cycle_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_date TEXT NOT NULL UNIQUE, note TEXT
        );
    """)
    # life_state（v7：含 first_met_on）、events 表（迁移 v6）、
    # chat_sessions / chat_messages（迁移 v8）、记忆去重唯一部分索引
    # （迁移 v5 建立、v10 扩谓词）、care_ledger（迁移 v19）：与生产
    # 共享同一份 DDL 常量，防止夹具漂移
    from database.migrations import (
        CARE_LEDGER_SCHEMA_SQL,
        CHAT_ARCHIVE_SCHEMA_SQL,
        CUSTOM_PROACTIVE_QUEUE_SQL,
        EVENTS_SCHEMA_SQL,
        LIFE_STATE_SCHEMA_SQL,
        MEMORIES_INDEX_SQL,
        MEMORY_EXTRACT_STATE_SQL,
        SESSION_SUMMARIES_SCHEMA_SQL,
        TOPIC_BANK_SCHEMA_SQL,
    )
    await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    await conn.executescript(EVENTS_SCHEMA_SQL)
    await conn.executescript(CHAT_ARCHIVE_SCHEMA_SQL)
    await conn.executescript(MEMORIES_INDEX_SQL)
    await conn.executescript(MEMORY_EXTRACT_STATE_SQL)
    await conn.executescript(CARE_LEDGER_SCHEMA_SQL)
    await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)
    await conn.executescript(CUSTOM_PROACTIVE_QUEUE_SQL)
    await conn.executescript(TOPIC_BANK_SCHEMA_SQL)
    # 插入初始生命状态行（与 database/core.py init_db 保持一致，v16 含 mood_changed_at）
    from datetime import datetime

    from utils.timezone import HKT as _HKT
    _now = datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    await conn.execute(
        "INSERT OR IGNORE INTO life_state (id, mood, daily_event, mood_changed_at, updated_at) "
        "VALUES (1, ?, ?, ?, ?)",
        ("开心", "刚刚醒来，映海还很安静", _now, _now),
    )
    await conn.commit()
    yield conn
    await conn.close()


@pytest.fixture
async def memory_mgr(tmp_db):
    """MemoryManager backed by a temporary database."""
    from memory.manager import MemoryManager
    return MemoryManager(tmp_db)


@pytest.fixture(scope="session", autouse=True)
async def _close_global_db_after_session():
    """session 收尾关闭 database.core 全局真实连接（防解释器退出挂起）。

    心跳测试经 _periodic_maintenance → prune_checkpoints/backup_database
    走真实的 database.core.get_db()（monkeypatch 只盖住 heartbeat 的
    get_db 引用，盖不住同模块内部调用），惰性建出连到真实 DB_PATH 的
    全局连接；生产侧由 server.py lifespan 调 close_db_connection() 收尾，
    测试侧此前无人收尾 → 单文件/子集跑法下该连接活到解释器关闭，
    threading._shutdown join 非守护 worker 线程永久挂起（基线 922b6e6
    即可复现，2026-08-04 排查确认；全量跑时 GC 恰好回收故不发作）。
    本 fixture 幂等兜底关闭，根治单文件/子集跑法。
    """
    yield
    from database import core
    await core.close_db_connection()


@pytest.fixture
async def _profile_conn():
    """画像测试专用内存库：memories（含 v15/v18 列）+ chat_messages。"""
    import aiosqlite
    conn = await aiosqlite.connect(":memory:")
    await conn.executescript("""
        CREATE TABLE memories (
            id TEXT PRIMARY KEY, content TEXT, category TEXT,
            created_at TEXT, last_used_at TEXT, access_count INTEGER,
            status TEXT, updated_at TEXT, source TEXT,
            last_mention_ts TEXT, importance INTEGER,
            valence REAL, arousal REAL, prev_content TEXT,
            superseded_by TEXT, hit_count INTEGER DEFAULT 0,
            last_hit_date TEXT
        );
        CREATE TABLE chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT, role TEXT, content TEXT,
            created_at TEXT, msg_type TEXT
        );
        -- life_state：Task 4 叙事注入测试走完整 build() 路径，
        -- 其中 get_mood_valence 读 life_state（无行 → None，降级不崩）
        CREATE TABLE life_state (
            id INTEGER PRIMARY KEY,
            mood TEXT, daily_event TEXT,
            mood_changed_at TEXT, updated_at TEXT
        );
    """)
    # memory_extract_state：Task 5 裁决（2026-08-11）——consolidate_memories
    # 的节流查询/记时无条件依赖该表（_throttle_allowed/_mark_consolidated），
    # 与 tmp_db 同源共享同一份 DDL 常量，防止夹具漂移
    from database.migrations import (
        MEMORY_EXTRACT_STATE_SQL,
        SESSION_SUMMARIES_SCHEMA_SQL,
    )
    await conn.executescript(MEMORY_EXTRACT_STATE_SQL)
    # session_summaries（P1-a）：narrative.build() 自 2026-08-13 起无条件
    # 查询该表——_profile_conn 的叙事测试不走 tmp_db，必须同源接表
    await conn.executescript(SESSION_SUMMARIES_SCHEMA_SQL)
    # seed：2 条用户对话（now-1h 相对时间戳，落在蒸馏 30 天窗口内）——
    # 计划缺陷裁决（2026-08-11）：计划原测试 1-4 断言 ran=True 需要
    # 窗口内有对话，空表下「先建行」不可能成立；test 5 自行清空。
    import datetime

    from utils.timezone import HKT as _HKT
    _recent = (datetime.datetime.now(_HKT) - datetime.timedelta(hours=1)).strftime("%Y-%m-%d %H:%M")
    await conn.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
        "VALUES ('seed', 'user', '最近在赶论文，常凌晨才睡', ?, 'chat')",
        (_recent,),
    )
    await conn.execute(
        "INSERT INTO chat_messages (session_id, role, content, created_at, msg_type) "
        "VALUES ('seed', 'user', '昨天和 JK 去吃了火锅', ?, 'chat')",
        (_recent,),
    )
    await conn.commit()
    yield conn
    await conn.close()


@pytest.fixture(autouse=True)
def _isolate_tool_globals():
    """每个用例后重置工具模块的进程级全局注入（P2-1 测试隔离）。

    工具仍依赖模块级全局（memory_manager / diary conn / http client），
    在改为请求级 DI 之前，用 autouse fixture 防止跨用例串扰。
    """
    yield
    from tools import reset_tool_globals
    reset_tool_globals()
