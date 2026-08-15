"""Web Push 发送器测试 — 异步发送 / 超时 / 失败隔离 / 410 清理 / 原子 UPSERT。

历史缺陷回归：
- v2.0 在 async 函数里直接调用同步的 webpush()（requests 实现，默认无超时），
  阻塞事件循环，半死订阅端点可挂死整个服务。
- save_subscription 先查后插，并发订阅同一 endpoint 会触发 IntegrityError。
"""
import asyncio

import pytest
from pywebpush import WebPushException


@pytest.fixture
def patch_db(tmp_db, monkeypatch):
    """把临时数据库注入 database.core 全局连接（send_push/save_subscription 经 get_db 使用）。"""
    import database.core as db_core

    monkeypatch.setattr(db_core, "_db", tmp_db)
    return tmp_db


@pytest.fixture
def vapid_enabled(monkeypatch):
    """启用 VAPID 密钥（默认配置为空 → 推送被跳过）。"""
    from config.settings import get_settings

    s = get_settings()
    monkeypatch.setattr(s, "vapid_public_key", "test-public-key")
    monkeypatch.setattr(s, "vapid_private_key", "test-private-key")
    return s


def _fake_webpush_recorder(calls: list, behavior=None):
    """构造一个记录调用的 webpush_async 替身。

    behavior: 可选 callable(subscription_info) -> None，可抛异常模拟失败。
    """

    async def fake_webpush(**kwargs):
        calls.append(kwargs)
        if behavior is not None:
            behavior(kwargs["subscription_info"])

    return fake_webpush


async def test_send_push_no_subscriptions(patch_db, vapid_enabled, monkeypatch):
    """无订阅时直接返回 0，不发起任何推送调用。"""
    from utils import web_push

    calls = []
    monkeypatch.setattr(web_push, "webpush_async", _fake_webpush_recorder(calls))

    sent = await web_push.send_push("标题", "正文")
    assert sent == 0
    assert calls == []


async def test_send_push_vapid_missing_skips(patch_db, monkeypatch):
    """VAPID 密钥未配置时跳过推送。"""
    from config.settings import get_settings
    from utils import web_push

    s = get_settings()
    monkeypatch.setattr(s, "vapid_public_key", "")
    monkeypatch.setattr(s, "vapid_private_key", "")

    await web_push.save_subscription("https://push.example.com/a", "p", "a")
    calls = []
    monkeypatch.setattr(web_push, "webpush_async", _fake_webpush_recorder(calls))

    sent = await web_push.send_push("标题", "正文")
    assert sent == 0
    assert calls == []


async def test_send_push_success_passes_timeout_and_fresh_claims(
    patch_db, vapid_enabled, monkeypatch
):
    """成功路径：传入显式 timeout，且每次调用使用全新的 claims 字典。"""
    from utils import web_push

    await web_push.save_subscription("https://push.example.com/a", "p1", "a1", "ua")
    await web_push.save_subscription("https://push.example.com/b", "p2", "a2", "ua")

    calls = []
    monkeypatch.setattr(web_push, "webpush_async", _fake_webpush_recorder(calls))

    sent = await web_push.send_push("标题", "正文")
    assert sent == 2
    assert len(calls) == 2

    for kw in calls:
        # 回归：必须有显式超时，绝不允许 timeout=None（无限挂起）
        assert kw["timeout"] == web_push._PUSH_TIMEOUT
        assert kw["vapid_private_key"] == "test-private-key"
        assert "aud" not in kw["vapid_claims"]  # 未被库原地修改过的新字典

    # 两次调用不共享 claims 字典（库会原地修改，共享会并发竞态）
    assert calls[0]["vapid_claims"] is not calls[1]["vapid_claims"]


async def test_send_push_isolates_failures_and_cleans_410(
    patch_db, vapid_enabled, monkeypatch
):
    """一个订阅 410 过期、一个正常：发送成功 1 个，过期的被清理，正常的不受影响。"""
    from utils import web_push

    await web_push.save_subscription("https://push.example.com/stale", "p1", "a1")
    await web_push.save_subscription("https://push.example.com/healthy", "p2", "a2")

    stale_response = type("Resp", (), {"status": 410})()

    def behavior(subscription_info):
        if subscription_info["endpoint"].endswith("/stale"):
            raise WebPushException("Push failed: 410 Gone", response=stale_response)

    calls = []
    monkeypatch.setattr(
        web_push, "webpush_async", _fake_webpush_recorder(calls, behavior)
    )

    sent = await web_push.send_push("标题", "正文")

    # 失败被隔离：健康订阅照常成功
    assert sent == 1

    # 410 订阅已从数据库清理，健康订阅保留
    cursor = await patch_db.execute(
        "SELECT endpoint FROM push_subscriptions ORDER BY endpoint"
    )
    rows = await cursor.fetchall()
    assert [r[0] for r in rows] == ["https://push.example.com/healthy"]


async def test_send_push_non_410_failure_keeps_subscription(
    patch_db, vapid_enabled, monkeypatch
):
    """非 410 的服务端错误（如 500）：不清理订阅（可能只是推送服务临时故障）。"""
    from utils import web_push

    await web_push.save_subscription("https://push.example.com/a", "p1", "a1")
    await web_push.save_subscription("https://push.example.com/b", "p2", "a2")

    server_error = type("Resp", (), {"status": 500})()

    def behavior(subscription_info):
        if subscription_info["endpoint"].endswith("/a"):
            raise WebPushException("Push failed: 500 Server Error", response=server_error)

    monkeypatch.setattr(
        web_push, "webpush_async", _fake_webpush_recorder([], behavior)
    )

    sent = await web_push.send_push("标题", "正文")
    assert sent == 1  # b 成功

    cursor = await patch_db.execute("SELECT COUNT(*) FROM push_subscriptions")
    assert (await cursor.fetchone())[0] == 2  # 两个订阅都保留


# ── Apple VAPID aud 覆盖（2026-08-15 iOS 403 BadJwtToken 回归）──


async def test_apple_endpoint_gets_site_origin_aud(vapid_enabled, monkeypatch):
    """Apple 端点 + 配置了 PUSH_SITE_ORIGIN：claims 显式写 aud=网站 origin。

    回归：pywebpush 缺省用 endpoint origin（https://web.push.apple.com）做
    aud，Apple 校验失败恒 403 BadJwtToken（08-15 iOS 订阅两连拒实锤）。
    """
    from utils import web_push

    s = web_push.get_settings()
    monkeypatch.setattr(s, "push_site_origin", "https://selena0603.xyz")

    claims = web_push._get_vapid_claims(
        "https://web.push.apple.com/QAWlO8NvG1xEx7_bpbKA6PwfJj48K8O8E"
    )
    assert claims["aud"] == "https://selena0603.xyz"
    assert claims["sub"] == s.vapid_claim_mailto


async def test_apple_endpoint_without_origin_keeps_pywebpush_default(vapid_enabled):
    """Apple 端点但未配置 PUSH_SITE_ORIGIN：不写 aud（交给 pywebpush 缺省）。"""
    from utils import web_push

    claims = web_push._get_vapid_claims("https://web.push.apple.com/xxx")
    assert "aud" not in claims


async def test_non_apple_endpoint_never_overrides_aud(vapid_enabled, monkeypatch):
    """非 Apple 端点即使配置了 PUSH_SITE_ORIGIN 也不覆盖 aud（FCM/Mozilla/WNS 按 RFC）。"""
    from utils import web_push

    s = web_push.get_settings()
    monkeypatch.setattr(s, "push_site_origin", "https://selena0603.xyz")

    for endpoint in (
        "https://fcm.googleapis.com/fcm/send/abc",
        "https://push.services.mozilla.com/def",
        "https://notify.windows.com/ghi",
    ):
        claims = web_push._get_vapid_claims(endpoint)
        assert "aud" not in claims, endpoint


async def test_send_push_passes_site_origin_aud_for_apple(
    patch_db, vapid_enabled, monkeypatch
):
    """端到端：Apple 订阅推送时，webpush_async 收到的 claims 里 aud=网站 origin。"""
    from utils import web_push

    s = web_push.get_settings()
    monkeypatch.setattr(s, "push_site_origin", "https://selena0603.xyz")

    await web_push.save_subscription(
        "https://web.push.apple.com/QAWlO8NvG1xEx7_bpbKA6PwfJj48K8O8E", "p1", "a1"
    )

    calls = []
    monkeypatch.setattr(web_push, "webpush_async", _fake_webpush_recorder(calls))

    sent = await web_push.send_push("标题", "正文")
    assert sent == 1
    assert calls[0]["vapid_claims"]["aud"] == "https://selena0603.xyz"


async def test_send_push_timeout_error_does_not_crash(
    patch_db, vapid_enabled, monkeypatch
):
    """网络超时（asyncio.TimeoutError）被安全消化，不炸调用方，订阅保留。"""
    from utils import web_push

    await web_push.save_subscription("https://push.example.com/slow", "p1", "a1")

    def behavior(subscription_info):
        raise asyncio.TimeoutError()

    monkeypatch.setattr(
        web_push, "webpush_async", _fake_webpush_recorder([], behavior)
    )

    sent = await web_push.send_push("标题", "正文")
    assert sent == 0

    cursor = await patch_db.execute("SELECT COUNT(*) FROM push_subscriptions")
    assert (await cursor.fetchone())[0] == 1


async def test_send_push_is_nonblocking_coroutine(patch_db, vapid_enabled, monkeypatch):
    """回归：send_push 走异步 I/O——推送"进行中"时事件循环仍可处理其他协程。

    旧版调用同步 webpush()（requests），会整体阻塞事件循环；
    新版基于 aiohttp 的 webpush_async，慢推送不会冻结其他任务。
    """
    from utils import web_push

    await web_push.save_subscription("https://push.example.com/slow", "p1", "a1")

    heartbeat_ticks = []

    async def fake_webpush(**kwargs):
        # 模拟一次慢推送：让出事件循环多次
        for _ in range(5):
            await asyncio.sleep(0)
            heartbeat_ticks.append("tick")

    monkeypatch.setattr(web_push, "webpush_async", fake_webpush)

    # 并发跑 send_push 与一个计数协程；若事件循环被同步阻塞，
    # 计数协程在 send_push 完成前拿不到任何调度机会
    counter = {"n": 0}

    async def count_while_waiting():
        while counter["n"] < 3:
            counter["n"] += 1
            await asyncio.sleep(0)

    sent, _ = await asyncio.gather(web_push.send_push("t", "b"), count_while_waiting())

    assert sent == 1
    assert counter["n"] == 3
    assert heartbeat_ticks  # 慢推送确实让出了事件循环


# ── save_subscription 原子 UPSERT ────────────────────────────


async def test_save_subscription_insert_and_upsert(patch_db, vapid_enabled):
    """首次插入返回新 ID；同 endpoint 再次保存更新密钥并返回原 ID。"""
    from utils.web_push import save_subscription

    first = await save_subscription("https://push.example.com/x", "p1", "a1", "ua1")
    second = await save_subscription("https://push.example.com/x", "p2", "a2", "ua2")

    assert first == second  # 同一订阅

    cursor = await patch_db.execute(
        "SELECT COUNT(*), MAX(p256dh), MAX(user_agent) FROM push_subscriptions"
    )
    count, p256dh, ua = await cursor.fetchone()
    assert count == 1
    assert p256dh == "p2"  # 密钥已更新
    assert ua == "ua2"


async def test_save_subscription_concurrent_same_endpoint(patch_db, vapid_enabled):
    """回归：并发保存同一 endpoint 不抛 IntegrityError，最终只有一行。"""
    from utils.web_push import save_subscription

    results = await asyncio.gather(
        save_subscription("https://push.example.com/race", "p1", "a1", "ua1"),
        save_subscription("https://push.example.com/race", "p2", "a2", "ua2"),
        save_subscription("https://push.example.com/race", "p3", "a3", "ua3"),
    )

    # 三次返回同一个订阅 ID，数据库只有一行
    assert len(set(results)) == 1
    cursor = await patch_db.execute("SELECT COUNT(*) FROM push_subscriptions")
    assert (await cursor.fetchone())[0] == 1
