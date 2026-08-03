"""覆盖历史链回归（体检 R3，Zep/Graphiti 边失效化 5% 移植）。

v12 的 prev_content 是单层快照：覆盖 A→B→C 后只剩 B，最初的 A 永久
丢失。v15 起每次覆盖把旧内容另落一行 status='superseded'（superseded_by
指向现记忆），历史链完整可溯；读面谓词统一 IN('active','core') 使
superseded 行天然消失在日常读面，v10 去重索引同谓词故不挡同内容重提。
"""
import json


async def test_update_leaves_superseded_row(memory_mgr, tmp_db):
    """一次覆盖：旧内容落 superseded 行，指向现记忆。"""
    saved = await memory_mgr.add("冷冷的信息", "冷冷爱吃火锅")

    assert await memory_mgr.update_content(saved["id"], "冷冷不爱吃火锅了")

    hist = await memory_mgr.history(saved["id"])
    assert len(hist) == 1
    assert hist[0]["content"] == "冷冷爱吃火锅"
    assert hist[0]["status"] == "superseded"
    assert hist[0]["superseded_by"] == saved["id"]


async def test_repeated_updates_keep_full_chain(memory_mgr, tmp_db):
    """覆盖 A→B→C：两代旧说法（A、B）都在历史链里——单层快照做不到的。"""
    saved = await memory_mgr.add("冷冷的信息", "冷冷爱喝奶茶不加糖")

    await memory_mgr.update_content(saved["id"], "冷冷最近在控糖")
    await memory_mgr.update_content(saved["id"], "冷冷控糖成功，偶尔喝半糖")

    hist = await memory_mgr.history(saved["id"])
    assert [h["content"] for h in hist] == [
        "冷冷爱喝奶茶不加糖",   # 最初版本（单层快照已丢失它）
        "冷冷最近在控糖",
    ]
    # 当前内容在记忆本行
    current = (await memory_mgr.all())[0]
    assert current["content"] == "冷冷控糖成功，偶尔喝半糖"
    assert current["prev_content"] == "冷冷最近在控糖"  # 单层快查仍在


async def test_superseded_rows_invisible_on_read_surfaces(memory_mgr, tmp_db):
    """superseded 行跌出一切日常读面（count/all/recall/list_categories）。"""
    saved = await memory_mgr.add("喜好", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    assert await memory_mgr.count() == 1
    assert [m["content"] for m in await memory_mgr.all()] == ["新说法"]
    # 含 archived 的回看面也不含 superseded（那是「遗忘的冷藏」，不是「改口」）
    assert [m["content"] for m in await memory_mgr.all(include_archived=True)] == ["新说法"]
    assert await memory_mgr.list_categories() == {"喜好": 1}
    assert [m["content"] for m in await memory_mgr.recall(passive=True)] == ["新说法"]


async def test_superseded_row_does_not_block_readd(memory_mgr, tmp_db):
    """v10 去重索引谓词 IN('active','core') 不含 superseded——重提旧内容
    插新行（忘了又想起），历史行不阻挡。"""
    saved = await memory_mgr.add("喜好", "冷冷在学吉他")
    await memory_mgr.update_content(saved["id"], "冷冷吉他搁置了")

    got = await memory_mgr.add("喜好", "冷冷在学吉他")  # 同旧说法重提

    assert got["id"] != saved["id"]       # 新行，不是刷历史行
    assert got["status"] == "active"
    cursor = await tmp_db.execute(
        "SELECT COUNT(*) FROM memories WHERE status IN ('active', 'core')"
    )
    assert (await cursor.fetchone())[0] == 2


async def test_superseded_row_keeps_original_created_at(memory_mgr, tmp_db):
    """历史行的 created_at 是本记忆的最初创建时间（时序诚实：这条旧
    说法确实是那时说出的），不是覆盖时间。"""
    saved = await memory_mgr.add("喜好", "旧说法")
    original_created = saved["created_at"]

    await memory_mgr.update_content(saved["id"], "新说法")

    hist = await memory_mgr.history(saved["id"])
    assert hist[0]["created_at"] == original_created


async def test_history_empty_for_never_updated(memory_mgr, tmp_db):
    """从未被覆盖的记忆：历史链为空列表（不是 None/异常）。"""
    saved = await memory_mgr.add("喜好", "从一而终的说法")
    assert await memory_mgr.history(saved["id"]) == []


# ── O3 链上限 + 导出开关（schema v17）─────────────────────────


async def test_history_caps_at_max_generations(memory_mgr, tmp_db):
    """O3：superseded 链保留最新 5 代，更旧的合并为一行 digest。"""
    saved = await memory_mgr.add("冷冷的信息", "说法 0")
    for i in range(1, 7):  # 再覆盖 6 次 → 6 个 superseded 行
        assert await memory_mgr.update_content(saved["id"], f"说法 {i}")

    hist = await memory_mgr.history(saved["id"])
    assert len(hist) <= 5                       # 上限 5（含 digest）
    # digest = 最旧 2 代合并（说法 0、说法 1）
    assert "说法 0" in hist[0]["content"]
    assert "说法 1" in hist[0]["content"]
    # 最新 4 代保留逐行
    assert [h["content"] for h in hist[1:]] == ["说法 2", "说法 3", "说法 4", "说法 5"]
    # 当前内容不丢
    assert (await memory_mgr.all())[0]["content"] == "说法 6"


async def test_history_digest_accumulates_across_overflow(memory_mgr, tmp_db):
    """O3：第二次溢出时 digest 继续累积（说法 0-2 都在 digest 里）。"""
    saved = await memory_mgr.add("冷冷的信息", "说法 0")
    for i in range(1, 8):  # 7 次覆盖 → 两次溢出
        await memory_mgr.update_content(saved["id"], f"说法 {i}")

    hist = await memory_mgr.history(saved["id"])
    assert len(hist) <= 5
    assert "说法 0" in hist[0]["content"]
    assert "说法 1" in hist[0]["content"]
    assert "说法 2" in hist[0]["content"]
    assert [h["content"] for h in hist[1:]] == ["说法 3", "说法 4", "说法 5", "说法 6"]


async def test_export_json_default_excludes_superseded(memory_mgr, tmp_db):
    """O3：默认导出不含 superseded 历史链（与读面一致）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    data = json.loads(await memory_mgr.export_json())
    assert len(data["memories"]) == 1
    assert data["memories"][0]["content"] == "新说法"


async def test_export_json_include_superseded(memory_mgr, tmp_db):
    """O3：include_superseded=True 时历史链可导出（此前只存于整库备份）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    data = json.loads(await memory_mgr.export_json(include_superseded=True))
    contents = {m["content"] for m in data["memories"]}
    assert {"旧说法", "新说法"} <= contents


async def test_backup_before_clear_includes_superseded(memory_mgr, tmp_db, tmp_path):
    """O3：清空前备份含 superseded 历史链（完整快照）。"""
    saved = await memory_mgr.add("冷冷的信息", "旧说法")
    await memory_mgr.update_content(saved["id"], "新说法")

    backup_path = await memory_mgr.backup_before_clear(backup_dir=tmp_path)

    data = json.loads(backup_path.read_text(encoding="utf-8"))
    contents = {m["content"] for m in data["memories"]}
    assert "旧说法" in contents  # superseded 历史行在备份里
