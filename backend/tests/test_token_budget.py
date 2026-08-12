"""TOKEN_COST_WARNING 预算告警测试（P2-1，2026-08-12）。"""
import logging

import pytest

from config.settings import get_settings
from utils import token_budget


@pytest.fixture(autouse=True)
def _clean_budget():
    token_budget._reset()
    yield
    token_budget._reset()


def _set_threshold(monkeypatch, value: int) -> None:
    monkeypatch.setattr(get_settings(), "token_cost_warning", value)


def test_enabled_reflects_threshold(monkeypatch):
    _set_threshold(monkeypatch, 0)
    assert token_budget.enabled() is False
    _set_threshold(monkeypatch, 5000)
    assert token_budget.enabled() is True


def test_disabled_is_noop(monkeypatch, caplog):
    _set_threshold(monkeypatch, 0)
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(10**9)
    assert "预算告警" not in caplog.text


def test_below_threshold_silent(monkeypatch, caplog):
    _set_threshold(monkeypatch, 1000)
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(400)
        token_budget.record_input(400)
    assert "预算告警" not in caplog.text


def test_crossing_threshold_warns_once(monkeypatch, caplog):
    _set_threshold(monkeypatch, 1000)
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(600)
        token_budget.record_input(600)  # 累计 1200 越过 1000 → 只告一次
    assert caplog.text.count("预算告警") == 1


def test_escalating_multiples(monkeypatch, caplog):
    _set_threshold(monkeypatch, 1000)
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(2500)  # 一次越过 1000/2000 两条线 → 各告一次
    assert caplog.text.count("预算告警") == 2


def test_date_roll_resets(monkeypatch, caplog):
    _set_threshold(monkeypatch, 1000)
    token_budget.record_input(900)
    token_budget._day = "2000-01-01"  # 模拟跨天
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(900)  # 归零重计，不越线 → 不告
    assert "预算告警" not in caplog.text


def test_negative_or_zero_tokens_ignored(monkeypatch, caplog):
    _set_threshold(monkeypatch, 100)
    with caplog.at_level(logging.WARNING):
        token_budget.record_input(0)
        token_budget.record_input(-5)
    assert "预算告警" not in caplog.text
