"""主动消息与对话记忆连贯性测试（Proactive Context Continuity）。

覆盖：
1. 会话 Checkpoint 自动水合（Auto-Hydration）：当 LangGraph Checkpoint 为空（脚本注入、重启、裁剪），
   自动从 SQLite chat_messages 档案表恢复历史，确保 LLM 能接上小汐主动说的话；
2. 跨会话主动消息感知：小汐近期主动发过消息，冷冷在其他窗口回“咋了”时，系统信息注入主动消息上下文；
3. append_proactive_turn 在 app=None（外部脚本/独立进程）时平滑降级并保证归档；
4. 全链路真实调用（Fake Chat Model 捕获输入）：验证冷冷回“咋了”时，LLM 实际收到的输入包含小汐的主动提问。
"""
import datetime
from unittest.mock import AsyncMock, patch

import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import MemorySaver

from agent.graph import build_agent, make_pre_model_hook
from api.routes.chat import (
    ChatRequest,
    _resolve_user_content,
    ensure_checkpoint_hydrated,
)
from era import append_proactive_turn
from memory import chat_archive
from utils.timezone import HKT, now_str


_captured_records = []


class RecordingChatModel(FakeListChatModel):
    """记录 LLM 实际接收到的输入消息序列。"""

    async def ainvoke(self, input, config=None, **kwargs):
        _captured_records.append(input)
        return await super().ainvoke(input, config=config, **kwargs)


@pytest.fixture
def recording_agent():
    """构建用于测试的真实 LangGraph Agent（内存 checkpointer + 记录型 model）。"""
    _captured_records.clear()
    model = RecordingChatModel(responses=["收到啦"])
    checkpointer = MemorySaver()
    pre_hook = make_pre_model_hook(None)
    agent = build_agent(
        model=model,
        tools=[],
        system_prompt="你叫小汐。",
        checkpointer=checkpointer,
        pre_model_hook=pre_hook,
    )
    return agent, model, checkpointer


async def test_ensure_checkpoint_hydrated_from_archive(tmp_db, recording_agent, monkeypatch):
    """当 checkpointer 为空时，从 chat_messages 档案表自动水合消息。"""
    agent, model, _ = recording_agent
    sid = "e-hydrate-test-1"

    async def fake_get_db():
        return tmp_db

    monkeypatch.setattr(chat_archive, "get_db", fake_get_db)

    # 1. 档案表中写入一条主动消息
    await chat_archive.record_solo_message(sid, "在吗在吗？冷冷，你知道 JK 怎么了吗？", is_era=1)

    # 2. 此时 checkpointer 中该 thread 还是空的
    state_before = await agent.aget_state({"configurable": {"thread_id": sid}})
    assert len(state_before.values.get("messages", [])) == 0

    # 3. 执行水合
    await ensure_checkpoint_hydrated(agent, sid)

    # 4. 验证 checkpointer 中已存在该条 AIMessage
    state_after = await agent.aget_state({"configurable": {"thread_id": sid}})
    msgs = state_after.values.get("messages", [])
    assert len(msgs) == 1
    assert isinstance(msgs[0], AIMessage)
    assert "你知道 JK 怎么了吗" in msgs[0].content

    # 5. 冷冷回复“咋了”，验证 LLM 接收到的消息序列同时包含小汐提问和冷冷回复
    user_input = await _resolve_user_content("咋了", ChatRequest(message="咋了", session_id=sid), sid)
    await agent.ainvoke(
        {"messages": [HumanMessage(content=user_input)]},
        {"configurable": {"thread_id": sid}},
    )

    assert len(_captured_records) == 1
    received_msgs = _captured_records[0]
    types = [m.type for m in received_msgs]
    # 消息中应包含 system prompt, ai 历史主动提问, 以及 human 的"咋了"
    assert "ai" in types
    assert "human" in types

    ai_msg = next(m for m in received_msgs if m.type == "ai")
    human_msg = next(m for m in received_msgs if m.type == "human")
    assert "你知道 JK 怎么了吗" in ai_msg.content
    assert "咋了" in human_msg.content


async def test_cross_session_recent_proactive_awareness(tmp_db, monkeypatch):
    """跨会话场景：小汐刚发过主动消息，冷冷在不同 session 回“咋了”，系统信息块注入该主动消息。"""
    async def fake_get_db():
        return tmp_db

    import api.routes.chat as chat_mod
    monkeypatch.setattr(chat_mod, "get_db", fake_get_db)

    now = datetime.datetime.now(HKT).strftime("%Y-%m-%d %H:%M")
    proactive_sid = "e-proactive-window"
    current_sid = "s-user-reply-window"

    # 插入一条最近的主动消息（5 分钟前发送）
    await tmp_db.execute(
        """INSERT INTO proactive_messages (id, content, created_at, status, session_id)
           VALUES ('msg_test_care', '在吗在吗？冷冷，你知道 JK 怎么了吗？', ?, 'delivered', ?)""",
        (now, proactive_sid),
    )
    await tmp_db.commit()

    # 冷冷在 current_sid 回“咋了”
    req = ChatRequest(message="咋了", session_id=current_sid)
    resolved = await _resolve_user_content("咋了", req, current_sid)

    assert "系统信息：" in resolved
    assert "你知道 JK 怎么了吗" in resolved
    assert "主动向冷冷发过消息" in resolved or "主动给冷冷发过消息" in resolved


async def test_append_proactive_turn_with_none_app(tmp_db, monkeypatch):
    """当 app 为 None（如 CLI 脚本执行）时，不报错且完整落库 chat_messages 与 proactive_messages。"""
    async def fake_get_db():
        return tmp_db

    import heartbeat
    monkeypatch.setattr(heartbeat, "get_db", fake_get_db)
    monkeypatch.setattr(chat_archive, "get_db", fake_get_db)

    # 模拟推送成功
    monkeypatch.setattr("utils.web_push.send_push", AsyncMock(return_value=1))

    sid = "e-script-inject-1"
    content = "昨晚他在弄技术维护的时候弄到好晚……你们是不是闹别扭啦？"

    # app=None 应该正常执行，不抛异常
    await append_proactive_turn(None, sid, content, kind="new_era", label="定制主动消息")

    # 验证档案与主动消息记录
    msgs = await chat_archive.get_messages(sid)
    assert len(msgs) == 1
    assert msgs[0]["role"] == "assistant"
    assert msgs[0]["msg_type"] == "solo"
    assert "弄到好晚" in msgs[0]["content"]

    cursor = await tmp_db.execute("SELECT content, status FROM proactive_messages WHERE session_id = ?", (sid,))
    row = await cursor.fetchone()
    assert row is not None
    assert "弄到好晚" in row[0]
    assert row[1] == "delivered"
