"""B1b：主动消息/早安共用推送流水线 _store_and_push_proactive 行为钉死。

关键安全语义：存库失败 → 不推送（否则设备收到 DB 里没有的通知，未读轮询
也拉不到，形成静默不一致）。另覆盖推送三态：成功→delivered、无设备→stored、
推送异常→stored 降级。
"""
from unittest.mock import AsyncMock

import heartbeat


class _BoomConn:
    """execute 即炸的假连接，模拟存库失败。"""

    async def execute(self, *a, **k):
        raise RuntimeError("disk on fire")

    async def commit(self):
        pass


async def test_store_failure_does_not_push(monkeypatch):
    """存库失败 → 直接返回，绝不调用 send_push（安全语义核心）。"""
    send_push = AsyncMock()
    monkeypatch.setattr("utils.web_push.send_push", send_push)

    async def fake_get_db():
        return _BoomConn()

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)

    # 不抛即通过
    await heartbeat._store_and_push_proactive("hello", label="早安消息")
    send_push.assert_not_called()


async def test_push_success_marks_delivered(tmp_db, monkeypatch):
    send_push = AsyncMock(return_value=1)
    monkeypatch.setattr("utils.web_push.send_push", send_push)

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)

    await heartbeat._store_and_push_proactive("推送成功", label="主动消息")

    send_push.assert_awaited_once()
    assert send_push.await_args.kwargs["body"] == "推送成功"
    cursor = await tmp_db.execute(
        "SELECT status FROM proactive_messages ORDER BY rowid DESC LIMIT 1"
    )
    row = await cursor.fetchone()
    assert row[0] == "delivered"


async def test_no_subscribers_marks_stored(tmp_db, monkeypatch):
    send_push = AsyncMock(return_value=0)  # 无订阅设备
    monkeypatch.setattr("utils.web_push.send_push", send_push)

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)

    await heartbeat._store_and_push_proactive("无设备", label="主动消息")

    cursor = await tmp_db.execute(
        "SELECT status FROM proactive_messages ORDER BY rowid DESC LIMIT 1"
    )
    row = await cursor.fetchone()
    assert row[0] == "stored"


async def test_push_exception_marks_stored(tmp_db, monkeypatch):
    send_push = AsyncMock(side_effect=RuntimeError("push down"))
    monkeypatch.setattr("utils.web_push.send_push", send_push)

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)

    # 推送异常不得外抛，降级 stored
    await heartbeat._store_and_push_proactive("推送炸了", label="早安消息")

    cursor = await tmp_db.execute(
        "SELECT status FROM proactive_messages ORDER BY rowid DESC LIMIT 1"
    )
    row = await cursor.fetchone()
    assert row[0] == "stored"


# ── 启动清扫孤儿 pending（体检 T1-6）──────────────────────────
#
# 存 pending 与推送之间进程崩溃 → 行永久 pending，unread 只读
# delivered/stored → 消息对冷冷彻底不可见。lifespan 启动时全扫降级。


async def _seed_proactive_rows(tmp_db):
    """pending / delivered / 已读 delivered 三种行各一。"""
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status) "
        "VALUES ('orphan1', '孤儿消息', '2026-08-01 10:00', 'pending')"
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, delivered_at) "
        "VALUES ('ok1', '正常送达', '2026-08-01 10:01', 'delivered', '2026-08-01 10:01')"
    )
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status, delivered_at, read_at) "
        "VALUES ('read1', '已读', '2026-08-01 10:02', 'delivered', '2026-08-01 10:02', '2026-08-01 10:05')"
    )
    await tmp_db.commit()


async def test_sweep_downgrades_orphan_pending(tmp_db, monkeypatch):
    """孤儿 pending 降级 stored；delivered/已读行不受影响。"""
    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)
    await _seed_proactive_rows(tmp_db)

    swept = await heartbeat.sweep_orphan_proactive_messages()

    assert swept == 1
    cursor = await tmp_db.execute(
        "SELECT id, status FROM proactive_messages ORDER BY id"
    )
    rows = dict(await cursor.fetchall())
    assert rows["orphan1"] == "stored"     # 孤儿获救
    assert rows["ok1"] == "delivered"      # 正常行不动
    assert rows["read1"] == "delivered"


async def test_sweep_makes_orphan_visible_to_unread(tmp_db, monkeypatch):
    """联动钉：清扫后孤儿进入 unread 读面（delivered/stored 谓词）。"""
    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)
    await tmp_db.execute(
        "INSERT INTO proactive_messages (id, content, created_at, status) "
        "VALUES ('orphan2', '崩在推送途中的消息', '2026-08-01 23:50', 'pending')"
    )
    await tmp_db.commit()

    # 清扫前：unread 读面（push.py:137 同款谓词）查无此消息
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM proactive_messages "
        "WHERE status IN ('delivered', 'stored') AND read_at IS NULL"
    )
    assert (await cursor.fetchone())[0] == 0

    await heartbeat.sweep_orphan_proactive_messages()

    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM proactive_messages "
        "WHERE status IN ('delivered', 'stored') AND read_at IS NULL"
    )
    assert (await cursor.fetchone())[0] == 1  # 冷冷打开页面能看到


async def test_sweep_no_orphans_returns_zero(tmp_db, monkeypatch):
    """无孤儿：返回 0，安静无事。"""
    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)
    assert await heartbeat.sweep_orphan_proactive_messages() == 0
