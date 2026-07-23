"""启动安全加固测试（P0-2：fail closed）。"""
import pytest

from utils.security import assert_safe_startup, detect_bind_host, detect_workers


# ── assert_safe_startup ──────────────────────────────────────

def test_loopback_without_token_ok():
    """仅本机绑定 + 空 token = 开发模式，放行。"""
    assert_safe_startup("127.0.0.1", "", allow_insecure=False, workers=1)
    assert_safe_startup("::1", "", allow_insecure=False, workers=1)


def test_non_loopback_without_token_refused():
    """非回环绑定 + 空 token → 拒绝启动（fail closed）。"""
    with pytest.raises(RuntimeError, match="XIAOXI_API_TOKEN"):
        assert_safe_startup("0.0.0.0", "", allow_insecure=False, workers=1)


def test_non_loopback_with_token_ok():
    """非回环绑定 + 已设 token → 放行。"""
    assert_safe_startup("0.0.0.0", "s3cret", allow_insecure=False, workers=1)


def test_allow_insecure_override():
    """显式 XIAOXI_ALLOW_INSECURE 覆盖（受控内网场景）。"""
    assert_safe_startup("0.0.0.0", "", allow_insecure=True, workers=1)


def test_multi_worker_refused():
    """workers > 1 → 拒绝启动（心跳/限流/配置都是进程内状态）。"""
    with pytest.raises(RuntimeError, match="workers"):
        assert_safe_startup("127.0.0.1", "s3cret", allow_insecure=False, workers=4)


# ── detect_bind_host / detect_workers ────────────────────────

def test_detect_bind_host_from_argv():
    argv = ["uvicorn", "api.server:app", "--host", "0.0.0.0", "--port", "8000"]
    assert detect_bind_host(argv) == "0.0.0.0"


def test_detect_bind_host_default_loopback(monkeypatch):
    monkeypatch.delenv("XIAOXI_BIND_HOST", raising=False)
    assert detect_bind_host(["uvicorn", "api.server:app"]) == "127.0.0.1"


def test_detect_bind_host_from_env(monkeypatch):
    monkeypatch.setenv("XIAOXI_BIND_HOST", "192.168.1.10")
    assert detect_bind_host(["uvicorn"]) == "192.168.1.10"


def test_detect_workers_from_argv():
    assert detect_workers(["uvicorn", "--workers", "2"]) == 2


def test_detect_workers_from_env(monkeypatch):
    monkeypatch.setenv("WEB_CONCURRENCY", "3")
    assert detect_workers(["uvicorn"]) == 3


def test_detect_workers_default_one(monkeypatch):
    monkeypatch.delenv("WEB_CONCURRENCY", raising=False)
    assert detect_workers(["uvicorn"]) == 1
