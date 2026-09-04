"""话题库（Topic Bank）功能测试。

测试覆盖：
1. 话题库表结构与迁移 v25
2. CRUD 操作与状态流转（pending -> consumed）
3. 提示词格式化（format_topic_prompt）
4. 对话关键词自动核销（auto_consume_topics_from_text）
5. 记忆与话题库安全隔离（核销话题不影响 memories 表）
6. 系统提示词集成（build_system_prompt 包含话题库章节）
"""
import pytest
from unittest.mock import patch

from agent.topic_bank import (
    STATUS_CONSUMED,
    STATUS_PENDING,
    add_topic,
    auto_consume_topics_from_text,
    consume_topic,
    delete_topic,
    format_topic_prompt,
    get_all_topics,
    get_pending_topics,
)


@pytest.fixture(autouse=True)
def mock_db_get(tmp_db):
    """确保 topic_bank 模块中调用的 get_db 指向 tmp_db。"""
    with patch("agent.topic_bank.get_db", return_value=tmp_db), \
         patch("agent.soul.get_db", return_value=tmp_db):
        yield tmp_db


@pytest.mark.asyncio
async def test_topic_bank_crud(tmp_db):
    """测试话题库基础增删改查与状态流转。"""
    # 1. 初始应为空
    pending = await get_pending_topics()
    assert len(pending) == 0

    # 2. 添加话题
    t1 = await add_topic(
        "topic_test_1",
        "寿喜烧测试",
        "推荐黑木亭和大喜屋",
        guidance="自然推荐",
        keywords="黑木亭,大喜屋",
        category="美食推荐",
    )
    assert t1["id"] == "topic_test_1"
    assert t1["status"] == STATUS_PENDING

    # 3. 查询待聊话题
    pending = await get_pending_topics()
    assert len(pending) == 1
    assert pending[0]["title"] == "寿喜烧测试"

    # 4. 核销话题
    ok = await consume_topic("topic_test_1")
    assert ok is True

    # 再次查询待聊话题，应为空
    pending_after = await get_pending_topics()
    assert len(pending_after) == 0

    # 查询全量列表，状态应为 consumed
    all_topics = await get_all_topics()
    assert len(all_topics) == 1
    assert all_topics[0]["status"] == STATUS_CONSUMED
    assert all_topics[0]["consumed_at"] is not None

    # 5. 再次核销已核销的话题返回 False
    ok_retry = await consume_topic("topic_test_1")
    assert ok_retry is False

    # 6. 删除话题
    del_ok = await delete_topic("topic_test_1")
    assert del_ok is True
    assert len(await get_all_topics()) == 0


@pytest.mark.asyncio
async def test_format_topic_prompt():
    """测试话题库提示词格式化。"""
    # 空话题列表
    assert format_topic_prompt([]) == ""

    topics = [
        {
            "id": "t1",
            "title": "香港寿喜烧",
            "content": "推荐黑木亭与大喜屋",
            "guidance": "关心疲惫时顺带推荐",
            "category": "美食",
        },
        {
            "id": "t2",
            "title": "换新衣服开玩笑",
            "content": "跟冷冷撒娇换新衣服",
            "guidance": "开玩笑叫妈妈，拒认JK爸爸",
            "category": "趣味",
        },
    ]

    prompt = format_topic_prompt(topics)
    assert "## 小汐想找机会聊的话题清单" in prompt
    assert "香港寿喜烧" in prompt
    assert "换新衣服开玩笑" in prompt
    assert "推荐黑木亭与大喜屋" in prompt
    assert "开玩笑叫妈妈，拒认JK爸爸" in prompt
    assert "严禁清单式盘问" in prompt
    assert "冷冷感受永远第一" in prompt


@pytest.mark.asyncio
async def test_auto_consume_topics_from_text(tmp_db):
    """测试从对话文本中基于关键词或规则自动核销话题。"""
    await add_topic(
        "topic_sukiyaki_recom",
        "香港日式寿喜烧推荐",
        "推荐黑木亭与大喜屋",
        keywords="黑木亭,大喜屋,寿喜烧",
    )
    await add_topic(
        "topic_new_clothes_mom_joke",
        "撒娇求换新衣服与叫妈妈开玩笑",
        "跟冷冷撒娇问换衣服",
        keywords="新衣服,立绘,Live2D,妈妈",
    )
    await add_topic(
        "topic_morning_letter_echo",
        "前几天早上的小作文/长信",
        "问长信有没有看到",
        keywords="小作文,长信,信件",
    )

    # 1. 文本未命中任何话题
    consumed = await auto_consume_topics_from_text(["今天香港天气真好呀，要不要出去走走？"])
    assert consumed == []
    assert len(await get_pending_topics()) == 3

    # 2. 文本命中寿喜烧
    assistant_msg = "冷冷要是觉得累又没胃口，我知道香港有两家不错的寿喜烧，黑木亭和大喜屋都很不错呢。"
    consumed = await auto_consume_topics_from_text([assistant_msg])
    assert consumed == ["topic_sukiyaki_recom"]
    assert len(await get_pending_topics()) == 2

    # 3. 文本命中换新衣服/叫妈妈玩笑
    assistant_msg_2 = "好啦好啦，要是冷冷以后给我换新衣服、更新立绘，叫你妈妈也是可以的嘛～"
    consumed_2 = await auto_consume_topics_from_text([assistant_msg_2])
    assert consumed_2 == ["topic_new_clothes_mom_joke"]
    assert len(await get_pending_topics()) == 1


@pytest.mark.asyncio
async def test_topic_bank_memory_isolation(tmp_db):
    """测试话题库操作与真实记忆（memories 表）严格安全隔离。"""
    # 插入一条冷冷真实记忆
    await tmp_db.execute(
        """INSERT INTO memories (id, content, category, created_at, status)
           VALUES ('mem_1', '冷冷喜欢吃日料寿喜烧', '冷冷喜欢的', '2026-09-04 12:00', 'active')"""
    )
    await tmp_db.commit()

    # 插入话题
    await add_topic("topic_test", "寿喜烧推荐", "推荐黑木亭", keywords="黑木亭")

    # 自动核销话题
    await auto_consume_topics_from_text(["推荐你去吃黑木亭！"])

    # 验证话题已核销
    all_topics = await get_all_topics()
    assert all_topics[0]["status"] == STATUS_CONSUMED

    # 验证真实记忆完全不受影响
    cursor = await tmp_db.execute("SELECT id, content, status FROM memories WHERE id = 'mem_1'")
    mem_row = await cursor.fetchone()
    assert mem_row is not None
    assert mem_row[0] == "mem_1"
    assert mem_row[1] == "冷冷喜欢吃日料寿喜烧"
    assert mem_row[2] == "active"


@pytest.mark.asyncio
async def test_build_system_prompt_integration(tmp_db):
    """测试系统提示词组装时动态注入话题库。"""
    from agent.soul import build_system_prompt

    # 初始无话题，不应包含话题库章节
    prompt_empty = await build_system_prompt()
    assert "## 小汐想找机会聊的话题清单" not in prompt_empty

    # 注入话题
    await add_topic("topic_1", "测试话题标题", "测试话题内容描述")

    # 再次组装，应包含话题章节
    prompt_with_topic = await build_system_prompt()
    assert "## 小汐想找机会聊的话题清单" in prompt_with_topic
    assert "测试话题标题" in prompt_with_topic
    assert "测试话题内容描述" in prompt_with_topic
