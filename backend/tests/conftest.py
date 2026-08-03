"""Shared test fixtures for the XiaoXi backend test suite."""
import asyncio

import pytest
import aiosqlite


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
            importance INTEGER DEFAULT 5
        );
        CREATE TABLE IF NOT EXISTS diaries (
            date TEXT PRIMARY KEY, title TEXT, content TEXT
        );
        CREATE TABLE IF NOT EXISTS proactive_messages (
            id TEXT PRIMARY KEY, content TEXT NOT NULL,
            created_at TEXT NOT NULL, delivered_at TEXT,
            read_at TEXT, status TEXT DEFAULT 'pending'
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
    # （迁移 v5 建立、v10 扩谓词）：与生产共享同一份 DDL 常量，防止夹具漂移
    from database.migrations import (
        CHAT_ARCHIVE_SCHEMA_SQL,
        EVENTS_SCHEMA_SQL,
        LIFE_STATE_SCHEMA_SQL,
        MEMORIES_INDEX_SQL,
        MEMORY_EXTRACT_STATE_SQL,
    )
    await conn.executescript(LIFE_STATE_SCHEMA_SQL)
    await conn.executescript(EVENTS_SCHEMA_SQL)
    await conn.executescript(CHAT_ARCHIVE_SCHEMA_SQL)
    await conn.executescript(MEMORIES_INDEX_SQL)
    await conn.executescript(MEMORY_EXTRACT_STATE_SQL)
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


@pytest.fixture(autouse=True)
def _isolate_tool_globals():
    """每个用例后重置工具模块的进程级全局注入（P2-1 测试隔离）。

    工具仍依赖模块级全局（memory_manager / diary conn / http client），
    在改为请求级 DI 之前，用 autouse fixture 防止跨用例串扰。
    """
    yield
    from tools import reset_tool_globals
    reset_tool_globals()
