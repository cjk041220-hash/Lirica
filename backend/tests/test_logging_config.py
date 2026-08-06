"""日志配置回归测试（体检 9.13）。

缺陷背景：utils/logger.py 读的是 os.environ，而 pydantic Settings
（extra="ignore"）不会把未声明的 .env 键放进 environ —— 导致按
README/.env.example 把 LOG_FILE/LOG_LEVEL 写进 .env 的部署者，
日志落盘**静默不生效**。修复 = Settings 声明字段（pydantic 天然
「真实环境变量 > .env 文件」优先级）+ server 启动时显式透传给
setup_logging。本组测试钉死这条链路的每一环。
"""
import logging
from logging.handlers import RotatingFileHandler

import pytest

from config.settings import Settings
from utils.logger import setup_logging


@pytest.fixture
def clean_root_logging():
    """快照 root handlers/级别并在测试后复原，防止文件句柄/级别跨测试污染。"""
    root = logging.getLogger()
    handlers, level = root.handlers[:], root.level
    yield
    for h in root.handlers[:]:
        if isinstance(h, RotatingFileHandler):
            h.close()
    root.handlers = handlers
    root.setLevel(level)


def _file_handlers() -> list[RotatingFileHandler]:
    return [h for h in logging.getLogger().handlers
            if isinstance(h, RotatingFileHandler)]


# ── Settings 侧：字段声明 + 环境变量读取 ─────────────────────


def test_settings_declares_log_fields():
    """LOG_FILE/LOG_LEVEL 必须是 Settings 字段，否则 .env 值被 extra=ignore 吞掉。"""
    s = Settings(_env_file=None)
    assert s.log_file == ""
    assert s.log_level == ""


def test_settings_reads_log_config_from_env(monkeypatch):
    monkeypatch.setenv("LOG_FILE", "/tmp/xiaoxi-test.log")
    monkeypatch.setenv("LOG_LEVEL", "DEBUG")
    s = Settings(_env_file=None)
    assert s.log_file == "/tmp/xiaoxi-test.log"
    assert s.log_level == "DEBUG"


# ── logger 侧：显式参数落盘 + 环境变量兼容 ────────────────────


def test_setup_logging_param_enables_file(tmp_path, clean_root_logging, monkeypatch):
    """server 透传的显式 log_file 参数必须启用落盘（.env 路径的等效语义）。"""
    monkeypatch.delenv("LOG_FILE", raising=False)
    target = tmp_path / "xiaoxi.log"
    setup_logging(log_file=str(target))
    handlers = _file_handlers()
    assert len(handlers) == 1
    assert handlers[0].baseFilename == str(target).replace("\\", "/") or \
        handlers[0].baseFilename.endswith(target.name)
    assert handlers[0].maxBytes == 10 * 1024 * 1024
    assert handlers[0].backupCount == 7


def test_setup_logging_env_var_still_works(tmp_path, clean_root_logging, monkeypatch):
    """真实环境变量路径（Docker compose env）不受修复影响。"""
    target = tmp_path / "env-driven.log"
    monkeypatch.setenv("LOG_FILE", str(target))
    setup_logging()
    handlers = _file_handlers()
    assert len(handlers) == 1
    assert handlers[0].baseFilename.endswith(target.name)


def test_setup_logging_param_beats_env(tmp_path, clean_root_logging, monkeypatch):
    """显式参数优先于环境变量（Settings 已含「真实 env > .env」优先级，
    logger 层参数即最终裁决）。"""
    env_target = tmp_path / "env.log"
    param_target = tmp_path / "param.log"
    monkeypatch.setenv("LOG_FILE", str(env_target))
    setup_logging(log_file=str(param_target))
    handlers = _file_handlers()
    assert len(handlers) == 1
    assert handlers[0].baseFilename.endswith(param_target.name)


def test_setup_logging_no_config_no_file(tmp_path, clean_root_logging, monkeypatch):
    """未配置 LOG_FILE（参数与环境变量皆无）→ 不产生文件 handler（默认行为不变）。"""
    monkeypatch.delenv("LOG_FILE", raising=False)
    setup_logging()
    assert _file_handlers() == []


def test_log_level_name_param(clean_root_logging, monkeypatch):
    """显式级别名参数生效（.env 的 LOG_LEVEL 经 Settings 透传走此路径）。"""
    monkeypatch.delenv("LOG_LEVEL", raising=False)
    setup_logging(log_level_name="WARNING")
    assert logging.getLogger().level == logging.WARNING


def test_invalid_log_level_falls_back_to_info(clean_root_logging, monkeypatch):
    monkeypatch.delenv("LOG_LEVEL", raising=False)
    setup_logging(log_level_name="NOT_A_LEVEL")
    assert logging.getLogger().level == logging.INFO
