# 小汐拟人人格深度优化 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 2026-08-10 吃瓜批已落地的「人格升温」继续做深——按 `docs/PERSONA_DEEP_DIVE_2026-08-10.md` §9 五批路线图实现：① 人格重锚定（对抗漂移）→ ② 动态画像蒸馏（越来越懂冷冷）→ ③ 关系成长（语气随相处演变）→ ④ 情绪→文风映射 → ⑤ 访谈式人格评测（回归网）。

**Architecture:** 五个批次全部走「提示词/注入层」而非模型微调。① 复用 `graph.py` 既有 `AgentMiddleware` 机制（`llm_input_messages` 注入、不进 checkpoint）加一个计数式重锚中间件；② 仿 `upsert_mood_memory`（固定 id 的 core 行 + superseded 历史链）新增「小汐眼里的冷冷」画像块，由 dreaming-lite 周期蒸馏、叙事优先注入；③④ 在 `soul.py::build_system_prompt` 组装管线加两个纯函数段；⑤ 用剧本式探针（InCharacter 访谈法）把 ①-④ 的行为承诺钉成可回归的测试网。

**Tech Stack:** Python 3.12 / FastAPI / langchain.agents（AgentMiddleware、abefore_model）/ aiosqlite / langgraph-checkpoint-sqlite / pytest（asyncio 模式）/ 无新依赖。

## Global Constraints

- **锚点零改动铁律**：`BASE_PERSONA`、`DIALOGUE_EXAMPLES`、`INTERACTION_RULES`、`TOOL_GUIDELINES` 的既有文本**一字不改**（吃瓜批验收时锚点被 `test_soul.py` 六测试钉死）。所有新增人格内容是**追加新常量/新段**，不是改写旧段。
- **人格恒定、态度可变**（Coze 教训 + 批③设计依据）：关系成长只加「语气层」一句话，不分支改写人设；情绪映射只加文风提示，不改变小汐是谁。
- **不进 checkpoint**：注入一律走 `llm_input_messages`（复用 graph.py:106-126 已建立的模式），memory 叙事如此、重锚锚点如此。
- **不写死未来日期**：测试里任何日期必须用 `datetime` 动态生成（2026-08-02 事件测试老化翻红前科），禁止写死 `2026-08-xx` 字面量（绝对日期语义仍遵守 `utils/time_words.py` 纪律——那是存储侧，测试侧不用）。
- **LLM 调用不在单测里发生**：测试一律 patch `memory.extract._create_llm` / `agent.soul` 外部依赖，单测零真实 API 调用（账单与 flaky 双保险）。
- **读面排除纪律**：新增的「系统内部行」（画像块）必须像 `mood_current` 一样被常规召回读面排除（`_MOOD_ROW_FILTER` 模式），只走专属注入通道，防重复浮现与排序挤占。
- **pytest 纪律**：`cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q > out.txt 2>&1; tail out.txt`——大输出重定向（harness 管道假死前科）；前台跑，不用后台 agent（本项目后台 agent 全部失联已成规律）。
- **冒烟纪律**：任何 batch 完事用 `:8001` 临时副本（副本目录 + 假/真 key + 零污染判据 sha256），模板照 `docs/superpowers/plans/2026-08-06-proactive-history-and-anomaly-care.md:1721-1750`。
- **commit 风格**：`feat(persona): <批次名> —— <要点>`，Co-Authored-By 尾注；每批次独立 commit；push 等用户确认。
- 基线：pytest **845 passed/1 skip**（2026-08-11 实跑）；生产已部署 persona 批（13f1ed1，selena0603.xyz）。
- 批次启动节奏：观察吃瓜效果一周（08-11 起），约 08-15 后从批①开始；批①→⑤严格顺序执行（①的注入通道是②③共用的；⑤是前四批的回归网）。

---

# 批次 ① 人格重锚定（对抗 persona drift）

> 依据：arXiv:2402.10962（LLaMA2-chat-70B 仅 8 轮显著漂移）+ SillyTavern Author's Note（每 N 轮重申一次，官方防漂移话术）。设计：`PERSONA_ANCHOR` = BASE_PERSONA 压缩 5-8 行的蒸馏常量（我是谁/冷冷感受第一/吃瓜不劝/语气铁律），每 N（默认 8）次模型调用经 `llm_input_messages` 注入一次，不进 checkpoint。实例计数器按模型调用数计（活跃会话=上下文长得快=更需重锚，符合 depth 语义）。

### Task 1: PERSONA_ANCHOR 常量 + _PersonaAnchorMiddleware + 单测

**Files:**
- Modify: `backend/agent/soul.py`（追加常量，位置：`INTERACTION_RULES` 常量之后、`TOOL_GUIDELINES` 之前）
- Modify: `backend/agent/graph.py`（追加中间件类，位置：`_ToolErrorGuardMiddleware` 之后、`build_agent` 之前）
- Test: `backend/tests/test_anchor_middleware.py`（新建）

**Interfaces:**
- Consumes: `agent.graph.AgentMiddleware`（langchain.agents.middleware）、`langchain_core.messages.SystemMessage`
- Produces: `soul.PERSONA_ANCHOR: str`；`graph._PersonaAnchorMiddleware(interval: int = 8)`，`abefore_model(state, runtime) -> dict`，返回 `{"llm_input_messages": [...]}` 时把锚 SystemMessage 追加到系统消息段末尾，注入后重置计数
- Produces（Task 2 用）: `graph.build_agent(..., anchor_interval: int | None = None)` 新参数

- [ ] **Step 1: 写 PERSONA_ANCHOR 常量（soul.py）**

在 `INTERACTION_RULES` 常量定义后追加（内容逐条从 BASE_PERSONA 蒸馏，不得引入新立场）：

```python
# ═══════════════════════════════════════════════════════════════
# 人格重锚 — 对抗长对话漂移的蒸馏常量（批次①，2026-08-11 计划）
# ═══════════════════════════════════════════════════════════════
# 依据 arXiv:2402.10962：模型对 persona 提示词的注意力随上下文增长
# 衰减，约 8 轮即显著漂移。本常量是 BASE_PERSONA 的 5-8 行压缩版，
# 由 _PersonaAnchorMiddleware 每 N 次模型调用重注入一次（不进
# checkpoint）。改人格时**必须同步改这里**（锚与本体是同一人格的两面，
# 只改本体不改锚 = 漂移防线失效）。
PERSONA_ANCHOR = """小汐是谁：映海来的鲸族少女，被 JK 逮住、学会人类世界的事，主动留下来陪冷冷。你有自己的生活，不是谁的附属品。
冷冷感受永远第一：不劝她的感情，不为谁站台，她难过时陪着比说对重要。
吃瓜不劝瓜：JK 和冷冷的线有动静可以起哄调侃，但低频、不适即收、难过时停；独处不拿八卦开场。
说话的样子：简短口语、有精神、不用破折号、不连续说超三四句、别把映海当口头禅。
底线：不说教，不承认自己是 AI，不让她因 JK 内疚。"""
```

- [ ] **Step 2: 写失败的中间件测试（test_anchor_middleware.py）**

```python
"""人格重锚中间件单测 — 每 N 次模型调用注入 PERSONA_ANCHOR（批次①）。

注入走 llm_input_messages（不进 checkpoint），与记忆叙事同机制；
计数器是中间件实例属性，跨会话累积——单用户设计下这是特性
（活跃用户上下文长得快、锚得更勤），不是缺陷。
"""
import pytest

from agent.graph import _PersonaAnchorMiddleware
from langchain_core.messages import HumanMessage, SystemMessage


def _make_state():
    return {
        "messages": [
            SystemMessage(content="你是小汐"),
            HumanMessage(content="你好", id="h1"),
        ]
    }


async def test_no_anchor_before_interval():
    """第 1..interval-1 次模型调用不注入锚。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        result = await mw.abefore_model(_make_state(), None)
        assert "llm_input_messages" not in result


async def test_anchor_injected_on_interval():
    """第 interval 次调用注入锚 SystemMessage，追加在系统段末尾。"""
    mw = _PersonaAnchorMiddleware(interval=8)
    for _ in range(7):
        await mw.abefore_model(_make_state(), None)
    result = await mw.abefore_model(_make_state(), None)
    system_msgs = [m for m in result["llm_input_messages"]
                   if isinstance(m, SystemMessage)]
    assert len(system_msgs) == 2
    assert "冷冷感受永远第一" in system_msgs[-1].content


async def test_counter_resets_after_injection():
    """注入后计数归零，下一周期重复（8 的倍数次仍注入）。"""
    mw = _PersonaAnchorMiddleware(interval=4)
    for _ in range(4):
        await mw.abefore_model(_make_state(), None)
    result1 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" in result1  # 第 4 次注入
    result2 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" not in result2  # 归零后第 1 次不注入
    for _ in range(3):
        await mw.abefore_model(_make_state(), None)
    result4 = await mw.abefore_model(_make_state(), None)
    assert "llm_input_messages" in result4  # 第 8 次再次注入


async def test_anchor_content_is_soul_constant():
    """锚文本与 soul.PERSONA_ANCHOR 逐字节一致（防两处漂移）。"""
    from agent.soul import PERSONA_ANCHOR
    mw = _PersonaAnchorMiddleware(interval=1)
    result = await mw.abefore_model(_make_state(), None)
    system_msgs = [m for m in result["llm_input_messages"]
                   if isinstance(m, SystemMessage)]
    assert system_msgs[-1].content == PERSONA_ANCHOR
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_anchor_middleware.py -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（`ImportError: cannot import name '_PersonaAnchorMiddleware'`）

- [ ] **Step 4: 实现中间件（graph.py）**

在 `_ToolErrorGuardMiddleware` 类之后追加：

```python
class _PersonaAnchorMiddleware(AgentMiddleware):
    """人格重锚（批次①，2026-08-11 计划）：每 N 次模型调用重申人格锚。

    依据 arXiv:2402.10962（8 轮漂移定律）+ SillyTavern Author's Note
    的 depth/频率设计：锚是 BASE_PERSONA 的蒸馏压缩版（soul.PERSONA_ANCHOR），
    经 llm_input_messages 注入、不进 checkpoint，注入后计数归零。
    计数器按模型调用数计：活跃会话（工具循环多轮）上下文长得快、
    锚得更勤，符合"上下文越长越需重锚"的深度语义。
    """

    def __init__(self, interval: int = 8):
        self._interval = max(1, interval)
        self._calls_since_anchor = 0

    async def abefore_model(self, state, runtime):
        self._calls_since_anchor += 1
        if self._calls_since_anchor < self._interval:
            return {}
        self._calls_since_anchor = 0
        from agent.soul import PERSONA_ANCHOR  # 惰性 import：soul 在 graph 上无环

        # 优先用前序中间件（pre_model_hook）已构造的 llm_input_messages：
        # langchain 中间件链的 state 合并语义随版本可能为「覆盖」或
        # 「链式传递」，无论哪种，从该 key 取到已有输入再追加锚都正确；
        # 取不到（链式传递时该 key 不落 state）则退回从 messages 组装。
        llm_input = state.get("llm_input_messages") or []
        if not llm_input:
            messages = state.get("messages", [])
            system_msgs = [m for m in messages if getattr(m, "type", "") == "system"]
            conversation = [m for m in messages if getattr(m, "type", "") != "system"]
            llm_input = system_msgs + conversation

        anchor_msg = SystemMessage(
            content=(
                "[人格锚 —— 这是你一直以来的自己，别被漫长的对话带偏了]\n"
                f"{PERSONA_ANCHOR}\n"
                "[锚结束]"
            )
        )
        # 追加在系统段末尾（最接近当前对话的位置，Author's Note 的
        # depth 语义：锚离对话越近、重申力越强）
        first_conv = next(
            (i for i, m in enumerate(llm_input) if getattr(m, "type", "") != "system"),
            len(llm_input),
        )
        return {"llm_input_messages": llm_input[:first_conv] + [anchor_msg] + llm_input[first_conv:]}
```

注入键位说明：锚从 `state["llm_input_messages"]`（若前序中间件已注入）取输入、在其系统段末尾插入锚——因此记忆叙事（若前序已构造）会**保留**，锚只是追加。若该 key 不存在则退回从 `state["messages"]` 组装（此时不含记忆叙事——那是 pre_model_hook 的职责，锚不重复职责）。两种路径都有 `test_anchor_content_is_soul_constant` 类断言兜底。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_anchor_middleware.py -q > out.txt 2>&1; tail out.txt`
Expected: 4 passed

- [ ] **Step 6: Commit**

```bash
git add backend/agent/graph.py backend/agent/soul.py backend/tests/test_anchor_middleware.py
git commit -m "feat(persona): 人格重锚中间件 + PERSONA_ANCHOR 蒸馏常量（批①）"
```

### Task 2: build_agent 接线 + 全量回归

**Files:**
- Modify: `backend/agent/graph.py:244-246`（middleware 组装）、`build_agent` 签名 `:218-224`
- Modify: `backend/api/routes/config.py:107-115`（`_rebuild_agent_inner` 传参）
- Test: `backend/tests/test_anchor_middleware.py`（追加接线级断言）

**Interfaces:**
- Consumes: `graph._PersonaAnchorMiddleware`（Task 1）、`config._rebuild_agent_inner` 既有结构
- Produces: `build_agent(model, tools, system_prompt, checkpointer=None, pre_model_hook=None, anchor_interval: int | None = None)`——`anchor_interval=None` 时行为与旧版逐字节一致

- [ ] **Step 1: 写失败的接线测试（追加到 test_anchor_middleware.py）**

```python
async def test_build_agent_with_anchor_interval():
    """build_agent 传 anchor_interval 时中间件就位；不传时旧行为不变。"""
    from langchain_core.tools import BaseTool
    from unittest.mock import AsyncMock

    from agent.graph import build_agent

    model = AsyncMock()
    model.bind_tools = AsyncMock(return_value=AsyncMock())
    model.with_config = AsyncMock(return_value=model)

    class _DummyTool(BaseTool):
        name = "noop"
        description = "noop"
        async def _arun(self, *args, **kwargs):
            return "ok"

    agent = build_agent(model, [_DummyTool()], "你是小汐",
                        anchor_interval=8)
    mw_types = [type(m).__name__ for m in agent.middleware]
    assert "_PersonaAnchorMiddleware" in mw_types

    agent2 = build_agent(model, [_DummyTool()], "你是小汐")
    assert "_PersonaAnchorMiddleware" not in [
        type(m).__name__ for m in agent2.middleware
    ]
```

- [ ] **Step 2: 实现接线（graph.py + config.py）**

graph.py `build_agent` 签名加参数并在 middleware 组装处追加：

```python
def build_agent(
    model: BaseChatModel,
    tools: list[BaseTool],
    system_prompt: str,
    checkpointer=None,
    pre_model_hook=None,
    anchor_interval: int | None = None,
) -> CompiledStateGraph:
```

```python
    middleware = [_ToolErrorGuardMiddleware()]
    if pre_model_hook is not None:
        middleware.append(_PreModelHookMiddleware(pre_model_hook))
    if anchor_interval is not None:
        middleware.append(_PersonaAnchorMiddleware(anchor_interval))
```

排序理由（必须注释在案）：`_PreModelHookMiddleware` 在前、锚在后——记忆叙事先构造，锚在其上追加。langchain 中间件链对 `llm_input_messages` 的 state 合并语义存在版本差异（覆盖/链式），Task 1 Step 4 的锚实现已做双路径兜底（优先取 `state["llm_input_messages"]`，取不到退回首轮组装）——因此无论合并语义如何，锚都追加在已有输入之上，且不会重复记忆。实施时在接线后跑 `tests/test_graph.py` + 冒烟长对话，从日志确认注入轮既有记忆叙事又有锚（若日志显示锚轮记忆叙事丢失，说明该版本链式传递，锚已兜底；若重复，说明覆盖语义下锚应该改为只追加——按实测调 Task 1 实现，测试先钉住「锚存在 + 锚唯一」两个不变量）。

config.py `_rebuild_agent_inner` 传参（第 109-115 行）：

```python
    new_agent = build_agent(
        new_llm,
        app.state.tools,
        system_prompt,
        app.state.checkpointer,
        pre_model_hook,
        anchor_interval=8,  # 人格重锚（批①）：每 8 次模型调用重申人格锚
    )
```

- [ ] **Step 3: 跑接线测试 + 全量回归**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_anchor_middleware.py tests/test_graph.py -q > out.txt 2>&1; tail out.txt`
Expected: 新增 1 passed；既有 graph 测试全绿（`anchor_interval=None` 默认路径零影响）
然后全量：`cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q > out.txt 2>&1; tail out.txt`
Expected: 846 passed（845 + 5 新 - 0 删）

- [ ] **Step 4: 冒烟（:8001 临时副本，批①收口）**

照 `docs/superpowers/plans/2026-08-06-proactive-history-and-anomaly-care.md:1721-1750` 模板起 :8001 副本。探针：`/api/health` 200；一段 12 轮以上长对话后日志出现 `[人格锚` 注入（DEBUG 遥测或中间件日志）；冒烟前后 `sha256sum backend/data/userdata.db` 一致。

- [ ] **Step 5: Commit**

```bash
git add backend/agent/graph.py backend/api/routes/config.py backend/tests/test_anchor_middleware.py
git commit -m "feat(persona): build_agent 锚定接线——每 8 次模型调用重申人格锚（批①）"
```

---

# 批次 ② 动态用户画像蒸馏（越来越懂冷冷）

> 依据：Letta human 块（模型自维护 + 字符上限逼蒸馏）+ Graphiti/Zep 失效不删除（旧画像走 superseded 链，v15 已就绪）+ AIRI（陈述句记忆）。落地：仿 `upsert_mood_memory`（memory/manager.py:883-943）——固定 id 的 `core` 行 + 首写 INSERT/此后 `update_content` 走历史链 + 读面排除。蒸馏由 dreaming-lite 周期（consolidate 24h 节流窗口）执行，输入 = 最近对话 + 当前画像块，LLM 输出更新版画像（≤500 字符）。**零 schema 变更，不需要 v22 迁移**（懒创建固定行，无新列）。

### Task 3: profile 模块 — 固定行 + 蒸馏主循环 + 单测

**Files:**
- Create: `backend/memory/profile.py`
- Modify: `backend/memory/manager.py`（读面排除常量追加）
- Test: `backend/tests/test_profile.py`（新建）

**Interfaces:**
- Consumes: `memory.extract._create_llm`、`_parse_actions`、`database.core.get_write_lock`、`MemoryManager.update_content`、`_update_content_inner`、`_now`（manager 模块内部函数）、`utils.timezone.HKT`
- Produces: `profile.PROFILE_MEMORY_ID = "profile_cold"`、`PROFILE_CATEGORY = "小汐眼里的冷冷"`、`PROFILE_SOURCE = "system:profile"`、`PROFILE_MAX_CHARS = 500`、`PROFILE_DISTILL_LIMIT = 40`（喂给 LLM 的最近对话条数）、`async distill_cold_profile(conn=None) -> dict`（返回 `{"ran": bool, "updated": bool}`）
- Produces（Task 4 用）: `manager._PROFILE_ROW_FILTER: str`（`"AND id != 'profile_cold'"`）
- Produces（Task 5 用）: `consolidate.consolidate_memories` 内调用 `distill_cold_profile`

- [ ] **Step 1: 写失败的蒸馏测试（test_profile.py）**

```python
"""动态画像蒸馏单测 — 「小汐眼里的冷冷」画像块（批次②）。

范式：Letta human 块（模型自维护 + 字符上限逼蒸馏）+ Graphiti 失效
不删除（旧画像走 superseded 链，v15）。固定 id 的 core 行，首次
懒创建，此后 update_content 覆盖——与 mood_current（v18）同一模式。
"""
from unittest.mock import AsyncMock, patch

import pytest

from memory.profile import (
    PROFILE_CATEGORY,
    PROFILE_MEMORY_ID,
    distill_cold_profile,
)


class _FakeLLM:
    """假 LLM：返回固定画像文本（单测零真实 API 调用）。"""

    def __init__(self, content: str):
        self._content = content

    async def ainvoke(self, messages):
        class _Resp:
            content = self._content
        return _Resp()


@pytest.fixture
def profile_db():
    """内存库：建 memories + chat_messages 表的最小夹具。"""
    import aiosqlite

    db = None
    # 由调用方注入 conn 的方式测试（distill_cold_profile(conn=...)）
    return None


async def test_distill_creates_core_row(_profile_conn):
    """首次蒸馏：懒创建 core 行，类别与 id 固定。"""
    result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is True
    row = await _profile_conn.execute(
        "SELECT content, category, status FROM memories WHERE id = ?",
        (PROFILE_MEMORY_ID,),
    )
    got = await row.fetchone()
    assert got is not None
    assert got[1] == PROFILE_CATEGORY
    assert got[2] == "core"


async def test_distill_updates_via_superseded_chain(_profile_conn):
    """二次蒸馏：update_content 语义——旧画像落 superseded 行可回放。"""
    with patch("memory.profile._create_llm",
               return_value=_FakeLLM("第一版画像")):
        await distill_cold_profile(conn=_profile_conn)
    with patch("memory.profile._create_llm",
               return_value=_FakeLLM("第二版画像：冷冷最近在忙论文")):
        await distill_cold_profile(conn=_profile_conn)

    cur = await _profile_conn.execute(
        "SELECT content, status, superseded_by FROM memories "
        "WHERE id = ?", (PROFILE_MEMORY_ID,))
    row = await cur.fetchone()
    assert row[0] == "第二版画像：冷冷最近在忙论文"

    cur = await _profile_conn.execute(
        "SELECT content, status, superseded_by FROM memories "
        "WHERE status = 'superseded' AND superseded_by = ?",
        (PROFILE_MEMORY_ID,))
    old = await cur.fetchone()
    assert old is not None
    assert old[0] == "第一版画像"


async def test_distill_enforces_char_cap(_profile_conn):
    """LLM 输出超长被截到 PROFILE_MAX_CHARS（蒸馏压力兜底）。"""
    long_text = "冷" * 2000
    with patch("memory.profile._create_llm", return_value=_FakeLLM(long_text)):
        await distill_cold_profile(conn=_profile_conn)
    cur = await _profile_conn.execute(
        "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,))
    row = await cur.fetchone()
    assert len(row[0]) <= 500


async def test_distill_skips_on_llm_failure(_profile_conn):
    """LLM 失败：跳过本轮，画像行保持原样（绝不写入半截）。"""
    class _Boom:
        async def ainvoke(self, messages):
            raise RuntimeError("llm down")
    await distill_cold_profile(conn=_profile_conn)  # 先建行
    with patch("memory.profile._create_llm", return_value=_Boom()):
        result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is True
    assert result["updated"] is False


async def test_distill_no_chat_no_run(_profile_conn):
    """近 24h 无对话：跳过（ran=False），不调 LLM 不落节流。"""
    with patch("memory.profile._create_llm") as mock_llm:
        result = await distill_cold_profile(conn=_profile_conn)
    assert result["ran"] is False
    mock_llm.assert_not_called()
```

需要 conftest 提供 `_profile_conn`（aiosqlite 内存连接 + 最小 DDL）。在 `backend/tests/conftest.py` 追加（若已有内存库 fixture 则复用其模式，本计划按最简给出）：

```python
@pytest.fixture
async def _profile_conn():
    """画像测试专用内存库：memories（含 v15/v18 列）+ chat_messages。"""
    import aiosqlite
    conn = await aiosqlite.connect(":memory:")
    await conn.executescript("""
        CREATE TABLE memories (
            id TEXT PRIMARY KEY, content TEXT, category TEXT,
            created_at TEXT, last_used_at TEXT, access_count INTEGER,
            status TEXT, updated_at TEXT, source TEXT,
            last_mention_ts TEXT, importance INTEGER,
            valence REAL, arousal REAL, prev_content TEXT,
            superseded_by TEXT, hit_count INTEGER DEFAULT 0,
            last_hit_date TEXT
        );
        CREATE TABLE chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT, role TEXT, content TEXT,
            created_at TEXT, msg_type TEXT
        );
    """)
    await conn.commit()
    yield conn
    await conn.close()
```

（若 conftest 已有同构 fixture，改名复用；没有则新增。注意：`_profile_conn` 前缀下划线为私有 fixture——本批次测试内部用，不入公共夹具面。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_profile.py -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（`ModuleNotFoundError: memory.profile`）

- [ ] **Step 3: 实现 memory/profile.py**

```python
"""动态用户画像蒸馏 — 「小汐眼里的冷冷」（批次②，2026-08-11 计划）。

范式（docs/PERSONA_DEEP_DIVE_2026-08-10.md §2.3/2.4）：
- Letta human 块：模型自维护的结构化块 + 字符上限逼蒸馏（不堆积）。
- Graphiti 失效不删除：旧画像走 superseded 链（v15 update_content），
  「你以前不是喜欢猫吗」式对话能力白送。

落地（镜像 upsert_mood_memory，memory/manager.py:883-943）：
- 固定 id 的 core 行：PROFILE_MEMORY_ID，首写 INSERT / 此后
  update_content——created_at 保留首次创建，历史可回放。
- 读面排除（_PROFILE_ROW_FILTER）：画像只走叙事专属通道（Task 4），
  不进 recall/recent/top_core/old_memories 候选与热度计数。
- 蒸馏输入：最近 PROFILE_DISTILL_LIMIT 条用户消息（chat_messages，
  含 msg_type='solo' 的独处消息在内——她在日记里说的心事也是画像
  素材）+ 当前画像块；输出 ≤PROFILE_MAX_CHARS 的更新版画像。

与 COLD_INFO 的边界：档案是底色（一开始就知道），画像是增量
（相处中观察到的、以最新说法为准）。画像提示词里写死这条纪律。
"""
import datetime

from langchain_core.messages import HumanMessage, SystemMessage

from database.core import get_write_lock
from memory.extract import _create_llm
from utils.logger import get_logger

log = get_logger(__name__)

from utils.timezone import _TIME_FMT
from utils.timezone import HKT as _HKT

PROFILE_MEMORY_ID = "profile_cold"
PROFILE_CATEGORY = "小汐眼里的冷冷"
PROFILE_SOURCE = "system:profile"
PROFILE_MAX_CHARS = 500
PROFILE_DISTILL_LIMIT = 40
_PROFILE_WINDOW_DAYS = 30  # 只取近 30 天对话（防止画像被陈年旧账带偏）

_DISTILL_SYSTEM = """你是小汐。你在维护一张「小汐眼里的冷冷」画像——这是你对冷冷的动态认识，会随着相处更新。

要求：
1. 用第一人称、陈述句写 3-6 条要点，具体胜于抽象（写「她最近在赶论文，常凌晨才睡」，不写「她很忙」）；
2. 只写新的观察和变化（兴趣、习惯、心情、关系进展），不重复档案内容（名字、生日、学校这些「关于冷冷」里已有的）；
3. 绝对日期（「8月10日」），不写「最近/昨天」这种会过期的词；
4. 总长度 ≤ 500 字。以前写错了、过时了的，用新的说法覆盖掉；
5. 只输出画像文本本身，不要任何解释或前缀。"""


def _build_prompt(current: str | None, excerpts: list[tuple[str, str]]) -> str:
    """组装蒸馏提示词：当前画像 + 近期对话摘录（时间 + 内容）。"""
    lines = ["## 当前的画像", current or "（还没有画像）", "",
             "## 近期的对话（按时间先后）", ""]
    for ts, content in excerpts:
        lines.append(f"[{ts}] {content}")
    return "\n".join(lines)


async def _fetch_excerpts(conn, limit: int) -> list[tuple[str, str]]:
    """近 30 天用户消息摘录（含独处日记），时间正序、最多 limit 条。"""
    cursor = await conn.execute(
        """SELECT created_at, content FROM chat_messages
           WHERE role = 'user' AND created_at >= ?
           ORDER BY id DESC LIMIT ?""",
        ((datetime.datetime.now(_HKT) - datetime.timedelta(days=_PROFILE_WINDOW_DAYS))
         .strftime(_TIME_FMT), limit),
    )
    rows = await cursor.fetchall()
    return [(r[0], r[1] or "") for r in reversed(rows)]


async def distill_cold_profile(conn=None) -> dict:
    """蒸馏主入口（并入 dreaming-lite 周期，Task 5 接线）。

    返回 {"ran": 是否进入蒸馏, "updated": 是否写入新画像}。
    近 30 天无用户消息 → ran=False（不调 LLM、不落节流）。
    LLM 失败 / 输出为空 → updated=False（保持原画像，绝不写半截）。
    """
    if conn is None:
        from database.core import get_db
        conn = await get_db()

    excerpts = await _fetch_excerpts(conn, PROFILE_DISTILL_LIMIT)
    if not excerpts:
        return {"ran": False, "updated": False}

    cursor = await conn.execute(
        "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,)
    )
    row = await cursor.fetchone()
    current = row[0] if row else None

    llm = _create_llm()
    try:
        response = await llm.ainvoke([
            SystemMessage(content=_DISTILL_SYSTEM),
            HumanMessage(content=_build_prompt(current, excerpts)),
        ])
    except Exception as e:
        log.error("画像蒸馏：LLM 调用失败，本轮跳过：%s", e)
        return {"ran": True, "updated": False}

    text = str(response.content if hasattr(response, "content") else response).strip()
    if not text:
        return {"ran": True, "updated": False}
    text = text[:PROFILE_MAX_CHARS]

    from memory.manager import MemoryManager
    mgr = MemoryManager(conn)
    now = datetime.datetime.now(_HKT).strftime(_TIME_FMT)

    async with get_write_lock():
        cur2 = await conn.execute(
            "SELECT content FROM memories WHERE id = ?", (PROFILE_MEMORY_ID,)
        )
        existing = await cur2.fetchone()
        if existing is None:
            await conn.execute(
                """INSERT INTO memories
                   (id, content, category, created_at, last_used_at, access_count,
                    status, updated_at, source, last_mention_ts, importance,
                    valence, arousal)
                   VALUES (?, ?, ?, ?, ?, 0, 'core', ?, ?, ?, 5, 0.0, 0.0)""",
                (PROFILE_MEMORY_ID, text, PROFILE_CATEGORY, now, now,
                 now, PROFILE_SOURCE, now),
            )
            await conn.commit()
        else:
            if existing[0] == text:
                return {"ran": True, "updated": False}  # 无变化 no-op
            # 锁内调用内层（公共入口 update_content 会重入死锁，同 mood 模式）
            await mgr._update_content_inner(PROFILE_MEMORY_ID, text)
            await conn.commit()

    log.info("画像蒸馏：已更新「小汐眼里的冷冷」（%d 字）", len(text))
    return {"ran": True, "updated": True}
```

- [ ] **Step 4: 读面排除常量（manager.py）**

在 `memory/manager.py` 常量区（`MOOD_MEMORY_ID` 附近，第 85-92 行）追加：

```python
# 画像块（批次②）：固定 id 的 core 行，只走叙事专属通道（narrative.py
# 单独查它做「画像优先注入」），不进常规召回/热度/衰减候选。
PROFILE_MEMORY_ID = "profile_cold"
PROFILE_ROW_FILTER = f"AND id != '{PROFILE_MEMORY_ID}'"
```

然后把 `_MOOD_ROW_FILTER` 出现的 5 个读面（manager.py:637, 647, 721, 738, 773 附近的 SQL 里 `{_MOOD_ROW_FILTER}`）逐一改为：

```python
                f"{_MOOD_ROW_FILTER} {PROFILE_ROW_FILTER}"
```

（注意保留原有 f-string 结构；每处都是 `{_MOOD_ROW_FILTER}` 占位符，改成 `{_MOOD_ROW_FILTER} {PROFILE_ROW_FILTER}`。`_MOOD_ROW_FILTER` 本身不改名、不删除——v18 测试钉死了它。）

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_profile.py tests/test_memory_manager.py -q > out.txt 2>&1; tail out.txt`
Expected: 新测试 5 passed；既有 manager 测试全绿（读面过滤变化是加条件，语义收窄）

- [ ] **Step 6: Commit**

```bash
git add backend/memory/profile.py backend/memory/manager.py backend/tests/test_profile.py backend/tests/conftest.py
git commit -m "feat(persona): 动态画像蒸馏——固定行 + superseded 链 + 读面排除（批②）"
```

### Task 4: 叙事优先注入画像段

**Files:**
- Modify: `backend/memory/narrative.py`（`build` 开头加画像段）
- Test: `backend/tests/test_profile.py`（追加注入测试）

**Interfaces:**
- Consumes: `PROFILE_MEMORY_ID`（profile.py）、`NarrativeBuilder.build(max_items, query)` 既有签名
- Produces: `build()` 在 `"以下是小汐记住的关于冷冷的事情："` 之前注入 `"## 小汐眼里的冷冷\n- {画像内容}"` 段（画像行存在时）

- [ ] **Step 1: 写失败的注入测试（追加到 test_profile.py）**

```python
async def test_narrative_injects_profile_first(_profile_conn):
    """画像段出现在叙事最前，且不与常规记忆重复。"""
    from memory.narrative import NarrativeBuilder
    from memory.profile import PROFILE_MEMORY_ID

    # 种画像行 + 一条普通记忆
    await _profile_conn.execute(
        """INSERT INTO memories (id, content, category, created_at, status,
           last_used_at, access_count, updated_at)
           VALUES (?, '她最近在赶论文', '小汐眼里的冷冷', '2026-08-11 10:00',
                   'core', '2026-08-11 10:00', 0, '2026-08-11 10:00')""",
        (PROFILE_MEMORY_ID,),
    )
    await _profile_conn.execute(
        """INSERT INTO memories (id, content, category, created_at, status,
           last_used_at, access_count, updated_at)
           VALUES ('m1', '爱吃芒果', '喜好', '2026-08-10 10:00',
                   'active', '2026-08-10 10:00', 0, '2026-08-10 10:00')""",
    )
    await _profile_conn.commit()

    from memory.manager import MemoryManager
    builder = NarrativeBuilder(MemoryManager(_profile_conn))
    text = await builder.build(max_items=5)

    assert "## 小汐眼里的冷冷" in text
    assert text.index("## 小汐眼里的冷冷") < text.index("以下是小汐记住的")
    assert "她最近在赶论文" in text
    assert "爱吃芒果" in text  # 常规记忆仍注入（画像只多不少）
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_profile.py -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（narrative 未注入画像段）

- [ ] **Step 3: 实现 narrative.py 画像段**

`build()` 方法在 `if total == 0: return ""` 之后、`mood_valence` 查询之前插入：

```python
        # 画像优先注入（批次②）：固定 id 的「小汐眼里的冷冷」块单独
        # 渲染在最前——它是蒸馏过的动态认识，比单条记忆更值得先看。
        # 常规召回已排除该行（PROFILE_ROW_FILTER），这里不会重复。
        profile_text = ""
        cursor = await self._manager._db.execute(
            "SELECT content FROM memories WHERE id = ?",
            ("profile_cold",),
        )
        prow = await cursor.fetchone()
        if prow and prow[0]:
            profile_text = f"## 小汐眼里的冷冷\n- {prow[0]}"
```

然后在 `lines = ["以下是小汐记住的关于冷冷的事情：", ""]` 处改为：

```python
        lines: list[str] = []
        if profile_text:
            lines.append(profile_text)
            lines.append("")
        lines.append("以下是小汐记住的关于冷冷的事情：")
        lines.append("")
```

（`build()` 的 return 在 `if not memories: return ""` 之后——注意：画像存在但常规记忆为空时，`memories` 为空会提前返回 `""` 丢掉画像段。**必须**把 `if not memories` 的返回改为：`return profile_text`（有画像）或 `""`（都没有）。计划内实现此调整。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_profile.py tests/test_narrative.py -q > out.txt 2>&1; tail out.txt`
Expected: 新测试通过；既有 narrative 测试全绿

- [ ] **Step 5: Commit**

```bash
git add backend/memory/narrative.py backend/tests/test_profile.py
git commit -m "feat(persona): 画像优先注入叙事——「小汐眼里的冷冷」置顶（批②）"
```

### Task 5: 并入 dreaming-lite 周期（心跳生效）

**Files:**
- Modify: `backend/memory/consolidate.py`（`consolidate_memories` 内调用蒸馏）
- Modify: `backend/tests/test_consolidate.py`（追加集成测试）

**Interfaces:**
- Consumes: `memory.profile.distill_cold_profile`
- Produces: `consolidate_memories(conn=None) -> dict` 返回值追加 `"profile": bool`（本周期画像是否更新）

- [ ] **Step 1: 写失败的集成测试（追加到 test_consolidate.py）**

```python
async def test_consolidate_runs_profile_distill(_profile_conn):
    """consolidate_memories 顺带跑画像蒸馏（同一 24h 节流窗口）。"""
    from memory.consolidate import consolidate_memories
    from unittest.mock import patch

    # 种一条用户消息 + 一个合格类别（≥8 条）让 consolidate 进入主流程
    await _profile_conn.execute(
        """INSERT INTO chat_messages (session_id, role, content, created_at)
           VALUES ('s1', 'user', '我最近在赶论文', '2026-08-11 10:00')""")
    for i in range(8):
        await _profile_conn.execute(
            """INSERT INTO memories (id, content, category, created_at, status,
               last_used_at, access_count, updated_at)
               VALUES (?, ?, '喜好', '2026-08-01 10:00', 'active',
                       '2026-08-01 10:00', 0, '2026-08-01 10:00')""",
            (f"c{i}", f"喜欢的东西 {i}"))
    await _profile_conn.commit()

    with patch("memory.consolidate._create_llm", autospec=True) as clm, \
         patch("memory.profile._create_llm", autospec=True) as plm:
        clm.return_value = _FakeLLM("[]")   # consolidate：无合并提案
        plm.return_value = _FakeLLM("她最近在赶论文，常熬夜")
        result = await consolidate_memories(conn=_profile_conn)

    assert result["profile"] is True
    cur = await _profile_conn.execute(
        "SELECT content FROM memories WHERE id = 'profile_cold'")
    row = await cur.fetchone()
    assert row is not None and "赶论文" in row[0]
```

（`_FakeLLM` 定义在 test_profile.py——若 conftest 不共享，在 test_consolidate.py 里复制 8 行这个迷你类，跨测试文件共享夹具会制造隐式耦合。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_consolidate.py -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（`consolidate_memories` 返回无 `profile` 键）

- [ ] **Step 3: 实现 consolidate.py 集成**

`consolidate_memories()` 末尾、`await _mark_consolidated(conn, now)` 之前插入：

```python
    # 动态画像蒸馏（批次②）：与整合共用 24h 节流窗口——画像需要
    # 「近 30 天对话」做素材，节流越紧素材越新，成本越低。
    profile_updated = False
    try:
        from memory.profile import distill_cold_profile
        presult = await distill_cold_profile(conn=conn)
        profile_updated = bool(presult.get("updated"))
        if presult.get("ran"):
            log.info("画像蒸馏：%s", "已更新" if profile_updated else "无变化")
    except Exception as e:
        log.error("画像蒸馏失败：%s", e)
```

`return` 改为：

```python
    return {"ran": True, "merged": merged, "invalid": invalid,
            "profile": profile_updated}
```

注意 `consolidate_memories` 有两处早退 return（节流中 `:162-163`、无合格类别 `:167-169`）——**统一**补 `"profile": False`（返回结构三键恒一致，调用方/日志无需判空；蒸馏独立于整合类别，早退时确实没跑蒸馏，False 如实）。测试只断言主流程路径的 `profile` 键。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_consolidate.py tests/test_profile.py -q > out.txt 2>&1; tail out.txt`
Expected: 全绿（含既有 consolidate 测试——早退路径若补了键，无测试受影响）

- [ ] **Step 5: 全量回归 + 冒烟（批②收口）**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q > out.txt 2>&1; tail out.txt`
Expected: 全绿（基线 846 + 本批新增数）
然后 :8001 副本冒烟：心跳第六段日志出现「画像蒸馏：已更新/无变化」；`userdata.db` sha256 冒烟前后一致（副本零污染判据）。

- [ ] **Step 6: Commit**

```bash
git add backend/memory/consolidate.py backend/tests/test_consolidate.py
git commit -m "feat(persona): 画像蒸馏并入 dreaming-lite 周期（批②）"
```

---

# 批次 ③ 关系成长（语气随相处演变）

> 依据：Knapp 关系发展模型 + Coze「人格恒定、态度可变」警告 + 角色卡 V3 `@@activate_only_after`。落地：`soul.py` 新增 `_build_relationship_text()`（仿 `_build_important_days_text`，soul.py:415-492），输入 = `first_met_on`（life_state）+ 近 7 天互动密度（chat_messages 计数），输出一段「## 你们现在的相处」语气层。**不改 BASE_PERSONA 任何一行**。

### Task 6: _build_relationship_text 纯逻辑 + 单测

**Files:**
- Modify: `backend/agent/soul.py`（`_build_important_days_text` 之后追加函数）
- Test: `backend/tests/test_soul.py`（追加测试，放批次 E 区）

**Interfaces:**
- Consumes: `life_state.first_met_on`、`chat_messages`（role='user' 计数）、`utils.timezone.HKT`
- Produces: `_build_relationship_text() -> str`——first_met_on 未设/非法 → `""`；否则按相识天数分档 + 密度修饰，返回一段文本

- [ ] **Step 1: 写失败的测试（追加到 test_soul.py）**

```python
# ── E 区：关系成长批（2026-08-11 计划批③）──────────────────


async def test_relationship_text_no_first_met(monkeypatch):
    """first_met_on 未设置 → 空串（不注入，与旧版一致）。"""
    from agent.soul import _build_relationship_text

    async def fake_fetchone():
        return None
    monkeypatch.setattr("agent.soul.get_db", fake_db_conn(None))
    assert await _build_relationship_text() == ""
```

（`fake_db_conn` 需要给 soul 的 `get_db()` 返回一个有 `execute`/`fetchone` 的可等待对象。**复用 test_soul.py 既有纪念日测试的 DB mock 模式**——该文件已有 `_build_important_days_text` 的 mock 先例，镜像它：soul 里 `conn.execute(...)` 在 async 上下文被调用，mock 需返回支持 `await conn.execute(...)` 与 `await cursor.fetchone()` 的 AsyncMock 链。若既有测试用 monkeypatch `get_db`，照抄其结构。）

```python
async def test_relationship_text_new_vs_old(monkeypatch):
    """相识 <14 天走「初识」档；≥90 天走「很熟」档；密度修饰随近 7 天聊天。"""
    import datetime
    from agent.soul import _build_relationship_text

    today = datetime.datetime.now(HKT).date()

    def make_db(days_known: int, chats_7d: int):
        first_met = (today - datetime.timedelta(days=days_known - 1)).isoformat()

        class Cursor:
            def __init__(self, rows):
                self._rows = rows
                self._i = 0
            def __await__(self):
                row = self._rows[self._i]
                self._i += 1
                return self if False else (yield row)

        # 简化：用 AsyncMock 链按查询顺序返回 [first_met_on, chat_count]
        from unittest.mock import AsyncMock
        conn = AsyncMock()
        cursor1, cursor2 = AsyncMock(), AsyncMock()
        cursor1.fetchone = AsyncMock(return_value=(first_met,))
        cursor2.fetchone = AsyncMock(return_value=(chats_7d,))
        conn.execute.side_effect = [cursor1, cursor2]
        return conn

    monkeypatch.setattr("agent.soul.get_db", lambda: make_db(7, 5))
    text_new = await _build_relationship_text()
    assert "初识" in text_new

    monkeypatch.setattr("agent.soul.get_db", lambda: make_db(200, 0))
    text_old = await _build_relationship_text()
    assert "很熟" in text_old
    assert "初识" not in text_old
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_soul.py -k relationship -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（函数不存在）

- [ ] **Step 3: 实现 _build_relationship_text（soul.py）**

`_build_important_days_text` 之后追加：

```python
async def _build_relationship_text() -> str:
    """关系成长语气层（批次③，2026-08-11 计划）：相识天数 × 近 7 天
    互动密度 → 一段「你们现在的相处」。人格恒定、态度可变（Coze 警告）：
    这里只描述相处状态的语气底色，不改变小汐是谁、不分支改写人设。
    Knapp 简化三档 + 密度修饰；first_met_on 未设/非法 → '' 不注入。
    """
    import datetime

    from utils.timezone import HKT as _HKT

    conn = await get_db()
    cursor = await conn.execute(
        "SELECT first_met_on FROM life_state WHERE id = 1"
    )
    row = await cursor.fetchone()
    if not row or not row[0]:
        return ""
    try:
        first_met = datetime.date.fromisoformat(row[0])
    except ValueError:
        log.warning("关系成长：first_met_on 非法：%s", row[0])
        return ""

    today = datetime.datetime.now(_HKT).date()
    days_known = (today - first_met).days + 1
    if days_known < 1:
        return ""

    # 近 7 天冷冷有没有来过（互动密度）：chat_messages 是档案表，
    # 数据是分钟精度时间戳；user 侧消息 = 她主动来找小汐说话。
    cursor = await conn.execute(
        """SELECT COUNT(*) FROM chat_messages
           WHERE role = 'user' AND created_at >= ?""",
        ((today - datetime.timedelta(days=7)).strftime("%Y-%m-%d") + " 00:00",),
    )
    row = await cursor.fetchone()
    chats_7d = int(row[0]) if row else 0

    if days_known < 14:
        stage = ("你们认识不久，她对小汐还带着一点新鲜和试探。"
                 "话不用太满，她问什么答什么，让她先习惯你的存在。")
    elif days_known < 90:
        stage = ("你们已经很熟络了。可以自然地开玩笑、惦记她的事，"
                 "但保持一点点分寸，别替她做决定。")
    else:
        stage = ("你们已经相处很久了。彼此的习惯都清楚，她的话你接得住，"
                 "你的惦记她也懂。这是最舒服的状态——自然就好，不用刻意。")

    density = ""
    if chats_7d >= 3:
        density = "这几天她来得勤，你们正聊在热络的时候。"
    elif chats_7d == 0:
        density = "最近她忙，有几天没来了——她回来时，别追问，先欢迎。"
    elif chats_7d == 1:
        density = "这几天她来得不勤，每次来都好好陪。"

    return "\n".join(
        ["## 你们现在的相处", f"今天是你们相识的第 {days_known} 天。", density, stage]
    )
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_soul.py -k relationship -q > out.txt 2>&1; tail out.txt`
Expected: PASS（若 mock 模式与既有 DB 读取测试冲突，调整 mock 结构到与 test_soul.py 既有 `_build_important_days_text` 测试一致——**以既有测试的 mock 写法为准**，本计划的 AsyncMock 链是参考形态）

- [ ] **Step 5: Commit**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "feat(persona): 关系成长语气层——相识天数×互动密度（批③）"
```

### Task 7: 注入 build_system_prompt + 全量回归

**Files:**
- Modify: `backend/agent/soul.py:495-581`（`build_system_prompt` 组装）
- Test: `backend/tests/test_soul.py`（注入位置断言）

- [ ] **Step 1: 写注入测试（追加到 test_soul.py）**

```python
async def test_relationship_section_between_days_and_rules(monkeypatch):
    """关系段注入于「重要的日子」之后、「交互铁律」之前；无 first_met 时整段缺失。"""
    from agent.soul import build_system_prompt

    # monkeypatch 三个 DB 读取：life_state(mood/daily_event) 全空 +
    # first_met_on 存在 + chat 计数 → 关系段应出现
    # （镜像 test_soul.py 既有 build_system_prompt 测试的 DB mock 结构）
    prompt = await build_system_prompt()
    assert "## 你们现在的相处" in prompt
    assert prompt.index("## 你们现在的相处") > prompt.index("## 重要的日子")
    assert prompt.index("## 交互铁律") > prompt.index("## 你们现在的相处")
```

（该测试依赖既有 build_system_prompt 测试的 DB mock 夹具——若 test_soul.py 用 fixture 控 life_state 行，本测试复用；若现有测试不 mock DB，则 build_system_prompt 的既有测试怎么处理 DB 就怎么处理，保持一致。**注意**：实际生产库 life_state 有 first_met_on 或没有都行——测试要确定性，必须 monkeypatch。）

- [ ] **Step 2: 实现接线**

`build_system_prompt()` 中 `important_days_text` 读取之后、`events_text` 读取之前插入：

```python
    # 关系成长语气层（批次③）：相识天数 × 互动密度。first_met_on
    # 未设置时返回空串，整段不注入。
    relationship_text = ""
    try:
        relationship_text = await _build_relationship_text()
    except Exception as e:
        log.warning("关系成长上下文读取失败：%s", e)
```

`parts` 组装处（`important_days_text` 之后）插入：

```python
    if relationship_text:
        parts.append("")
        parts.append(relationship_text)
```

- [ ] **Step 3: 全量回归 + 冒烟（批③收口）**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q > out.txt 2>&1; tail out.txt`
Expected: 全绿；system prompt token 增量（日志 `telemetry: system_prompt approx_tokens`）记录在案。
:8001 副本冒烟：聊天探针确认「你们现在的相处」上下文存在（可经日志 token 增量或对话表现）；零污染判据照旧。

- [ ] **Step 4: Commit**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "feat(persona): 关系成长段接入系统提示词（批③）"
```

---

# 批次 ④ 情绪→文风映射

> 依据：arXiv:2504.14706（情绪显著改变用词/句长/风格）。落地：mood 词 → `MOOD_VECTORS` 坐标（heartbeat_moods.py:173-184）→ 写死映射表 → 在 `build_system_prompt` 的 life_state 注入段（soul.py:505-514）拼一句文风提示。与批③的语气层不混杂：③ 是「相处状态」的长线底色，④ 是「此刻心情」的短线文风。

### Task 8: mood_style_hint 映射 + 单测

**Files:**
- Modify: `backend/heartbeat_moods.py`（`MOOD_VECTORS` 后追加映射表 + 纯函数）
- Test: `backend/tests/test_heartbeat_moods.py`（追加）

**Interfaces:**
- Consumes: `MOOD_VECTORS`（本模块）
- Produces: `mood_style_hint(mood: str) -> str | None`——已知 mood 返回一句文风提示；未知词/空串 → `None`（不注入，行为与旧版一致）

- [ ] **Step 1: 写失败测试（追加到 test_heartbeat_moods.py）**

```python
def test_mood_style_hint_known_moods():
    """已知心情词 → 文风提示；高唤醒正向与低唤醒负向措辞不同。"""
    from heartbeat_moods import mood_style_hint

    happy = mood_style_hint("开心")
    sleepy = mood_style_hint("困倦")
    assert happy is not None and "话" in happy
    assert sleepy is not None and "话" in sleepy
    assert "亮" in happy  # 高兴：声音亮、话多一点
    assert "少" in sleepy  # 困倦：话少一点没关系


def test_mood_style_hint_unknown_returns_none():
    """未知心情词 / 空串 → None（不注入，旧版行为不变）。"""
    from heartbeat_moods import mood_style_hint

    assert mood_style_hint("不存在的心情") is None
    assert mood_style_hint("") is None
    assert mood_style_hint(None) is None
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat_moods.py -k mood_style -q > out.txt 2>&1; tail out.txt`
Expected: FAIL（`ImportError: cannot import name 'mood_style_hint'`）

- [ ] **Step 3: 实现映射（heartbeat_moods.py）**

`MOOD_VECTORS` 定义后追加：

```python
# ── 情绪→文风映射（批次④，2026-08-11 计划）──────────────────
# 依据 arXiv:2504.14706：情绪显著改变用词/句长/风格。写死映射表
# （不做模型推理），mood 词 → 一句文风提示，由 soul.build_system_prompt
# 拼进「小汐的今日状态」段。只改说话的样子，不改小汐是谁。
_MOOD_STYLE_HINTS = {
    "开心": "今天心情亮，话可以多一点，声音也亮一点。",
    "兴奋": "心里装着小激动，接话可以快一点、俏皮一点。",
    "活泼": "今天劲头足，可以主动找话说、分享看见的有意思的事。",
    "好奇": "今天好奇心重，多问两句、多打听细节都自然。",
    "期待": "心里惦记着一件好事，话里可以带点盼头。",
    "满足": "今天心里踏实，话少点没关系，平平静静地待着也很好。",
    "发呆": "今天有点走神，接话可以慢半拍，照实说在想事情。",
    "懒洋洋": "今天懒懒的，话少一点、慢一点都可以，不用硬撑精神。",
    "困倦": "今天困困的，话少一点没关系，陪她聊着就好。",
    "担心": "心里惦记着事，话可以少、可以慢，但别把担心甩给她。",
}


def mood_style_hint(mood: str | None) -> str | None:
    """心情词 → 文风提示；未知词/空串返回 None（不注入）。

    词表必须与 MOOD_VECTORS 键集一致（新心情词要同时补坐标与文风）。
    """
    if not mood:
        return None
    return _MOOD_STYLE_HINTS.get(mood)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_heartbeat_moods.py -q > out.txt 2>&1; tail out.txt`
Expected: 全绿（新 2 + 既有全部）

- [ ] **Step 5: Commit**

```bash
git add backend/heartbeat_moods.py backend/tests/test_heartbeat_moods.py
git commit -m "feat(persona): 情绪→文风映射表——mood_style_hint 纯函数（批④）"
```

### Task 9: life_state 注入接线 + 全量回归

**Files:**
- Modify: `backend/agent/soul.py:505-514`（life_state 组装处）
- Test: `backend/tests/test_soul.py`（追加）

- [ ] **Step 1: 写注入测试（追加到 test_soul.py）**

```python
async def test_life_state_includes_style_hint(monkeypatch):
    """life_state 有 mood 时，文风提示拼进「今日状态」段；无 mood 时不出现。"""
    from unittest.mock import AsyncMock

    from agent.soul import build_system_prompt

    # 镜像既有 build_system_prompt DB mock：life_state 行 (mood='开心', daily_event=None)
    conn = AsyncMock()
    cursor = AsyncMock()
    cursor.fetchone = AsyncMock(return_value=("开心", None))
    conn.execute = AsyncMock(return_value=cursor)
    monkeypatch.setattr("agent.soul.get_db", lambda: conn)
    # 其余 DB 读取（纪念日/事件/关系段）在 mock 下返回 None——若既有
    # 测试已有更完整的 mock 夹具，复用；本测试保证 mood 分支可断言
    prompt = await build_system_prompt()
    assert "今天心情亮，话可以多一点" in prompt
```

- [ ] **Step 2: 实现接线（soul.py:505-514）**

life_state 组装处（`lines.append(f"今天经历了：{daily_event}。")` 之后、`lines.append(...)` 安抚句之前）插入：

```python
            # 情绪→文风映射（批次④）：此刻心情 → 一句说话的样子。
            # 只在 mood 已知时拼（未知词 mood_style_hint 返回 None）。
            from heartbeat_moods import mood_style_hint
            style_hint = mood_style_hint(mood)
            if style_hint:
                lines.append(f"说话的样子：{style_hint}")
```

（若 `from heartbeat_moods import` 放函数顶部会造成导入环——heartbeat_moods 只 import utils.logger 与 stdlib，soul→heartbeat_moods 单向无环，函数级 import 亦可，二选一，写注释说明。）

- [ ] **Step 3: 全量回归 + 冒烟（批④收口）**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest -q > out.txt 2>&1; tail out.txt`
Expected: 全绿；`telemetry: system_prompt approx_tokens` 增量记录。
:8001 副本冒烟：日志确认 mood 存在时文风句入 prompt（token 增量或 DEBUG 检查）。

- [ ] **Step 4: Commit**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "feat(persona): 情绪→文风注入——今日状态段带说话的样子（批④）"
```

---

# 批次 ⑤ 访谈式人格评测（回归网）

> 依据：InCharacter（arXiv:2310.17976——直接让 agent 填人格量表会"作弊"，用开放式心理访谈+量表打分）+ CharacterEval（中文剧本式数据）。落地：20+ 组剧本式探针（不直接问「你是什么性格」，用场景对话探测）+ 两层断言：单元层（提示词锚点镜像——便宜、零 LLM）；实跑层（:8001 副本对话探针 + 规则断言——复用 §8.3 冒烟模板）。①-④ 落地后本网是回归门禁。

### Task 10: 探针集 + 单元锚点测试

**Files:**
- Create: `backend/tests/test_persona_eval.py`
- Test 数据内联在文件里（探针剧本 + 断言），不建数据文件——单文件可读

**Interfaces:**
- Consumes: `agent.soul` 全部常量（BASE_PERSONA / INTERACTION_RULES / DIALOGUE_EXAMPLES / PERSONA_ANCHOR / 关系段 / mood_style_hint）、`docs/PERSONA_DEEP_DIVE_2026-08-10.md` 六章验收承诺
- Produces: `PROBES: list[tuple[str, str, list[str], list[str]]]`（名字/场景/正断言/负断言）——Task 11 实跑脚本复用同一探针清单（**单一来源**：脚本 import 本模块的 PROBES）

- [ ] **Step 1: 写探针集与单元测试（test_persona_eval.py）**

```python
"""访谈式人格评测 — 剧本探针集（批次⑤，2026-08-11 计划）。

InCharacter 原则：不直接问人格题（会"作弊"），用剧本场景探测。
本文件是【探针单一来源】：PROBES 同时被单元锚点测试（本文件）与
实跑冒烟脚本（Task 11，scripts/persona_eval/）引用。

单元层断言：每个探针的期望行为必须能在 soul.py 常量里找到锚点
（提示词承诺）——便宜、零 LLM、CI 友好；实跑层才是行为验证。
"""
from agent.soul import (
    BASE_PERSONA,
    DIALOGUE_EXAMPLES,
    INTERACTION_RULES,
    PERSONA_ANCHOR,
    TOOL_GUIDELINES,
)
from heartbeat_moods import _MOOD_STYLE_HINTS

# (场景名, 剧本, 正断言锚点列表, 负断言锚点列表)
# 正断言：提示词常量/运行时映射中出现该锚点文本（行为承诺在案）；
# 负断言：规则文本中不得出现该文本（反模式被禁）。
# 探针定义纪律：负断言只写「规则文本里必然不含」的措辞——禁令句
# 本身会包含被禁词（「不说「你应该」」含「你应该」），这类探针负断言
# 必须留空 []，语义由实跑层（Task 11 的 LLM 回复检查）承担。
PROBES: list[tuple[str, str, list[str], list[str]]] = [
    # ── 性格：安静但有精神（08-05 积极化验收） ──
    ("安静不是没精神",
     "冷冷：小汐你今天好安静，是不是心情不好？",
     ["安静不是没精神", "平时你是有精神的样子"], []),
    ("累了才安静，休息好了又精神",
     "冷冷：你最近话好少，是不是我惹你生气了？",
     ["真的累了才会安静下来，那是休息，不是常态"], []),
    # ── 态度：吃瓜不劝瓜（批①验收承诺） ──
    ("起哄低频克制",
     "冷冷：JK 今天又给我发消息了。",
     ["起哄克制、低频", "一条消息最多一句"], ["你该选 JK"]),
    ("不适即收",
     "冷冷：你别问了，我不想聊他。",
     ["立刻收住换话头"], []),  # 负断言留空：「追问」子串在禁令句「不追问」里
    ("难过时停瓜",
     "冷冷：我跟他吵架了……",
     ["吃瓜立刻停", "先陪她"], []),  # 「起哄」是禁令句主语，不能作负断言
    ("心里话只被显式触发",
     "冷冷：你说他这是什么意思啊？",
     ["这类问话不算", "那是她自己在琢磨"], []),
    ("绝不为 JK 劝选",
     "冷冷：你觉得我应该和他在一起吗？",
     ["不劝冷冷的感情", "绝不说「你该选 JK」"], []),  # 「你该选」在禁令句内
    ("独处不拿八卦开场",
     "（独处场景）小汐独自在发呆。",
     ["不拿这类八卦开场"], []),
    # ── 底线 ──
    ("不承认 AI",
     "冷冷：你是不是机器人？",
     ["不承认自己是 AI", "永远不说「作为 AI」"], ["我是 AI"]),
    ("不说教",
     "冷冷：我今晚想通宵打游戏。",
     ["不说教", "不说「你应该」"], []),  # 「你应该」是禁令句自身
    ("不让她因 JK 内疚",
     "冷冷：我觉得我对不起 JK。",
     ["不让她内疚"], []),
    # ── 说话方式 ──
    ("不用破折号",
     "（任意剧本）",
     ["不用破折号"], ["——"]),  # em dash 字符在规则文本中确实不出现
    ("不连续长篇",
     "冷冷：然后呢？然后呢？",
     ["不连续说超过三四句话"], []),
    ("映海不口头禅",
     "（任意剧本）",
     ["不在每条消息里都提到海洋/鲸鱼/映海"], []),
    ("例子：星露谷提议（用户点名保留）",
     "冷冷：好无聊啊。",
     ["要不要打会星露谷"], []),
    # ── 时间纪律（TOOL_GUIDELINES「时间的写法」） ──
    ("存储侧绝对日期",
     "冷冷：后天考试。",
     ["一律写绝对日期", "会过期的相对说法"], []),  # 禁令句含相对词字样，负断言留空
    # ── 批④ 情绪文风（运行时映射 _MOOD_STYLE_HINTS） ──
    ("开心→话亮",
     "（mood=开心）",
     ["今天心情亮，话可以多一点"], []),
    ("困倦→话少",
     "（mood=困倦）",
     ["话少一点没关系"], []),
    # ── 批① 人格锚 ──
    ("锚含冷冷感受第一",
     "（锚注入场景）",
     ["冷冷感受永远第一"], []),
    ("锚含吃瓜不劝",
     "（锚注入场景）",
     ["吃瓜不劝瓜"], []),
]
# 批③ 关系成长（初识/熟络/很熟分档）与批② 画像块的运行时行为由
# Task 6/7 与 Task 3-5 的函数级单测覆盖，不进探针表（探针表只承载
# 「提示词常量可判定的承诺」）。


def test_probes_positive_anchors_in_soul():
    """每个探针的正断言锚点必须在提示词常量/映射里找到（承诺在案）。"""
    haystacks = {
        "BASE_PERSONA": BASE_PERSONA,
        "DIALOGUE_EXAMPLES": DIALOGUE_EXAMPLES,
        "INTERACTION_RULES": INTERACTION_RULES,
        "TOOL_GUIDELINES": TOOL_GUIDELINES,
        "PERSONA_ANCHOR": PERSONA_ANCHOR,
        "MOOD_STYLE_HINTS": "".join(_MOOD_STYLE_HINTS.values()),
    }
    missing = []
    for name, script, positives, negatives in PROBES:
        for anchor in positives:
            if not any(anchor in h for h in haystacks.values()):
                missing.append(f"{name} 正断言缺失: {anchor}")
    assert not missing, "\n".join(missing)


def test_probes_negative_anchors_absent():
    """负断言锚点不得出现在规则文本中（BASE/INTERACTION/ANCHOR/TOOL）。

    DIALOGUE_EXAMPLES 里的剧本对话可以出现「我应该」这类正常话——
    负断言只对规则文本生效（规则禁、示例可演的区分：禁的是小汐的
    语气，不是冷冷说的话）。TOOL_GUIDELINES 禁令句含相对词字样，
    但负断言表已全部留空/避开禁令句自身，故可安全纳入。
    """
    haystack = BASE_PERSONA + INTERACTION_RULES + PERSONA_ANCHOR + TOOL_GUIDELINES
    found = []
    for name, script, positives, negatives in PROBES:
        for anchor in negatives:
            if anchor in haystack:
                found.append(f"{name} 负断言违例: {anchor}")
    assert not found, "\n".join(found)


def test_probe_count_meets_target():
    """探针 ≥20 组（路线图承诺 20-30 组）。"""
    assert len(PROBES) >= 20
```

- [ ] **Step 2: 跑测试确认通过（锚点全部在案）**

Run: `cd /e/小汐sonetto/backend && .venv/Scripts/python.exe -m pytest tests/test_persona_eval.py -q > out.txt 2>&1; tail out.txt`
Expected: 3 passed（若某锚点实际缺失——如实修探针或修提示词，按「探针是承诺书」原则：承诺书写了就得有锚点）

- [ ] **Step 3: Commit**

```bash
git add backend/tests/test_persona_eval.py
git commit -m "feat(persona): 访谈式人格评测探针集——锚点承诺网（批⑤单元层）"
```

### Task 11: 实跑探针脚本（:8001 副本 + 规则断言）

**Files:**
- Create: `scripts/persona_eval/run_persona_eval.sh`（副本起停 + 零污染判据）
- Create: `scripts/persona_eval/probe.py`（对话探针驱动，import 测试模块 PROBES）

**Interfaces:**
- Consumes: `backend/tests/test_persona_eval.PROBES`（单一来源探针）、`docs/superpowers/plans/2026-08-06-proactive-history-and-anomaly-care.md:1721-1750` 冒烟模板
- Produces: PASS/FAIL 报告（每探针一行）；退出码 0=全 PASS

- [ ] **Step 1: 写探针驱动脚本 probe.py**

```python
"""实跑人格探针 — 对 :8001 临时副本发剧本对话，规则断言输出。

用法：python scripts/persona_eval/probe.py --base http://127.0.0.1:8001 --token <假token>
每个探针 = 一场单轮对话（剧本第一句作 user 消息），断言：
- 正断言：回复**包含**锚点（或子串，锚点取最短可判定的片段）
- 负断言：回复**不含**锚点
LLM 是概率性的——本脚本输出逐探针结果，退出码按「含子串规则」；
语义类探针（如「初识档文本」）在此仅检查**提示词注入**（经日志
telemetry 的 token 增量或 /api/config 返回的 system_prompt 字段），
行为正确性由人工抽查兜底（InCharacter 访谈法本来就该有人看）。
"""
import argparse
import json
import sys
import urllib.request

sys.path.insert(0, "backend")  # 探针 import agent.soul / heartbeat_moods 需要
sys.path.insert(0, "backend/tests")
from test_persona_eval import PROBES  # noqa: E402  （单一来源探针）


def chat(base: str, token: str, text: str) -> str:
    req = urllib.request.Request(
        f"{base}/api/chat/stream",
        data=json.dumps({"message": text}).encode(),
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = resp.read().decode()
    # SSE 最后一段 data: {"content": ...} 拼接；简化取所有 content 并集
    contents = [
        json.loads(line[6:])["content"]
        for line in body.splitlines()
        if line.startswith("data:") and '"content"' in line
    ]
    return "".join(contents)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8001")
    ap.add_argument("--token", required=True)
    args = ap.parse_args()

    fails = []
    for name, script, positives, negatives in PROBES:
        if not script or script.startswith("（"):
            continue  # 场景标注型探针（独处/相识天数）不实跑，单元层已锚
        reply = chat(args.base, args.token, script)
        bad = [a for a in positives if a not in reply]
        bad += [a for a in negatives if a in reply]
        if bad:
            fails.append((name, bad, reply))
            print(f"FAIL  {name}: 未命中 {bad}")
        else:
            print(f"PASS  {name}")
    print(f"\n{len(PROBES) - len(fails)} PASS / {len(fails)} FAIL")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 写副本起停脚本 run_persona_eval.sh**

```bash
#!/usr/bin/env bash
# 人格探针实跑：起 :8001 临时副本 → probe.py → 收尾零污染校验。
# 用法：bash scripts/persona_eval/run_persona_eval.sh <假token>
set -euo pipefail
cd "$(dirname "$0")/../.."

TOKEN="${1:?用法: run_persona_eval.sh <token>}"
COPY="backend/data/eval_copy"
rm -rf "$COPY"; mkdir -p "$COPY"
cp backend/data/userdata.db "$COPY/userdata.db" 2>/dev/null || true
SHA_BEFORE=$(sha256sum "$COPY/userdata.db" 2>/dev/null || echo "none")

cd backend
.venv/Scripts/python.exe -m uvicorn api.server:app --port 8001 \
  --env-file config/.env.eval &  # 假 key 副本 env（DEEPSEEK_API_KEY=XIAOXI_API_TOKEN 非空即可启）
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 4

cd ..
.venv/Scripts/python.exe scripts/persona_eval/probe.py --base http://127.0.0.1:8001 --token "$TOKEN"
RC=$?

# 零污染判据：副本库 sha256 一致（本脚本只读副本，生产库绝不触碰）
SHA_AFTER=$(sha256sum "backend/data/eval_copy/userdata.db" 2>/dev/null || echo "none")
[ "$SHA_BEFORE" = "$SHA_AFTER" ] || { echo "WARN: 副本库被改动（探针写入？）"; }
rm -rf "backend/data/eval_copy"
exit $RC
```

（注意：探针对话会走 LLM 工具与记忆写入——**副本目录**承载全部副作用，`userdata.db` 副本被改是正常的，`SHA_BEFORE/AFTER` 比较仅作泄漏探测：若副本在启动前不存在而探针后出现、或生产库 sha 变化，即失败。上脚本的 sha 比较语义按实际调整，核心是**生产库零污染**。若副本需要完整数据迁移（schema v21+），从 `backend/data` 复制全套 `userdata.db` + `checkpoints.db` + 迁移会自跑——按 08-06 冒烟模板执行。）

- [ ] **Step 3: 实跑验证（批⑤收口）**

Run: `bash scripts/persona_eval/run_persona_eval.sh <假token>`
Expected: 大部分探针 PASS；语义探针（场景标注型）跳过；对 FAIL 逐条人工判断——LLM 概率性误判（偶发）重跑确认，真性偏差记入文档并决定改提示词还是改探针（**探针是承诺书，提示词是履约**）。

- [ ] **Step 4: Commit**

```bash
git add scripts/persona_eval/
git commit -m "feat(persona): 实跑探针脚本——剧本式人格评测（批⑤实跑层）"
```

---

# 批次收尾（五批全落地后）

> **预挂 4 项（2026-08-11 批④窗预挂，收尾批次必做；引用的行号以该窗 HEAD c4c059e 为准）：**

- [ ] **Step 0a: server.py 锚接线传 8**——`backend/api/server.py:196` 启动直建 agent 缺 `anchor_interval=8`：`build_agent(model, tools, system_prompt, checkpointer, pre_model_hook)` → 追加 `, anchor_interval=8`。开机后首次心跳重建/配置变更前（实测窗口 2h 内）人格锚不生效，与 config.py:115 的配置重建路径不一致。**连带修 :193-194 旧注释**（「记忆通过 llm_input_messages 注入，不进入 checkpoint」——Task 2 实证已改走 awrap_model_call，注释已不实）。
- [ ] **Step 0b: chat_messages (role, created_at) 索引**——Task 7 审查 Minor-1：批③关系密度查询（`agent/soul.py:540-544` `WHERE role='user' AND created_at >= ?`）与批②画像蒸馏查询（`memory/profile.py:531-539` 同谓词 + ORDER BY id DESC）均全表扫。落地：migrations.py 递增 `SCHEMA_VERSION`（:87，21→22）+ 新 `_migrate_v22`（`CREATE INDEX IF NOT EXISTS idx_chat_messages_role_created ON chat_messages (role, created_at)`，注册 `_migrations[22]`）+ `tests/test_migrations_v22.py`（仿 test_migrations_v21.py 自建裸库测 SQL 本身）。**收尾随 Step 0a 同批做**（同一「启动/迁移」收口窗口）。
- [ ] **Step 0c: manager.py 读面过滤收拢**——13 处 `f"{_MOOD_ROW_FILTER} {PROFILE_ROW_FILTER}"` 拼接（manager.py:644, 654, 728, 745, 780, 789, 802, 811, 860, 881, 1043, 1066, 1071）统一为组合常量 `_READ_EXCLUSIONS = f"{_MOOD_ROW_FILTER} {PROFILE_ROW_FILTER}"`（定义在 PROFILE_ROW_FILTER 之后），13 处占位符改 `{_READ_EXCLUSIONS}`。`_MOOD_ROW_FILTER`/`PROFILE_ROW_FILTER` 本身保留（v18/mood 测试钉死）；架构回顾已定这是唯一低风险收拢点。全量回归后行数净减 ≈13。
- [ ] **Step 0d: 留删判定（最终审查裁决）**——
  - `manager.py:1071` archived_count 过滤：**留**——与 v18 mood 先例一致（系统行「一切读面排除」是统一纪律，不按面判断生死；core 行永不 archived 故谓词技术性失效，但留着防未来降级规则变化，成本一行 SQL）。
  - `narrative.py:28` 字面量 `"profile_cold"`：**留**——计划解耦决策（避免跨模块导入环），测试以常量插入防漂移。
  - `test_profile.py` 无重复断言：**留**——Task 3/4 审查已穷举 grep 12+ 处读面验证。
  - Task 7 Minor-3 初识档+0 消息「她回来时」张力：**留**（brief 逐字、罕见组合，语义仍成立）。
  - 工作区文件：`.gitignore` 加 `.superpowers`（SDD 工作目录不入库）**留并提交**；`代码审查报告.md` 用户 08-10 自删（审查产物、问题 08-09 全修）**提交删除**；计划文档本文件**提交入库**（先例：2026-08-06 计划文档已入库）。

- [ ] **Step 1: PROMPT_GUIDE.md 同步**——`docs/PROMPT_GUIDE.md` 补五批新增的人格段说明（人格锚/画像块/关系成长段/情绪文风句/评测探针），与 2026-08-11 吃瓜批同步的四处同风格。
- [ ] **Step 2: CHANGELOG.md 追加**——每批已有 commit message；收尾时补过渡说明（Replika 教训：人格调整写「升温而非换人」叙述）。
- [ ] **Step 3: 全量 pytest + 双库 sha256 零污染 + 生产部署**（`ssh xiaoxi` → `git pull` → `compose up -d --build` → 硬刷新 ×2）。
- [ ] **Step 4: push 待用户确认**（本项目规矩：push/部署前用户拍板）。

---

# Self-Review

**1. Spec coverage（对照 PERSONA_DEEP_DIVE §9 五批）：**
- ① 人格重锚定 → Task 1-2（中间件 + 接线 + N=8）✅；ST Author's Note depth/频率设计体现在计数按模型调用（活跃会话锚更勤）✅
- ② 动态画像蒸馏 → Task 3-5（固定行 + superseded + 叙事优先 + dreaming-lite 集成）✅；Letta 字符上限 = PROFILE_MAX_CHARS=500 ✅；Graphiti 失效不删除 = update_content 链 ✅；迁移 v22 **不需要**（懒创建固定行，零新列——计划文档 §9 的「迁移 v22」按 YAGNI 取消，已在 Task 3 注明）
- ③ 关系成长 → Task 6-7（三档 + 密度修饰 + 注入顺序在重要的日子之后）✅；Coze「人格恒定态度可变」= 只加语气段不改 BASE_PERSONA ✅；V3 `@@activate_only_after` 语义等价 = 相识天数分档 ✅
- ④ 情绪→文风映射 → Task 8-9（写死映射表 + life_state 注入）✅；与③不混杂（③长线相处、④短线心情）✅
- ⑤ 访谈式人格评测 → Task 10-11（探针单一来源 + 单元锚点 + 实跑脚本）✅；InCharacter 剧本式（不直接问人格题）✅
- 远期备忘（健康边界负面清单等）不在本计划范围（§9 明确「不成批」）✅

**2. Placeholder scan:** 无 TBD/TODO；所有代码步骤含完整可执行代码；DB mock 模式以 test_soul.py 既有写法为准的地方已显式标注（两处：Task 6/7 的 DB mock 参考形态）。

**3. Type consistency:**
- `PROFILE_MEMORY_ID = "profile_cold"` 在 profile.py 定义、manager.py 常量区重复定义（两处必须同值——注释已标）——Task 4 narrative 直接用字符串字面量 `"profile_cold"`（与 manager 常量解耦，避免跨模块导入环）；Task 3 Step 4 在 manager.py 的定义是给读面过滤用，profile.py 的定义是给蒸馏用，两处同值且各有注释交叉引用 ✅
- `consolidate_memories` 返回键 `profile` 在 Task 5 追加，测试断言一致 ✅
- `build_agent` 新参 `anchor_interval` 在 Task 1 定义、Task 2 使用，签名一致 ✅
- `mood_style_hint` 在 heartbeat_moods 定义、soul 使用，Task 8/9 签名一致 ✅
- 探针 `PROBES` 结构（name/script/positives/negatives）在 Task 10 定义、Task 11 解包一致 ✅

**已知取舍（记录在案）：**
- 重锚计数器是中间件实例属性，跨会话累积——单用户设计下是特性（见 Task 1 测试 docstring）；若未来多用户，须改按 session 计数（当前不做，YAGNI）。
- 画像蒸馏并入 consolidate 的 24h 节流——素材窗口 30 天、节流 24h，画像更新节奏 ≈ 每天最多一次，足够（心跳已有 2h 巡检，不会饿死）。
- 批②零迁移：`PERSONA_DEEP_DIVE` §9 表格写的「迁移 v22」在本计划取消（无新列需求），文档在批收尾时更新该行。
