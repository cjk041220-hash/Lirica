"""情绪→文风映射单测 — mood_style_hint（批次④，2026-08-11 计划）。

heartbeat_moods 模块既有测试（MOOD_VECTORS / _gauss_weights 等）仍住在
test_heartbeat.py（模块自 heartbeat.py 拆出时未迁测试）；本文件按计划
brief 新建，承接批次④新增的 mood_style_hint 测试——heartbeat_moods 的
新功能测试归位到专用测试文件，不再堆回 heartbeat 大文件。
"""
from heartbeat_moods import mood_style_hint


def test_mood_style_hint_known_moods():
    """已知心情词 → 文风提示；高唤醒正向与低唤醒负向措辞不同。"""
    happy = mood_style_hint("开心")
    sleepy = mood_style_hint("困倦")
    assert happy is not None and "话" in happy
    assert sleepy is not None and "话" in sleepy
    assert "亮" in happy  # 高兴：声音亮、话多一点
    assert "少" in sleepy  # 困倦：话少一点没关系


def test_mood_style_hint_unknown_returns_none():
    """未知心情词 / 空串 → None（不注入，旧版行为不变）。"""
    assert mood_style_hint("不存在的心情") is None
    assert mood_style_hint("") is None
    assert mood_style_hint(None) is None
