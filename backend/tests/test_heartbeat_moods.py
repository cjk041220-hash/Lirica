"""情绪→文风映射单测 — mood_style_hint（批次④，2026-08-11 计划）。

heartbeat_moods 模块既有测试（MOOD_VECTORS / _gauss_weights 等）仍住在
test_heartbeat.py（模块自 heartbeat.py 拆出时未迁测试）；本文件按计划
brief 新建，承接批次④新增的 mood_style_hint 测试——heartbeat_moods 的
新功能测试归位到专用测试文件，不再堆回 heartbeat 大文件。
"""
import math

from heartbeat_moods import _MOOD_STYLE_HINTS, MOOD_VECTORS, TIME_PERIODS, mood_style_hint

# 批次一 P0-2 新增词（2026-08-12，用户拍板「4 词保底」）——几何/可达性
# 测试的检查对象。未来再扩词时更新此清单（A/B 实测文风句无方向性影响，
# 扩词的「细腻表达」动机已证伪，只余「减少重复」价值）。
_BATCH1_NEW = {"平静", "安心", "轻松", "想念"}


def _dist(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2)


def test_mood_vectors_and_style_hints_keys_aligned():
    """永久纪律「新心情词双补」的守护：坐标键集 ≡ 文风句键集。

    现状为承诺补实：路线图声称键集一致性有测试，实际 2026-08-11 前
    不存在——漏坐标的新词会在 _gauss_weights 走兜底权重 1.0（伪均匀，
    实测每个漏词 ~19.4% 概率成为常客），本测试钉死双补。
    """
    assert set(MOOD_VECTORS) == set(_MOOD_STYLE_HINTS)
    assert len(MOOD_VECTORS) >= 14  # 防误删：10 原词 + 批次一 4 新词


def test_batch1_new_moods_not_semantic_redundant():
    """新增词与全表最小距离 ≥0.22（防语义冗余：<0.2 的词对高斯采样
    权重几乎重叠，等于同一情绪）。原 10 词之间的近距离（好奇↔活泼
    0.14）是既有设计，不在本测试检查域。"""
    for new in _BATCH1_NEW:
        for old, coord in MOOD_VECTORS.items():
            if old != new:
                assert _dist(MOOD_VECTORS[new], coord) >= 0.22, (
                    f"{new} ↔ {old} 距离 <0.22，语义冗余"
                )


def test_new_moods_reachable_in_their_periods():
    """新增词在其出现的每个时段池内都有 ≤0.65 近邻（防死词）。

    _choose_mood 从 period["moods"] 高斯采样（σ=0.25）——距离 0.65 时
    权重 exp(-0.65²/0.125)≈0.034，仍可被事件信号推近后采到；超阈值 =
    除非目标点恰好落该词坐标附近否则永不采到。原 10 词例外：担心靠
    care 事件信号驱动（夜晚池最近 0.85 是信号路径非几何路径），豁免。
    """
    for period_cfg in TIME_PERIODS.values():
        for mood in period_cfg["moods"]:
            if mood not in _BATCH1_NEW:
                continue
            nearest = min(
                _dist(MOOD_VECTORS[mood], MOOD_VECTORS[other])
                for other in period_cfg["moods"] if other != mood
            )
            assert nearest <= 0.65, (
                f"{mood} 在时段池最近 {nearest:.2f} > 0.65，死词（采不到）"
            )


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


def test_time_periods_events_expanded_and_diverse():
    """时段事件池扩容（批次1：solo 素材井扩容，每个时段 ≥20 条，涵盖小汐自己的生活）。"""
    for period_key, cfg in TIME_PERIODS.items():
        events = cfg["events"]
        assert len(events) >= 20, f"时段 {period_key} 事件池仅 {len(events)} 条，不足 20 条"
        # 确保包含小汐自己的生活素材（非纯盯冷冷）
        joined = " ".join(events)
        assert any(k in joined for k in ["曲", "歌", "书", "番", "甜品", "汤", "光", "星", "浪", "海"]), (
            f"时段 {period_key} 缺少小汐自己的生活/兴趣/感官元素"
        )
