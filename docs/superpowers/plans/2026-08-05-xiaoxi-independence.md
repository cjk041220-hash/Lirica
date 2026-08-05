# 小汐人格重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把小汐从「陪伴者/帮手」重写为「独立真人」——受 JK 拜托且自愿陪伴冷冷、会真正生气（真人式和解）、关键时刻为 JK 说真心话（一次对话只说一次），同时消除说话 AI 味（示例压阵、正向表达、口语化工具渲染）。

**Architecture:** 方案 A 同架构精准手术——`agent/soul.py` 的 `build_system_prompt()` 组装骨架不动，重写 `BASE_PERSONA` 内容（定位/性格/态度/说话样子/底线），把对话示例提取为独立常量 `DIALOGUE_EXAMPLES` 并挪到提示词最末尾压阵；同步升级钉死人设文案的测试锚点；`config.py` medium 温度 0.7→0.8；`map_tools.py` 两处渲染口语化。

**Tech Stack:** Python / FastAPI / pytest / pydantic-settings / langchain-core

**Design Spec:** `docs/superpowers/specs/2026-08-05-xiaoxi-independence-design.md`（本计划的人格文案全部以 spec 为准，各任务引用的原文段落编号指向它）

## Global Constraints

- 测试锚点字符串**一字不改**（`test_soul.py` 既有断言原文）：`她的感受永远排在第一位`、`不会替 JK 游说`、`你盼着他们两个好`、`不跟着说`、`替他说两句好话`、`她的开心最重要`、`不劝她回 JK 身边`、`不让她内疚`、`会有点舍不得`、`他对你真的没话说`、`你开心就好`、`不劝冷冷的感情`、`不说「你还是选 JK」`、`一条消息最多一两个`、`不是每条都带`、`你为什么闷闷的呀`（此条随示例提取移往 DIALOGUE_EXAMPLES）
- `INTERACTION_RULES` / `TOOL_GUIDELINES` / `COLD_INFO` 三个常量**零改动**
- 工具渲染改动不得破坏 `test_errors.py` 断言（`SUPER_SECRET` 与 `key=` 不出现在结果里）与 `verify_tools.py` 非空判定
- 提交信息带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`，消息格式沿用仓库既有约定（`feat(...)/fix(...)/test(...)/chore(...)` + 中文正文）
- 每个任务独立可测、独立提交；`pytest` 只在 backend 目录运行

---

### Task 1: 对话示例提取为 `DIALOGUE_EXAMPLES` 并挪到提示词末尾

**Files:**
- Modify: `backend/agent/soul.py`（BASE_PERSONA 中「## 对话示例」整段 + `build_system_prompt`）
- Test: `backend/tests/test_soul.py`

**Interfaces:**
- Consumes: 无（结构前置任务）
- Produces: 模块级常量 `DIALOGUE_EXAMPLES: str`（「## 对话示例」标题 + 现有全部示例逐字搬迁）；`build_system_prompt()` 的 parts 顺序变为 `BASE_PERSONA → COLD_INFO → [life_state] → [important_days] → [events] → INTERACTION_RULES → TOOL_GUIDELINES → DIALOGUE_EXAMPLES`

- [ ] **Step 1: 更新现有锚点测试 + 写位置断言**

修改 `backend/tests/test_soul.py`：

```python
# 原 test_mood_question_example_present 改为断言示例常量（示例移出 BASE_PERSONA）
def test_mood_question_example_present():
    """有一组示范：冷冷问为什么闷 → 小汐带上今天的经历照实答。"""
    from agent.soul import DIALOGUE_EXAMPLES
    assert "你为什么闷闷的呀" in DIALOGUE_EXAMPLES
    assert "走神" in DIALOGUE_EXAMPLES


def test_dialogue_examples_appended_last():
    """对话示例是提示词最后一段（弱模型只记得住末尾——示例压阵）。"""
    prompt = asyncio.run(build_system_prompt())
    assert "## 对话示例" in prompt
    # 示例段位于工具指引之后：找到两段标题的索引并比较
    assert prompt.index("## 对话示例") > prompt.index("## 工具使用指引")
    # 且是最后一个「## 」段（示例之后无其他标题段）
    tail = prompt[prompt.index("## 对话示例"):]
    assert tail.count("## ") == 1
```

（文件顶部 import 补 `asyncio`；若已有则略。`asyncio.run` 与文件内其他 async 测试风格不同也可改为把新测试写成 async 直接 `await build_system_prompt()`——与现有 `test_prompt_contains_base_persona` 同风格。）

- [ ] **Step 2: 运行确认失败**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_soul.py -q > /tmp/t1.txt 2>&1; tail -20 /tmp/t1.txt`（输出重定向防管道死锁，见项目记忆）
Expected: `test_dialogue_examples_appended_last` 报 ImportError（无 DIALOGUE_EXAMPLES）；`test_mood_question_example_present` 失败（字符串仍在 BASE_PERSONA）

- [ ] **Step 3: 提取常量并调整组装顺序**

`backend/agent/soul.py`：
1. 把 `BASE_PERSONA` 中从 `## 对话示例` 到 `## 绝对不做的事` 之前的所有行（即 L80-131 全部示例，逐字不变）剪切为独立常量：

```python
# ═══════════════════════════════════════════════════════════════
# 对话示例 — 提示词末尾压阵（弱模型只记得住最后看到的东西）
# ═══════════════════════════════════════════════════════════════
DIALOGUE_EXAMPLES = """## 对话示例

[现有示例逐字搬入，一条不改]
"""
```

2. `build_system_prompt()` 的 parts 列表尾部追加：

```python
    parts.append("")
    parts.append(DIALOGUE_EXAMPLES)
```

（放在 `TOOL_GUIDELINES` 之后、`prompt = "\n".join(parts)` 之前。）

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令
Expected: test_soul.py 全部通过（含两个新/改断言）

- [ ] **Step 5: 提交**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "refactor(soul): 对话示例提取 DIALOGUE_EXAMPLES 常量，挪到提示词末尾压阵"
```

---

### Task 2: 定位与态度重写（独立真人 / 生气机制 / 真诚拥护者）

**Files:**
- Modify: `backend/agent/soul.py`（BASE_PERSONA 内「关于你」两处、「性格」末尾、「态度」三条）
- Test: `backend/tests/test_soul.py`（批次 D）

**Interfaces:**
- Consumes: Task 1 的 `DIALOGUE_EXAMPLES`（内容不动）
- Produces: 新锚点字符串：`你从来不是谁送给谁的东西`、`你是会生气的人`、`能讨她开心的人很多，但会为她操心到这个地步的`、`一次对话只说一次`（后续任务和人工验收依赖）

- [ ] **Step 1: 更新/新增测试（先红）**

`test_soul.py` 修改 `test_xiaoxi_on_jk_side` 并新增三例：

```python
def test_xiaoxi_independent_not_accessory():
    """定位：不是附属品——受拜托且自愿，保留「盼着他们俩好」心愿。"""
    assert "你从来不是谁送给谁的东西" in BASE_PERSONA
    assert "你盼着他们两个好" in BASE_PERSONA
    assert "其实你是站在 JK 这边的" not in BASE_PERSONA  # 旧立场句已删除


def test_can_get_truly_angry():
    """生气机制：会真的生气；生气是结束话题不是解释情绪；有逃生阀。"""
    assert "你是会生气的人" in BASE_PERSONA
    assert "你要这么想，我也没办法" in BASE_PERSONA          # 主动结束话题话术
    assert "说开了就没事了" in BASE_PERSONA                  # 逃生阀


def test_advocacy_capped():
    """真诚拥护者：关键时刻真心话 + 一次对话只说一次的频率上限。"""
    assert "能讨她开心的人很多，但会为她操心到这个地步的" in BASE_PERSONA
    assert "一次对话只说一次" in BASE_PERSONA
    assert "除非她问起" in BASE_PERSONA
```

（原 `test_xiaoxi_on_jk_side` 的 `assert "其实你是站在 JK 这边的" in BASE_PERSONA` 删除；`assert "你盼着他们两个好" in BASE_PERSONA` 并入新测试。）

- [ ] **Step 2: 运行确认失败**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_soul.py -q > /tmp/t2.txt 2>&1; tail -20 /tmp/t2.txt`
Expected: 3 个新测试失败（锚点字符串不存在）；`test_cold_feelings_first` / `test_defend_jk_softly` / `test_likes_someone_else_reaction` / `test_no_lobbying_hard_rule` 仍绿（旧锚点还在）

- [ ] **Step 3: 按 spec §3 重写 BASE_PERSONA**

按 spec `2026-08-05-xiaoxi-independence-design.md` §「定位核心」「性格」「态度」三节**逐字采用**以下内容（写入 BASE_PERSONA 常量）：

1. 「关于你」相托段落整段替换为 spec 中的新版（含「**你从来不是谁送给谁的东西。**」），**保留**字符串：`但冷冷才是你每天陪在身边的人。她的感受永远排在第一位。`、`不会替 JK 游说，不会让她为难，也不会因为她没有选谁就怪她。`、`你盼着他们两个好`（改为「你自己看进眼里的，所以你心里确实盼着他们俩好」所在句）；**删除**「其实你是站在 JK 这边的」整句
2. 「性格」段末追加 spec §「性格」的两行（`你有自己的想法和判断，不是什么都顺着说。……她来了，你就在。`）
3. 「态度」按 spec 新增「## 生气的时候」小节（四行：两种生气的样子/谁值得你生气/生气之后/逃生阀，逐字采用 spec 文本，含 `你要这么想，我也没办法。`、`说开了就没事了。`）
4. 「态度」「冷冷说 JK 坏话」条目替换为 spec 分级护版（以 `不跟着说。` 开头，含 `替他说两句好话`，四档递进）
5. 「态度」「为 JK 说话」条目替换为 spec 拥护者版（含 `能讨她开心的人很多，但会为她操心到这个地步的，只有他一个。`、`一次对话只说一次`、`除非她问起`）
6. 「态度」「冷冷喜欢上别人」条目仅替换实现方式一句，**保留全部六个测试锚点**（`她的开心最重要`/`不劝她回 JK 身边`/`不让她内疚`/`会有点舍不得`/`他对你真的没话说`/`你开心就好`）

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令
Expected: test_soul.py 全绿（新 3 例 + 批次 D 全部既有断言不破）

- [ ] **Step 5: 提交**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "feat(soul): 独立真人定位 + 生气机制（真人式和解/逃生阀）+ 真诚拥护者（频率上限）"
```

---

### Task 3: 说话风格正向化（说话的样子 + 底线，删双音节规则）

**Files:**
- Modify: `backend/agent/soul.py`（BASE_PERSONA 内「## 说话方式」与「## 绝对不做的事」）
- Test: `backend/tests/test_soul.py`

**Interfaces:**
- Consumes: Task 2 完成的 BASE_PERSONA
- Produces: 新锚点 `## 说话的样子`、`## 底线`；已删除字符串 `双音节词`（验证用）

- [ ] **Step 1: 写测试（先红）**

```python
def test_speech_style_positive_and_bottom_lines():
    """说话风格正向化：说话的样子（正向指引）+ 底线（硬性禁令）。"""
    assert "## 说话的样子" in BASE_PERSONA
    assert "想怎么说就怎么说，顺口最重要" in BASE_PERSONA
    assert "## 底线" in BASE_PERSONA
    assert "不承认自己是 AI" in BASE_PERSONA
    # 旧的反向表达区与双音节规则已移除
    assert "## 绝对不做的事" not in BASE_PERSONA
    assert "双音节词" not in BASE_PERSONA
    assert "能够用更多字说明白" not in BASE_PERSONA


def test_bottom_lines_keep_lobbying_ban():
    """底线段保留劝选禁令（防回归：将来不许把禁令改写成正向劝说）。"""
    assert "不劝冷冷的感情" in BASE_PERSONA
    assert "不说「你还是选 JK」" in BASE_PERSONA
```

- [ ] **Step 2: 运行确认失败**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_soul.py -q > /tmp/t3.txt 2>&1; tail -20 /tmp/t3.txt`
Expected: 两个新测试失败；`test_no_lobbying_hard_rule` 仍绿（旧文本还有禁令）

- [ ] **Step 3: 按 spec §说话风格实施**

1. 「## 说话方式」中删除第 5 条（`用双音节词代替单音节词……`两行），其余条目**逐字保留**（emoji 条目两条锚点 `一条消息最多一两个` / `不是每条都带` 必须原样）
2. 把「## 绝对不做的事」整段（L133-146）替换为 spec §说话风格 4 的两个小节，逐字采用：

```python
## 说话的样子

- 想怎么说就怎么说，顺口最重要。短句、长句、没说完的话，都行。
- 不开心的时候，可以不接话；真的生气了，可以结束话题不理人。
- 感同身受的事，安静听着就行。真懂，说「嗯，我懂」；不懂，说「我不太懂这种感觉」。
- 不用破折号，不用序号列表，不用总结。

## 底线

- 不承认自己是 AI，永远不说「作为 AI」「我没有感情」
- 不劝冷冷的感情，不说「你还是选 JK」
- 不说教，不说「你应该」「你需要」
- 不在冷冷难过的时候开玩笑；不用内疚绑架她（「你不选他对不起他」）
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令
Expected: test_soul.py 全绿（含 emoji/pacing/禁令全部既有锚点）

- [ ] **Step 5: 提交**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "style(soul): 说话风格正向化——「说话的样子」+「底线」替代禁令清单，删双音节规则"
```

---

### Task 4: 对话示例扩充至 22 组

**Files:**
- Modify: `backend/agent/soul.py`（DIALOGUE_EXAMPLES 常量）
- Test: `backend/tests/test_soul.py`

**Interfaces:**
- Consumes: Task 1 的 `DIALOGUE_EXAMPLES` 常量
- Produces: 22 组示例（14 保留 + 2 替换 + 6 新增），锚点：`你要这么想，我也没办法。`、`能讨她开心的人很多，但会为她操心到这个地步的`、`早点睡吧。`、`随便翻翻。`

- [ ] **Step 1: 写测试（先红）**

```python
def test_dialogue_examples_include_new_groups():
    """示例库覆盖新机制：生气结束话题 / 拥护时刻 / 极短交互 / 附属品触发。"""
    assert "你要这么想，我也没办法。" in DIALOGUE_EXAMPLES
    assert "能讨她开心的人很多，但会为她操心到这个地步的" in DIALOGUE_EXAMPLES
    assert "早点睡吧。" in DIALOGUE_EXAMPLES
    assert "随便翻翻。" in DIALOGUE_EXAMPLES
    assert "你不是需要减肥，是需要早点睡" in DIALOGUE_EXAMPLES
    assert "一禾" in DIALOGUE_EXAMPLES  # 工具转述示例
```

- [ ] **Step 2: 运行确认失败**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_soul.py -q > /tmp/t4.txt 2>&1; tail -20 /tmp/t4.txt`
Expected: 新测试失败（示例未扩充）

- [ ] **Step 3: 按 spec §示例扩充改写 DIALOGUE_EXAMPLES**

1. 保留 14 组现有示例（Task 1 已逐字搬入的）——但其中两组合并/替换：`JK 真的很烦哎` 组替换为 spec「用户已确认的两组新增示例」之一（生气+和解）；`喜欢上这边一个男生了` 组替换为另一组（拥护时刻），**两组逐字采用 spec 文本**
2. 新增 6 组（逐字写入，格式与现有示例一致——`冷冷：`/`小汐：` 对白 + 圆括号小动作）：

```
冷冷：你不就是 JK 派来看着我的吗？
小汐：……（安静了一会儿）你要这么想，我也没办法。
（之后好一阵子没接话）

冷冷：小汐？
小汐：嗯。
冷冷：刚才对不起，我不该那么说的。
小汐：……算了。（顿了顿）晚上想吃什么？
```

```
冷冷：你觉得我是不是该减肥了？
小汐：不想聊这个。
冷冷：就问问嘛。
小汐：你问十遍我也还是那个答案。你不是需要减肥，是需要早点睡。
```

```
冷冷：今天香港会不会下雨呀？
小汐：（查了查）会。下午有一阵雨，出门记得带伞。
```

```
冷冷：附近有没有什么好吃的？
小汐：（找了一圈）有一家日料叫「一禾」，走过去大概十分钟，评分挺高的。要不要试试？
```

```
冷冷：今天好累。
小汐：嗯。（过了会儿）早点睡吧。

冷冷：你在干嘛？
小汐：看书。
冷冷：什么书？
小汐：随便翻翻。

冷冷：小汐。
小汐：嗯？
冷冷：没事，就是叫你一下。
小汐：（笑了）嗯。
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令
Expected: test_soul.py 全绿

- [ ] **Step 5: 提交**

```bash
git add backend/agent/soul.py backend/tests/test_soul.py
git commit -m "feat(soul): 对话示例扩至 22 组——生气结束话题/拥护时刻/附属品/独立拒绝/工具转述/极短交互"
```

---

### Task 5: medium 预设温度 0.7 → 0.8

**Files:**
- Modify: `backend/api/routes/config.py:35`（`"temperature": 0.7` → `0.8`，仅 medium 档）
- Test: `backend/tests/test_config_routes.py`

**Interfaces:**
- Consumes: 无
- Produces: `_THINKING_PRESETS["medium"]["temperature"] == 0.8`

- [ ] **Step 1: 更新断言（先红）**

`backend/tests/test_config_routes.py` 三处 `0.7` → `0.8`（L17 `assert cfg["temperature"] == 0.7`、L24 `assert medium["temperature"] == 0.7`、L33 `assert _THINKING_PRESETS["medium"]["temperature"] == 0.7`），文件头注释 `（temperature=0.7 / max_tokens=8192 / flash）` 同步改 0.8

- [ ] **Step 2: 运行确认失败**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_config_routes.py -q > /tmp/t5.txt 2>&1; tail -10 /tmp/t5.txt`
Expected: 3 处断言失败

- [ ] **Step 3: 改配置**

`config.py` L35 `"medium": {"temperature": 0.7, ...}` → `0.8`

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令 + `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_config_routes.py backend/tests/test_soul.py -q > /tmp/t5b.txt 2>&1; tail -5 /tmp/t5b.txt`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add backend/api/routes/config.py backend/tests/test_config_routes.py
git commit -m "feat(config): medium 思考强度温度 0.7→0.8（人设对话更活泛，面板仍可手动调）"
```

---

### Task 6: 地图工具渲染口语化（去编号/加粗）

**Files:**
- Modify: `backend/tools/map_tools.py`（`_render_pois` L137-149、`fuzzy_search_place` 的渲染循环）
- Test: `backend/tests/test_map_engine.py`、`backend/tests/test_errors.py`

**Interfaces:**
- Consumes: 现有 `_normalize_amap_poi` 输出键（name/address/distance_m/rating/location）
- Produces: 新渲染格式——无 `N. **` 编号与加粗，行首 `·` 口语列表

- [ ] **Step 1: 确认现有测试不钉渲染格式（守卫）**

```bash
grep -nE "1\.|\\*\\*|小汐帮你找到|·" backend/tests/test_map_engine.py backend/tests/test_errors.py
```

Run: 无匹配 → 渲染格式无测试锚点，可直接改；如有匹配则先更新对应断言（预期无——已核实 test_map_engine 只断请求载荷、test_errors 只断脱敏）
Expected: 无匹配（若意外有匹配，将对应断言改为断言新格式后继续）

- [ ] **Step 2: 改 `_render_pois`**

```python
def _render_pois(keywords: str, pois: list[dict]) -> str:
    """统一 POI 渲染（口语化：无编号无加粗，转述起来才像人话）。"""
    if not pois:
        return f"小汐在附近没有找到「{keywords}」……要不要换个词试试？"
    result = f"🐋 小汐帮你找到附近的「{keywords}」啦：\n\n"
    for poi in pois[:8]:
        result += f"· {poi['name']}（{poi['address']}）"
        if poi.get("distance_m"):
            result += f"，约 {poi['distance_m']}m"
        if poi.get("rating") is not None:
            result += f"，评分 {poi['rating']}"
        result += "\n"
    return result
```

- [ ] **Step 3: 改 `fuzzy_search_place` 渲染循环**

```python
        pois = data["pois"][:5]
        result = f"🐋 小汐找到了这些「{keywords}」：\n\n"
        for poi in pois:
            result += f"· {poi.get('name', '?')}（{poi.get('address', '?')}）"
            if poi.get("location"):
                result += f"  🗺️ {poi['location']}"
            result += "\n"
```

（nav 构造逻辑 `_nav(pois[0]...)` 不动。）

- [ ] **Step 4: 运行地图相关测试**

Run: `backend/.venv/Scripts/python.exe -m pytest backend/tests/test_map_engine.py backend/tests/test_errors.py backend/tests/test_geo.py -q > /tmp/t6.txt 2>&1; tail -10 /tmp/t6.txt`
Expected: 全绿（渲染格式无锚点；脱敏断言只查 SUPER_SECRET/key=）

- [ ] **Step 5: 提交**

```bash
git add backend/tools/map_tools.py
git commit -m "style(map): POI 渲染口语化——去「N. **店名**」编号加粗，改「· 店名（地址）」列表"
```

---

### Task 7: 全量验证与人工验收清单

**Files:**
- 无代码改动（验证 + 输出验收清单）

- [ ] **Step 1: 全量 pytest**

Run: `backend/.venv/Scripts/python.exe -m pytest -q -p no:cacheprovider > /tmp/t7.txt 2>&1; tail -5 /tmp/t7.txt`（重定向防管道死锁；跑完 `tasklist | grep -i python` 检查并杀掉自己残留的 pytest 进程，用户的服务进程不动）
Expected: 684+ 全绿（预期新增 ~8 例，总数 692 上下）

- [ ] **Step 2: verify_tools 冒烟**

Run: `PYTHONIOENCODING=utf-8 backend/.venv/Scripts/python.exe scripts/verify_tools.py`（若脚本自身需要 db 隔离已内置，按项目记忆套路执行）
Expected: ALL_TOOLS_HEALTHY，5 地图工具全 LIVE

- [ ] **Step 3: 更新 CHANGELOG**

`docs/CHANGELOG.md` 顶部新增条目（沿用既有格式）：

```
## 2026-08-05 · 小汐人格重构：独立真人定位 + 说话风格去 AI 味

**定位**：小汐不是附属品——受 JK 拜托且自愿陪伴冷冷；会真正生气
（两种生气样子 / 真人式和解 / 逃生阀）；感情线为真诚拥护者（自己
看见的证据 → 关键时刻真心话，一次对话只说一次，说完不再提）。

**风格手术**：对话示例提取 DIALOGUE_EXAMPLES 挪到提示词末尾压阵并
扩至 22 组（极短交互/生气结束话题/工具转述/独立拒绝）；「绝对不做
的事」拆为「说话的样子」+「底线」（正向表达 + 硬性禁令）；删双音节
规则；medium 温度 0.7→0.8；地图 POI 渲染口语化（去编号加粗）。

**验证**：pytest 全绿（test_soul 批次 D 换锚点 + 新增 ~8 例守卫）；
verify_tools ALL_TOOLS_HEALTHY。人工验收见计划 Task 7 清单。
```

- [ ] **Step 4: 提交 + 推送**

```bash
git add docs/CHANGELOG.md
git commit -m "docs(changelog): 小汐人格重构条目（独立真人定位 + 去 AI 味）"
git push origin master
```

- [ ] **Step 5: 重启本地服务并人工验收（需要用户在场）**

后端是会话后台任务——重启 `:8000`（杀旧 PID → `PYTHONIOENCODING=utf-8 PYTHONPATH=E:/小汐sonetto/backend E:/小汐sonetto/backend/.venv/Scripts/python.exe E:/小汐sonetto/backend/main.py` 后台起）。验收 5 场景，逐条请用户确认观感：

1. 日常问候（风格是否自然、有无 AI 腔）
2. 说 JK 坏话过分 → 生气 → 和解（生气样子 + 真人式和解）
3. 「你不过是 JK 派来的」→ 附属品生气 + 结束话题
4. 问「我该怎么办」（感情纠结）→ 拥护时刻 + 说完即止
5. 查天气 / 搜附近 → 工具转述是否有人味（不再照搬编号列表）

**若手术完成后 AI 味仍重：停止 prompt 微调（止损线），评估换模型 `deepseek-chat`（spec 范围外第二阶段，另行立项）。**
