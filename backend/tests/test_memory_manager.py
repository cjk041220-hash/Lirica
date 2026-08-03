"""MemoryManager 单元测试 — CRUD、去重、相关性排序、矛盾覆盖。"""
import json

import pytest

from memory.manager import MemoryManager


# ── 基础 CRUD ─────────────────────────────────────────────────


async def test_add_inserts_new_memory(memory_mgr):
    """add() 插入新记忆并返回正确的字段。"""
    result = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert result["category"] == "喜好"
    assert result["content"] == "喜欢吃芒果"
    assert "id" in result
    assert "created_at" in result
    assert "access_count" in result
    assert result["status"] == "active"
    assert await memory_mgr.count() == 1


async def test_add_dedup_same_category_content(memory_mgr):
    """同一 category + content 不重复添加，只更新时间。"""
    first = await memory_mgr.add("喜好", "喜欢吃芒果")
    second = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert await memory_mgr.count() == 1
    assert second["id"] == first["id"]


async def test_add_different_content_not_deduped(memory_mgr):
    """不同 content 不去重。"""
    await memory_mgr.add("喜好", "喜欢吃芒果")
    await memory_mgr.add("喜好", "喜欢吃草莓")
    assert await memory_mgr.count() == 2


async def test_add_different_category_not_deduped(memory_mgr):
    """同一 content、不同 category 不去重。"""
    await memory_mgr.add("喜好", "芒果")
    await memory_mgr.add("讨厌的", "芒果")
    assert await memory_mgr.count() == 2


async def test_delete_existing(memory_mgr):
    """删除已有记忆返回 True。"""
    mem = await memory_mgr.add("test", "content")
    assert await memory_mgr.delete(mem["id"]) is True
    assert await memory_mgr.count() == 0


async def test_delete_nonexistent(memory_mgr):
    """删除不存在的记忆返回 False。"""
    assert await memory_mgr.delete("nonexistent_id") is False


async def test_clear(memory_mgr):
    """clear() 返回清除的条数。"""
    await memory_mgr.add("a", "1")
    await memory_mgr.add("b", "2")
    await memory_mgr.add("c", "3")
    cleared = await memory_mgr.clear()
    assert cleared == 3
    assert await memory_mgr.count() == 0


# ── 更新（矛盾覆盖） ──────────────────────────────────────────


async def test_update_content_overwrites(memory_mgr):
    """update_content() 覆盖旧记忆内容。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    assert await memory_mgr.update_content(mem["id"], "不爱吃火锅了") is True
    all_mems = await memory_mgr.all()
    assert len(all_mems) == 1
    assert all_mems[0]["content"] == "不爱吃火锅了"


async def test_update_content_nonexistent(memory_mgr):
    """update_content() 对不存在的 ID 返回 False。"""
    assert await memory_mgr.update_content("fake_id", "new content") is False


async def test_update_content_refreshes_timestamp(memory_mgr):
    """update_content() 保留 created_at，只刷 updated_at（v12：覆盖不重置时间衰减）。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    old_created = mem["created_at"]
    result = await memory_mgr.update_content(mem["id"], "不爱吃火锅了")
    assert result is True
    updated = (await memory_mgr.all())[0]
    assert updated["created_at"] == old_created          # 首次记住的时间不动
    assert updated["updated_at"] is not None
    assert updated["updated_at"] >= old_created          # 修改时间已刷新


async def test_update_content_saves_prev_content(memory_mgr):
    """v12 覆盖留痕：旧文本存 prev_content，纠正后可回看旧说法。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    await memory_mgr.update_content(mem["id"], "不爱吃火锅了")
    updated = (await memory_mgr.all())[0]
    assert updated["prev_content"] == "爱吃火锅"


async def test_add_stores_source(memory_mgr):
    """v12 来源追踪：add(source=...) 落库，读回可追溯。"""
    mem = await memory_mgr.add("喜好", "喜欢喝椰青水", source="chat:s-abc123")
    assert mem["source"] == "chat:s-abc123"


async def test_add_dedup_keeps_original_source(memory_mgr):
    """v12：去重刷新（同内容再次 add）保留首次来源，不覆盖成新来源。"""
    first = await memory_mgr.add("喜好", "喜欢喝椰青水", source="chat:s-first")
    await memory_mgr.add("喜好", "喜欢喝椰青水", source="chat:s-second")
    refreshed = await memory_mgr.all()
    assert len(refreshed) == 1
    assert refreshed[0]["source"] == "chat:s-first"


async def test_export_json_include_archived(memory_mgr, tmp_db):
    """D8：export_json(include_archived=True) 才含冷藏记忆。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    await tmp_db.execute(
        "UPDATE memories SET status = 'archived' WHERE id = ?", (mem["id"],)
    )
    await tmp_db.commit()

    active_only = json.loads(await memory_mgr.export_json())
    assert len(active_only["memories"]) == 0
    with_archived = json.loads(await memory_mgr.export_json(include_archived=True))
    assert len(with_archived["memories"]) == 1
    assert with_archived["memories"][0]["status"] == "archived"


async def test_all_include_archived(memory_mgr, tmp_db):
    """D8：all(include_archived=True) 面板回看冷库。"""
    mem = await memory_mgr.add("喜好", "爱吃火锅")
    await tmp_db.execute(
        "UPDATE memories SET status = 'archived' WHERE id = ?", (mem["id"],)
    )
    await tmp_db.commit()

    assert len(await memory_mgr.all()) == 0
    assert len(await memory_mgr.all(include_archived=True)) == 1


# ── 查询 ──────────────────────────────────────────────────────


async def test_recall_all(memory_mgr):
    """recall() 不传参返回全部记忆。"""
    await memory_mgr.add("a", "1")
    await memory_mgr.add("b", "2")
    result = await memory_mgr.recall()
    assert len(result) == 2


async def test_recall_by_category(memory_mgr):
    """recall(category) 只返回匹配的记忆。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("城市", "香港")
    result = await memory_mgr.recall("水果")
    assert len(result) == 1
    assert result[0]["content"] == "芒果"


async def test_recall_updates_last_used_at_and_access_count(memory_mgr):
    """recall() 会更新被召回记忆的 last_used_at 和 access_count。"""
    await memory_mgr.add("test", "hello")
    result = await memory_mgr.recall("test")
    assert result[0]["last_used_at"] is not None
    # 重新查询确认 access_count 已增加
    all_mems = await memory_mgr.all()
    assert all_mems[0]["access_count"] >= 1


async def test_recall_passive_does_not_update_counters(memory_mgr):
    """recall(passive=True) 纯只读，不更新 access_count / last_used_at。

    供叙事注入等系统被动读取场景使用，防止「越被注入越热门」的正反馈闭环。
    """
    await memory_mgr.add("test", "hello")
    before = (await memory_mgr.all())[0]

    result = await memory_mgr.recall("test", passive=True)
    assert len(result) == 1

    after = (await memory_mgr.all())[0]
    assert after["access_count"] == before["access_count"] == 0
    assert after["last_used_at"] == before["last_used_at"]


async def test_recall_default_limit(memory_mgr):
    """recall() 默认最多返回 20 条，防止撑爆 LLM 上下文。"""
    for i in range(50):
        await memory_mgr.add("test", f"memory_{i:03d}")
    result = await memory_mgr.recall()
    assert len(result) <= 20


async def test_recall_custom_limit(memory_mgr):
    """recall(limit=N) 只返回 N 条。"""
    for i in range(30):
        await memory_mgr.add("test", f"item_{i:03d}")
    result = await memory_mgr.recall(limit=5)
    assert len(result) == 5


async def test_recall_category_respects_limit(memory_mgr):
    """recall(category, limit=N) 按类别筛选时也遵守 limit。"""
    for i in range(25):
        await memory_mgr.add("水果", f"水果_{i:03d}")
    result = await memory_mgr.recall("水果", limit=10)
    assert len(result) == 10


async def test_recall_by_category_method(memory_mgr):
    """recall_by_category() 按类别精确查询，不更新 access_count。"""
    await memory_mgr.add("喜好", "火锅")
    await memory_mgr.add("喜好", "奶茶")
    await memory_mgr.add("城市", "香港")
    result = await memory_mgr.recall_by_category("喜好", limit=5)
    assert len(result) == 2
    # recall_by_category 不更新 access_count
    all_mems = await memory_mgr.all()
    for m in all_mems:
        if m["category"] == "喜好":
            assert m["access_count"] == 0


async def test_list_categories(memory_mgr):
    """list_categories() 返回正确的 {类别: 数量} 映射。"""
    await memory_mgr.add("水果", "芒果")
    await memory_mgr.add("水果", "草莓")
    await memory_mgr.add("城市", "香港")
    cats = await memory_mgr.list_categories()
    assert cats["水果"] == 2
    assert cats["城市"] == 1


async def test_count_empty(memory_mgr):
    """空数据库 count() 返回 0。"""
    assert await memory_mgr.count() == 0


async def test_all_returns_ordered(memory_mgr):
    """all() 按 created_at DESC 返回。"""
    await memory_mgr.add("a", "first")
    await memory_mgr.add("b", "second")
    result = await memory_mgr.all()
    assert len(result) == 2
    assert all("id" in m for m in result)


async def test_recent_limits_results(memory_mgr):
    """recent(limit) 只返回指定数量的记忆。"""
    for i in range(40):
        await memory_mgr.add("test", f"memory_{i:03d}")
    result = await memory_mgr.recent(limit=10)
    assert len(result) == 10


async def test_recent_chronological_order(memory_mgr):
    """recent() 按时间正序排列（最早的在前）。"""
    await memory_mgr.add("test", "first")
    await memory_mgr.add("test", "second")
    result = await memory_mgr.recent(limit=10)
    assert len(result) == 2
    assert result[0]["content"] == "first"
    assert result[1]["content"] == "second"


async def test_export_json(memory_mgr):
    """export_json() 返回有效 JSON。"""
    await memory_mgr.add("test", "hello")
    raw = await memory_mgr.export_json()
    data = json.loads(raw)
    assert "memories" in data
    assert "exported_at" in data
    assert len(data["memories"]) == 1


# ── 状态过滤 ──────────────────────────────────────────────────


async def test_count_excludes_non_active(memory_mgr):
    """count() 只统计 active 状态的记忆。"""
    # 手动插入一条 superseded 记忆
    await memory_mgr._db.execute(
        """INSERT INTO memories (id, content, category, created_at, status)
           VALUES ('superseded_1', 'old', 'test', '2026-01-01 00:00', 'superseded')"""
    )
    await memory_mgr._db.commit()
    await memory_mgr.add("test", "active_one")
    assert await memory_mgr.count() == 1


async def test_all_excludes_non_active(memory_mgr):
    """all() 只返回 active 状态的记忆。"""
    await memory_mgr._db.execute(
        """INSERT INTO memories (id, content, category, created_at, status)
           VALUES ('superseded_2', 'old', 'test', '2026-01-01 00:00', 'superseded')"""
    )
    await memory_mgr._db.commit()
    await memory_mgr.add("test", "active_one")
    result = await memory_mgr.all()
    assert len(result) == 1
    assert result[0]["content"] == "active_one"


# ── #24 回归：去重原子化 ─────────────────────────────────────


async def test_add_concurrent_same_content_dedups(memory_mgr):
    """回归：并发写入同一 (category, content) 只产生 1 行（DB 层去重）。

    旧版「先查后写」在心跳与聊天并发时可产生重复行。
    """
    import asyncio

    await asyncio.gather(*(memory_mgr.add("并发类别", "相同内容") for _ in range(5)))

    cursor = await memory_mgr._db.execute(
        "SELECT COUNT(*) FROM memories "
        "WHERE category = '并发类别' AND content = '相同内容' AND status = 'active'"
    )
    assert (await cursor.fetchone())[0] == 1


async def test_add_duplicate_returns_existing_entry_with_real_access_count(memory_mgr):
    """回归：重复添加返回既有行，access_count 为真实值（旧版硬编码 0）。"""
    first = await memory_mgr.add("类别X", "内容A")

    await memory_mgr._db.execute(
        "UPDATE memories SET access_count = 3 WHERE id = ?", (first["id"],)
    )
    await memory_mgr._db.commit()

    second = await memory_mgr.add("类别X", "内容A")
    assert second["id"] == first["id"]
    assert second["access_count"] == 3


# ── LIKE 转义与参数校验 ───────────────────────────────────────


async def test_recall_escapes_like_wildcards(memory_mgr):
    """category 含下划线时按字面匹配，不再被 `_` 当单字符通配。"""
    await memory_mgr.add("user_info", "冷冷的生日")
    await memory_mgr.add("userXinfo", "不该被匹配的记忆")

    matched = await memory_mgr.recall("user_info", passive=True)
    categories = {m["category"] for m in matched}
    assert "user_info" in categories
    assert "userXinfo" not in categories  # 未转义时 `_` 会匹配任意字符


async def test_recall_by_category_escapes_percent(memory_mgr):
    """category 含 % 时同样只按字面匹配。"""
    await memory_mgr.add("50%折扣", "活动信息")
    await memory_mgr.add("其他", "无关记忆")

    matched = await memory_mgr.recall_by_category("50%折扣")
    assert all(m["category"] == "50%折扣" for m in matched)
    assert len(matched) == 1


async def test_add_rejects_empty_category_or_content(memory_mgr):
    """空 category / content 在入口被拒（防止 LIKE '%%' 匹配全类别串味）。"""
    import pytest

    with pytest.raises(ValueError):
        await memory_mgr.add("", "有内容但没类别")
    with pytest.raises(ValueError):
        await memory_mgr.add("   ", "空白类别")
    with pytest.raises(ValueError):
        await memory_mgr.add("类别", "  ")

    assert await memory_mgr.count() == 0


# ── 话题条件召回（B1）──────────────────────────────────────────


def test_char_bigrams_basic():
    """字符 2-gram：去空白、短串兜底、空串空集。"""
    from memory.manager import _char_bigrams

    assert _char_bigrams("火锅") == {"火锅"}
    assert _char_bigrams("想吃火锅") == {"想吃", "吃火", "火锅"}
    assert _char_bigrams("想 吃 火锅") == {"想吃", "吃火", "火锅"}  # 空白剥离
    assert _char_bigrams("饿") == {"饿"}                          # 单字兜底
    assert _char_bigrams("") == set()
    assert _char_bigrams("   ") == set()


def test_bigram_overlap_semantics():
    """overlap 系数：短 query 全命中长文得满分，无关为 0。"""
    from memory.manager import _bigram_overlap

    # query 的每个 bigram 都在内容里 → 满分（Jaccard 在此会趋零）
    assert _bigram_overlap("火锅", "冷冷昨天说想吃火锅") == 1.0
    # 部分命中按比例（4 个 query bigram 命中 3 个 → 0.75）
    assert _bigram_overlap("喜欢火锅吗", "冷冷喜欢火锅") == 0.75
    # 完全无关
    assert _bigram_overlap("飞去北京", "冷冷喜欢火锅") == 0.0
    # 空值安全
    assert _bigram_overlap("", "火锅") == 0.0
    assert _bigram_overlap("火锅", "") == 0.0


async def test_recall_query_promotes_topic_memory(memory_mgr, tmp_db):
    """话题命中：无关新流水占屏时，query 命中让 10 天前的旧记忆浮到顶。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("去过的地方", "冷冷上周去了趟澳门吃蛋挞")
    ten_days_ago = (
        datetime.datetime.now(HKT) - datetime.timedelta(days=10)
    ).strftime(_TIME_FMT)
    # O1：created_at 与 last_mention_ts 一起拨——「旧记忆」= 10 天没被提及
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? WHERE content LIKE '%澳门%'",
        (ten_days_ago, ten_days_ago),
    )
    await tmp_db.commit()

    for i in range(8):
        await memory_mgr.add("日常", f"流水账 {i}")

    # 无 query：旧记忆 recency≈0.37 沉在新流水（1.0）之下
    no_query = await memory_mgr.recall(limit=3, passive=True)
    assert not any("澳门" in m["content"] for m in no_query)

    # 有 query：话题满分命中（+1.0）把它顶进 top-3
    with_query = await memory_mgr.recall(limit=3, passive=True, query="好想去澳门啊")
    assert any("澳门" in m["content"] for m in with_query), (
        "话题命中的旧记忆未浮现——query 条件召回失效"
    )


async def test_recall_query_passive_keeps_counters(memory_mgr, tmp_db):
    """双钉回归：query 只进排序键，passive=True 仍不刷热度（正反馈闭环保持阻断）。"""
    await memory_mgr.add("喜好", "冷冷喜欢洛天依")
    before = await memory_mgr.recall(limit=1, passive=True)
    acc_before = before[0]["access_count"]

    await memory_mgr.recall(limit=1, passive=True, query="洛天依出新曲了")

    after = await memory_mgr.recall(limit=1, passive=True)
    assert after[0]["access_count"] == acc_before


async def test_recall_surfaces_old_frequently_used_memory(memory_mgr, tmp_db):
    """相关性召回不能被 created_at 预筛死：旧的高频记忆要能浮现。

    回归：此前候选池是 `ORDER BY created_at DESC LIMIT limit*2`，
    access 热度因子对「最新 6 条」之外的记忆完全失效——核心偏好
    （创建早、天天被想起）会被流水账永久挤出 top-K，narrative 宣称的
    「相关路 + 最新路」hybrid 注入名存实亡（相关路退化成最新路）。
    """
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    # 旧记忆：10 天前创建，被想起过 800 次，刚刚才用过。
    # 评分 ≈ exp(-1) + ln(801)*0.15 + 0.3 ≈ 1.67，要高于新记忆
    # 的 recency 1.0 + last_used 0.3 = 1.3（add() 会同时写 last_used_at）
    await memory_mgr.add("核心偏好", "冷冷喜欢喝椰子水")
    ten_days_ago = (
        datetime.datetime.now(HKT) - datetime.timedelta(days=10)
    ).strftime(_TIME_FMT)
    now = datetime.datetime.now(HKT).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_used_at = ?, access_count = 800 "
        "WHERE content = ?",
        (ten_days_ago, now, "冷冷喜欢喝椰子水"),
    )
    await tmp_db.commit()

    # 8 条新的流水账（created_at=now、热度 0）——旧实现的候选池
    # （limit*2 = 6 条）全是它们，旧记忆永远进不了评分环节
    for i in range(8):
        await memory_mgr.add("日常", f"流水账 {i}")

    matched = await memory_mgr.recall(limit=3, passive=True)
    assert any(m["content"] == "冷冷喜欢喝椰子水" for m in matched), (
        "旧的高频记忆未能进入相关性召回 top-3——候选池又被 created_at 预筛了"
    )


async def test_recall_category_surfaces_old_frequent_memory(memory_mgr, tmp_db):
    """D2 回归：类别路径不再 created_at 预筛——类别内旧的高频记忆
    也能进候选池。修复前 ORDER BY created_at DESC LIMIT limit*2 把
    候选池限死在同类别最新 2*limit 条，旧核心偏好（创建早、天天被
    想起）被同类别新流水永久挤出，与无类别路径的病同源。
    """
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("核心偏好", "冷冷喜欢喝椰子水")
    ten_days_ago = (
        datetime.datetime.now(HKT) - datetime.timedelta(days=10)
    ).strftime(_TIME_FMT)
    now = datetime.datetime.now(HKT).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_used_at = ?, access_count = 800 "
        "WHERE content = ?",
        (ten_days_ago, now, "冷冷喜欢喝椰子水"),
    )
    await tmp_db.commit()

    # 同类别 8 条更新的流水——修复前候选池（limit*2=6）全是它们
    for i in range(8):
        await memory_mgr.add("核心偏好", f"新偏好 {i}")

    matched = await memory_mgr.recall(category="核心偏好", limit=3, passive=True)
    assert any(m["content"] == "冷冷喜欢喝椰子水" for m in matched), (
        "旧的高频记忆未能进入类别召回 top-3——类别路径又被 created_at 预筛了"
    )


# ── old_memories（小本本：久远记忆采样，批次 D）────────────────


async def _age_memory(tmp_db, content: str, days_ago: int):
    """把指定记忆拨到 N 天前（O1：created_at 与 last_mention_ts 一起拨——
    「久远」= 多久没被提及，不是多久前创建）。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    ts = (datetime.datetime.now(HKT) - datetime.timedelta(days=days_ago)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ? WHERE content = ?",
        (ts, ts, content),
    )
    await tmp_db.commit()


async def test_old_memories_age_filter(memory_mgr, tmp_db):
    """只返回年龄达标的活跃记忆。"""
    await memory_mgr.add("日常", "旧事甲")
    await memory_mgr.add("日常", "旧事乙")
    await memory_mgr.add("日常", "新事")
    await _age_memory(tmp_db, "旧事甲", 120)
    await _age_memory(tmp_db, "旧事乙", 95)

    result = await memory_mgr.old_memories(min_age_days=90, limit=5)
    assert {m["content"] for m in result} == {"旧事甲", "旧事乙"}


async def test_old_memories_exclude_and_limit(memory_mgr, tmp_db):
    """exclude_ids 排除已提过的；limit 生效。"""
    for name in ("甲", "乙", "丙", "丁"):
        await memory_mgr.add("日常", f"旧事{name}")
        await _age_memory(tmp_db, f"旧事{name}", 120)

    result = await memory_mgr.old_memories(
        min_age_days=90, limit=5, exclude_ids=[
            m["id"] for m in await memory_mgr.all()
            if m["content"] in ("旧事甲", "旧事乙")
        ],
    )
    assert {m["content"] for m in result} == {"旧事丙", "旧事丁"}

    one = await memory_mgr.old_memories(min_age_days=90, limit=1)
    assert len(one) == 1


async def test_old_memories_read_only(memory_mgr, tmp_db):
    """纯被动：调用前后 access_count / last_used_at 逐条不变。

    若刷了热度，被 echo 的记忆会在相关性评分里突然霸榜
    （manager 注释记录的正反馈闭环），这条护栏必须钉死。
    """
    await memory_mgr.add("日常", "旧事")
    await _age_memory(tmp_db, "旧事", 120)

    async def snapshot():
        cursor = await tmp_db.execute(
            "SELECT id, access_count, last_used_at FROM memories ORDER BY id"
        )
        return await cursor.fetchall()

    before = await snapshot()
    await memory_mgr.old_memories(min_age_days=90, limit=5)
    await memory_mgr.old_memories(min_age_days=90, limit=5)
    after = await snapshot()
    assert before == after


# ── 千条级冒烟（D9：记忆量级扩展未验证的补课） ──────────────


async def test_recall_at_scale_1000(memory_mgr, tmp_db):
    """千条记忆下 recall 功能正确（候选池全表评分路径，单用户量级上限）。

    设计纪律：不做严格计时断言（零 flaky 纪律）——钉死功能正确性：
    命中话题、limit 生效、计数正确。这条路径在 1000 行下毫秒级，
    若未来量级再涨，由 embedding 语义检索方案接手（评审 P2）。
    """
    now = "2026-07-01 10:00"
    rows = [
        (f"m{i:04d}", f"流水账第 {i} 条", "日常", now) for i in range(999)
    ]
    rows.append(("mtopic", "冷冷喜欢喝椰青水", "冷冷喜欢的", now))
    await tmp_db.executemany(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES (?, ?, ?, ?, 'active')",
        rows,
    )
    await tmp_db.commit()
    assert await memory_mgr.count() == 1000

    # 全表召回（不带 category）：话题命中浮到最前
    hit = await memory_mgr.recall(limit=5, query="喝椰青水")
    assert len(hit) == 5
    assert hit[0]["content"] == "冷冷喜欢喝椰青水"

    # 类别筛选：候选池被 LIKE 预筛，仍能命中
    cat = await memory_mgr.recall(category="冷冷喜欢的", limit=5)
    assert len(cat) == 1
    assert cat[0]["content"] == "冷冷喜欢喝椰青水"

    # 被动读取：千条级不刷热度（前面两次非被动 recall 已把 mtopic 刷到 2，
    # 被动调用前后必须逐位相等——只比被动这一段）
    cursor = await tmp_db.execute(
        "SELECT access_count FROM memories WHERE id = 'mtopic'"
    )
    before = (await cursor.fetchone())[0]
    await memory_mgr.recall(limit=3, passive=True)
    cursor = await tmp_db.execute(
        "SELECT access_count FROM memories WHERE id = 'mtopic'"
    )
    assert (await cursor.fetchone())[0] == before


# ── 清空与清空前备份（v13 阻塞项 #2：archived 不得被静默销毁）──


async def _seed_archived(tmp_db):
    """造一条冷藏记忆（读面不可见，但行在库里）。"""
    await tmp_db.execute(
        "INSERT INTO memories (id, content, category, created_at, status) "
        "VALUES ('x1', '冷藏的记忆', '日常', '2026-01-01 10:00', 'archived')"
    )
    await tmp_db.commit()


async def test_clear_counts_archived_too(memory_mgr, tmp_db):
    """clear 返回的清除量含 archived——旧版只数 active+core，删全表
    却少报删除量（冷藏也是记忆，用户有权知道它被删了）。"""
    await memory_mgr.add("日常", "活跃记忆")
    await _seed_archived(tmp_db)

    assert await memory_mgr.clear() == 2

    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 0


async def test_backup_before_clear_includes_archived(memory_mgr, tmp_db, tmp_path):
    """清空前备份含 archived——否则冷藏记忆在清空时永久销毁且无备份，
    违反「遗忘是可回溯的冷藏，不是销毁」（v13 阻塞项 #2 核心回归）。"""
    await memory_mgr.add("日常", "活跃记忆")
    await _seed_archived(tmp_db)

    backup_path = await memory_mgr.backup_before_clear(backup_dir=tmp_path)

    data = json.loads(backup_path.read_text(encoding="utf-8"))
    contents = {m["content"] for m in data["memories"]}
    assert {"活跃记忆", "冷藏的记忆"} <= contents

    # 备份 → 清空 → 冷库行仍可从备份找回（端到端不丢数据）
    await memory_mgr.clear()
    cursor = await tmp_db.execute("SELECT COUNT(*) FROM memories")
    assert (await cursor.fetchone())[0] == 0


# ── O1 双时钟：created_at 不可变 + last_mention_ts（schema v17）────────


async def test_add_dedup_does_not_refresh_created_at(memory_mgr, tmp_db):
    """O1 核心回归：add 去重刷新 last_mention_ts，绝不刷新 created_at。

    修复前 add 去重把 created_at 刷成现在——被反复 add 的记忆衰减时钟
    被重置，而反复 update 的记忆老化，同一衰减体系两套矛盾时钟。
    """
    first = await memory_mgr.add("喜好", "喜欢吃芒果")
    await tmp_db.execute(
        "UPDATE memories SET created_at = '2026-01-01 10:00', "
        "last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (first["id"],),
    )
    await tmp_db.commit()

    second = await memory_mgr.add("喜好", "喜欢吃芒果")
    assert second["id"] == first["id"]
    assert second["created_at"] == "2026-01-01 10:00"  # 不可变
    assert second["last_mention_ts"] != "2026-01-01 10:00"  # 提及时钟照常刷新


async def test_update_content_refreshes_last_mention_ts(memory_mgr, tmp_db):
    """O1：update 也刷新 last_mention_ts——被反复 update 的记忆不再老化。"""
    saved = await memory_mgr.add("喜好", "爱吃火锅")
    await tmp_db.execute(
        "UPDATE memories SET last_mention_ts = '2026-01-01 10:00' WHERE id = ?",
        (saved["id"],),
    )
    await tmp_db.commit()

    await memory_mgr.update_content(saved["id"], "不爱吃火锅了")

    cursor = await tmp_db.execute(
        "SELECT created_at, last_mention_ts FROM memories WHERE id = ?",
        (saved["id"],),
    )
    created, last_mention = await cursor.fetchone()
    assert created != "2026-01-01 10:00"  # created_at 是首次创建（今天）
    assert last_mention != "2026-01-01 10:00"  # 覆盖后提及时钟已刷新


def test_relevance_recency_uses_last_mention_ts():
    """O1：衰减时钟是 last_mention_ts 而非 created_at（公式级单测）。"""
    import datetime
    from memory.manager import _relevance_score
    from utils.timezone import HKT

    now = datetime.datetime.now(HKT)
    old = (now - datetime.timedelta(days=100)).strftime("%Y-%m-%d %H:%M")
    today = now.strftime("%Y-%m-%d %H:%M")

    # 100 天前创建、今天提及 → 新鲜（修复前只看 created_at 会认为老透）
    fresh = _relevance_score(
        {"created_at": old, "last_mention_ts": today, "access_count": 0, "importance": 5},
        now, "",
    )
    # 今天创建、100 天前提及 → 老化（修复前会被误判为新鲜）
    stale = _relevance_score(
        {"created_at": today, "last_mention_ts": old, "access_count": 0, "importance": 5},
        now, "",
    )
    assert fresh > stale


# ── I1 importance 因子（schema v17）────────────────────────────


async def test_importance_stored_and_default(memory_mgr):
    """I1：add 显式传 importance 落库；不传默认 5（中性）。"""
    hi = await memory_mgr.add("重要的事", "第一次约会", importance=10)
    assert hi["importance"] == 10
    plain = await memory_mgr.add("日常", "今天吃了饭")
    assert plain["importance"] == 5


async def test_importance_clamped_to_1_10(memory_mgr):
    """I1：LLM 手滑超界 → 钳制回 1-10。"""
    assert (await memory_mgr.add("a", "x", importance=99))["importance"] == 10
    assert (await memory_mgr.add("a", "y", importance=0))["importance"] == 1
    assert (await memory_mgr.add("a", "z", importance="7"))["importance"] == 7
    assert (await memory_mgr.add("a", "w", importance="abc"))["importance"] == 5


async def test_importance_boosts_ranking(memory_mgr, tmp_db):
    """I1 核心回归：同等时近下，高重要性记忆浮到前面。"""
    import datetime
    from utils.timezone import HKT, _TIME_FMT

    await memory_mgr.add("喜好", "日常流水", importance=1)   # 先加（同分时 rowid 靠前）
    await memory_mgr.add("喜好", "重要的事", importance=10)
    old = (datetime.datetime.now(HKT) - datetime.timedelta(days=10)).strftime(_TIME_FMT)
    await tmp_db.execute(
        "UPDATE memories SET created_at = ?, last_mention_ts = ?", (old, old)
    )
    await tmp_db.commit()

    result = await memory_mgr.recall(limit=2, passive=True)
    assert result[0]["content"] == "重要的事"  # 修复前同分按 rowid → 日常流水在前
