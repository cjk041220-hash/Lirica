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
            status TEXT DEFAULT 'active'
        );
        CREATE TABLE IF NOT EXISTS diaries (
            date TEXT PRIMARY KEY, title TEXT, content TEXT
        );
        CREATE TABLE IF NOT EXISTS life_state (
            id INTEGER PRIMARY KEY DEFAULT 1,
            mood TEXT, daily_event TEXT, updated_at TEXT
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
        -- 与生产 schema 一致（迁移 v5）：记忆去重唯一部分索引，
        -- manager.add 的 ON CONFLICT 推断依赖此约束存在
        CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_cat_content
            ON memories(category, content) WHERE status = 'active';
    """)
    # 插入初始生命状态行（与 database/core.py init_db 保持一致）
    await conn.execute(
        "INSERT OR IGNORE INTO life_state (id, mood, daily_event, updated_at) VALUES (1, ?, ?, ?)",
        ("开心", "刚刚醒来，映海还很安静", "2026-07-21 10:00"),
    )
    await conn.commit()
    yield conn
    await conn.close()


@pytest.fixture
async def memory_mgr(tmp_db):
    """MemoryManager backed by a temporary database."""
    from memory.manager import MemoryManager
    return MemoryManager(tmp_db)
