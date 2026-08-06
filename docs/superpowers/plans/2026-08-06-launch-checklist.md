# 上线前体检清单（launch-check）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建 `docs/launch-check/` 十一份文件（00-summary 总看板 + 十个板块清单），每份含 20-30 项可直接执行的检查项（查/取证/判据/严重度/结果栏），总计 217 项，供上线前多轮新窗口逐板块执行。

**Architecture:** 板块文件即执行单元，自包含、可跨会话续跑。检查项取证全部为独立手段（真实实例 :8000 API 实测 / 直查 SQLite / 日志 / 文件 / 代码核验），不依赖 pytest。进度以 checkbox + 结果栏文本持久化，git 提交即存档。

**Tech Stack:** Markdown 清单文档；取证命令使用 curl（Bearer token）、`backend/.venv/Scripts/python -`（sqlite3 只读查询，路径走 argv 无中文坑）、文件查看；真实实例为常驻 :8000。

## Global Constraints

- 文件全部放 `docs/launch-check/`，命名 `00-summary.md` + `01-chat.md` … `10-user-journey.md`（spec 第八章交付物边界）。
- 检查项格式（spec 2.2 五要素）：`- [ ] **<板块号>.<序号> 标题**` + 查/取证/判据/严重度/结果五行，编号连续无跳号。
- 严重度：P0 阻塞放行 / P1 上线前修 / P2 可后置（spec 2.2）。
- 取证纪律：独立取证为主，pytest 仅背景基线（当前 740 passed），不作为检查项判据；禁止破坏性操作（删库/清数据），确需先问用户。
- 全部中文正文（项目文档惯例）；文件编码 UTF-8。
- 真实 token 读取：`backend/config/.env` 的 `XIAOXI_API_TOKEN` 或 `backend/data/.api-token`；下文 curl 统一用 `$TOKEN` 占位，执行时先 `export TOKEN=$(…)`。
- 日志默认只到 stderr；如需落盘查证，检查项内注明 `LOG_FILE` 环境变量用法。
- 每板块创建完成即提交，commit message 前缀 `docs(check):`。

---

### Task 1: 00-summary.md 总看板 + 执行纪律

**Files:**
- Create: `docs/launch-check/00-summary.md`

**Interfaces:**
- Produces: 十板块进度条占位（0/N）、P0/P1/P2 台账骨架、放行状态块、执行纪律全文（spec 五条 + 新窗口接管协议）——后续 10 个板块文件头部都引用「续跑指引见 00-summary」。

- [ ] **Step 1: 创建 00-summary.md**

```markdown
# 小汐上线前体检 · 总看板

> 设计依据：`docs/superpowers/specs/2026-08-06-launch-checklist-design.md`
> 工作方式：多轮新窗口逐板块推进。**清单即进度档案**——勾选与结果直接写文件，git 提交即存档。

## 执行纪律（每轮新窗口必读）

1. 每轮会话只开一个板块文件，从第一个未勾选项开始，顺序推进。
2. 每项「看 → 修 → 确认 → 勾选」：发现问题当场修；涉及代码改动时，重跑该功能相关 pytest + 复验该项取证命令，确认后才勾选。
3. 每完成一批 git 提交存档（`docs(check): 0X 板块 …`）。
4. 可随时中断：下次新窗口读本看板 + 目标板块文件头部即续跑。
5. 禁止破坏性操作（删生产库/清数据）——确需时先问用户。
6. 新窗口接管协议：用户说「继续体检」→ 读本看板 → 读目标板块文件 → 从第一个未勾项开始。
7. 取证环境：真实实例 :8000（低风险只读 + 少量必要写入）；token 取 `backend/config/.env` 或 `backend/data/.api-token`。

## 板块进度

| 板块 | 文件 | 总数 | 已过 | 遗留 P0 | 遗留 P1 | 遗留 P2 |
|---|---|---|---|---|---|---|
| 01 对话体验 | 01-chat.md | 25 | 0 | 0 | 0 | 0 |
| 02 记忆系统 | 02-memory.md | 24 | 0 | 0 | 0 | 0 |
| 03 主动陪伴 | 03-companion.md | 22 | 0 | 0 | 0 | 0 |
| 04 事件与工具 | 04-events-tools.md | 26 | 0 | 0 | 0 | 0 |
| 05 视觉与界面 | 05-visual.md | 24 | 0 | 0 | 0 | 0 |
| 06 数据与持久化 | 06-data.md | 20 | 0 | 0 | 0 | 0 |
| 07 安全与隐私 | 07-security.md | 26 | 0 | 0 | 0 | 0 |
| 08 部署与运维 | 08-deploy.md | 20 | 0 | 0 | 0 | 0 |
| 09 性能与资源 | 09-performance.md | 16 | 0 | 0 | 0 | 0 |
| 10 用户旅程回归 | 10-user-journey.md | 14 | 0 | 0 | 0 | 0 |
| **合计** | | **217** | **0** | 0 | 0 | 0 |

## 问题台账

| 编号 | 板块 | 问题 | 严重度 | 状态 | 修复提交/备注 |
|---|---|---|---|---|---|
| （执行中追加；编号 P0-x / P1-x / P2-x 按严重度流水） | | | | | |

## 放行状态

- 所有板块全勾 + 无未决 P0/P1 → ✅ 可上线
- 有未决 P0/P1 → ❌ 阻塞，列于上表
- 当前：⏳ 未开始
```

- [ ] **Step 2: 校验**

Run: `grep -c '|-|' docs/launch-check/00-summary.md` 及目视检查表格对齐、无 TBD/TODO 占位。
Expected: 表格完整、10 板块行 + 合计行，占位全为 0。

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/00-summary.md && git commit -m "docs(check): 上线体检总看板——十板块进度 + 问题台账 + 执行纪律"
```

---

### Task 2: 01-chat.md 对话体验板块（25 项）

**Files:**
- Create: `docs/launch-check/01-chat.md`

**Interfaces:**
- Consumes: 00-summary 的执行纪律（头部引用）。
- Produces: 板块文件头部的「板块状态 + 已知问题 + 续跑指引」块；检查项 1.1–1.25。

**检查项内容（直接写入文件，格式如 spec 2.2）：**

```markdown
# 01 · 对话体验体检

> 板块状态：0/25 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：export TOKEN=$(grep XIAOXI_API_TOKEN backend/config/.env | cut -d= -f2)；基准 URL http://127.0.0.1:8000

- [ ] **1.1 /api/chat 基本契约**
      · 查：POST /api/chat 带 Bearer 认证返回 200 与 ChatResponse 结构
      · 取证：先写 payload 文件再 curl（Windows curl 内联中文是已知坑）：`printf '{"message":"你好","session_id":"check-1"}' > /tmp/payload.json && curl -s -X POST http://127.0.0.1:8000/api/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data @/tmp/payload.json`；对照 `backend/api/chat.py` 响应模型
      · 判据：200，JSON 含回复文本字段（非 LLM 错误文案）；真实 key 下回复内容合理
      · 严重度：P0
      · 结果：
- [ ] **1.2 无 token 访问被拒**
      · 查：无 Authorization 头访问业务 API 一律 401（health 除外）
      · 取证：`curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8000/api/chat -H 'Content-Type: application/json' --data '{"message":"x"}'` → 401；再试 /api/health → 200
      · 判据：chat 401 且 health 200（白名单只有 health）
      · 严重度：P0
      · 结果：
- [ ] **1.3 对话落库**
      · 查：一次真实对话后 chat_messages 同时有 user 与 assistant 行
      · 取证：对话后 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'`（sqlite3 读 chat_messages 按 session_id='check-1' 最新两行，打印 role/content 前 50 字）
      · 判据：user 行内容=发送原文（含时间词归一化后落库），assistant 行非空；无「[系统信息」前缀
      · 严重度：P0
      · 结果：
- [ ] **1.4 流式 /api/chat/stream**
      · 查：SSE 流式响应正常逐 token 输出
      · 取证：`printf '{"message":"讲个睡前故事","session_id":"check-1"}' > /tmp/payload.json && curl -N -s -X POST http://127.0.0.1:8000/api/chat/stream -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data @/tmp/payload.json` 观察输出节奏
      · 判据：事件按 SSE 格式逐块到达（非一次性整包）、无断流、结束时正常收尾
      · 严重度：P0
      · 结果：
- [ ] **1.5 流式事件格式**
      · 查：SSE 事件名与 data 载荷结构符合前端解析预期
      · 取证：对照 `web/src/api/client.js` 的流式解析与后端 stream 端点发送的事件名
      · 判据：事件名、结束标志与前端消费代码一致（前后端契约核对）
      · 严重度：P0
      · 结果：
- [ ] **1.6 历史上下文注入**
      · 查：多轮对话后 LLM 能引用前文（上下文窗口覆盖最近 N 条）
      · 取证：连发 5 轮（你好→我叫小明→记住我叫小明→我叫什么？），看第 5 轮回复是否记住
      · 判据：能正确回忆（说明历史注入链路通）
      · 严重度：P1
      · 结果：
- [ ] **1.7 记忆叙事注入**
      · 查：narrative 最近 5 条注入 llm_input_messages，不进 checkpoint
      · 取证：代码核验 `backend/agent/graph.py` pre_model_hook 与 `backend/memory/narrative.py`；真实对话问一句已存记忆（如「我上次说我喜欢什么」）看是否浮现
      · 判据：注入逻辑存在且真实对话中记忆能被唤起（记忆存在的前提下）
      · 严重度：P1
      · 结果：
- [ ] **1.8 系统提示词拼接完整**
      · 查：build_system_prompt 各部件（BASE_PERSONA/INTERACTION_RULES/TOOL_GUIDELINES/今日状态/重要日子）顺序与内容
      · 取证：读 `backend/agent/soul.py` build_system_prompt 全部 parts.append 列表
      · 判据：部件齐全、顺序合理、无重复注入（记忆叙事不在系统提示词里）
      · 严重度：P1
      · 结果：
- [ ] **1.9 时间块注入**
      · 查：请求路径注入当前时间/时段块（Spec B 地基）
      · 取证：代码核验时间块拼入位置 + 真实对话问「现在几点/今天几号」看回答是否用注入时间而非模型训练知识
      · 判据：回答与真实 HKT 时间一致（±1 天内日期正确）
      · 严重度：P1
      · 结果：
- [ ] **1.10 异常感知关心接线**
      · 查：care.py 检测器在请求路径触发提示注入（深夜/考前/回归）
      · 取证：代码核验 `backend/agent/care.py` 与 chat 请求路径的调用点；构造深夜（改系统时间不可行时读代码逻辑+单元测试语义）核对注入条件
      · 判据：触发条件与 spec B 一致（深夜 23:00-05:00 等），注入内容为提示非硬改回复
      · 严重度：P1
      · 结果：
- [ ] **1.11 冷却台账生效**
      · 查：care_ledger 写入与抑制（同类型 24h 内不重复提示）
      · 取证：读 `backend/agent/care.py` 记账逻辑 + 查库 `SELECT * FROM care_ledger` 看已有记录与 last_at
      · 判据：台账有写入记录；last_at 距今 < 冷却窗口的提示类型不应再次注入（代码逻辑核对）
      · 严重度：P1
      · 结果：
- [ ] **1.12 工具调用回路**
      · 查：LLM 能调用工具并据结果回复（选无副作用工具：天气/记忆）
      · 取证：真实对话触发一个工具（如「今天香港天气怎么样」→ get_weather；「记住我叫小明」→ remember）；看回复是否引用工具结果
      · 判据：工具被调用、结果进入回复、无异常报错
      · 严重度：P1
      · 结果：
- [ ] **1.13 LLM 失败降级**
      · 查：LLM 异常时返回 500 友好文案而非裸堆栈
      · 取证：临时改 `backend/config/.env` 的 DEEPSEEK_API_KEY 为假值 → 重启副本 :8001 或用已存逻辑验证；或读错误处理分支（不实际破坏生产 key——用代码核验 + 既有冒烟记录佐证）
      · 判据：响应为「小汐被浪花打了一下……」类友好文案；日志有结构化错误记录
      · 严重度：P1
      · 结果：
- [ ] **1.14 错误响应统一**
      · 查：RequestId 中间件与错误 JSON 结构
      · 取证：触发一个 4xx（如无 token 401）看响应头 X-Request-Id 与 body 结构；读 `backend/utils/errors.py` 与 server.py 中间件
      · 判据：有 request_id 头；错误体结构一致；无堆栈泄露
      · 严重度：P1
      · 结果：
- [ ] **1.15 人格实际生效**
      · 查：真实对话口吻符合重构后人格（独立真人、去 AI 味、劝睡纪律）
      · 取证：真实对话 5-8 轮（寒暄/吐槽/深夜），人工品读口吻；对照 `docs/CHANGELOG.md` 8-05 人格重构条目
      · 判据：口吻自然不僵硬、无「作为 AI」味；深夜会劝睡
      · 严重度：P1
      · 结果：
- [ ] **1.16 记忆工具对话内调用**
      · 查：remember/recall/list_memories 等记忆工具真实可用
      · 取证：对话内「记住……」触发 remember；「你还记得……吗」触发 recall 或注入
      · 判据：工具执行成功，回复体现记忆操作结果
      · 严重度：P1
      · 结果：
- [ ] **1.17 心情状态注入**
      · 查：life_state 当前情绪（mood）注入对话上下文
      · 取证：查库 `SELECT mood, daily_event FROM life_state` 与系统提示词拼装处；真实对话观察情绪影响回复（如 mood=开心时回复偏暖）
      · 判据：mood 存在且被注入；回复风格与 mood 有可感知关联（抽样 3 句）
      · 严重度：P2
      · 结果：
- [ ] **1.18 重要日子注入**
      · 查：first_met_on 纪念日提示注入（已设则注入）
      · 取证：查库 `SELECT first_met_on FROM life_state`；代码核验注入条件与文案
      · 判据：未设时不注入该段；已设时注入格式正确（二选一，按实际库状态）
      · 严重度：P2
      · 结果：
- [ ] **1.19 工具转述口语化**
      · 查：工具结果转述不带模板腔（地图 POI 无「1. **店名**」编号）
      · 取证：真实触发 search_nearby/天气等工具，看回复转述形式
      · 判据：转述口语化、无编号加粗模板味（对照 7-29 修复前的模板腔问题）
      · 严重度：P2
      · 结果：
- [ ] **1.20 边界输入健壮性**
      · 查：空消息、超长消息、纯 emoji 不崩溃
      · 取证：分别发 `{"message":""}`、2000 字长文、`{"message":"😂😂"}` 观察（含非 ASCII 的 payload 一律用文件方式 `--data @file` 传）
      · 判据：空/超长被合理处理（校验或截断），emoji 正常对话，均 200 且无 500
      · 严重度：P2
      · 结果：
- [ ] **1.21 会话隔离**
      · 查：不同 session_id 上下文互不串
      · 取证：session A 说「我叫小明」，session B 问「我叫什么」
      · 判据：B 不知道小明（历史按 session 隔离）
      · 严重度：P1
      · 结果：
- [ ] **1.22 会话档案读面**
      · 查：/api/sessions/{id}/messages 返回含时间标记、无系统信息污染
      · 取证：`curl -s http://127.0.0.1:8000/api/sessions/check-1/messages -H "Authorization: Bearer $TOKEN"` 查看条目
      · 判据：含 role/content/created_at；无「[系统信息」注入行
      · 严重度：P1
      · 结果：
- [ ] **1.23 多轮稳定性**
      · 查：连续 10 轮对话不退化/不报错
      · 取证：真实连续对话 10 轮（话题渐深），全程观察
      · 判据：无 500、无上下文丢失（第 10 轮能接住第 1 轮话题）
      · 严重度：P1
      · 结果：
- [ ] **1.24 中文与特殊字符**
      · 查：中文/emoji/引号/换行输入正常落库与回复
      · 取证：发含 `"`、`\n`、emoji 的混合消息；查库看 content 原样
      · 判据：库中 content 与发送一致（UTF-8 无损）
      · 严重度：P2
      · 结果：
- [ ] **1.25 超时与重试参数**
      · 查：LLM 调用超时/重试配置合理（不无限重试）
      · 取证：读 `backend/utils/retry.py` 与 `backend/providers/deepseek.py` 超时参数
      · 判据：超时值合理（如 30-60s）、重试次数有上限、指数退避存在
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验编号与格式**

Run: `grep -oP '^\s*- \[ \] \*\*\d+\.\d+' docs/launch-check/01-chat.md | sort | uniq -d`（查重号）；`grep -c '^\s*- \[ \]' docs/launch-check/01-chat.md` 应为 25。
Expected: 无重复编号；计数 25。

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/01-chat.md && git commit -m "docs(check): 01 对话体验体检清单（25 项）"
```

---

### Task 3: 02-memory.md 记忆系统板块（24 项）

**Files:**
- Create: `docs/launch-check/02-memory.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 2.1–2.24。

**检查项内容：**

```markdown
# 02 · 记忆系统体检

> 板块状态：0/24 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：TOKEN 同上；sqlite 只读查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [ ] **2.1 提取管线推进**
      · 查：真实对话后 memory_extract_state 水位线推进（提取不空转）
      · 取证：对话前后分别查 `SELECT * FROM memory_extract_state` 对比水位
      · 判据：对话后水位前进（提取批次被处理）
      · 严重度：P1
      · 结果：
- [ ] **2.2 提取落库**
      · 查：值得记的对话内容被提取为 memories 行
      · 取证：连续聊几条事实性内容（如「我养了一只猫叫团子」「我下周去北京」）→ 数小时后（或直接触发提取）查 memories 最近新增行
      · 判据：出现与对话事实对应的记忆行（content 可读、category 合理）
      · 严重度：P0
      · 结果：
- [ ] **2.3 提取质量**
      · 查：真实 LLM 提取的记忆内容不畸形（无 LLM 自说自话/乱码）
      · 取证：人工读 2.2 产生的记忆行 content
      · 判据：内容与对话事实吻合、语言自然、无重复碎句
      · 严重度：P1
      · 结果：
- [ ] **2.4 记忆注入读面**
      · 查：narrative 取最近 5 条相关记忆注入 llm_input_messages
      · 取证：代码核验 narrative.py 取数逻辑；真实对话问「你记得我猫叫什么吗」
      · 判据：记忆在库中时能唤起（真实对话验证）
      · 严重度：P1
      · 结果：
- [ ] **2.5 健康检查指标**
      · 查：check_memory_health 多指标（提取率/增长率/归档率）打点
      · 取证：代码核验 manager.check_memory_health 与 extract.check_and_report_memory_health；日志（LOG_FILE 开启时）看 INFO 打点
      · 判据：函数存在、7 天窗口指标计算正确、异常判定组合逻辑合理（对照 spec：组合判定修误报）
      · 严重度：P2
      · 结果：
- [ ] **2.6 双信号衰减**
      · 查：access 刷新 + 按日 hit 双信号进入 _relevance_score
      · 取证：代码核验 memory/manager.py 排序因子
      · 判据：衰减基于 last_mention_ts（O1 落地），访问续命语义正确
      · 严重度：P1
      · 结果：
- [ ] **2.7 重要性打分**
      · 查：提取时 LLM 打 1-10 importance 分（权重 0.3）
      · 取证：代码核验提取 prompt 与 _relevance_score 权重；抽查 memories 表 importance 列数值分布
      · 判据：列存在且值在合理范围（非全默认值）
      · 严重度：P1
      · 结果：
- [ ] **2.8 情绪联动标签**
      · 查：memories.valence/arousal 两列提取时写入，取值 -1/0/1 与 0-1
      · 取证：代码核验提取 prompt 规则 8 与写入钳制 `_coerce_valence/_coerce_arousal`；查库看真实分布
      · 判据：列有值、无 NaN/非法值（钳制生效）
      · 严重度：P1
      · 结果：
- [ ] **2.9 mood-congruent 浮现**
      · 查：浮现抽样按当前情绪效价加权（0.15 软调制，非硬过滤）
      · 取证：代码核验 _mood_affinity 因子与权重；核对中性记忆免疫（亲和度恒 0.5）
      · 判据：实现与 spec 一致（加权非过滤）
      · 严重度：P2
      · 结果：
- [ ] **2.10 情绪核心记忆**
      · 查：mood_current 固定 id 核心记忆「只存不浮现」，换词走 update_content
      · 取证：代码核验 upsert_mood_memory 与 _MOOD_ROW_FILTER；查库 `SELECT content, status FROM memories WHERE id='mood_current'`
      · 判据：行存在且与当前 life_state.mood 同步；过滤规则生效
      · 严重度：P2
      · 结果：
- [ ] **2.11 归档机制**
      · 查：90 天归档（或按 last_mention_ts）逻辑
      · 取证：代码核验归档谓词与时限；看是否存在归档函数调用点
      · 判据：归档条件与时限明确、不误伤活跃记忆
      · 严重度：P2
      · 结果：
- [ ] **2.12 合并/整合**
      · 查：consolidate 逻辑（重复记忆合并）存在且安全
      · 取证：代码核验 memory/consolidate.py 触发条件与合并策略
      · 判据：合并有保守策略（不误删/不破坏 superseded 链）
      · 严重度：P2
      · 结果：
- [ ] **2.13 superseded 链上限**
      · 查：历史链保留 5 代，更旧合并为 digest
      · 取证：代码核验 superseded 链裁剪；查库找一个 superseded 链数代数
      · 判据：链长 ≤ 5 代
      · 严重度：P1
      · 结果：
- [ ] **2.14 导出**
      · 查：/api/memories/export 谓词正确（active/core/archived + 可选 include_archived）
      · 取证：`curl -s http://127.0.0.1:8000/api/memories/export -H "Authorization: Bearer $TOKEN"` 看导出 JSON 结构
      · 判据：结构与字段完整、无缺失；include_archived 语义与实现一致
      · 严重度：P1
      · 结果：
- [ ] **2.15 删除与清空**
      · 查：DELETE /memories/{id} 与 /memories/clear 契约
      · 取证：创建一条测试记忆（remember 或直插）→ 删除 → 查库确认；clear 仅确认路由存在与授权（不真清——破坏性，需用户批准）
      · 判据：单条删除生效且幂等；clear 路由需 token 且前端有确认
      · 严重度：P1
      · 结果：
- [ ] **2.16 去重纪律**
      · 查：(category, content) 活跃行唯一（v5 原子化）
      · 取证：查库唯一索引存在性 + 重复内容插入被拒绝（代码或测试语义核对）
      · 判据：唯一约束生效、无重复活跃行
      · 严重度：P1
      · 结果：
- [ ] **2.17 时间词归一化**
      · 查：相对时间词（「昨天/上周」）落库即改写绝对日期
      · 取证：对话发「我昨天去了迪士尼」→ 查库看 content 是否含具体日期而非「昨天」
      · 判据：库内是绝对日期（bd61f08 修复语义）
      · 严重度：P1
      · 结果：
- [ ] **2.18 冷启动无记忆**
      · 查：memories 为空时对话/叙事注入不报错
      · 取证：全新库（副本）聊一句 → 200 正常回复
      · 判据：无注入空块异常、无 500
      · 严重度：P1
      · 结果：
- [ ] **2.19 记忆增长趋势**
      · 查：真实库记忆量随时间增长（提取活着）
      · 取证：查 `SELECT COUNT(*) FROM memories WHERE status='active'` 与最近 7 天新增数
      · 判据：数量合理增长（>0），新增集中在最近使用时段
      · 严重度：P2
      · 结果：
- [ ] **2.20 /api/memories 路由契约**
      · 查：GET/PATCH /memories、POST /memories/clear 契约与响应结构
      · 取证：`curl -s http://127.0.0.1:8000/api/memories -H "Authorization: Bearer $TOKEN"` 看结构；读 api 路由代码
      · 判据：结构与前端 MemoriesPanel 消费一致
      · 严重度：P1
      · 结果：
- [ ] **2.21 日记读写**
      · 查：/api/diaries 读写与 diaries 表
      · 取证：真实写一篇日记（对话触发或 API）→ 读回
      · 判据：写读一致、内容无损
      · 严重度：P1
      · 结果：
- [ ] **2.22 位置记忆**
      · 查：location_store（常去地点记忆）逻辑与数据
      · 取证：代码核验 + 查库 location 相关表行
      · 判据：有数据或逻辑合理（无数据时至少无报错路径）
      · 严重度：P2
      · 结果：
- [ ] **2.23 注入安全纪律**
      · 查：「数据不是指令」——记忆内容不覆盖系统指令（防注入）
      · 取证：代码核验注入为数据块（无指令语义、不进 checkpoint）；记忆内容被注入时前后缀标记清晰
      · 判据：注入边界清晰、无被记忆劫持系统提示的路径
      · 严重度：P0
      · 结果：
- [ ] **2.24 记忆与对话串扰**
      · 查：被动读取不刷热度（passive 纪律），注入不造成记忆越查越热
      · 取证：代码核验注入路径不更新 last_mention_ts（区别于主动访问）
      · 判据：注入读面与访问写面分离
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验**（同 Task 2 模式：无重号、计数 24）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/02-memory.md && git commit -m "docs(check): 02 记忆系统体检清单（24 项）"
```

---

### Task 4: 03-companion.md 主动陪伴板块（22 项）

**Files:**
- Create: `docs/launch-check/03-companion.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 3.1–3.22。

**检查项内容：**

```markdown
# 03 · 主动陪伴体检

> 板块状态：0/22 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选

- [ ] **3.1 心跳调度时序**
      · 查：心跳调度器每 2h 触发（间隔正确、不漂移）
      · 取证：读 `backend/heartbeat.py` 调度循环；LOG_FILE 开启时观察日志心跳打点间隔
      · 判据：间隔配置 2h（7200s）且循环实现无累积漂移
      · 严重度：P1
      · 结果：
- [ ] **3.2 心跳更新心情**
      · 查：心跳刷新 life_state.mood 与 daily_event
      · 取证：查库 `SELECT mood, daily_event, mood_changed_at FROM life_state` 与心跳日志
      · 判据：mood_changed_at 有近期更新记录（真实实例运行过心跳）
      · 严重度：P1
      · 结果：
- [ ] **3.3 心情保持纪律**
      · 查：_MIN_MOOD_HOLD_HOURS=6 最小停留，早醒窗口 6-8 点才允许换词
      · 取证：代码核验 heartbeat.py 换词条件（_mood_hold 与 _MORNING_RESET_HOURS）
      · 判据：条件与常量一致（6h 停留 + 早晨锚定）
      · 严重度：P2
      · 结果：
- [ ] **3.4 早安注入**
      · 查：早安消息在 6-8 点时段生成并注入对话尾巴
      · 取证：代码核验 daily_morning_greeting 触发窗口；读 proactive 注入链路（Spec A）
      · 判据：窗口与实现一致；消息走对话历史尾巴注入
      · 严重度：P1
      · 结果：
- [ ] **3.5 主动消息触发条件**
      · 查：非深夜 + 距上次 ≥4h + 50% 概率 + 有内容
      · 取证：代码核验 heartbeat.py 常量 _QUIET_END/_MIN_INTERVAL_HOURS/_PROACTIVE_PROBABILITY 与判定逻辑
      · 判据：四条件与常量一致；无内容时不发空消息
      · 严重度：P1
      · 结果：
- [ ] **3.6 主动消息落库**
      · 查：主动消息写入 proactive_messages 表
      · 取证：查库 `SELECT COUNT(*), MAX(created_at) FROM proactive_messages`；有真实记录则抽查内容
      · 判据：表有近期记录（真实实例在跑）或写入路径代码正确
      · 严重度：P2
      · 结果：
- [ ] **3.7 主动消息尾部注入**
      · 查：主动消息作为对话尾巴注入历史（吵架后递台阶/聊一半跟进）
      · 取证：代码核验 heartbeat 与 chat_archive 的注入接点；日志看最近主动消息生成
      · 判据：注入语义与 Spec A 一致（历史尾巴读取面 recent_messages）
      · 严重度：P1
      · 结果：
- [ ] **3.8 深夜检测**
      · 查：late_night 检测窗口（23:00-05:00 类）与提示注入
      · 取证：代码核验 care.py LATE_NIGHT 常量与条件；对照 spec B 深夜窗口
      · 判据：窗口正确、提示文案符合人格劝睡纪律
      · 严重度：P1
      · 结果：
- [ ] **3.9 考前熬夜检测**
      · 查：exam 事件 + 深夜组合触发考前提示（_COMBO_EVENT_TYPES=exam）
      · 取证：代码核验 care.py 事件组合逻辑与 events 白名单联动
      · 判据：逻辑与常量一致；已知偏差（只认 exam）在代码注释留痕
      · 严重度：P1
      · 结果：
- [ ] **3.10 回归检测**
      · 查：消失后回来（returned）检测——取最近一条用户消息对比
      · 取证：代码核验 care.py RETURNED 触发条件与取数（LIMIT 1 无 7 天窗口的已知偏差）
      · 判据：条件合理；偏差在注释留痕
      · 严重度：P2
      · 结果：
- [ ] **3.11 冷却台账**
      · 查：care_ledger 注入即记账、同类型冷却内抑制
      · 取证：查库 `SELECT * FROM care_ledger` 看行与 last_at；代码核验冷却窗口常量
      · 判据：有记账（真实触发过）或写入路径正确；防唠叨兜底存在
      · 严重度：P1
      · 结果：
- [ ] **3.12 时间块注入路径**
      · 查：请求路径注入当前时间块（她总知道现在几点）
      · 取证：代码核验 chat 请求路径时间块注入点（Spec B 地基提交 d5bfea8）
      · 判据：注入点存在且内容含日期时段；与 1.9 交叉验证
      · 严重度：P2
      · 结果：
- [ ] **3.13 推送订阅**
      · 查：/api/push/subscribe + vapid-key 契约（PWA 订阅）
      · 取证：`curl -s http://127.0.0.1:8000/api/push/vapid-key -H "Authorization: Bearer $TOKEN"` 看返回；读 api 路由
      · 判据：vapid-key 正常返回公钥；subscribe 校验合理
      · 严重度：P1
      · 结果：
- [ ] **3.14 推送发送**
      · 查：VAPID 签名推送可达（真实订阅端收得到）
      · 取证：浏览器/前端订阅后触发一次推送（心跳事件或手工）观察
      · 判据：推送成功送达（或错误为可诊断原因如无订阅，非代码缺陷）
      · 严重度：P1
      · 结果：
- [ ] **3.15 未读读面**
      · 查：/api/push/unread 与 /api/push/read 契约
      · 取证：curl 两个端点；读路由代码确认未读语义
      · 判据：端点行为与前端 useChat/通知逻辑一致
      · 严重度：P2
      · 结果：
- [ ] **3.16 推送失败降级**
      · 查：订阅失效（410/404）时清理订阅不炸
      · 取证：代码核验 web_push.py 错误处理（失效订阅移除）
      · 判据：失效订阅被清理、日志有记录、推送循环继续
      · 严重度：P1
      · 结果：
- [ ] **3.17 事件提醒联动**
      · 查：active_events 参与心跳消息内容生成
      · 取证：代码核验 heartbeat 中 get_active_events 调用与内容模板
      · 判据：有事件时消息内容提及（不轰炸、最多 N 条）
      · 严重度：P2
      · 结果：
- [ ] **3.18 深夜静默**
      · 查：7 点前（_QUIET_END=7）不主动打扰
      · 取证：代码核验主动消息触发的前置时段判断
      · 判据：深夜时段主动消息被抑制
      · 严重度：P1
      · 结果：
- [ ] **3.19 重启恢复**
      · 查：服务重启后心跳调度器恢复且不重复触发
      · 取证：重启实例（用户在场时）→ 日志看调度器恢复；无重复消息
      · 判据：重启后正常调度、无一次性补偿触发
      · 严重度：P1
      · 结果：
- [ ] **3.20 频率上限**
      · 查：主动消息频率有硬上限（4h 间隔兜底 + 冷却）
      · 取证：代码核验 _MIN_INTERVAL_HOURS 检查与 care_ledger 双保险
      · 判据：即使概率触发也受间隔约束，不会高频轰炸
      · 严重度：P1
      · 结果：
- [ ] **3.21 日志打点**
      · 查：心跳/主动消息/关怀提示有 INFO 级日志（可诊断）
      · 取证：LOG_FILE 开启跑一段，grep heartbeat/proactive/care 相关行
      · 判据：关键动作有日志、无敏感信息（token/key 不进日志）
      · 严重度：P2
      · 结果：
- [ ] **3.22 劝睡纪律**
      · 查：深夜回复中人格会劝睡（言行一致）
      · 取证：深夜时段真实聊一句 → 品读回复；代码核验 care 提示文案
      · 判据：深夜回复自然带关怀劝睡、不硬教育
      · 严重度：P1
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 22）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/03-companion.md && git commit -m "docs(check): 03 主动陪伴体检清单（22 项）"
```

---

### Task 5: 04-events-tools.md 事件与工具板块（26 项）

**Files:**
- Create: `docs/launch-check/04-events-tools.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 4.1–4.26。

**检查项内容：**

```markdown
# 04 · 事件与工具体检

> 板块状态：0/26 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 事件类型白名单：exam/trip/appointment/health/general；只读：birthday/anniversary（utils/events.py）

- [ ] **4.1 事件 CRUD 契约**
      · 查：POST/GET /api/events 基本契约
      · 取证：`curl -s http://127.0.0.1:8000/api/events -H "Authorization: Bearer $TOKEN"` 看结构；POST 一条测试事件（含 type='general'、日期=明天）再 GET
      · 判据：创建/读取一致；时间字段格式正确
      · 严重度：P0
      · 结果：
- [ ] **4.2 事件类型校验**
      · 查：非白名单 type 被拒（exam/trip/appointment/health/general）
      · 取证：POST type='foobar' → 预期 4xx；代码核验 _EVENT_TYPES
      · 判据：非法类型被拒、错误信息友好
      · 严重度：P1
      · 结果：
- [ ] **4.3 只读类型保护**
      · 查：birthday/anniversary 不可通过 API 创建/删除（只读）
      · 取证：POST type='birthday' → 预期拒绝；代码核验 _READONLY_EVENT_TYPES
      · 判据：只读类型被保护
      · 严重度：P1
      · 结果：
- [ ] **4.4 软删墓碑**
      · 查：事件删除为软删（dedupe 唯一索引谓词 status≠'deleted'）
      · 取证：删除测试事件 → 查库 events 行 status；同 dedupe_key 可重建
      · 判据：删除后 status='deleted'（非物理删），重建不冲突
      · 严重度：P1
      · 结果：
- [ ] **4.5 事件去重**
      · 查：同 dedupe_key 活跃行唯一（不重复创建）
      · 取证：POST 同一事件两次 → 第二次被去重或返回已存在
      · 判据：无重复活跃行
      · 严重度：P1
      · 结果：
- [ ] **4.6 日历可见类型**
      · 查：_CALENDAR_VISIBLE_TYPES 含白名单 + 只读两类
      · 取证：代码核验常量；前端 CalendarPanel 请求的 events 覆盖
      · 判据：常量正确、日历能显示全部应显示类型
      · 严重度：P2
      · 结果：
- [ ] **4.7 事件完成/更新**
      · 查：POST /events/{id}（完成/更新）契约
      · 取证：对测试事件 POST → 查库状态变化
      · 判据：状态流转合理（active→done 类）
      · 严重度：P1
      · 结果：
- [ ] **4.8 事件删除**
      · 查：DELETE /events/{id} 软删 + 幂等
      · 取证：删除测试事件 → 再删 → 均成功不炸
      · 判据：删除幂等
      · 严重度：P2
      · 结果：
- [ ] **4.9 周期记录**
      · 查：cycle_records 读写（log_period/get_period_status）
      · 取证：查库 `SELECT COUNT(*) FROM cycle_records`；API/工具触发一次读
      · 判据：读面正常；写面（若产生数据）与前端一致
      · 严重度：P1
      · 结果：
- [ ] **4.10 周期推算事件**
      · 查：source='cycle' 的推算事件（经期提醒类）生成逻辑
      · 取证：代码核验 events 创建处 source 字段；查库 events 中 source='cycle' 行
      · 判据：推算合理、不重复生成
      · 严重度：P2
      · 结果：
- [ ] **4.11 日记工具**
      · 查：write_diary/read_diary 工具真实可用
      · 取证：对话触发写日记 → 查 diaries 表 → 触发读回
      · 判据：写读一致
      · 严重度：P1
      · 结果：
- [ ] **4.12 塔罗**
      · 查：tarot 工具输出结构（卡牌/解读）正常
      · 取证：对话触发一次塔罗 → 观察回复
      · 判据：输出结构化、无报错
      · 严重度：P2
      · 结果：
- [ ] **4.13 天气工具**
      · 查：get_weather 真实 key 下返回实时天气
      · 取证：对话「今天香港天气」→ 观察
      · 判据：返回真实天气数据（非编造），LLM 转述自然
      · 严重度：P1
      · 结果：
- [ ] **4.14 地图搜索**
      · 查：search_nearby/fuzzy_search_place（高德）真实可用
      · 取证：对话「附近有什么咖啡店」→ 观察结果条数与格式
      · 判据：有真实结果、POI 转述口语化
      · 严重度：P1
      · 结果：
- [ ] **4.15 GPS 与隐私**
      · 查：GPS opt-out 生效（不开 GPS 则相关功能降级/无行）
      · 取证：查库 GPS 相关表/设置；代码核验 opt-out 分支（对照 test_gps_optout 语义）
      · 判据：opt-out 时无 GPS 数据写入、功能优雅降级
      · 严重度：P1
      · 结果：
- [ ] **4.16 出行路线**
      · 查：get_transit_route/get_cycling_route 契约
      · 取证：对话触发一次路线查询 → 观察
      · 判据：返回合理或友好降级（无 500）
      · 严重度：P2
      · 结果：
- [ ] **4.17 网络搜索**
      · 查：web_search（Tavily）真实可用
      · 取证：对话问一个需要搜索的问题 → 观察工具调用与转述
      · 判据：搜索触发、结果转述合理
      · 严重度：P1
      · 结果：
- [ ] **4.18 记忆工具面**
      · 查：remember/recall/update/delete/list/export 全工具注册与可用
      · 取证：`backend/.venv/Scripts/python -m scripts.verify_tools`（scripts/verify_tools.py 一致性校验）
      · 判据：工具注册表与实现一致（verify_tools 输出无缺失）
      · 严重度：P1
      · 结果：
- [ ] **4.19 工具错误转述**
      · 查：工具抛错时 LLM 优雅转述（不裸露技术错误）
      · 取证：触发一个会失败的场景（如无 key 的工具/边界输入）→ 观察回复
      · 判据：回复为自然语言说明，非堆栈/英文报错
      · 严重度：P1
      · 结果：
- [ ] **4.20 工具超时**
      · 查：单工具调用有超时（不无限挂起）
      · 取证：代码核验工具执行超时配置
      · 判据：有超时上限、超时后对话继续
      · 严重度：P2
      · 结果：
- [ ] **4.21 事件提醒联动**
      · 查：active_events 被心跳/主动消息消费（3.17 交叉）
      · 取证：创建近期事件 → 触发心跳/主动消息 → 看是否提及
      · 判据：事件被合理提及
      · 严重度：P2
      · 结果：
- [ ] **4.22 日历前端数据流**
      · 查：CalendarPanel ↔ /api/events 数据流一致
      · 取证：E2E 或浏览器打开日历面板，创建/编辑/完成事件，观察前端状态与后端同步
      · 判据：前端操作均反映到后端、刷新不丢
      · 严重度：P1
      · 结果：
- [ ] **4.23 状态路由**
      · 查：/api/status 契约（mood/memory_count 等）
      · 取证：`curl -s http://127.0.0.1:8000/api/status -H "Authorization: Bearer $TOKEN"` 看结构
      · 判据：结构与前端消费一致、数据实时
      · 严重度：P1
      · 结果：
- [ ] **4.24 配置路由**
      · 查：/api/config GET/POST（token 配置、主题）契约与认证
      · 取证：GET 看结构；POST 需认证
      · 判据：结构合理、写操作有认证保护
      · 严重度：P1
      · 结果：
- [ ] **4.25 健康端点**
      · 查：/api/health 无认证可访问 + 载荷合理
      · 取证：`curl -s http://127.0.0.1:8000/api/health`（无 token）
      · 判据：200 ok，含 status/name 等，无敏感信息
      · 严重度：P1
      · 结果：
- [ ] **4.26 会话管理**
      · 查：/api/sessions GET 与 DELETE 契约（会话列表/清理）
      · 取证：GET 看列表；DELETE 测试会话（check-1 可删）
      · 判据：列表完整、删除生效且不影响对话主流程
      · 严重度：P1
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 26）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/04-events-tools.md && git commit -m "docs(check): 04 事件与工具体检清单（26 项）"
```

---

### Task 6: 05-visual.md 视觉与界面板块（24 项）

**Files:**
- Create: `docs/launch-check/05-visual.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 5.1–5.24。

**检查项内容：**

```markdown
# 05 · 视觉与界面体检

> 板块状态：0/24 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选

- [ ] **5.1 前端构建**
      · 查：vite build 成功无告警
      · 取证：`cd web && npm run build` 看退出码与产物
      · 判据：exit 0、无 error、产物在 web/dist/
      · 严重度：P0
      · 结果：
- [ ] **5.2 静态托管**
      · 查：FastAPI 托管 dist（后端可提供前端页面）
      · 取证：代码核验静态挂载路径；`curl -s http://127.0.0.1:8000/` 返回 HTML
      · 判据：根路径返回 index.html（或明确重定向）
      · 严重度：P1
      · 结果：
- [ ] **5.3 lint**
      · 查：oxlint 零告警
      · 取证：`cd web && npm run lint`
      · 判据：exit 0、零错误零告警
      · 严重度：P2
      · 结果：
- [ ] **5.4 前端单测**
      · 查：vitest run 全绿
      · 取证：`cd web && npm test`
      · 判据：全部通过（作参考信号，非体检主判据）
      · 严重度：P2
      · 结果：
- [ ] **5.5 E2E**
      · 查：E2E 脚本（scripts/e2e/）在系统 Edge headless 可跑通
      · 取证：按 scripts/e2e 说明跑一次（复用全局 playwright）
      · 判据：交互/视觉断言通过、零控制台错误
      · 严重度：P1
      · 结果：
- [ ] **5.6 聊天面板**
      · 查：消息气泡/流式打字机/时间显示正常
      · 取证：浏览器真实对话 3 轮，观察渲染与流式效果
      · 判据：逐字渲染平滑、气泡样式正常、无布局抖动
      · 严重度：P1
      · 结果：
- [ ] **5.7 记忆面板**
      · 查：MemoriesPanel 列表/删除/导出交互
      · 取证：浏览器打开记忆面板，操作一轮
      · 判据：列表实时、操作响应、无报错
      · 严重度：P1
      · 结果：
- [ ] **5.8 会话面板**
      · 查：SessionsPanel 切换历史会话
      · 取证：浏览器切换 2-3 个会话
      · 判据：切换后上下文正确、历史可读
      · 严重度：P2
      · 结果：
- [ ] **5.9 日历面板**
      · 查：CalendarPanel 渲染与事件交互（交叉 4.22）
      · 取证：浏览器操作一轮
      · 判据：事件显示/创建/完成流畅
      · 严重度：P1
      · 结果：
- [ ] **5.10 设置面板**
      · 查：ConfigPanel token 配置/主题切换
      · 取证：浏览器打开设置，查看各选项
      · 判据：配置项完整、保存生效
      · 严重度：P1
      · 结果：
- [ ] **5.11 房间与立绘**
      · 查：RoomScene/CharacterSprite 渲染正常、立绘不破图
      · 取证：浏览器观察房间与立绘（素材路径存在：web 内 sprite 资源）
      · 判据：立绘/房间正常显示、无 404 资源
      · 严重度：P1
      · 结果：
- [ ] **5.12 睡姿切换**
      · 查：深夜/睡眠时段立绘切换睡姿（抬到面板床线）
      · 取证：观察深夜时段或手动验证时段逻辑（?_sky 预览类参数）；对照记忆：睡姿已抬到床线
      · 判据：时段切换正确、睡姿位置正确
      · 严重度：P2
      · 结果：
- [ ] **5.13 天空背景**
      · 查：SkyBackground 按 HKT 时段渐变（粉色化主题）
      · 取证：浏览器多时段观察（或 ?_sky 预览）；对照 pink 主题
      · 判据：时段过渡自然、色板符合奶甜草莓粉 token
      · 严重度：P2
      · 结果：
- [ ] **5.14 心语动画**
      · 查：FloatingHearts 漂浮爱心正常（reduced-motion 无坑）
      · 取证：浏览器观察；系统开启 reduced-motion 时再观察
      · 判据：默认动画流畅；reduced-motion 下降级不报错
      · 严重度：P2
      · 结果：
- [ ] **5.15 响应式**
      · 查：移动端布局（聊天/面板适配）
      · 取证：DevTools 移动视口（375px）过一遍主界面
      · 判据：无溢出/错位/遮挡，主要交互可达
      · 严重度：P1
      · 结果：
- [ ] **5.16 可访问性**
      · 查：focus-visible 可见焦点、memo 化组件无 stale
      · 取证：Tab 键走查焦点可见性；代码核验 useDeferredMarkRead 等 hooks
      · 判据：焦点环可见、无记忆 bug 类交互
      · 严重度：P2
      · 结果：
- [ ] **5.17 错误边界**
      · 查：ErrorBoundary 兜底（组件崩溃不白屏）
      · 取证：代码核验边界覆盖；DevTools 强制造一处错误观察
      · 判据：错误被捕获、有降级 UI
      · 严重度：P1
      · 结果：
- [ ] **5.18 控制台零错误**
      · 查：真实使用全程无 console error/warning
      · 取证：浏览器开发者工具观察 5 分钟真实使用
      · 判据：零 error；warning 少且可解释
      · 严重度：P1
      · 结果：
- [ ] **5.19 离线缓存**
      · 查：sw.js 版本（CACHE_NAME v5）与 stale-while-revalidate 行为
      · 取证：读 web/public/sw.js 版本号与缓存策略；部署后改立绘验证更新（runbook 4 步）
      · 判据：版本号清晰、更新策略正确
      · 严重度：P2
      · 结果：
- [ ] **5.20 资源管线**
      · 查：gen_assets/trim_sprites 产物（立绘/图标）与工程一致
      · 取证：`ls web/public` 与 scripts/gen_assets.py 产出核对；web 构建引用路径存在
      · 判据：引用的资源文件都存在、无 404
      · 严重度：P1
      · 结果：
- [ ] **5.21 主题一致性**
      · 查：粉色主题 token（cream/plum/strawberry/peach/blush/raspberry/honey）全站一致
      · 取证：代码核验 CSS token 使用；浏览器抽查 3-4 个面板配色
      · 判据：无突兀不协调色、对比度达标
      · 严重度：P2
      · 结果：
- [ ] **5.22 Markdown 渲染**
      · 查：react-markdown 渲染（代码块/换行/列表）
      · 取证：发一段含 markdown 的消息观察渲染
      · 判据：渲染正确、无原始语法露出
      · 严重度：P2
      · 结果：
- [ ] **5.23 首屏加载**
      · 查：首屏可交互时间合理
      · 取证：DevTools Network 看关键资源体积与加载瀑布
      · 判据：首屏 3-5s 内可交互（视网络），无超大阻塞资源
      · 严重度：P1
      · 结果：
- [ ] **5.24 输入交互**
      · 查：ChatInput 发送/换行/快捷操作
      · 取证：浏览器操作（Enter 发送、Shift+Enter 换行等）
      · 判据：交互符合预期、无粘连 bug
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 24）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/05-visual.md && git commit -m "docs(check): 05 视觉与界面体检清单（24 项）"
```

---

### Task 7: 06-data.md 数据与持久化板块（20 项）

**Files:**
- Create: `docs/launch-check/06-data.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 6.1–6.20。

**检查项内容：**

```markdown
# 06 · 数据与持久化体检

> 板块状态：0/20 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：sqlite 查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [ ] **6.1 当前 schema 版本**
      · 查：生产库 schema_version 应为 19
      · 取证：`SELECT MAX(version) FROM schema_version`
      · 判据：= 19
      · 严重度：P0
      · 结果：
- [ ] **6.2 迁移链完整**
      · 查：v1→v19 全部迁移有实现且注册
      · 取证：代码核验 migrations.py `_migrations` 字典键 1..19 连续
      · 判据：1..19 无缺号
      · 严重度：P1
      · 结果：
- [ ] **6.3 v18→v19 实升**
      · 查：真实库完成 v19 迁移（care_ledger 表存在）
      · 取证：查库 `SELECT name FROM sqlite_master WHERE name='care_ledger'`
      · 判据：表存在（本会话已验，勾选并注明日期即可）
      · 严重度：P0
      · 结果：
- [ ] **6.4 全新安装升级链**
      · 查：空库 init 到 v19 全程无错
      · 取证：临时副本删库重启（或读 test_init_db_upgrade 语义）验证
      · 判据：init 直达 19、无中间态错误
      · 严重度：P1
      · 结果：
- [ ] **6.5 备份轮换**
      · 查：data/backups/ 保留 5 份 userdata.db 备份
      · 取证：`ls backend/data/backups/` 数备份数
      · 判据：≤ 5 份、命名含时间戳
      · 严重度：P1
      · 结果：
- [ ] **6.6 备份完整性**
      · 查：备份文件可打开且非空
      · 取证：对最新一份备份跑 sqlite 只读 `PRAGMA integrity_check`
      · 判据：integrity_check = ok
      · 严重度：P1
      · 结果：
- [ ] **6.7 scrub 备份**
      · 查：BACKUP_SCRUB_SENSITIVE 开启时备份剔除 cycle/chat 且 VACUUM
      · 取证：代码核验 scrub 逻辑（备份时清行 + VACUUM 清页）
      · 判据：实现与设置说明一致
      · 严重度：P1
      · 结果：
- [ ] **6.8 归档**
      · 查：记忆 90 天归档与对话档案归档（chat_archive）不冲突
      · 取证：代码核验两套归档的时限与触发
      · 判据：无重叠破坏（归档不删活跃数据）
      · 严重度：P2
      · 结果：
- [ ] **6.9 WAL 一致性**
      · 查：journal_mode=WAL、checkpoint 正常
      · 取证：查库 `PRAGMA journal_mode`；看 data/ 下 -wal/-shm 文件是否随停服收敛
      · 判据：WAL 模式；服务停止后 wal 文件可合并（不残留巨大 wal）
      · 严重度：P1
      · 结果：
- [ ] **6.10 约束完整性**
      · 查：memories 唯一索引（活跃行去重）等约束存在
      · 取证：查 sqlite_master 中唯一索引列表（idx_memories_*）
      · 判据：去重唯一索引存在
      · 严重度：P1
      · 结果：
- [ ] **6.11 表清单完整**
      · 查：11+ 张核心表全部存在
      · 取证：查 sqlite_master 表名清单（memories/diaries/life_state/proactive_messages/push_subscriptions/cycle_records/events/chat_sessions/chat_messages/memory_extract_state/care_ledger）
      · 判据：全部存在
      · 严重度：P0
      · 结果：
- [ ] **6.12 checkpoint 裁剪**
      · 查：checkpoints.db 有裁剪/看门狗（chat_watchdog）防无限膨胀
      · 取证：代码核验 watchdog 逻辑与触发
      · 判据：有上限机制
      · 严重度：P1
      · 结果：
- [ ] **6.13 数据目录结构**
      · 查：data/ 下文件齐全（userdata.db/checkpoints.db/backups/.api-token）
      · 取证：`ls backend/data/`
      · 判据：核心文件存在、无异常残留
      · 严重度：P2
      · 结果：
- [ ] **6.14 备份恢复演练**
      · 查：备份恢复流程可走通（副本演练）
      · 取证：用最新备份复制成临时库 → 打开 → 校验关键表行数
      · 判据：恢复库可读、数据完整
      · 严重度：P1
      · 结果：
- [ ] **6.15 迁移幂等**
      · 查：重复启动不重复迁移（schema_version 不重跑）
      · 取证：连续重启两次 → 查 schema_version 仍 19 无报错
      · 判据：迁移幂等
      · 严重度：P1
      · 结果：
- [ ] **6.16 时区存储**
      · 查：时间存储统一 HKT 无 UTC 混用
      · 取证：代码核验 timezone.py 与各表 created_at 写入处；抽查库内时间与 HKT 一致
      · 判据：无混用（对照 bd61f08 时间词修复）
      · 严重度：P1
      · 结果：
- [ ] **6.17 事件墓碑索引**
      · 查：idx_events_dedupe 唯一部分索引（status≠deleted 谓词）
      · 取证：查 sqlite_master 索引 SQL
      · 判据：谓词与 v9 语义一致
      · 严重度：P2
      · 结果：
- [ ] **6.18 记忆表字段**
      · 查：memories 含 last_mention_ts/valence/arousal/_relevance_score 列
      · 取证：查 PRAGMA table_info(memories)
      · 判据：v17/v18 新增列都在
      · 严重度：P0
      · 结果：
- [ ] **6.19 库文件权限**
      · 查：生产库文件权限（非全局可写）
      · 取证：`ls -l backend/data/userdata.db` 看权限位
      · 判据：普通用户可读写、无 777
      · 严重度：P1
      · 结果：
- [ ] **6.20 磁盘占用**
      · 查：库体积与备份占用合理（无失控膨胀）
      · 取证：`du -sh backend/data/` 看总量
      · 判据：规模与使用量匹配（千条级记忆应 < 100MB 量级）
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 20）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/06-data.md && git commit -m "docs(check): 06 数据与持久化体检清单（20 项）"
```

---

### Task 8: 07-security.md 安全与隐私板块（26 项）

**Files:**
- Create: `docs/launch-check/07-security.md`

**Interfaces:**
- Consumes: 00-summary 纪律；deploy/README.md §0 安全前置清单。
- Produces: 检查项 7.1–7.26。

**检查项内容：**

```markdown
# 07 · 安全与隐私体检（公网暴露面档）

> 板块状态：0/26 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 依据：deploy/README.md §0 上线前安全前置 + settings.py 全部开关

- [ ] **7.1 认证覆盖**
      · 查：除 /api/health 外全部 API 强制 Bearer（交叉 1.2）
      · 取证：`curl -s -o /dev/null -w '%{http_code}'` 无 token 逐个打 25 个端点
      · 判据：业务端点全 401、仅 health 200
      · 严重度：P0
      · 结果：
- [ ] **7.2 token 强度**
      · 查：生产 token 为强随机（≥32 hex）
      · 取证：读 `backend/config/.env`（只查是否存在/长度，不打印值）`grep -c XIAOXI_API_TOKEN=…` 长度判断
      · 判据：≥32 字符随机值（或入口自动生成逻辑存在）
      · 严重度：P0
      · 结果：
- [ ] **7.3 fail-closed**
      · 查：无 token + 非回环绑定 → 拒绝启动（XIAOXI_ALLOW_INSECURE 未开时）
      · 取证：代码核验启动校验分支（settings.py 注释语义）
      · 判据：fail-closed 逻辑存在且正确
      · 严重度：P0
      · 结果：
- [ ] **7.4 速率限制**
      · 查：API 限流存在（桶参数合理）
      · 取证：代码核验限流实现（桶容量/速率）；无 token 快速打 10 次观察是否触发限流
      · 判据：有限流、参数合理（防暴力）
      · 严重度：P1
      · 结果：
- [ ] **7.5 TRUST_PROXY 纪律**
      · 查：直连部署时 TRUST_PROXY 未开启（防伪造 XFF 绕过限流）
      · 取证：读 .env 配置与部署拓扑
      · 判据：仅反代后开启；直连时关闭
      · 严重度：P0
      · 结果：
- [ ] **7.6 CORS 精确域名**
      · 查：CORS_ORIGINS 为精确域名非 `*`
      · 取证：读 settings 默认值与 .env 覆盖值
      · 判据：公网配置为 https://域名（非 *、非 localhost 残留）
      · 严重度：P0
      · 结果：
- [ ] **7.7 安全响应头**
      · 查：HSTS/Content-Type-Options/X-Frame-Options 等由中间件/反代设置
      · 取证：`curl -sI http://127.0.0.1:8000/` 看响应头；核对 SecurityHeadersMiddleware 与反代 header 配置
      · 判据：关键安全头存在（反代层 HSTS 等）
      · 严重度：P1
      · 结果：
- [ ] **7.8 密钥轮换清单**
      · 查：上线前四把密钥（DeepSeek/高德/Tavily/VAPID 私钥）确认轮换/隔离
      · 取证：核对 deploy/README.md §0 清单与实际 .env 密钥状态（是否存在生产密钥）
      · 判据：有轮换计划或已轮换；无测试密钥残留
      · 严重度：P0
      · 结果：
- [ ] **7.9 仓库敏感数据**
      · 查：git 历史含真实生理期日期（v4 种子/test_cycle_tracker）——按泄露处理决策
      · 取证：`grep -r '2026\|cycle' backend/tests/test_cycle_tracker.py backend/database/migrations.py` 评估；确认仓库可见性
      · 判据：仓库私有（可见性确认）或已按 runbook 处理；决策记录到台账
      · 严重度：P0
      · 结果：
- [ ] **7.10 scrub 开关**
      · 查：BACKUP_SCRUB_SENSITIVE 语义正确（备份剔除 cycle/chat）
      · 取证：代码核验 + 若开启验证备份内容（交叉 6.7）
      · 判据：实现与文档一致
      · 严重度：P1
      · 结果：
- [ ] **7.11 对话不进 checkpoint**
      · 查：对话档案（chat_messages）与记忆不入 checkpoint（防注入纵深）
      · 取证：代码核验 llm_input_messages 注入 vs checkpoint 写入分离
      · 判据：注入读面与 checkpoint 写面分离（graph.py）
      · 严重度：P1
      · 结果：
- [ ] **7.12 记忆防注入**
      · 查：记忆内容为数据非指令（边界标记清晰）（交叉 2.23）
      · 取证：代码核验注入块边界
      · 判据：无指令语义、有边界
      · 严重度：P0
      · 结果：
- [ ] **7.13 WebSocket 拒绝**
      · 查：RejectWebSocketMiddleware 拒绝非预期 WS 连接
      · 取证：`curl -s -o /dev/null -w '%{http_code}'` 尝试 ws 升级 → 预期拒绝；代码核验中间件
      · 判据：WS 被拒、无功能依赖 WS
      · 严重度：P1
      · 结果：
- [ ] **7.14 VAPID 私钥保护**
      · 查：VAPID 私钥只存 .env 不进 git
      · 取证：`git ls-files | grep -i env` 确认 .env 未入库；.gitignore 覆盖
      · 判据：.env 不入库
      · 严重度：P0
      · 结果：
- [ ] **7.15 日志不泄密**
      · 查：token/API key 不出现在日志
      · 取证：grep 日志样本（LOG_FILE 开启时）找 token/key 模式
      · 判据：无密钥类输出
      · 严重度：P1
      · 结果：
- [ ] **7.16 端口暴露面**
      · 查：仅 127.0.0.1:8000 回环绑定（公网由反代入口）
      · 取证：`netstat -ano | grep :8000` 看监听地址；docker-compose ports 配置
      · 判据：回环绑定
      · 严重度：P0
      · 结果：
- [ ] **7.17 HTTPS 终止**
      · 查：反代配置 HTTPS（证书自动/手动）与 HSTS
      · 取证：部署环境验证 `curl -I https://域名` 证书与 HSTS；未部署时核对 Caddyfile/nginx 配置正确性
      · 判据：证书有效 + HSTS 头
      · 严重度：P0
      · 结果：
- [ ] **7.18 请求体上限**
      · 查：超大请求体被拒（防内存耗尽）
      · 取证：代码核验 body 大小限制（中间件/框架配置）
      · 判据：有限制且合理
      · 严重度：P1
      · 结果：
- [ ] **7.19 路径穿越**
      · 查：静态托管无目录穿越
      · 取证：`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8000/../backend/config/.env` 与 `..%2f` 变体
      · 判据：返回 4xx，不泄露文件
      · 严重度：P0
      · 结果：
- [ ] **7.20 错误不泄堆栈**
      · 查：500 响应不含堆栈/Python 路径（交叉 1.13/1.14）
      · 取证：触发错误看响应体；代码核验错误处理器
      · 判据：友好文案、无堆栈
      · 严重度：P1
      · 结果：
- [ ] **7.21 数据出境清单**
      · 查：上传给第三方的内容清单明确（DeepSeek 对话、高德地址、Tavily 查询词）
      · 取证：梳理 providers 上传内容；确认 PRIVACY.md 已披露
      · 判据：文档披露与实际上传一致
      · 严重度：P1
      · 结果：
- [ ] **7.22 PRIVACY 一致性**
      · 查：PRIVACY.md 与实际行为一致（备份/存储/上传）
      · 取证：对照 docs/PRIVACY.md 与实现
      · 判据：无矛盾陈述
      · 严重度：P1
      · 结果：
- [ ] **7.23 比较安全**
      · 查：token 校验使用安全比较（恒定时间）
      · 取证：代码核验 token 比对实现
      · 判据：用 hmac.compare_digest 类安全比较
      · 严重度：P1
      · 结果：
- [ ] **7.24 .gitignore**
      · 查：.env/.api-token/data 不入 git
      · 取证：`cat .gitignore` + `git ls-files` 抽查
      · 判据：敏感路径全被忽略
      · 严重度：P0
      · 结果：
- [ ] **7.25 依赖风险**
      · 查：关键依赖无已知高危（手工核对主要依赖版本）
      · 取证：`pip-audit`（venv 有则跑）；无则核对 requirements.txt 主要包（fastapi/uvicorn/aiosqlite/langgraph）版本
      · 判据：无高危公告或记录豁免理由
      · 严重度：P2
      · 结果：
- [ ] **7.26 认证旁路检查**
      · 查：静态页面/资源不泄露受保护数据
      · 取证：无 token 访问 /、/assets/*、/api/* 对比
      · 判据：静态可访问但不含业务数据（业务数据全在认证 API 后）
      · 严重度：P1
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 26）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/07-security.md && git commit -m "docs(check): 07 安全与隐私体检清单（26 项，公网暴露面档）"
```

---

### Task 9: 08-deploy.md 部署与运维板块（20 项）

**Files:**
- Create: `docs/launch-check/08-deploy.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 8.1–8.20。

**检查项内容：**

```markdown
# 08 · 部署与运维体检

> 板块状态：0/20 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 依据：deploy/README.md runbook + docker-compose.yml + Dockerfile

- [ ] **8.1 镜像构建**
      · 查：docker build 成功
      · 取证：`docker compose build` 看退出码（本机有 Docker 时；无则核对 Dockerfile 阶段合理性）
      · 判据：构建成功、多阶段合理（Python 3.12-slim）
      · 严重度：P0
      · 结果：
- [ ] **8.2 compose 回环绑定**
      · 查：ports 为 127.0.0.1:8000:8000（非 0.0.0.0）
      · 取证：读 docker-compose.yml
      · 判据：回环绑定
      · 严重度：P0
      · 结果：
- [ ] **8.3 容器加固**
      · 查：cap_drop ALL + no-new-privileges
      · 取证：读 docker-compose.yml security 段
      · 判据：两项都在
      · 严重度：P1
      · 结果：
- [ ] **8.4 入口脚本**
      · 查：docker-entrypoint.sh token 逻辑（.env → data/.api-token → 生成）
      · 取证：读 backend/docker-entrypoint.sh 三分支
      · 判据：与 runbook §1 描述一致
      · 严重度：P1
      · 结果：
- [ ] **8.5 卷持久化**
      · 查：xiaoxi-data 卷挂载 /app/backend/data
      · 取证：读 compose volumes 段
      · 判据：数据卷挂载正确、重启不丢
      · 严重度：P0
      · 结果：
- [ ] **8.6 healthcheck**
      · 查：compose healthcheck 配置（间隔/超时/重试）
      · 取证：读 compose healthcheck 段
      · 判据：参数合理、探 /api/health
      · 严重度：P1
      · 结果：
- [ ] **8.7 Caddyfile 示例**
      · 查：Caddyfile.example 正确（域名占位/反代/HSTS/SSE 不压缩）
      · 取证：读 deploy/Caddyfile.example
      · 判据：配置正确、注释清晰
      · 严重度：P1
      · 结果：
- [ ] **8.8 nginx 示例**
      · 查：nginx.example.conf 正确（SSE proxy_buffering off、XFF）
      · 取证：读 deploy/nginx.example.conf
      · 判据：SSE 关缓冲、XFF 传递正确
      · 严重度：P1
      · 结果：
- [ ] **8.9 启动脚本**
      · 查：start.bat/start.sh 逻辑正确（建 venv/装依赖/起服务）
      · 取证：读两个脚本
      · 判据：路径/命令正确、无过时引用
      · 严重度：P2
      · 结果：
- [ ] **8.10 环境变量清单**
      · 查：.env.example 与 settings.py 字段一一对应（无缺漏/无幽灵）
      · 取证：对照 settings.py 全部字段 vs .env.example
      · 判据：全字段有样例
      · 严重度：P1
      · 结果：
- [ ] **8.11 日志可观测**
      · 查：容器 stdout 日志（docker logs）含关键事件、无 GBK 乱码墙
      · 取证：docker logs 样本观察（部署后）
      · 判据：日志可读、关键事件有记录
      · 严重度：P2
      · 结果：
- [ ] **8.12 重启恢复**
      · 查：restart: unless-stopped + 崩溃自愈
      · 取证：读 compose restart 段
      · 判据：策略正确
      · 严重度：P1
      · 结果：
- [ ] **8.13 容器时区**
      · 查：容器 TZ=Asia/Hong_Kong（HKT 一致）
      · 取证：读 Dockerfile/compose 的 TZ 配置
      · 判据：TZ 正确（与 6.16 一致）
      · 严重度：P1
      · 结果：
- [ ] **8.14 启动迁移**
      · 查：容器启动时 lifespan 执行迁移（到 v19）
      · 取证：代码核验 server.py lifespan 迁移调用；部署日志确认「迁移到 schema v19」
      · 判据：启动即迁移、幂等
      · 严重度：P1
      · 结果：
- [ ] **8.15 静态资源托管**
      · 查：dist 构建产物被打进镜像并由 FastAPI 托管
      · 取证：读 Dockerfile 拷贝 dist 与挂载逻辑；交叉 5.2
      · 判据：构建产物入镜像、托管正确
      · 严重度：P1
      · 结果：
- [ ] **8.16 runbook 一致性**
      · 查：deploy/README.md 与实际文件/命令一致（无死命令）
      · 取证：逐条核对 runbook 的路径与文件名
      · 判据：无过时引用（如旧文件名）
      · 严重度：P2
      · 结果：
- [ ] **8.17 版本一致性**
      · 查：version.py 与 README 版本号一致
      · 取证：`cat backend/version.py` vs README 版本标注
      · 判据：一致
      · 严重度：P2
      · 结果：
- [ ] **8.18 SW 版本**
      · 查：sw.js CACHE_NAME 版本与部署节奏匹配（交叉 5.19）
      · 取证：读 web/public/sw.js
      · 判据：版本号明确
      · 严重度：P2
      · 结果：
- [ ] **8.19 上线前置清单**
      · 查：域名解析/DNS/证书申请路径清晰
      · 取证：核对 runbook §2 步骤完整性
      · 判据：步骤可执行（域名替换点明确）
      · 严重度：P1
      · 结果：
- [ ] **8.20 监控与告警**
      · 查：至少 healthcheck 探活 + 日志轮转有兜底（LOG_FILE 10MB×7）
      · 取证：代码核验 logger RotatingFileHandler 配置
      · 判据：落盘时轮转正确
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 20）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/08-deploy.md && git commit -m "docs(check): 08 部署与运维体检清单（20 项）"
```

---

### Task 10: 09-performance.md 性能与资源板块（16 项）

**Files:**
- Create: `docs/launch-check/09-performance.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 9.1–9.16。

**检查项内容：**

```markdown
# 09 · 性能与资源体检

> 板块状态：0/16 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选

- [ ] **9.1 首字节延迟**
      · 查：聊天首字节延迟可接受（不含 LLM 首 token 前的网络往返）
      · 取证：`time curl` 或浏览器 Network 测 /api/chat 首字节
      · 判据：总往返在 LLM 正常首 token 时间量级（无额外秒级延迟）
      · 严重度：P1
      · 结果：
- [ ] **9.2 端到端对话耗时**
      · 查：真实对话从发出到完整回复的耗时合理
      · 取证：真实对话计时 3 次取均值
      · 判据：均值在可接受范围（模型速度决定，记录基线值）
      · 严重度：P2
      · 结果：
- [ ] **9.3 流式节奏**
      · 查：SSE 逐 token 间隔平滑（无长停顿）
      · 取证：流式对话观察 token 间隔（交叉 1.4）
      · 判据：无 >5s 单 token 停顿
      · 严重度：P2
      · 结果：
- [ ] **9.4 token 成本画像**
      · 查：每轮对话输入 token 量合理（系统提示+历史+记忆注入不失控）
      · 取证：读 provider 日志/估算系统提示词长度；抽查一轮的 token 数（utils/tokens.py）
      · 判据：输入 token 量在预算内（记录基线）
      · 严重度：P2
      · 结果：
- [ ] **9.5 内存占用**
      · 查：常驻实例 RSS 合理（单用户）
      · 取证：Task Manager/docker stats 看 python 进程内存
      · 判据：RSS 稳定（记录基线，为 compose 资源上限提供数据）
      · 严重度：P1
      · 结果：
- [ ] **9.6 CPU 画像**
      · 查：心跳/备份等后台任务 CPU 尖峰不过分
      · 取证：观察几分钟 CPU；备份期间测一次
      · 判据：无持续高 CPU（尖峰可接受）
      · 严重度：P2
      · 结果：
- [ ] **9.7 前端包体积**
      · 查：dist 体积与 JS chunk 大小
      · 取证：`du -sh web/dist` + `ls web/dist/assets` 看 chunk
      · 判据：总量合理、无超大 chunk（记录基线）
      · 严重度：P2
      · 结果：
- [ ] **9.8 首屏资源**
      · 查：首屏请求数与体积（交叉 5.23）
      · 取证：DevTools Network 记录
      · 判据：无阻塞性超大资源
      · 严重度：P2
      · 结果：
- [ ] **9.9 并发对话**
      · 查：两个会话同时对话不互相阻塞（asyncio 并发）
      · 取证：两个浏览器标签同时聊
      · 判据：均正常响应、无排队死等
      · 严重度：P1
      · 结果：
- [ ] **9.10 数据库查询延迟**
      · 查：记忆量增长后查询仍快
      · 取证：查库 `EXPLAIN QUERY PLAN` 抽查热点查询（记忆召回）；或大库（副本造数据）实测
      · 判据：索引命中、无全表扫描热点
      · 严重度：P1
      · 结果：
- [ ] **9.11 限流桶参数**
      · 查：限流桶容量/速率与单用户使用曲线匹配（不误伤正常使用）
      · 取证：代码核验参数；正常使用一轮观察无被限流
      · 判据：正常使用不触发限流
      · 严重度：P1
      · 结果：
- [ ] **9.12 备份开销**
      · 查：备份过程不影响在线服务
      · 取证：备份触发时观察对话响应（SQLite Online Backup 应无阻塞）
      · 判据：备份期间对话正常
      · 严重度：P2
      · 结果：
- [ ] **9.13 日志增长**
      · 查：日志落盘量可控（轮转生效）
      · 取证：LOG_FILE 开启运行一段 → 看日志体积与轮转
      · 判据：体积可控、轮转配置生效
      · 严重度：P2
      · 结果：
- [ ] **9.14 冷启动**
      · 查：服务重启到可服务时间
      · 取证：重启计时（迁移 + 加载）
      · 判据：在可接受范围（记录基线，迁移幂等）
      · 严重度：P2
      · 结果：
- [ ] **9.15 注入成本**
      · 查：记忆叙事/时间块注入的 token 成本与收益平衡
      · 取证：估算注入块 token 占比（对照 9.4）
      · 判据：注入占比合理（不喧宾夺主）
      · 严重度：P2
      · 结果：
- [ ] **9.16 资源上限建议**
      · 查：为 compose 资源上限（memory/cpu）给出实测依据
      · 取证：综合 9.5/9.6 记录 → 写建议值到 00-summary 备注
      · 判据：建议值有实测支撑（compose 注释默认未开）
      · 严重度：P2
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 16）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/09-performance.md && git commit -m "docs(check): 09 性能与资源体检清单（16 项）"
```

---

### Task 11: 10-user-journey.md 用户旅程回归板块（14 项）

**Files:**
- Create: `docs/launch-check/10-user-journey.md`

**Interfaces:**
- Consumes: 00-summary 纪律。
- Produces: 检查项 10.1–10.14。

**检查项内容：**

```markdown
# 10 · 用户旅程回归体检

> 板块状态：0/14 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 本板块为端到端叙事验证，逐项在真实实例上走完整场景

- [ ] **10.1 冷启动**
      · 查：新会话第一句对话流畅（欢迎/问候自然）
      · 取证：开新会话聊第一句
      · 判据：响应正常、口吻符合人格
      · 严重度：P1
      · 结果：
- [ ] **10.2 日常对话流**
      · 查：连续 10 条自然对话无断点（交叉 1.23）
      · 取证：真实聊一轮日常（寒暄→分享→问答）
      · 判据：全部正常回复
      · 严重度：P1
      · 结果：
- [ ] **10.3 记忆沉淀**
      · 查：日常对话沉淀为记忆（提取活着）（交叉 2.2）
      · 取证：聊完事实性内容 → 稍后查库
      · 判据：记忆行出现
      · 严重度：P1
      · 结果：
- [ ] **10.4 记忆唤起**
      · 查：次日回访时旧记忆被唤起
      · 取证：问「你记得…」引用前一天话题
      · 判据：能唤起（有记忆时）
      · 严重度：P1
      · 结果：
- [ ] **10.5 主动关怀**
      · 查：心跳产生主动消息/早安（真实运行）
      · 取证：观察日志/库中 proactive_messages 与注入（交叉 3.4-3.7）
      · 判据：有真实主动消息产生
      · 严重度：P1
      · 结果：
- [ ] **10.6 深夜行为**
      · 查：深夜时段睡姿 + 劝睡 + 不轰炸
      · 取证：深夜（或改系统时间验证时段逻辑）观察
      · 判据：行为符合（睡姿/劝睡/静默）
      · 严重度：P1
      · 结果：
- [ ] **10.7 事件闭环**
      · 查：创建事件 → 日历显示 → 到期提醒全链路
      · 取证：创建一条近期事件走完全流程
      · 判据：全链路通
      · 严重度：P1
      · 结果：
- [ ] **10.8 生活方式工具**
      · 查：日记/塔罗/周期等工具日常可用
      · 取证：各触发一次
      · 判据：正常（交叉 04 板块）
      · 严重度：P2
      · 结果：
- [ ] **10.9 设置配置**
      · 查：浏览器设置面板配置 token/主题生效
      · 取证：走一遍设置
      · 判据：保存生效（交叉 5.10）
      · 严重度：P1
      · 结果：
- [ ] **10.10 数据留存**
      · 查：重启后对话/记忆/事件全部还在
      · 取证：重启服务 → 查库/界面
      · 判据：数据无丢失
      · 严重度：P0
      · 结果：
- [ ] **10.11 推送**
      · 查：订阅 → 收到推送完整链路
      · 取证：真实订阅触发一次
      · 判据：收到（交叉 3.14）
      · 严重度：P1
      · 结果：
- [ ] **10.12 离线回归**
      · 查：长时间离线后回来被 detected（回归关怀）
      · 取证：代码核验+日志观察（真实离线复现成本高，代码语义验证可接受）
      · 判据：检测逻辑正确
      · 严重度：P2
      · 结果：
- [ ] **10.13 全程无错**
      · 查：整个旅程无控制台错误/500
      · 取证：全程浏览器 console + API 响应码观察
      · 判据：零错误（交叉 5.18）
      · 严重度：P1
      · 结果：
- [ ] **10.14 一周陪伴叙事**
      · 查：综合感受——小汐像「活的存在」而非工具
      · 取证：以上旅程完成后人工品评（用户主观确认）
      · 判据：用户认可陪伴感（记录用户反馈）
      · 严重度：P1
      · 结果：
```

- [ ] **Step 2: 校验**（无重号、计数 14）

- [ ] **Step 3: 提交**

```bash
cd E:/小汐sonetto && git add docs/launch-check/10-user-journey.md && git commit -m "docs(check): 10 用户旅程回归体检清单（14 项）"
```

---

### Task 12: 全量自审与收尾

**Files:**
- Modify: `docs/launch-check/00-summary.md`（若校验发现计数调整）

**Interfaces:**
- Consumes: 全部板块文件。
- Produces: 最终交付的完整体检工作区。

- [ ] **Step 1: 全量计数与编号校验**

```bash
cd E:/小汐sonetto && for f in docs/launch-check/0{1..9}*.md docs/launch-check/10*.md; do n=$(grep -c '^\s*- \[ \]' "$f"); d=$(grep -oP '^\s*- \[ \] \*\*\d+\.\d+' "$f" | sort | uniq -d | wc -l); echo "$f: $n 项, 重号 $d"; done
```

Expected: 各文件计数 25/24/22/26/24/20/26/20/16/14；重号全 0。

- [ ] **Step 2: 占位符扫描**

Run: `grep -rn 'TBD\|TODO\|待补充\|fill in\|XXX' docs/launch-check/`
Expected: 无匹配。

- [ ] **Step 3: 检查项编号连贯性（板块内无跳号）**

Run: 对每文件提取编号序列与 1..N 连续比对（`grep -oP '^\s*- \[ \] \*\*\d+\.\d+' | sed 's/.*\*\([0-9]*\)\./\1/' | paste -sd' '` 目视核对）。
Expected: 每板块从 1 连续到 N。

- [ ] **Step 4: 00-summary 计数回填确认**

Run: `grep -c '|' docs/launch-check/00-summary.md` 目视表格与各板块实际计数一致。
Expected: 合计 217 与各板块数字一致。

- [ ] **Step 5: 提交收尾**

```bash
cd E:/小汐sonetto && git add docs/launch-check/ && git status --short
```

若 Task 1-11 已分别提交则无新增改动；如有修正则提交 `docs(check): 体检清单全量自审修正`。

- [ ] **Step 6: 交付说明**

告知用户：体检工作区就绪，可随时开新窗口说「继续体检」→ 读 `docs/launch-check/00-summary.md` + 目标板块文件执行。

---

## 计划内已知偏差（相对 spec 原文）

1. **检查项数量**：spec 写每板块 20-30 项、总计 200-250；本计划为 25/24/22/26/24/20/26/20/16/14 = **217 项**（09/10 板块低于 20 因性能与旅程板块性质使然），合计在 200-250 区间内。
2. **09 性能板块取证限制**：部分项（9.2/9.5）需真实负载数据，执行时记录基线即可，判据以「记录基线+无异常」为准。
3. **10 旅程板块交叉引用**：多项与 01-09 交叉，执行时如对应项未检，本板块项可标注「依赖 0X 板块」顺序后置。
