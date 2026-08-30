# 补查：跨交互入口的会话连续性与状态同步

## LangGraph Checkpointer vs Store 分层模型为多渠道提供原生解法

LangGraph 官方文档明确将持久化拆分为两层：Checkpointer 是线程级（thread-scoped）短期记忆，每个 thread_id 维护独立的 checkpoint 链；Store 是跨线程（cross-thread）长期记忆，按 (namespace, key) 组织 JSON 文档。对多渠道场景的指导是：不要把 user_id 拼进 thread_id，而是保持每个渠道一个 thread_id，再通过 Store 的 user namespace 共享画像/偏好/关系阶段等长期事实。论坛讨论进一步警告：SqliteSaver 适合单机开发，但并发写入时会阻塞，生产多渠道应换 PostgresSaver 或在 SQLite 上套 WAL+busy_timeout。这对小汐意味着：现有 SQLite 记忆系统天然对应 Store 层（已是六因子召回），缺的是把 web 会话和 Telegram 会话视为两个独立 thread 但共享同一个 user namespace 的桥接；不需要合并消息历史，只需在每次 LLM 调用前从 Store 拉取统一的 persona_anchor + 关系段 + 情绪基线。

**对小汐的启示**: 可迁移：立刻在小汐 SQLite 中新增 channel_threads 表（user_id, channel, thread_id, last_checkpoint_ts），web 和 Telegram 各自维护独立消息流但查询时 join 到同一 user profile；长期记忆继续走现有六因子召回无需改造。不该照搬：不要为了对齐 LangGraph 把 SQLite 换成 Postgres——单用户场景下 SQLite WAL 模式 + asyncio.Lock(per-user) 已足够，引入 PG 会增加自托管运维成本且违背预算约束。

**来源**: https://docs.langchain.com/oss/python/langgraph/persistence | https://forum.langchain.com/t/multi-tenant-per-user-checkpoint-querying-with-asyncpostgressaver/2604 | https://machinelearningplus.com/gen-ai/langgraph-memory-systems-short-long-term-conversation/

## Aiogram 3 FSM GLOBAL_USER 策略 + 自定义 KeyBuilder 实现跨渠道状态共享

aiogram 3.x 的 FSM 默认按 (bot_id, chat_id, user_id) 三元组生成状态键，这意味着同一用户在私聊、群组、Web App Mini 中的状态彼此隔离。要跨渠道共享，必须自定义 KeyBuilder 或切换到 GLOBAL_USER 策略，让状态键只含 user_id；同时存储后端必须外置（Redis/Mongo/SQLAlchemy），不能用 MemoryStorage。社区 issue #1633 记录了典型踩坑：用户在 A 聊天进入填表状态，切到 B 聊天后状态丢失，改用 GLOBAL_USER + Redis 后解决。aiogram-sqlalchemy-storage 包允许直接把 FSM 状态写进 SQLAlchemy 表，这和小汐现有 SQLite 技术栈完全兼容。对小汐而言，这意味着 Telegram bot 的对话状态（如「正在记录生理期」「正在设提醒」）可以和 web 端共享同一个状态机，用户在手机上开始、在电脑上继续不会丢上下文。

**对小汐的启示**: 可迁移：给小汐加 aiogram 3 + aiogram-sqlalchemy-storage，定义 ChannelAgnosticKeyBuilder(user_id only)，FSM 状态表复用现有 SQLite；web 端 FastAPI 通过同一张表读取当前 FSM 状态，前端渲染对应提示（'你刚才在 Telegram 开始记录生理期，要继续吗？'）。不该照搬：不要为了跨渠道强行上 Redis——单用户 SQLite 足够，且避免增加部署组件；GLOBAL_USER 策略在多用户 SaaS 中有隐私风险，但小汐是单用户无此顾虑。

**来源**: https://docs.aiogram.dev/en/latest/dispatcher/finite_state_machine/storages.html | https://github.com/aiogram/aiogram/issues/1633 | https://pypi.org/project/aiogram-sqlalchemy-storage/

## Telegram Webhook 幂等性与 update_id 去重是多入口必过的第一道关

Telegram Bot API 不保证 webhook 只投递一次：网络抖动、bot 重启、超时都会触发重试，且没有内置幂等键。官方唯一契约是 update_id 在同一 bot 内单调递增且同一事件重试时 update_id 不变。Stack Overflow 和 tdlib issue #126 都强调必须在应用层自行去重。实践模式是：webhook handler 立即返回 200 OK（防止 Telegram 重试风暴），把 update 推入 asyncio.Queue 或内存 ring buffer，由后台 worker 串行处理；处理前检查 update_id 是否 <= last_processed[user_id]，是则丢弃。对小汐的关键点是：当用户在 web 活跃时，Telegram 推送可能延迟到达，如果此时 web 刚写完一条记忆，Telegram 旧消息再入库会造成时序错乱。解决方案是给每条入站消息打 server_ts 而非 trust client date，并在写入记忆时按 server_ts 排序而非 arrival order。

**对小汐的启示**: 可迁移：在小汐 FastAPI 新增 /telegram/webhook 端点，handler 只做 update_id 去重 + 入队（<5ms 响应），worker 持 per-user asyncio.Lock 串行处理；记忆写入统一用 server_ts 字段并建索引，六因子召回的时间衰减改为基于 server_ts 而非 message.date。不该照搬：不要用 Redis 做去重缓存——单用户场景 dict[user_id, last_update_id] in-memory + SQLite 持久化兜底即可；不要相信 Telegram message.date 作为时序依据，它受客户端时钟影响。

**来源**: https://github.com/tdlib/telegram-bot-api/issues/126 | https://stackoverflow.com/questions/64948270/concurrency-issue-when-processing-webhooks | https://stackoverflow.com/questions/31707256/prevent-getting-old-updates-from-telegram-bot-api-using-a-web-hook

## 并发消息处理的五种策略中 queue + interrupt-resume 最适合陪伴型 Agent

Chat SDK 文档列出五种重叠消息策略：burst（全部并发）、queue（FIFO 串行）、debounce（合并短时间多条）、drop（丢弃中间态）、concurrent（带锁并发）。对 AI 陪伴场景，drop 和 debounce 都是灾难——用户发的每条消息都承载情感信号，丢了就人格崩塌。Restate  durable execution 博客展示了另一种思路：把每次 LLM 调用建模为 durable task，中途被打断可从 checkpoint 恢复而非重跑。LangGraph 的 interrupt()/Command(resume=...) 也是同构机制。对小汐的具体问题是：用户在 web 打字到一半，Telegram 来了一条语音，当前 generation 要不要取消？答案是分优先级：(a) 如果 generation 还没开始流式输出，安全取消并 merge 两条输入重新规划；(b) 如果已经在流式回复，标记为 interrupted，把新消息入队作为下一轮 turn，而不是暴力 cancel。这需要小汐当前的 LangGraph middleware 暴露 cancellation token。

**对小汐的启示**: 可迁移：给小汐 LangGraph agent 加 InterruptToken 概念，generation 开始前检查 pending_queue，有则 merge-and-replan；generation 中收到新消息则 enqueue 而非 cancel；frontend/websocket 协议加 interrupted 事件让 UI 显示 '小汐看到你发了新消息，稍等我看完刚才那条'。不该照搬：不要学 voice AI 的 barge-in 全双工打断——文字陪伴场景用户对'被打断'更敏感，宁可排队也不要截断回复；durable execution 框架（Restate/Temporal）对单用户过重，LangGraph 自带 interrupt 已够用。

**来源**: https://chat-sdk.dev/docs/concurrency | https://www.restate.dev/blog/building-an-llm-chat-task-bot-with-restate | https://docs.langchain.com/oss/python/langgraph/interrupts

## 多渠道消息必须先过 Normalization Layer 才能进入统一记忆，否则人格会被渠道格式污染

AWS Bedrock AgentCore 的多渠道指南明确提出 identity resolution 层：WhatsApp 用 phone_number、Instagram 用 ig_user_id，在进入 agent 前先映射到 unified_customer_id，DynamoDB 存 mapping table + shared memory namespace。OpenClaw 的 hub-and-spoke 架构异曲同工：Gateway 是唯一控制面，所有渠道插件（Telegram/Discord/Slack/WebChat）先把原始消息转成 normalized envelope（sender_unified_id, content_text, attachments[], source_channel, source_message_id），再交给 agent core。这两者共同点是：agent core 永远不知道消息来自哪个渠道，渠道差异在 gateway 层就被抹平。对小汐的意义是：不能简单地把 telegram_bot.py 和 web_api.py 各写一套消息处理逻辑然后共用数据库，而要在它们之上抽一层 MessageNormalizer，把所有入口的消息转成统一 Envelope dataclass，再喂给同一个 LangGraph graph。否则 persona prompt 会因为渠道特有的格式噪声（Telegram 的 markdown、web 的 HTML、语音转文字的口语化）而产生风格漂移。

**对小汐的启示**: 可迁移：在小汐新建 app/channels/envelope.py 定义 MessageEnvelope(user_id, text, media_refs, channel, raw_id, server_ts)；telegram_adapter / web_adapter / future_voice_adapter 都只负责 convert-to-envelope；LangGraph entry node 接收 Envelope 而非 raw update。附件（图片/语音）先异步转写/描述成文本摘要塞进 envelope.text，原文件路径存 media_refs 供后续按需检索。不该照搬：不要抄 AWS 的 DynamoDB identity resolution 表——小汐单用户，unified_id 就是硬编码常量，mapping 表退化为 config 即可；不要引入 OpenClaw gateway 这种重型运行时，自己写 200 行 adapter 接口就够了。

**来源**: https://dev.to/aws/multichannel-ai-agent-shared-memory-across-messaging-platforms-56j4 | https://docs.openclaw.ai/gateway/security | https://milvus.io/zh/blog/openclaw-formerly-clawdbot-moltbot-explained-a-complete-guide-to-the-autonomous-ai-agent.md

## Proactive 消息必须带 session-awareness 闸门，否则多入口会变成多重打扰

多篇 UX 研究（tunde.me 2026、arXiv:2601.10253、Autoplay AI）达成共识：proactive 消息的核心设计原则是 'earn the right to interrupt'。具体机制包括：(1) 实时 session awareness——如果用户当前在 web 活跃（最近 N 秒有 keystroke/ws heartbeat），suppress 或 delay 所有非紧急 proactive push；(2) on_pause trigger——只在检测到用户停止交互 T 秒后才触发关心类消息；(3) 跨渠道去重——同一 proactive event 不能在 web push + Telegram 都发一遍，要有 unified dispatch ledger 记录已送达渠道。beff.life 指出 companion 类产品区别于普通 chatbot 的分水岭就是能否在 app 关闭时主动触达，但这恰恰是最容易引发 interruption fatigue 的点。对小汐现有 Spec B（异常感知关心+冷却台账）的启示是：冷却台账必须升级为 channel-aware，且与 web session 心跳联动；否则冷冷在网页上正跟小汐聊得投入，手机又弹一条'你是不是熬夜了'会严重破坏沉浸感。

**对小汐的启示**: 可迁移：给小汐加 session_active(user_id) -> bool 函数（基于 ws 最后心跳 or 最近 API call < 60s）；proactive dispatcher 在下发前检查 if session_active and urgency < HIGH then defer 5min；dispatch ledger 存 SQLite (event_id, delivered_channels[], delivered_at)，同一 event 24h 内最多触达 1 个首选渠道。不该照搬：不要照抄 Intercom 的 real-time session API 复杂度——单用户场景一个 Redis-free 的 in-memory last_active_ts + SQLite fallback 即可；不要学 make.com 那种'bot talk until user close'的无限循环模式，陪伴类 agent 必须尊重沉默权。

**来源**: https://tunde.me/blog/proactive-ai-notification-design-interruption-right | https://arxiv.org/html/2601.10253v1 | https://beff.life/en/proactive-ai | https://blog.autoplay.ai/p/making-intercom-chat-proactive-with

## Anthropic PSM 理论确认 persona 是 constitutive 的，渠道切换本身构成 drift 风险需主动锚定

Anthropic 2026-02 发布的 Persona Selection Model（PSM）提出：LLM 在 pre-training 中学会模拟多种角色，post-training 主要是 elicitation 而非 teaching new persona；persona 是 constitutive（决定泛化行为）而非 cosmetic（表面措辞）。紧接着的 Assistant Axis 文章定义了 organic persona drift：即使没有对抗攻击，纯对话上下文累积也会让模型滑离预设人格。Zylos 2026-04 的实测显示 persona-consistent chatbot 比不一致的高 67% engagement，但测量方法依赖 controlled eval set。arXiv:2511.02979 的四象限分类法把 companion 按 emotional-connection × functional-augmentation 分象限，指出跨模态切换（voice↔text）属于 technical implementation 轴上的高风险扰动点，但目前没有公开 benchmark。对小汐的含义是：现有 PERSONA_ANCHOR 每 8 次调用重锚是对的，但还不够——渠道切换本身应该被视为一次 anchor trigger，因为 Telegram 的消息长度、emoji 使用习惯、语音转文字的口语化都会 pull model toward different latent persona。

**对小汐的启示**: 可迁移：把 channel_switch 加入 PERSONA_ANCHOR 触发条件（除原有的 8-call 计数外）；在 envelope normalization 阶段注入 channel-specific style hint（Telegram: concise+emoji-friendly; web: longer-form; voice-transcript: tolerate disfluency but respond in written register）作为 system prompt suffix 而非 persona 本体，避免污染核心人格；建立最小 drift 监控：每周抽样对比 web-only vs telegram-only 回复的词汇多样性/情绪词频，偏离 >2σ 触发人工 review。不该照搬：不要尝试复刻 NVIDIA PersonaPlex 的全双工语音 conditioning——小汐当前是文本优先，语音是未来增量；不要迷信 Zylos 67% 数字，它的 eval set 是商业客服场景，与陪伴场景目标函数不同。

**来源**: https://alignment.anthropic.com/2026/psm/ | https://www.anthropic.com/research/assistant-axis | https://zylos.ai/research/2026-04-10-ai-agent-persona-design-behavioral-consistency/ | https://arxiv.org/abs/2511.02979

## IronEngine 的 unified orchestration core 为单用户多表面 agent 提供了架构范式

arXiv:2603.08425 IronEngine 提出了面向通用 AI 助手的 unified orchestration core 架构，明确连接 desktop UI、REST API、cross-platform interface 三类表面。其核心设计是把所有外部交互抽象为 capability requests，orchestrator 根据请求来源、当前 active surface、用户偏好路由到合适的执行路径，并把结果反向适配回源渠道的呈现格式。这与小汐的需求高度吻合：web 是富媒体桌面体验（立绘、天空、日历视图），Telegram 是轻量移动触达（纯文本+inline button+语音条），同一语义内容（比如'今天生理期第2天，注意保暖'）在两个表面的表达形式必须不同但人格一致。IronEngine 还强调了 single-user assumption 下的简化空间：不需要 multi-tenant isolation，orchestrator 可以直接持有全局 mutable state，这正好是小汐的优势。论文未开源代码，但架构图可作为小汐 channel adapter 设计的参考蓝本。

**对小汐的启示**: 可迁移：借鉴 IronEngine 的 capability-request 抽象，在小汐定义 IntentEnvelope(intent_type, payload, preferred_surface?)，让 agent core 产出意图而非最终文案，再由 channel renderer 根据目标渠道格式化（web: 带立绘表情+CSS class; telegram: markdown+inline keyboard; voice: TTS-ready plain text）。single-user 优势要用足：renderer 可以持有全局 theme_state（当前天空色、关系段、情绪基调）而不必每次请求重建。不该照搬：IronEngine 是学术原型，未经验证生产稳定性，不要直接复制其 orchestrator 实现；其 desktop UI 假设是 Electron 级富客户端，小汐 web 是浏览器 React，capabilities 集合需裁剪。

**来源**: https://arxiv.org/abs/2603.08425 | https://huggingface.co/papers/2603.08425

## 项目

- **aiogram 3 + aiogram-sqlalchemy-storage** (https://docs.aiogram.dev/en/latest/dispatcher/finite_state_machine/storages.html) [PyPI stable, 3k+ GitHub stars (aiogram)] — FSM GLOBAL_USER 策略 + SQLAlchemy storage 可直接对接小汐 SQLite，零 Redis 依赖实现 Telegram↔Web 状态共享
- **OpenClaw Gateway** (https://docs.openclaw.ai/) [Open-source, active 2026] — Hub-and-spoke 多渠道网关范式值得借鉴，但运行时太重；小汐应提取其 normalized envelope 思想自写轻量 adapter 层
- **likenneth/persona_drift** (https://github.com/likenneth/persona_drift) [Research code, small repo] — 提供 persona drift 可视化与度量脚本，可作为小汐周度 drift 监控的起点，需替换为中文情绪词表
- **KirtiJha/langgraph-interrupt-workflow-template** (https://github.com/KirtiJha/langgraph-interrupt-workflow-template) [Template repo, provider-agnostic] — 生产可用的 interrupt/resume 模板，可直接适配小汐 LangGraph middleware 实现跨渠道 generation 中断安全

## 论文

- **Systematizing LLM Persona Design: A Four-Quadrant Technical Taxonomy for AI Companion Applications** (arXiv:2511.02979) 2025 — AI companion 的四象限分类法（emotional×functional），跨模态切换被列为 persona drift 高风险点，但缺乏量化 benchmark
- **IronEngine: Towards General AI Assistant** (arXiv:2603.08425) 2026 — Unified orchestration core 连接 desktop/REST/cross-platform，single-user assumption 允许全局 mutable state 简化多渠道路由
- **Developer Interaction Patterns with Proactive AI: A Five-Day Field Study** (arXiv:2601.10253) 2026 — Proactive AI 的五日田野研究发现 timing intervention 必须对齐用户 workflow，on_pause trigger 比定时触发更少打扰
- **Proactive Conversational Agents with Inner Thoughts** (arXiv:2501.00383) 2025 — Proactive conversational agent 的 inner thoughts 机制，on_pause 作为触发时机，可借鉴用于小汐 Spec B 的异常感知关心

## 建议

- P0 立即做：定义 MessageEnvelope dataclass + channel adapter 接口（web/telegram 两个实现），所有入站消息归一化后再进 LangGraph；这是后续一切跨渠道能力的地基，工作量约 2-3 天，无外部依赖
- P0 立即做：给 webhook 端点加 update_id 去重 + asyncio.Lock(per-user) 串行处理，防止 Telegram 重试污染记忆时序；与小汐现有 fail-closed 安全哲学一致，1-2 天可完成
- P1 下一批：接入 aiogram 3 + aiogram-sqlalchemy-storage，启用 GLOBAL_USER FSM 策略，让 Telegram 上的工具调用状态（生理期记录/提醒设置）可在 web 端续接；这是 Path 5 Telegram Bot 上线的前置条件
- P1 下一批：升级 proactive dispatcher 加 session_active 闸门 + dispatch ledger，把现有 Spec B 冷却台账扩展为 channel-aware；避免多入口变成多重打扰，直接影响冷冷的信任感
- P2 观察后做：PERSONA_ANCHOR 增加 channel_switch 触发条件 + 周度 drift 抽样监控；先用 likenneth/persona_drift 脚本跑 baseline，确认 web/telegram 回复分布差异是否真超 2σ 再决定是否调整锚定频率
- P2 观察后做：IntentEnvelope + channel renderer 分离（借鉴 IronEngine），让 agent 产出意图而非文案；这项重构收益大但面广，建议在 Telegram Bot 稳定运行 1 个月后再启动，避免同时改太多变量
- 不做：不要引入 Redis/Postgres/RabbitMQ 等外部中间件——单用户 SQLite WAL + asyncio 原语已覆盖所有并发场景；不要照搬 OpenClaw/AWS AgentCore 的重型 gateway 运行时；不要追求全双工语音打断能力直到文本多渠道稳定

## 警示

- WebFetch 被网络策略阻断，arXiv/LinkedIn/dev.to/OpenClaw docs 等原文未能直接抓取，论文细节主要来自搜索摘要与社区综述，需后续人工核验 arXiv:2511.02979、arXiv:2603.08425、arXiv:2601.10253 的完整章节
- OpenClaw/Mem0/AWS Bedrock AgentCore 的多渠道方案均为厂商自述或商业文档，缺少独立第三方基准；对小汐这种单用户 SQLite 项目的可移植性需要 PoC 验证
- Persona drift 量化指标（Zylos 67%、Anthropic assistant axis）来自不同实验设置，跨模态（语音↔文本）切换导致漂移的实证研究仍然稀缺，小汐若做语音入口需自建评测集
- Telegram Bot API 的 update_id 去重机制仅保证同一 bot 实例内单调递增，若未来引入多 bot 或 polling/webhook 混用，需要额外全局去重键

