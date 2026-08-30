# 通用工作能力（贾维斯化核心）

## MCP 协议已迭代至 2026-07-28 无状态版，FastAPI 接入成本极低

MCP 协议从 2024-11 首发到 2026-07-28 经历了四个大版本：2025-03-26 引入 roots/sampling、2025-06-18 加入 elicitation（服务端向用户追问）、structured tool output、OAuth 2.1 安全加固、2025-11-25 引入实验性 Async Tasks（长任务返回 taskId 轮询）和 Extensions 框架、2026-07-28 彻底重构为无状态核心——去掉 session handshake，Streamable HTTP transport 要求 Mcp-Method/Mcp-Name header，原生适配 CDN/Worker/Serverless。Python 侧 fastapi_mcp（tadata-org）可零配置将现有 FastAPI endpoint 自动暴露为 MCP tool（读 Pydantic schema+docstring），两行代码即可启用；另有 gofastmcp.com 的 FastMCP 库提供独立 server 模式。对小汐而言，现有 22 个硬编码工具的 REST API 几乎可以无损转为 MCP tool，且 DeepSeek V3.1/V4 已稳定支持 function calling，社区有 deepseek-mcp-server 适配器。

**对小汐的启示**: 可迁移：把小汐 22 个工具用 fastapi_mcp 包装成 MCP server，获得标准化发现/调用接口，未来接任何 MCP client（Claude Desktop、OpenClaw、LibreChat）都零改动。不该照搬：不要直接依赖公共 registry（Smithery 2025-10 被曝路径遍历漏洞泄露 3000+ server API key），单用户自托管项目自建本地 registry 或干脆不用 registry 只靠静态配置文件列举 trusted server 即可。优先级高，ROI 最高的一步。

**来源**: https://modelcontextprotocol.io/specification/2025-06-18 | https://workos.com/blog/mcp-2025-11-25-spec-update | https://blog.modelcontextprotocol.io/posts/2026-07-28/ | https://github.com/tadata-org/fastapi_mcp | https://gofastmcp.com/integrations/fastapi

## MCP 安全生态脆弱，单用户自托管需自建信任边界而非信任 registry

2026 年 1-4 月研究者披露 40+ MCP CVE，覆盖 Python/TS/Java 实现的注入、不安全反序列化、配置缺陷。arXiv:2603.05637（Taraghi et al., 2026-03）建立了首个 MCP 软件故障分类体系，含 prompt sanitization 缺失等类别。NSA/CISA 2026-06 发布 MCP 安全设计指南警告动态工具调用的隐式信任风险。GitGuardian 2025-10 发现 Smithery.ai 路径遍历漏洞波及 3000+ server。官方 registry 明确声明不做代码扫描，委托外部生态。当前最佳实践已从「trust by listing」转向「zero-trust verification」——registry 仅提供发现，消费方必须自己实现 gating/scanning/policy。LangGraph checkpointer 自身也被发现 RCE 链（CVE-2025-67644, CVE-2026-28277）。

**对小汐的启示**: 可迁移：小汐作为单用户自托管项目天然具备最强信任边界——所有 MCP server 跑在本机 Docker 容器内、不暴露公网、工具白名单写在配置里。这比任何企业级 gateway 都安全。不该照搬：不要引入 AgentRegistry/Kong MCP Registry 等企业级发现层，单人项目维护这些基础设施的成本远超收益。安全策略应该是「本地 stdio transport + 白名单 + 输入校验中间件」，而非 OAuth flow。

**来源**: https://arxiv.org/html/2603.05637v1 | https://blog.gitguardian.com/breaking-mcp-server-hosting/ | https://labs.cloudsecurityalliance.org/research/csa-research-note-langgraph-rce-chain-20260614-csa-styled/ | https://media.defense.gov/2026/Jun/02/2003943289/-1/-1/0/CSI_MCP_SECURITY.PDF

## Computer Use/GUI Agent 已达人类水平但可靠性仍不足，对小汐 ROI 低

OSWorld 基准从 2024 初 12% 飙升至 2025 末 72.6%（超越人类基线 72.36%），2026-08 顶级 agent Coasty 达 82%、Claude Opus 4.6 达 72.7%。UI-TARS-2（ByteDance, 2025-09）在 OSWorld 47.5%/WindowsAgentArena 50.6%/AndroidWorld 73.3%。Microsoft UFO 2（2025-04）提出 Desktop AgentOS 统一 GUI+API 执行模型；OmniParser V2 在 ScreenSpot-Pro 达 39.5%。但 Stanford HAI 2026 AI Index 指出 agent 仍失败大量真实任务，benchmark 与实用差距巨大。Mobile-Agent-v3.5（2026-02）推 GUI-Owl1.5 跨平台基础模型。关键问题：这些都需要视觉模型+屏幕截图流，token 消耗极高，且 DeepSeek 目前无原生 computer use 能力。

**对小汐的启示**: 不该照搬：现阶段给小汐加 computer use 是投入产出比最低的路线。DeepSeek 不支持原生 computer use，需要额外部署视觉模型（如 UI-TARS 7B/72B），阿里云 HK 单机预算扛不住；且 GUI agent 失败率 30-50% 对陪伴型 agent 的信任伤害远大于收益。可迁移：关注 browser-use（Python/Playwright）作为轻量替代——它走 DOM 解析而非纯视觉，token 成本低一个数量级，适合「帮主人查网页信息」这类低频任务。建议列入 Phase 3 而非 Phase 1。

**来源**: https://coasty.ai/blog/osworld-benchmark-results-2026-ai-computer-use-agents-ranked | https://arxiv.org/html/2509.02544v1 | https://arxiv.org/html/2504.14603v2 | https://zylos.ai/research/2026-02-08-computer-use-gui-agents/ | https://hai.stanford.edu/ai-index/2026-ai-index-report

## browser-use 是当前最适合个人项目的浏览器自动化方案

browser-use（GitHub 21K+ stars）是 Python 库，基于 Playwright（2026 部分迁移至直接 CDP），LLM 无关，WebVoyager 基准 89% 成功率。架构：LLM 接收 DOM snapshot+截图 → 决策 → Playwright 执行动作 → 循环。2026-05 发布 Production Architecture 文档，已有从本地脚本到百万 agent 的扩展路径。对比 Skyvern（更擅长陌生网站自主导航但有 managed 依赖）、Stagehand（TypeScript 生态），browser-use 是最纯粹的 Python-native 方案，可直接 pip install 集成到小汐的 LangGraph agent 中。关键优势：不需要视觉模型，DOM-based 交互 token 成本可控；支持 code mode（让 LLM 写 JS 片段执行复杂操作）；与小汐现有 Playwright E2E 测试基础设施复用。

**对小汐的启示**: 可迁移：作为小汐「调研能力」的执行层，配合 open_deep_research 模式使用。封装为一个 LangGraph tool，当主人说「帮我查一下 XX」时触发。利用现有 Playwright+Edge 基础设施，零额外依赖。不该照搬：不要上 Skyvern 的 managed cloud 或 headless browser farm，单用户项目本地 Playwright 实例足够。注意：browser-use 的自主循环可能产生意外操作，必须加确认机制（小汐已有 fail-closed 安全文化，正好适配）。优先级中，Phase 2。

**来源**: https://github.com/browser-use/browser-use | https://browser-use.com/posts/production-architecture-browser-use | https://michaellivs.com/blog/state-of-browser-use-2026/ | https://machinelearningmastery.com/building-browser-using-ai-agents-in-python/

## Open Deep Research 模式成熟，LangGraph 三阶段架构可直接移植

LangChain open_deep_research（2025-07 开源）采用 Scope→Research→Write 三阶段 LangGraph workflow：Scope 节点澄清研究范围、Research 节点并行搜索+抓取+提取、Write 节点综合生成带引用的报告。支持自带模型/搜索工具/MCP server。HuggingFace/Tongyi/Together AI 均有同类开源实现。核心 pattern 是 planner-executor 循环：planner 分解子问题 → executor 逐个搜索/抓取 → synthesizer 合并结果 → 质量检查决定是否继续深挖。Tongyi DeepResearch（2025-09）更进一步用合成数据训练专用 researcher 模型。DigitalApplied 2026-08 测评了四个开源方案，结论是 LangChain 版最易定制、Together AI 版最快出结果、HuggingFace 版最透明。

**对小汐的启示**: 可迁移：这是小汐获得「调研能力」最直接的路径。用 LangGraph 构建一个 research subgraph，搜索工具复用现有 web search tool + 新增 web scrape tool（可用 httpx+beautifulsoup），写入工具复用记忆系统。DeepSeek V4 的 function calling 足以驱动 planner 节点。该 subgraph 可作为 heartbeat 任务的延伸——主人问复杂问题时自动触发异步调研，结果存入核心记忆。不该照搬：不要用 GPT-4o/Gemini 做 researcher，成本不可控；DeepSeek API 的并发限制需要注意，加 rate limiter。优先级高，Phase 1-2。

**来源**: https://github.com/langchain-ai/open_deep_research | https://www.langchain.com/blog/open-deep-research | https://huggingface.co/blog/open-deep-research | https://tongyi-agent.github.io/blog/introducing-tongyi-deep-research/ | https://www.digitalapplied.com/blog/open-source-deep-research-agents-2026-guide

## LangGraph checkpoint 不是真正的 durable execution，长任务需补充机制

Diagrid 2026-02 分析指出 LangGraph checkpoint 仅提供 state snapshot+resumability（通过 thread_id），缺乏 Temporal/Inngest 级别的 durable execution 保证：无自动 retry with backoff、无 activity timeout、无 saga compensation、checkpoint 恢复可能不从正确 node 续接（forum 2025-11 bug report）。LangGraph RCE 链漏洞（CVE-2025-67644）暴露 checkpointer SQL 注入风险。但对于小汐的场景——单进程单用户、SQLite backend、任务最长几分钟——这些缺陷影响有限。LangGraph 的 time-travel debugging（2026-04 TowardsAI 文章）对开发调试有价值。create_react_agent 已 deprecated，应迁移到 create_agent。

**对小汐的启示**: 可迁移：小汐已有的 SQLite 记忆系统天然就是 checkpoint store，LangGraph 的 SqliteSaver 可直接对接。对于调研类长任务，在应用层补 retry+timeout 即可，不需要上 Temporal。不该照搬：不要因为看到「durable execution」就去学 Temporal/Inngest——那是多租户 SaaS 的基础设施，单用户自托管项目的复杂度杀手。保持 LangGraph + SQLite 组合，够用且维护成本低。注意升级 create_agent 替代 deprecated API。

**来源**: https://www.diagrid.io/blog/checkpoints-are-not-durable-execution-why-langgraph-crewai-google-adk-and-others-fall-short-for-production-agent-workflows | https://forum.langchain.com/t/restoring-to-checkpoint-doesnt-resume-from-node-of-checkpoint/2279 | https://pub.towardsai.net/deepagents-on-langgraph-debugging-long-running-ai-agents-with-time-travel-ff897ef50b73 | https://labs.cloudsecurityalliance.org/research/csa-research-note-langgraph-rce-chain-20260614-csa-styled/

## OpenClaw 的心跳+技能+Cron 架构是小汐主动性的最佳参照系

OpenClaw（原 Clawdbot）架构核心：本地 Gateway 长驻进程绑定 localhost，管理 WhatsApp/Telegram/Slack/Discord/WebChat 多渠道。五种输入类型：Messages、Heartbeats（默认 30min 周期唤醒，读 HEARTBEAT.md 清单决定做什么）、Crons（持久化定时任务，agent wake-up 后输出到 chat channel）、Skills（5400+ 注册技能）、Browser Control。StarksLab 源码分析揭示 Gateway 内部是 session routing + memory files + model dispatch 三层。Heartbeat 优化建议：用小模型（如 Haiku）做心跳判断，大模型仅处理复杂任务以控制成本。Cron issue #5825 讨论 sub-agent scheduling 改进。与小汐现有 5 个心跳任务高度同构，但 OpenClaw 的技能系统和 HEARTBEAT.md 声明式设计更优雅。

**对小汐的启示**: 可迁移：① 将小汐 5 个硬编码心跳任务重构为声明式 HEARTBEAT.md 配置，降低代码耦合；② 借鉴 Skills 注册模式，把 22 个工具+未来 MCP 工具统一为 skill manifest（JSON/YAML 描述 name/description/trigger/input_schema），运行时动态加载而非 import 时绑定；③ Heartbeat 用小模型做路由判断（DeepSeek-V3-lite 或本地 Qwen2.5-7B），只有命中复杂任务才调主力模型。不该照搬：OpenClaw 的 Node.js Gateway 和多渠道 adapter 与小汐 Python+FastAPI 栈不兼容，只借鉴设计思想不搬代码。优先级高，与 MCP 接入并行推进。

**来源**: https://starkslab.com/notes/i-read-openclaws-source-code | https://docs.openclaw.ai/automation/cron-jobs | https://docs.openclaw.ai/concepts/architecture | https://newsletter.systemdesign.one/p/openclaw-architecture | https://github.com/VoltAgent/awesome-openclaw-skills

## HuggingGPT/JARVIS 四阶段 pipeline 仍是现代 planner-executor 的理论基础

Microsoft JARVIS/HuggingGPT（2023）的四阶段：Task Planning（LLM 分解意图为子任务）→ Model Selection（从 HF Hub 选专家模型）→ Task Execution（调用并收集结果）→ Response Generation（整合输出）。2024-2026 演进：ADaPT 提出 as-needed decomposition（只在执行失败时才分解，避免过度规划）；Meta-Task Planning（arXiv:2405.16510）强调先确定可行序列再执行；Task-Decoupled Planning（arXiv:2601.07577, 2026）解耦规划与即时执行约束以应对 long-horizon 任务。Cisco Outshift 2025 发布 JARVIS 风格多 agent 编排深度技术文章。核心教训：纯顺序 pipeline 不够，需要 reflection+retry loop；model selection 在单模型场景下退化为 tool selection。

**对小汐的启示**: 可迁移：小汐的 LangGraph agent 已经是 ReAct loop，本质上就是 JARVIS pipeline 的单模型简化版。升级方向：① 在 ReAct loop 中加入 as-needed decomposition——简单请求直接执行，复杂请求先让 planner 节点分解；② tool selection 利用 MCP 的 dynamic discovery 替代静态列表，按任务上下文动态注入相关工具子集（节省 token）；③ 加 reflection 节点：执行后自评结果质量，不满意则重试或换策略。不该照搬：不要做多模型 orchestration（HF Hub 选模型），小汐单模型+多工具架构是正确的，多模型增加运维复杂度但对单用户场景收益边际递减。

**来源**: https://github.com/microsoft/JARVIS | https://arxiv.org/html/2601.07577v1 | https://arxiv.org/html/2405.16510v3 | https://outshift.cisco.com/blog/ai-ml/jarvis-technical-deep-dive-multi-agent-design | https://starlog.is/articles/llm-engineering/microsoft-jarvis/

## DeepSeek V4 已具备稳定的 agentic function calling，支撑贾维斯化的模型基础

DeepSeek V3.1（2025-08）引入 Strict Function Calling beta 和 agentic workflow 优化；V3.2（2025-12）加入 Thinking in Tool-Use（推理过程中执行工具调用）；V4（2026-04）专门针对 agentic 任务训练，multi-step tool use 和 structured JSON output 显著增强。R1 系列早期 function calling 不稳定（需传回 reasoning_content 否则 400 错误），R1-0528 后改善。社区 deepseek-mcp-server 已验证 DeepSeek 可通过第三方适配器接入 MCP 生态。Fireworks/SambaNova 等推理服务商也提供 DeepSeek function calling 支持。关键限制：自托管 vLLM/Ollama 部署需要正确的 chat template 和 tool parser，官方未完整开源 tool formatting template。

**对小汐的启示**: 可迁移：小汐已用 DeepSeek API，升级到 V4 即可获得可靠的 multi-step tool calling 能力，无需换模型供应商。如果用 API 方式调用，function calling 开箱即用；如果自托管，需要确保 chat template 正确配置。Thinking in Tool-Use 特性特别适合调研类任务（边推理边搜索）。不该照搬：不要因为 R1 的 reasoning 能力强就用 R1 做工具调用——R1 的 tool calling 仍不如 V4 稳定，让小汐的主力模型保持 V4，R1 仅用于需要深度推理的特殊场景（如代码分析）。

**来源**: https://api-docs.deepseek.com/guides/tool_calls | https://api-docs.deepseek.com/news/news250821/ | https://macaron.im/blog/deepseek-v4-tool-calling | https://github.com/DMontgomery40/deepseek-mcp-server | https://fireworks.ai/blog/function-calling-deepseekv3

## MCP 2026-07-28 无状态重构使 MCP 原生适配 CDN/边缘/Serverless，利好长期

2026-07-28 spec 是 MCP 诞生以来最大重构：移除 session handshake，协议层变为完全无状态；Streamable HTTP transport 成为唯一标准传输，要求 Mcp-Method/Mcp-Name header 用于路由；OAuth 2.1 正式纳入核心规范。Cloudflare 2026-08-06 发文确认 MCP 可原生运行在 Workers 上。这意味着 MCP server 可以像普通 REST API 一样被缓存、负载均衡、全球分发。对自托管项目的影响：短期内 breaking change 需要迁移（去掉 session 依赖），长期看简化了部署拓扑——MCP server 可以和 FastAPI app 共用同一个 Caddy reverse proxy，无需特殊长连接处理。2026-07-28 目前还是 release candidate 阶段，SDK v2.0+ 已跟进。

**对小汐的启示**: 可迁移：小汐的 Caddy + Docker 部署架构天然适配无状态 MCP——MCP server 作为 FastAPI 的一个 mount path 暴露，Caddy 统一代理。不需要 WebSocket/SSE 长连接管理。不该照搬：不要在 RC 阶段就升级，等 2026-Q4 正式版稳定后再迁移。当前先用 2025-06-18 或 2025-11-25 spec 实现，代码结构做好 transport 抽象以便后续切换。注意：无状态意味着每次请求都要携带认证信息，对小汐单用户场景反而简化了（固定 token 即可）。

**来源**: https://blog.modelcontextprotocol.io/posts/2026-07-28/ | https://blog.cloudflare.com/mcp-v2/ | https://www.developersdigest.tech/blog/mcp-stateless-migration-guide-2026 | https://avinashsangle.com/blog/mcp-stateless-spec-migration-guide

## 动态工具发现解决 token 膨胀问题，对小汐 22 工具→N 工具扩展至关重要

硬编码 22 个工具的 system prompt 已占不少 token；扩展到 50-100 个 MCP 工具后，全量注入会吃掉 context window 30-50%。Microsoft Tech Community 2026 文章对比 MCP vs mcp-cli 的动态发现：按需拉取相关工具子集，每 session 仅注入当前任务相关的 5-10 个工具描述。Lunar.dev 分析了动态发现如何解决 context management problem。Obot AI 2026 列出 5 种 MCP tool discovery 方案。核心 pattern：agent 先用轻量 embedding 或关键词匹配从 registry 检索候选工具 → 注入 top-K 工具描述 → 执行 → 结果反馈更新工具相关性排序。对单用户项目，甚至不需要 embedding，按「任务类型→工具组」的静态映射就够了。

**对小汐的启示**: 可迁移：小汐应该实现一个简单的工具路由层：维护 tool_groups.yaml（如 weather: [get_weather, get_forecast]、research: [web_search, web_scrape, summarize]），planner 节点先识别任务类型，只注入对应组的工具描述。这比全量注入省 60-80% tool token。可以用现有的六因子召回思路来做工具相关性排序（话题命中×最近使用热度）。不该照搬：不要上向量检索做工具发现——22-100 个工具的规模，关键词匹配+分组足矣，向量库引入的运维成本不值当。

**来源**: https://techcommunity.microsoft.com/blog/azuredevcommunityblog/mcp-vs-mcp-cli-dynamic-tool-discovery-for-token-efficient-ai-agents/4494272 | https://www.lunar.dev/post/why-dynamic-tool-discovery-solves-the-context-management-problem | https://obot.ai/resources/learning-center/mcp-tool-discovery/ | https://medium.com/@amiarora/solving-the-mcp-tool-discovery-problem-how-ai-agents-find-what-they-need-b828dbce2c30

## 个人助理开源竞品对比：Khoj/OpenClaw/Open Interpreter 各有侧重

CETIC 2025-06 比较分析报告和 Vellum 2026-05 评测覆盖了主流开源个人助理。Khoj：self-hostable AI second brain，强项是语义搜索+文档管理+Obsidian 集成，弱项是工具执行能力有限。OpenClaw：24/7 主动型助手，强项是 heartbeat+cron+skills 生态（5400+ skills），弱项是 Node.js 栈、依赖 Claude/GPT API。Open Interpreter：强项是本地代码执行+文件系统操作，弱项是无持久记忆、无主动性。共同点：都依赖商业 API（Claude/GPT），无一使用 DeepSeek；都有 MCP 集成或计划中；都面临「工具越多越难管理」的问题。小汐的差异化优势：自研 SQLite 记忆系统（无向量库依赖）、DeepSeek 低成本、已上线的主动性（5 心跳任务）、人格系统。

**对小汐的启示**: 可迁移：① 从 OpenClaw 借鉴 skills manifest 格式和 heartbeat 声明式设计；② 从 Khoj 借鉴文档索引思路（如果未来要处理主人的文件）；③ 从 Open Interpreter 借鉴安全沙箱执行代码的模式（如果未来要加代码执行能力）。不该照搬：不要切换到它们的 runtime——小汐的 Python+FastAPI+LangGraph+SQLite 栈已经比它们任何一个都更适合单人维护。保持差异化：记忆系统和人格是小汐的核心壁垒，通用工作能力是增量而非替代。

**来源**: https://www.cetic.be/Mastering-AI-at-Work-A-Comparative-Analysis-of-Open-Source-Personal-Assistants | https://www.vellum.ai/blog/best-open-source-personal-ai-assistants | https://github.com/khoj-ai/khoj | https://medium.com/@seemantkamlapuri88/openclaw-your-personal-ai-assistant-self-hosted-that-actually-works-under-the-hood-a2e3a7e682f9

## 项目

- **fastapi_mcp (tadata-org)** (https://github.com/tadata-org/fastapi_mcp) [活跃维护，2025-04 首发] — 零配置将 FastAPI endpoints 暴露为 MCP tools，两行代码启用，是小汐 MCP 接入的首选库
- **open_deep_research (LangChain)** (https://github.com/langchain-ai/open_deep_research) [活跃维护，2025-07 开源] — Scope→Research→Write 三阶段 LangGraph workflow，支持自定义模型/搜索/MCP，可直接移植为小汐的调研 subgraph
- **browser-use** (https://github.com/browser-use/browser-use) [21K+ stars，2026-05 发布 Production Architecture] — Python-native 浏览器自动化库，DOM-based 交互 token 成本低，可与小汐 Playwright 基础设施复用，作为调研能力的执行层
- **OpenClaw (formerly Clawdbot)** (https://openclaw.ai/) [活跃社区，5400+ skills] — heartbeat+cron+skills 声明式架构是小汐主动性重构的最佳参照系；HEARTBEAT.md 模式和 skills manifest 格式值得直接借鉴
- **deepseek-mcp-server (DMontgomery40)** (https://github.com/DMontgomery40/deepseek-mcp-server) [社区维护] — 验证了 DeepSeek 可通过第三方适配器接入 MCP 生态，小汐如需将 DeepSeek 本身作为 MCP 可调用的推理资源可参考此实现
- **FastMCP (gofastmcp)** (https://gofastmcp.com/integrations/fastapi) [活跃维护，MCP 生态标准库] — 独立 MCP server 框架，与 fastapi_mcp 互补；如果需要将某些功能作为独立 MCP server 而非 FastAPI endpoint 暴露，用这个
- **langchain-mcp-adapters** (https://github.com/langchain-ai/langchain-mcp-adapters) [LangChain 官方维护] — 将 MCP tools 转换为 LangChain/LangGraph 兼容格式的桥接层，是小汐 LangGraph agent 消费 MCP 工具的标准方式

## 论文

- **Real Faults in Model Context Protocol (MCP) Software: a Comprehensive Taxonomy** (arXiv:2603.05637) 2026 — 首个 MCP 软件故障分类体系，涵盖安全/prompt sanitization/配置缺陷等类别，基于从业者调查验证
- **UI-TARS-2 Technical Report: Advancing GUI Agent with Multi-Turn Interaction** (arXiv:2509.02544) 2025 — UI-TARS-2 在 OSWorld 47.5%/AndroidWorld 73.3%，多轮 GUI agent SOTA，但距离生产可靠仍有差距
- **UFO 2: The Desktop AgentOS** (arXiv:2504.14603) 2025 — Microsoft UFO 2 提出 Desktop AgentOS 统一 GUI+API 执行模型，是 computer use 从纯视觉走向混合执行的里程碑
- **Task-Decoupled Planning for Long-Horizon Agents** (arXiv:2601.07577) 2026 — Task-Decoupled Planning 解耦规划与即时执行约束，解决 long-horizon agent 过度规划问题
- **Meta-Task Planning for Language Agents** (arXiv:2405.16510) 2024 — Meta-Task Planning 强调先确定可行/最优序列再执行，超越简单的 task decomposition
- **HuggingGPT: Solving AI Tasks with ChatGPT and its Friends in Hugging Face** (arXiv:2303.17580) 2023 — HuggingGPT/JARVIS 原始论文，四阶段 pipeline（planning→selection→execution→generation）奠定了现代 agent 架构基础
- **A First Look at the Security Issues in the Model Context Protocol** (arXiv:2510.16558) 2025 — 首批系统性分析 MCP 安全问题的论文，为后续 CVE 披露和安全指南奠定学术基础
- **Mobile-Agent-v3.5: Multi-platform Fundamental GUI Agents** (arXiv:2602.16855) 2026 — Mobile-Agent-v3.5 提出 GUI-Owl1.5 跨平台基础模型，mobile+desktop 统一 GUI agent 的最新进展

## 建议

- 【P0 - 立即启动】MCP 接入：用 fastapi_mcp 将现有 22 个工具包装为 MCP server，同时实现工具分组路由（tool_groups.yaml）控制 token 消耗。这是所有后续扩展的基础设施层，预计 2-3 周工作量。安全策略：本地 stdio/SSE transport + 白名单 + 输入校验中间件，不接入公共 registry。
- 【P0 - 并行推进】主动性重构：借鉴 OpenClaw HEARTBEAT.md 模式，将 5 个硬编码心跳任务改为声明式配置；引入 skills manifest 格式统一管理工具/技能元数据。与 MCP 接入共享同一套工具注册表，互相增强。
- 【P1 - 第二阶段】Deep Research 能力：基于 LangGraph 构建 Scope→Research→Write 三阶段 research subgraph，搜索复用现有 web search + 新增 web scrape tool，planner 由 DeepSeek V4 驱动。作为 heartbeat 的延伸，主人提复杂问题时异步触发。预计 3-4 周。
- 【P1 - 第二阶段】模型升级：将主力模型从当前版本升级到 DeepSeek V4 以获得稳定的 multi-step function calling 和 Thinking in Tool-Use 能力。这是 MCP 工具和 research agent 能可靠运行的前提。
- 【P2 - 第三阶段】浏览器自动化：集成 browser-use 作为 LangGraph tool，赋能「帮主人查网页/填表/比价」等任务。复用现有 Playwright+Edge 基础设施，加确认机制防止意外操作。预计 2 周。
- 【P2 - 持续】动态工具发现：当工具数超过 30 个时实现按任务类型的工具子集注入，避免 context window 膨胀。初期用静态分组，后期可加使用频率统计做自适应排序。
- 【P3 - 观望】Computer Use / GUI Agent：等 DeepSeek 原生支持或多模态模型成本降至可接受范围后再考虑。当前阶段用 browser-use 的 DOM 模式覆盖 80% 的网页操作需求即可。
- 【不建议】引入 Temporal/Inngest 做 durable execution、部署企业级 MCP Registry/Gateway、切换 Node.js runtime、接入公共 MCP registry（安全风险过高）。这些都超出单用户自托管项目的合理复杂度边界。

