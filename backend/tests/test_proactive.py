"""主动消息 + Web Push 系统测试。"""
import datetime
from datetime import timedelta
from unittest.mock import AsyncMock, MagicMock, patch

from utils.timezone import HKT as _HKT

# ── 触发条件测试 ──────────────────────────────────────────────


async def test_should_not_send_during_quiet_hours(tmp_db):
    """深夜（0:00-7:00）不触发主动消息。"""
    import heartbeat

    fake_now = datetime.datetime(2026, 7, 21, 3, 0, tzinfo=_HKT)
    with patch("heartbeat.datetime") as mock_dt:
        mock_dt.datetime.now.return_value = fake_now
        mock_dt.datetime.side_effect = lambda *a, **kw: datetime.datetime(*a, **kw)
        result = await heartbeat._should_send_proactive()
        assert result is False


async def test_should_not_send_within_interval(tmp_db):
    """距上次主动消息不足 4 小时，不触发。"""
    import database.core as db_core
    import heartbeat

    # 注入测试数据库
    original_db = db_core._db
    db_core._db = tmp_db

    try:
        # 插入一条 1 小时前的主动消息
        one_hour_ago = (datetime.datetime.now(_HKT) - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M")
        await tmp_db.execute(
            "INSERT INTO proactive_messages (id, content, created_at, status) VALUES (?, ?, ?, 'delivered')",
            ("test_msg_1", "hello", one_hour_ago),
        )
        await tmp_db.commit()

        # 直接调用（不 mock datetime），因为消息是 1h 前发的，小于 4h 间隔
        result = await heartbeat._should_send_proactive()
        assert result is False
    finally:
        db_core._db = original_db


async def test_should_send_when_conditions_met(tmp_db):
    """所有条件满足且随机值 < 概率阈值时触发。

    mock datetime 为固定的白天时刻（14:00）：旧实现用真实 now()，
    注释自认「只要此刻不是深夜必定通过」——凌晨 1 点跑测试必然翻红
    （2026-07-27 全量测试实测命中）。
    """
    import database.core as db_core
    import heartbeat

    # 注入测试数据库（同 test_should_not_send_within_interval 的模式）
    original_db = db_core._db
    db_core._db = tmp_db

    try:
        fake_now = datetime.datetime(2026, 7, 21, 14, 0, tzinfo=_HKT)
        # 插入一条「假时间」5 小时前的主动消息（> 4h 最小间隔）
        old_time = (fake_now - timedelta(hours=5)).strftime("%Y-%m-%d %H:%M")
        await tmp_db.execute(
            "INSERT INTO proactive_messages (id, content, created_at, status) VALUES (?, ?, ?, 'delivered')",
            ("test_msg_old", "hello", old_time),
        )
        await tmp_db.commit()

        with patch("heartbeat.datetime") as mock_dt:
            mock_dt.datetime.now.return_value = fake_now
            # strptime 交给真实实现（解析刚插入的消息时间）
            mock_dt.datetime.strptime.side_effect = datetime.datetime.strptime
            # 只 mock random：保证 50% 概率必定命中
            with patch("heartbeat.random") as mock_random:
                mock_random.random.return_value = 0.1  # < 0.5
                result = await heartbeat._should_send_proactive()
                assert result is True
    finally:
        db_core._db = original_db


# ── 消息生成测试 ──────────────────────────────────────────────


async def test_generate_proactive_message_returns_text():
    """消息生成返回非空文本。"""
    import heartbeat

    mock_response = MagicMock()
    mock_response.content = "  冷冷，映海今天好安静，小汐在想你~  "

    mock_llm = AsyncMock()
    mock_llm.ainvoke = AsyncMock(return_value=mock_response)

    with patch("providers.deepseek.create_deepseek_llm", return_value=mock_llm), \
         patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])):
        result = await heartbeat._generate_proactive_message("开心", "在映海浅滩玩", 14)

    assert len(result) > 0


async def test_generate_proactive_truncates_long_text():
    """超过 200 字的文本会被截断。"""
    import heartbeat

    mock_response = MagicMock()
    mock_response.content = "x" * 300

    mock_llm = AsyncMock()
    mock_llm.ainvoke = AsyncMock(return_value=mock_response)

    with patch("providers.deepseek.create_deepseek_llm", return_value=mock_llm), \
         patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])):
        result = await heartbeat._generate_proactive_message("开心", "test", 14)

    assert len(result) <= 200


async def test_generate_proactive_fallback_on_short_text():
    """太短的文本会使用 fallback。"""
    import heartbeat

    mock_response = MagicMock()
    mock_response.content = "嗯"  # 太短（< 5 字）

    mock_llm = AsyncMock()
    mock_llm.ainvoke = AsyncMock(return_value=mock_response)

    with patch("providers.deepseek.create_deepseek_llm", return_value=mock_llm), \
         patch("memory.chat_archive.recent_messages", new=AsyncMock(return_value=[])):
        result = await heartbeat._generate_proactive_message("开心", "test", 14)

    assert len(result) >= 5


# ── 消息存储测试 ──────────────────────────────────────────────


async def test_proactive_message_stored_in_db(tmp_db):
    """主动消息正确存入 proactive_messages 表。"""
    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status) VALUES (?, ?, ?, ?)",
        ("msg_001", "冷冷，小汐在想你~", now, "pending"),
    )
    await tmp_db.commit()

    cursor = await tmp_db.execute("SELECT content, status FROM proactive_messages WHERE id = 'msg_001'")
    row = await cursor.fetchone()
    assert row is not None
    assert row[0] == "冷冷，小汐在想你~"
    assert row[1] == "pending"


async def test_unread_messages_query(tmp_db):
    """未读消息查询只返回 delivered + 未读的。"""
    now = datetime.datetime.now(_HKT).strftime("%Y-%m-%d %H:%M")

    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, delivered_at) VALUES (?, ?, ?, 'delivered', ?)",
        ("msg_a", "消息A", now, now),
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, delivered_at, read_at) VALUES (?, ?, ?, 'read', ?, ?)",
        ("msg_b", "消息B", now, now, now),
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status) VALUES (?, ?, ?, 'pending')",
        ("msg_c", "消息C", now),
    )
    await tmp_db.commit()

    cursor = await tmp_db.execute(
        "SELECT id FROM proactive_messages WHERE status = 'delivered' AND read_at IS NULL"
    )
    rows = await cursor.fetchall()
    assert len(rows) == 1
    assert rows[0][0] == "msg_a"


# ── Push 订阅测试 ─────────────────────────────────────────────


async def test_save_subscription_inserts(tmp_db):
    """保存推送订阅。"""
    import database.core as db_core
    from utils.web_push import save_subscription

    # 注入测试数据库到全局 _db
    original_db = db_core._db
    db_core._db = tmp_db
    try:
        sub_id = await save_subscription(
            endpoint="https://push.example.com/sub1",
            p256dh="test_p256dh_key",
            auth="test_auth_key",
            user_agent="Mozilla/5.0 iPhone",
        )
    finally:
        db_core._db = original_db

    assert sub_id is not None

    cursor = await tmp_db.execute(
        "SELECT endpoint, p256dh, auth FROM push_subscriptions WHERE id = ?",
        (sub_id,),
    )
    row = await cursor.fetchone()
    assert row is not None
    assert row[0] == "https://push.example.com/sub1"


async def test_save_subscription_upsert_by_endpoint(tmp_db):
    """同一 endpoint 不重复插入，只更新密钥。"""
    import database.core as db_core
    from utils.web_push import save_subscription

    original_db = db_core._db
    db_core._db = tmp_db
    try:
        id1 = await save_subscription("https://push.example.com/sub2", "key1", "auth1")
        id2 = await save_subscription("https://push.example.com/sub2", "key2", "auth2")
    finally:
        db_core._db = original_db

    assert id1 == id2

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM push_subscriptions")
    count = (await cursor.fetchone())[0]
    assert count == 1

    cursor = await tmp_db.execute(
        "SELECT p256dh FROM push_subscriptions WHERE endpoint = ?",
        ("https://push.example.com/sub2",),
    )
    row = await cursor.fetchone()
    assert row[0] == "key2"
