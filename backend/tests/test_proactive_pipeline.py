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
