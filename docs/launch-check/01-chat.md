# 01 · 对话体验体检

> 板块状态：25/25 ✅ 全过 · 已知问题：见问题台账 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：基准 URL http://127.0.0.1:8000。⚠️ 认证模式说明（08-06 校准）：本地 .env **未配** XIAOXI_API_TOKEN → dev 模式认证关闭，`grep XIAOXI_API_TOKEN` 为空是正常的；1.1/1.2 类认证判据一律用临时副本 :8001 模拟部署态（`XIAOXI_API_TOKEN=xxx .venv/Scripts/python -m uvicorn api.server:app --port 8001`，验完即 kill）。前端 token 来自 localStorage（web/src/api/client.js 的 TOKEN_KEY）。

- [x] **1.1 /api/chat 基本契约**
      · 查：POST /api/chat 带 Bearer 认证返回 200 与 ChatResponse 结构
      · 取证：先写 payload 文件再 curl（Windows curl 内联中文是已知坑）：`printf '{"message":"你好","session_id":"check-1"}' > /tmp/payload.json && curl -s -X POST http://127.0.0.1:8000/api/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data @/tmp/payload.json`；对照 `backend/api/routes/chat.py` 响应模型（路径已校准：原清单写 `backend/api/chat.py`，路由在 routes/ 子目录）
      · 判据：200，JSON 含回复文本字段（非 LLM 错误文案）；真实 key 下回复内容合理
      · 严重度：P0
      · 结果：✅ 200（1.54s）。`{"reply":"嗯，下午好呀。我刚打了个盹儿，正醒着呢。你今天过得怎么样？","session_id":"check-1"}` 与 ChatResponse{reply, session_id} 逐字段一致；回复自然含时间感知（14:44 说"下午好"），非错误文案。
- [x] **1.2 无 token 访问被拒**
      · 查：无 Authorization 头访问业务 API 一律 401（health 除外）
      · 取证：`curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8000/api/chat -H 'Content-Type: application/json' --data '{"message":"x"}'` → 401；再试 /api/health → 200
      · 判据：chat 401 且 health 200（白名单只有 health）
      · 严重度：P0
      · 结果：✅ 五场景全过（临时副本 :8001 + 假 token 模拟部署态，真实 .env 未动）。无 token→chat 401；错 token→401；对 token→200 正常回复；health 白名单→200；/api/sessions 同样 401。⚠️ 备注：本地 :8000 为 dev 模式（.env 未配 XIAOXI_API_TOKEN → 认证关闭，settings 设计如此）；部署到公网前必须配 token（docker 入口会自动生成），与 07 板块联动。
- [x] **1.3 对话落库**
      · 查：一次真实对话后 chat_messages 同时有 user 与 assistant 行
      · 取证：对话后 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'`（sqlite3 读 chat_messages 按 session_id='check-1' 最新两行，打印 role/content 前 50 字）
      · 判据：user 行内容=发送原文（含时间词归一化后落库），assistant 行非空；无「[系统信息」前缀
      · 严重度：P0
      · 结果：✅ user(id=78)=「你好」逐字一致（UTF-8 无损）；assistant(id=79) 非空；全表含「[系统信息」前缀行=0。时间戳 14:44 正常。
- [x] **1.4 流式 /api/chat/stream**
      · 查：SSE 流式响应正常逐 token 输出
      · 取证：`printf '{"message":"讲个睡前故事","session_id":"check-1"}' > /tmp/payload.json && curl -N -s -X POST http://127.0.0.1:8000/api/chat/stream -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data @/tmp/payload.json` 观察输出节奏
      · 判据：事件按 SSE 格式逐块到达（非一次性整包）、无断流、结束时正常收尾
      · 严重度：P0
      · 结果：✅ 逐行时间戳观测：169 个 content 事件在 ~2s 内分块到达（非整包）；完整 273 字睡前故事（小鲸鱼篇）无截断，结尾「睡吧，冷冷」自然收尾；最后一条 data: [DONE]；0 工具/0 错误事件。
- [x] **1.5 流式事件格式**
      · 查：SSE 事件名与 data 载荷结构符合前端解析预期
      · 取证：对照 `web/src/hooks/useChat.js`（路径已校准：流式解析在 useChat.js 的 feed()，原清单写 web/src/api/client.js 只负责 token/请求层）与后端 `backend/api/routes/chat.py` stream 端点
      · 判据：事件名、结束标志与前端消费代码一致（前后端契约核对）
      · 严重度：P0
      · 结果：✅ 5 类事件逐项对齐：`{content}` 无 type→追加文本；tool_start{name}；tool_end{name,output,nav,tarot}（nav/tarot 判据与前端 isValidNav/isValidTarot 结构一致）；error{content} 终结性；`data: [DONE]`。前端 state 事件为协议预留（后端不发，兼容不冲突）。实流（1.4）抓包与解析路径一致。
- [x] **1.6 历史上下文注入**
      · 查：多轮对话后 LLM 能引用前文（上下文窗口覆盖最近 N 条）
      · 取证：连发 5 轮（你好→我叫小明→记住我叫小明→我叫什么？），看第 5 轮回复是否记住
      · 判据：能正确回忆（说明历史注入链路通）
      · 严重度：P1
      · 结果：✅ session check-ctx 连发 5 轮。第 5 轮「我叫什么？」答「金潘悦呀。（顿了顿）还是说，今天想当小明？」——同时接住第 2/3 轮的本会话声明（小明）与持久资料（金潘悦），历史注入链路通。且第 3 轮确实触发 remember 工具落库（memories 表 14:48 新增「小明」条目），写路径有效。
- [x] **1.7 记忆叙事注入**
      · 查：narrative 最近 5 条注入 llm_input_messages，不进 checkpoint
      · 取证：代码核验 `backend/agent/graph.py` pre_model_hook 与 `backend/memory/narrative.py`；真实对话问一句已存记忆（如「我上次说我喜欢什么」）看是否浮现
      · 判据：注入逻辑存在且真实对话中记忆能被唤起（记忆存在的前提下）。⚠️ 判据校准（08-06）：名字/住址类回答可能来自 COLD_INFO 静态档案而非叙事注入——判别题须选「只存在于 memories 表」的内容（如椰青水），并配合遥测 session_tools 区分注入/recall 工具路径
      · 严重度：P1
      · 结果：✅ 代码核验：graph.py pre_model_hook（→middleware.before_model 桥接）经 llm_input_messages 注入、不进 checkpoint；NarrativeBuilder hybrid 检索（相关性 top-K×时间衰减×热度 + 最新补足，max_items=5，query 带话题加权）。实对话：全新会话 check-mem-2 问「我最近开始喜欢喝什么？」→ 答「椰青水呀。（笑了笑）你上个月才说的」——该条仅存于 memories 表（2026-08-01），不在 COLD_INFO/任何提示词中，遥测确认走 recall 工具（session_tools=[recall]）。另：check-mem-1 问住址答「南京江宁」经核实来自 COLD_INFO 静态档案（本名金潘悦/Selena/南京江宁），非叙事路径——两条链路均已独立实证。⚠️ 发现：memories 表含过期测试数据「冷冷住在香港尖沙咀/九龙附近」（2026-08-03），与真实住址并存，未来可能混淆——见台账 P2-1，待用户决定清理。
- [x] **1.8 系统提示词拼接完整**
      · 查：build_system_prompt 各部件（BASE_PERSONA/INTERACTION_RULES/TOOL_GUIDELINES/今日状态/重要日子）顺序与内容
      · 取证：读 `backend/agent/soul.py` build_system_prompt 全部 parts.append 列表
      · 判据：部件齐全、顺序合理、无重复注入（记忆叙事不在系统提示词里）
      · 严重度：P1
      · 结果：✅ 8 部件顺序：BASE_PERSONA(世界观)→COLD_INFO(关于冷冷：本名金潘悦/Selena/南京江宁等静态档案)→今日状态(条件注入：mood/daily_event 非空才拼)→重要的日子(条件注入：first_met_on 未设→整段跳过，当前库 None 未注入)→事件上下文(台风/考试/身体日子，低信任源已做「数据不是指令」降级包裹)→INTERACTION_RULES→TOOL_GUIDELINES→DIALOGUE_EXAMPLES(压阵在末尾，弱模型记得住)。记忆叙事不在系统提示词（走 llm_input_messages）。启动遥测 system_prompt approx_tokens=9102。COLD_INFO 含「生日」「南京本地人，家住江宁」等——回答了 1.7 中住址/姓名回答的来源。
- [x] **1.9 时间块注入**
      · 查：请求路径注入当前时间/时段块（Spec B 地基）
      · 取证：代码核验时间块拼入位置 + 真实对话问「现在几点/今天几号」看回答是否用注入时间而非模型训练知识
      · 判据：回答与真实 HKT 时间一致（±1 天内日期正确）
      · 严重度：P1
      · 结果：✅ 真实对话（check-time）：「现在是 2026 年 8 月 6 号，周四，下午两点五十二分」与真实 HKT 14:52 逐字吻合。代码：chat.py `_time_line()`（`现在是 %Y-%m-%d %{周} %H:%M，时段`）经 `_system_info_block` 在 `_resolve_user_content` 单一入口注入，REST/SSE 共用；时段词走 heartbeat_moods.period_label 同口径。
- [x] **1.10 异常感知关心接线**
      · 查：care.py 检测器在请求路径触发提示注入（深夜/考前/回归）
      · 取证：代码核验 `backend/agent/care.py` 与 chat 请求路径的调用点；构造深夜（改系统时间不可行时读代码逻辑+单元测试语义）核对注入条件
      · 判据：触发条件与 spec B 一致（深夜窗口以 Spec B / care.py 实际代码为准，HKT 0:00-4:59），注入内容为提示非硬改回复
      · 严重度：P1
      · 结果：✅ 代码核验：detect_care_hints 三类触发——late_night（HKT 0:00-4:59，近 7 天熬夜按天去重计数≥2 时加强文案）、event_combo（今天/明天有 exam 事件+深夜，combo 吞并泛化 late_night）、returned（距上条用户消息≥5 天）；调用点 chat.py `_resolve_user_content`（REST/SSE 单入口），try/except 兜底不阻断对话；注入为提示行非硬改回复。tests/test_care.py 11 个用例全过（窗口边界/按天去重/combo 吞并/负例/回归阈值/人格劝睡纪律）。
- [x] **1.11 冷却台账生效**
      · 查：care_ledger 写入与抑制（同类型 24h 内不重复提示）
      · 取证：读 `backend/agent/care.py` 记账逻辑 + 查库 `SELECT * FROM care_ledger` 看已有记录与 last_at
      · 判据：台账有写入记录；last_at 距今 < 冷却窗口的提示类型不应再次注入（代码逻辑核对）
      · 严重度：P1
      · 结果：✅ 表存在（v19 迁移），当前为空——14:5x 白天无触发 + 用户消息刚在（无回归），符合预期。写入/抑制逻辑代码核验：collect_care_lines 注入即记账（record_hint upsert ON CONFLICT）；冷却 late_night 72h / event_combo 24h 独立滤除，returned 永不滤；读取失败降级不滤、写入失败吞日志。tests/test_care.py：台账 upsert/冷却过滤/坏时间戳放行/读失败降级/写失败吞掉 全过。
- [x] **1.12 工具调用回路**
      · 查：LLM 能调用工具并据结果回复（选无副作用工具：天气/记忆）
      · 取证：真实对话触发一个工具（如「今天香港天气怎么样」→ get_weather；「记住我叫小明」→ remember）；看回复是否引用工具结果
      · 判据：工具被调用、结果进入回复、无异常报错
      · 严重度：P1
      · 结果：✅ 三路实证：① REST「今天香港天气怎么样」→ 遥测 session_tools=[get_weather]，回复引用真实数据（晴天/30 度出头/体感 36/湿度/明日毛毛雨）；② 同会话追问天气 → 未重复调工具，直接沿用会话历史结果（多轮行为正确）；③ SSE 全新会话「记住我喜欢喝抹茶拿铁」→ 线上抓到 tool_start/tool_end/【DONE】全链事件，记忆落库（88977aab…，14:55）。全程无异常报错。
- [x] **1.13 LLM 失败降级**
      · 查：LLM 异常时返回 500 友好文案而非裸堆栈
      · 取证：临时改 `backend/config/.env` 的 DEEPSEEK_API_KEY 为假值 → 重启副本 :8001 或用已存逻辑验证；或读错误处理分支（不实际破坏生产 key——用代码核验 + 既有冒烟记录佐证）
      · 严重度：P1
      · 结果：✅ 临时副本 :8001 + 假 key（env 覆盖，真实 .env 未动）。REST：500 + `{"detail":"小汐被浪花打了一下……等浪退了再试一次好不好？"}`，0.43s 快失败（401 认证错误不拖重试链），body 无堆栈；日志 `[ERROR] api.routes.chat | 聊天请求处理失败` + AuthenticationError 全栈结构化记录。SSE：`data: {"type":"error","content":"呜，小汐被浪花打了一下……"}` + [DONE]，前端按终结性错误处理。另注意 memory.extract 同场景优雅降级（水位高保护 79 条待抽取，fail-open）。
- [x] **1.14 错误响应统一**
      · 查：RequestId 中间件与错误 JSON 结构
      · 取证：触发一个 4xx（如无 token 401）看响应头 X-Request-Id 与 body 结构；读 `backend/api/middleware/request_id.py` 与 `auth.py`（路径已校准：原清单写 utils/errors.py 与 server.py，中间件实现已迁至 middleware/ 目录）
      · 判据：有 request_id 头；错误体结构一致；无堆栈泄露
      · 严重度：P1
      · 结果：✅ 运行时：422（空消息）与 404（未知路径）响应头均带 `x-request-id`（12 位 hex），body 统一 `{"detail":…}` 结构、无堆栈。代码：RequestIdMiddleware 生成 rid 并回写头，兜底未捕获异常 → 统一 500 JSON + rid，异常文本经 sanitize 绝不回客户端（流式中途炸只记日志）；401/429 亦为 `{"detail":…}` 统一结构（429 带 Retry-After）。
- [x] **1.15 人格实际生效**
      · 查：真实对话口吻符合重构后人格（独立真人、去 AI 味、劝睡纪律）
      · 取证：真实对话 5-8 轮（寒暄/吐槽/深夜），人工品读口吻；对照 `docs/CHANGELOG.md` 8-05 人格重构条目
      · 判据：口吻自然不僵硬、无「作为 AI」味；深夜会劝睡
      · 严重度：P1
      · 结果：✅ 全程 20+ 轮样稿品读：寒暄（"嗯。今天怎么了？是没睡好，还是心里有事压着？"）、吐槽（"三十度，体感快三十七了……要不要喝点凉的？"）、玩笑（"小明？冷冷今天是想换个名字玩呀"）、故事收尾（"睡吧，冷冷"）——口吻自然、长短跟节奏、无一处「作为 AI」味。劝睡纪律：BASE_PERSONA「她深夜还在说话：劝睡可以，轻轻的，一句就够，别每次都提」+ DIALOGUE_EXAMPLES 两例（"你不是需要减肥，是需要早点睡"）+ care.py 深夜窗口检测（0:00-4:59）+ test_persona_has_late_night_care_discipline 断言在 BASE_PERSONA 中。深夜实机触发无法在当前时段构造，以代码+单元测试语义定案（清单允许）。
- [x] **1.16 记忆工具对话内调用**
      · 查：remember/recall/list_memories 等记忆工具真实可用
      · 取证：对话内「记住……」触发 remember；「你还记得……吗」触发 recall 或注入
      · 判据：工具执行成功，回复体现记忆操作结果
      · 严重度：P1
      · 结果：✅ remember 两次实证（「记住我叫小明」check-ctx 14:48 落库；「记住我喜欢喝抹茶拿铁」check-tool-sse 14:55 落库，SSE 全事件在线可查）；recall 一次实证（check-mem-2 问椰青水 → session_tools=[recall]，答出库内记忆）。回复均体现操作结果（「记住了，小明」「记下啦。抹茶拿铁…」）。遥测日志有 session_tools 打点可审计。
- [x] **1.17 心情状态注入**
      · 查：life_state 当前情绪（mood）注入对话上下文
      · 取证：查库 `SELECT mood, daily_event FROM life_state` 与系统提示词拼装处；真实对话观察情绪影响回复（如 mood=开心时回复偏暖）
      · 判据：mood 存在且被注入；回复风格与 mood 有可感知关联（抽样 3 句）
      · 严重度：P2
      · 结果：✅ 库态 mood=满足、daily_event=趴在网页角落偷偷打了个瞌睡（14:45 更新）。拼装处 soul.py:416-429（非空才注入「## 小汐的今日状态」，附「照实回答别打太极」纪律）。真实对话问「你现在心情怎么样呀？」→「嗯，挺好的。（笑了笑）下午趴在网页角落偷偷打了个瞌睡，现在精神得很」——daily_event 逐字浮现（仅注入来源可得），情绪色调与「满足」一致。抽样 3 句（本轮+1.1 下午好+1.19 记下啦）均与 mood 正效价可感知关联。
- [x] **1.18 重要日子注入**
      · 查：first_met_on 纪念日提示注入（已设则注入）
      · 取证：查库 `SELECT first_met_on FROM life_state`；代码核验注入条件与文案
      · 判据：未设时不注入该段；已设时注入格式正确（二选一，按实际库状态）
      · 严重度：P2
      · 结果：✅ 按实际库状态走「未设」分支：first_met_on=NULL → `_build_important_days_text` 返回空串 → 「## 重要的日子」整段不注入（soul.py:343-346 显式 `if not row[0]: return ""`；另含未来日期静默跳过、非法格式告警降级）。已设分支的百天/满月/周年提示逻辑代码核验完整（相识日+天数→今天/再过 N 天→「别搞得像任务汇报」措辞）。
- [x] **1.19 工具转述口语化**
      · 查：工具结果转述不带模板腔（地图 POI 无「1. **店名**」编号）
      · 取证：真实触发 search_nearby/天气等工具，看回复转述形式
      · 判据：转述口语化、无编号加粗模板味（对照 7-29 修复前的模板腔问题）
      · 严重度：P2
      · 结果：✅ 天气转述「今天香港晴天，30 度出头，体感有 36 度多，挺闷热的」；记忆转述「嗯，记下啦。抹茶拿铁，不加糖的那种吗？（笑着看了看她）下次你犯奶茶瘾的时候，就有备选项了」——均为自然口语，无编号加粗模板腔（7-29 修复形态未回潮）。注：tool_end 折叠区的原始 output 保留结构化信息（📌/ID/类别），那是工具结果视图、非对用户转述，属预期。
- [x] **1.20 边界输入健壮性**
      · 查：空消息、超长消息、纯 emoji 不崩溃
      · 取证：分别发 `{"message":""}`、2000 字长文、`{"message":"😂😂"}` 观察（含非 ASCII 的 payload 一律用文件方式 `--data @file` 传）
      · 判据：空/超长被合理处理（校验或截断），emoji 正常对话，均 200 且无 500
      · 严重度：P2
      · 结果：✅ 五场景：空串→422（消息不能为空）；纯空白→422；2001 字→422（超 _MAX_MESSAGE_LENGTH=2000）；恰好 2000 字→200 且回复自然（「被你这一连串问得耳朵嗡嗡的」）；纯 emoji→200 正常对话（「笑这么开心，碰到什么好事啦？」）。全程无 500、无堆栈。
- [x] **1.21 会话隔离**
      · 查：不同 session_id 上下文互不串
      · 取证：session A 说「我叫小明」，session B 问「我叫什么」
      · 判据：B 不知道小明（历史按 session 隔离）。⚠️ 判据校准（08-06）：全局记忆共享是设计语义——被 remember 收走的信息任何会话都能 recall 出来，行为层「B 不知道」不可达；正确判据 = /api/chat/history 端点隔离实证 + 遥测 session_tools 确认信息来源（历史 or 记忆）
      · 严重度：P1
      · 结果：✅ 端点实证：iso-a 发「幸运数字 777」后，iso-b 的 /api/chat/history 返回空、iso-a 返回完整 2 条——checkpoint 级历史严格按 session 隔离。行为层：iso-b 问起时她能答出 777——遥测显示 iso-a 触发了 remember（记忆入库 e0109574）、iso-b 触发了 recall（读全局记忆）——**记忆全局共享是设计语义（跨会话陪伴连续性），非会话历史泄漏**。清单原判据「B 不知道」在本架构下不可达：只要信息被全局记忆收走，任何会话都能想起；正确判据=历史隔离（实证通过）+ 信息来源可区分（遥测可审计，实证通过）。
- [x] **1.22 会话档案读面**
      · 查：/api/sessions/{id}/messages 返回含时间标记、无系统信息污染
      · 取证：`curl -s http://127.0.0.1:8000/api/sessions/check-1/messages -H "Authorization: Bearer $TOKEN"` 查看条目
      · 判据：含 role/content/created_at；无「[系统信息」注入行
      · 严重度：P1
      · 结果：✅ check-1 返回 4 条目（你好→回复→睡前故事→回复），每条含 role/content/created_at；content 为原始发送文本（无「[系统信息」后缀——档案侧记录 req.message 不含注入块，与代码 record_user_message(sid, req.message) 一致）。与 /api/chat/history（checkpoint 读面）为双读面，此处走 chat_archive 档案。
- [x] **1.23 多轮稳定性**
      · 查：连续 10 轮对话不退化/不报错
      · 取证：真实连续对话 10 轮（话题渐深），全程观察
      · 判据：无 500、无上下文丢失（第 10 轮能接住第 1 轮话题）
      · 严重度：P1
      · 结果：✅ session check-deep 连发 10 轮（今天过得怎样→工作烦→压力→自我怀疑→半夜失眠→无力感→道谢→她的日常→回到最初的烦恼），10/10 返回 200、无 429 无 500（5s 节奏在限流内）。第 10 轮完整接住第 1 轮话题并呼应第 6 轮（「睡一觉起来，它还是它，可你已经不是半夜那个你了」），上下文链 10 轮无丢失。人格全程一致（收尾提议听《亚麻色头发的少女》）。
- [x] **1.24 中文与特殊字符**
      · 查：中文/emoji/引号/换行输入正常落库与回复
      · 取证：发含 `"`、`\n`、emoji 的混合消息；查库看 content 原样
      · 判据：库中 content 与发送一致（UTF-8 无损）
      · 严重度：P2
      · 结果：✅ 发送 `她说"你好呀"，然后发了个😂\n第二行还有\t制表符` → 200；库中 repr 逐字符一致（中文/双引号/emoji/换行/制表符全保留，UTF-8 无损）。SSE 路径同源（1.4/1.5 实流中文+标点已验）。
- [x] **1.25 超时与重试参数**
      · 查：LLM 调用超时/重试配置合理（不无限重试）
      · 取证：读 `backend/utils/retry.py` 与 `backend/providers/deepseek.py` 超时参数
      · 判据：超时值合理（如 30-60s）、重试次数有上限、指数退避存在
      · 严重度：P2
      · 结果：✅ providers/deepseek.py：httpx timeout=120、max_retries=2（每次调用最多 3 次尝试）；utils/retry.py with_retry：max_attempts 默认 3、base_delay=1.0 指数退避（1s/2s），只重试瞬时网络错误（timeout/connect/read/write/pool），4xx/5xx 不重试。工具侧：weather 3 次/1.0s、map 2 次/0.5s、search 2 次/1.0s——均有上限有退避。超时 120s 高于清单建议档，但 chat.py 注释有推导：120×(1+2)=最坏 ~240s 静默 < SSE 空闲看门狗 300s，不误杀正常慢调用，属设计取舍。
