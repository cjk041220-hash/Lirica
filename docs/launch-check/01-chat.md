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
      · 判据：触发条件与 spec B 一致（深夜窗口以 Spec B / care.py 实际代码为准，HKT 0:00-4:59），注入内容为提示非硬改回复
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
