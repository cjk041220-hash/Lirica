# 同类开源项目深挖

## OpenClaw Gateway 架构：Markdown 文件记忆 + 心跳主动 + 技能热插拔

OpenClaw（原 Clawdbot/Moltbot）由 Peter Steinberger 于 2025-11-24 发布，2026-02 加入 OpenAI 后项目转基金会运营。核心是一个单长驻 Node.js 进程（Gateway），绑定 localhost，作为中枢神经连接 13+ 消息渠道（WhatsApp/Telegram/Slack/Discord/Signal/iMessage/WebChat 等）。Gateway 持有全部状态与连接，通过 WebSocket 将路由消息分发给 Agent Runtime。系统提示词动态组装三层：AGENTS.md（多 agent 编排/项目上下文）、SOUL.md（人格/目的/行为准则）、TOOLS.md（能力声明），运行时注入而非静态默认。Memory 不使用 PostgreSQL/Redis，而是写纯 Markdown 文件到 ~/.openclaw/workspace/memory/YYYY-MM-DD，配合 SQLite 向量索引做语义检索——这种「Markdown 日记 + 本地向量」设计极其轻量且人类可读可编辑。Skills 是 skills/ 目录下的 Markdown 文档，支持热加载/卸载无需重启。Heartbeat 是定时主会话 turn（不是后台任务记录），每次触发读取 HEARTBEAT.md 作为主动检查清单。沙箱用 Docker 隔离工具执行，默认关闭，可按 agent 粒度开启。60 天内超越 React 十年 star 记录达 302K+，但安全研究者警告其深度系统访问在企业场景有风险。

**对小汐的启示**: 对小汐启示极大：① Markdown 文件记忆方案可直接借鉴——比 SQLite 六因子更人类可读、可 git 版本控制、可用编辑器手动修正幻觉，适合单用户场景；② SOUL.md 人格文件化思路与小汐 PERSONA_ANCHOR 互补，可将人格锚点从代码常量变为可热编辑文件；③ Heartbeat.md 主动任务清单模式比小汐当前硬编码 5 任务更灵活，主人可随时编辑而不改代码；④ 但 Gateway 多渠道架构对小汐过度——小汐单用户网页端不需要 13 渠道适配层，不应照搬；⑤ Skills-as-Markdown 模式值得参考，为未来工具插件化铺路。

**来源**: https://ppaolo.substack.com/p/openclaw-system-architecture-overview | https://docs.openclaw.ai/concepts/architecture | https://docs.openclaw.ai/gateway/heartbeat | https://pub.towardsai.net/openclaw-wont-bite-a-zero-to-hero-guide-for-people-who-hate-terminal-14dd1ae6d1c2 | https://sausheong.com/dissecting-openclaw-733213e9c853 | https://thesoogroup.com/blog/openclaw-github-phenomenon-autonomous-agent-framework

## Letta (MemGPT) 三层记忆模型：Core/Recall/Archival + Agent 自编辑

Letta（MemGPT 公司化）将学术论文中的 OS-inspired 内存层级产品化为 Agent-as-a-Service。三层记忆模型：Core Memory（固定大小块，始终在 LLM 上下文窗口内，agent 可通过 function call 自主编辑，存放人格/用户画像/当前任务等高优先级信息）、Recall Memory（对话历史和消息日志，通过搜索工具按需检索）、Archival Memory（无界向量存储，长期外部知识和历史数据，仅在需要时 page-in）。Agent Development Environment (ADE) 提供可视化调试、内存状态检查和监控。2026 年 Letta 同时提供自托管部署和 Constellation 云服务。关键设计理念是 agent 自己管理自己的记忆——通过工具调用决定何时写入 core、何时归档到 archival，而非开发者预设规则。Memory blocks 可在多 agent 间共享，实现协作状态同步。

**对小汐的启示**: 对小汐启示：① Core Memory 概念与小汐 active memory 高度对应，但 Letta 让 agent 自主编辑 core memory 而非后台提取器被动管理——小汐可考虑给模型增加'自我编辑核心记忆'的工具调用能力；② Archival Memory 的向量检索是小汐当前纯 SQLite 六因子召回的对标物，但 Letta 证明分层（core 常驻 + archival 按需）比全量召回更高效；③ ADE 可视化调试思路值得简化版移植——哪怕只是一个 /debug-memory 命令查看当前 core/recall/archival 状态；④ 但 Letta 的多租户 SaaS 架构对单用户小汐过重，不应引入其服务端框架。

**来源**: https://blog.stackademic.com/letta-platform-for-stateful-llm-agents-a83b58a1c926 | https://medium.com/@piyush.jhamb4u/stateful-ai-agents-a-deep-dive-into-letta-memgpt-memory-models-a2ffc01a7ea1 | https://github.com/letta-ai/letta | https://sureprompts.com/blog/letta-memgpt-walkthrough | https://www.getfeather.store/theory/ai-memory-systems-comparison-2026

## Zep/Graphiti 双时态知识图谱 vs Mem0 Extract-Retrieve：基准与取舍

Zep 的核心创新是 Graphiti——一个开源（Apache-2.0）的双时态知识图谱引擎（bi-temporal knowledge graph），每条边携带 validity 元数据（valid_from, valid_to, created_at），原生建模事实的时间有效性。底层支持 Neo4j、FalkorDB、Amazon Neptune。arXiv:2501.13956 论文报告 Zep 在 Deep Memory Retrieval 和 LongMemEval 上比 MemGPT 准确率提升最高 18.5%，同时降低延迟。第三方对比站 RankSquire (2026-05) 给出 LongMemEval 时序推理分数：Zep(GPT-4o) 63.8% vs Mem0 OSS 49.0%，优势来自事实有效期追踪。Zep 的商业服务是托管版，但 Graphiti 引擎本身完全开源可自托管。相比之下，Mem0 采用 extract-and-retrieve 范式（LLM 提取结构化记忆→嵌入→向量检索），更简单但丢失时序关系。Mem0 内存占用远低于 Zep（1,764 tokens vs 600K+ tokens/会话），适合资源受限场景。

**对小汐的启示**: 对小汐启示：① 双时态概念直击小汐痛点——小汐的 superseded 链已有时态意识但不够系统化，Graphiti 的 valid_from/valid_to 模式可增强记忆回放精度；② 但知识图谱基础设施（Neo4j/FalkorDB）对小汐单进程 SQLite 架构是巨大负担，不建议引入；③ 折中方案：在 SQLite 中为记忆条目增加 valid_from/valid_to 字段，用 SQL 查询模拟时态图谱的核心能力，无需图数据库；④ Mem0 的 extract-and-retrieve 反而更适合小汐的资源约束，但其基准分数需注意厂商自报偏差。

**来源**: https://arxiv.org/abs/2501.13956 | https://github.com/getzep/graphiti | https://ranksquire.com/2026/05/06/long-term-memory-for-ai-agents/ | https://particula.tech/blog/agent-memory-frameworks-tested-mem0-zep-letta-cognee-2026 | https://nomadx.ae/blog/advanced-agent-memory-knowledge-graphs-zep-graphiti-2026/

## Cognee ECL 流水线：图+向量+元数据三合一记忆控制平面

Cognee 定位为开源 AI Memory Control Plane，核心是 ECL 流水线（Extract→Cognify→Load）：从原始数据提取实体/关系，通过 LLM 认知化处理（消歧、合并、推理），加载到统一存储层。独特之处是将图数据库（关系结构）、向量存储（语义检索）、关系型元数据（精确过滤）三合一为统一检索层。Python SDK 形式分发，支持 LanceDB 作为轻量级文件向量后端，也可接 Neo4j/PostgreSQL。2026 年被多个框架评测列为顶级开源记忆框架。与小汐的差异：Cognee 面向通用 RAG 管线，不做人格/情绪/主动性；其 ECL 流水线的 Cognify 步骤需要额外 LLM 调用，对单用户预算有限场景有成本压力。

**对小汐的启示**: 对小汐启示：① ECL 流水线中的 Cognify（消歧+合并）步骤与小汐 dreaming-lite 的近义合并+画像蒸馏功能对标，Cognee 的实现可作为改进参考；② 三合一检索层（图+向量+元数据）的理想很丰满，但小汐已有六因子召回覆盖了元数据维度，缺的主要是向量语义——优先补向量而非重构整个检索层；③ LanceDB 作为文件级向量库值得关注，零依赖、嵌入式、Python 原生，可能是小汐补向量检索的最小侵入方案；④ 不应照搬 ECL 全流程，LLM 调用成本对小汐不友好。

**来源**: https://www.cognee.ai/ | https://github.com/topoteretes/cognee | https://tryxlr8.ai/blogs/best-open-source-ai-memory-frameworks-2026 | https://www.lancedb.com/blog/case-study-cognee | https://www.zenml.io/llmops-database/building-ai-memory-layers-with-file-based-vector-storage-and-knowledge-graphs

## LangMem SDK：LangGraph 原生三种记忆类型 + 零摩擦集成

LangMem 是 LangChain 生态的长期记忆 SDK（2025-02 发布），与小汐已有的 LangGraph 技术栈天然兼容。定义三种记忆类型镜像人类认知：Semantic Memory（事实知识如生日、偏好）、Episodic Memory（过往交互经历、场景回忆）、Procedural Memory（行为规则、操作习惯）。提供 memory enrichment process 平衡记忆创建与整合，开发者可自定义提取指令。架构模块化，从简单内存存储到生产数据库均可接入。2026 年 DigitalOcean 教程更新了完整集成指南。与小汐的关系：小汐已在用 LangGraph(create_agent+中间件)，LangMem 是唯一可以'零摩擦'集成的记忆层，不需要引入新框架。

**对小汐的启示**: 对小汐启示：① 最高优先级的集成候选——小汐已用 LangGraph，LangMem 是同生态 SDK，集成成本远低于 Mem0/Zep/Cognee 等外部服务；② Semantic/Episodic/Procedural 三分法可为小汐的记忆分类提供理论框架，当前六因子召回偏重 episodic，semantic（稳定事实）和 procedural（行为习惯）维度较弱；③ Memory enrichment process 的 consolidation 逻辑可补充小汐 dreaming-lite 的整合机制；④ 风险：LangMem 仍较年轻（2025-02 发布），社区验证不如 Mem0/Zep 充分，建议先在小汐测试环境验证再上生产。

**来源**: https://www.langchain.com/blog/langmem-sdk-launch | https://langchain-ai.github.io/langmem/concepts/conceptual_guide/ | https://www.digitalocean.com/community/tutorials/langmem-sdk-agent-long-term-memory | https://levelup.gitconnected.com/building-agent-with-semantic-episodic-procedural-memory-8f481aee9614

## AIRI 现状：视觉交互成熟但记忆系统仍缺失

AIRI（moeru-ai/airi）是目标复刻 Neuro-sama 的开源 AI VTuber/伴侣项目，2026 年中 GitHub 40K+ stars。已实现的功能：Live2D/VRM 头像渲染+口型同步、实时双向语音聊天（原生麦克风输入）、Minecraft/Factorio 游戏集成、Discord/Telegram 跨平台、屏幕视觉感知（YOLOv9+WebGPU）、桌面应用（WinGet 安装）。但关键短板：GitHub Issue #387 承认'目前缺乏 RAG 记忆系统'，DuckDB 仅创建临时表；2026 初营销声称'有记忆'但技术文档显示仍在演进中。人格持久化也是设计挑战而非已完成特性。VRChat 支持和多人交互列为 Q2 2026 计划。本质上是'视觉交互强、记忆智能弱'的项目。

**对小汐的启示**: 对小汐启示：① AIRI 的记忆短板反证了小汐在记忆深度上的差异化优势——小汐的六因子召回+dreaming-lite+mood-congruent 远超 AIRI 的临时表方案；② AIRI 的 Live2D/VRM + 实时语音是小汐未来可考虑的交互升级方向，但优先级低于工作能力建设；③ AIRI 的游戏集成思路（让 AI 在 Minecraft 中陪伴）是'陪伴→共同活动'的优秀案例，小汐可从简单浏览器小游戏开始探索；④ 不应模仿 AIRI 的重前端路线——小汐的核心价值在记忆和人格深度，不在视觉表现力。

**来源**: https://github.com/moeru-ai/airi | https://explainx.ai/blog/airi-ai-vtuber-neuro-sama-guide-2026 | https://www.sitepoint.com/designing-souls-for-code-the-architecture-of-moeruaiairi/ | https://byteiota.com/airi-self-hosted-ai-companion-webgpu-voice-chat-minecraft-gaming/ | https://github.com/moeru-ai/airi/issues/387

## SillyTavern V3 + MemoryBooks：Lorebook 即记忆的实战范式

SillyTavern 的角色卡 V3 规范（kwaroran/character-card-spec-v3）引入了原生 lorebook 嵌入、多语言资产、结构化状态持久化，V2/V3 双写保证向后兼容。更重要的是 MemoryBooks 扩展（aikohanasaki/SillyTavern-MemoryBooks，2025-09 发布，2026-08 仍活跃更新）：自动将完成的聊天场景或选定片段转化为 lorebook 条目，把 lorebook 引擎变成持久记忆系统。Lorebook 条目支持 scan_depth 等装饰器控制注入时机。另有 Data Bank (RAG) 系统处理文件附件，与关键词触发的 lorebook 互补。2026 最佳实践已将大量角色描述移入 lorebook 按需注入，节省上下文窗口。这套'lorebook-as-memory'范式是角色扮演社区多年实战沉淀的结果。

**对小汐的启示**: 对小汐启示：① MemoryBooks 的'聊天→记忆条目'自动转化机制与小汐后台提取器功能对标，但其 lorebook 关键词触发注入比小汐六因子召回更简单直观——可作为小汐'精确匹配'召回通道的参考；② V3 规范的 scan_depth 装饰器概念可用于小汐记忆的优先级标注——不同记忆在不同对话深度才被注入；③ SillyTavern 社区证明了'关键词+语义'混合召回在角色扮演场景的有效性，小汐可考虑在六因子之外增加精确关键词匹配通道；④ 角色卡 V3 规范本身可作为小汐人格数据格式的互操作标准，方便未来与其他 RP 平台互通。

**来源**: https://github.com/kwaroran/character-card-spec-v3 | https://github.com/aikohanasaki/SillyTavern-MemoryBooks | https://docs.sillytavern.app/usage/core-concepts/worldinfo/ | https://honeychat.bot/en/blog/ai-character-cards-guide-v2-v3-2026/ | https://docs.sillytavern.app/usage/core-concepts/data-bank/

## Khoj 自托管第二大脑：Automations + RAG 但无人格

Khoj 是自托管 AI 第二大脑，Python(~51%)+TypeScript(~36%) 混合架构，2026 年进入 v2.0 beta。核心能力：RAG over 个人文档（Obsidian/Emacs/WhatsApp/网页/Desktop）、Deep Research、Scheduled Automations（定时重复任务，类似 cron）、自定义 Agent 构建。支持 Ollama/OpenAI/Anthropic/HuggingFace 多 LLM 后端。与小汐的关键差异：Khoj 定位是'知识检索+自动化'而非'陪伴'，无人格系统、无情绪模型、无关系成长；但其 Automations 功能（定时查询、周期性任务）与小汐心跳 5 任务有功能重叠。自托管可用 Docker，历史上支持 SQLite（轻量）和 PostgreSQL（生产）。2026-03 在 GitHub trending  resurgence。

**对小汐的启示**: 对小汐启示：① Khoj 的 Scheduled Automations 是可参考的通用化心跳机制——比小汐硬编码 5 任务更灵活，用户可自然语言配置定时任务；② Khoj 的 Obsidian/笔记集成思路提示小汐可扩展'读取主人笔记'能力，作为工作能力的入口；③ Khoj 证明了 Python+TS 混合架构在自托管 AI 项目中的可行性，与小汐 FastAPI+React 技术栈一致；④ 但 Khoj 缺乏人格/情绪/主动性，恰好是小汐的强项——两者互补而非竞争，不应将小汐做成另一个 Khoj。

**来源**: https://github.com/khoj-ai/khoj | https://docs.khoj.dev/features/automations/ | https://docs.khoj.dev/category/get-started | https://blog.einverne.info/post/2026/06/khoj-ai-personal-second-brain.html

## Dify New Agent 沙箱隔离：网络分段 + Per-Execution UID

Dify 在 2026 年推出 New Agent 架构，每个 agent 运行在独立沙箱中，拥有自己的命令执行和文件管理能力。关键安全升级：local_sandbox 从默认网络移至专用 agent_sandbox_network 和 local_sandbox_proxy_network，实现网络级隔离；Imperva 安全审计报告推动从共享身份改为 per-execution UID。DifySandbox 早在 2024-07 就发布了基础设计，2026 年的重点是精细化隔离和 DevOps for Agent Era（给 agent 明确的规则和身体）。与小汐的关系：小汐当前 22 个硬编码工具无沙箱隔离，fail-closed 安全靠代码逻辑而非运行时隔离。随着工具增多和能力扩展，沙箱将成为刚需。

**对小汐的启示**: 对小汐启示：① Dify 的网络隔离+per-execution UID 思路可直接指导小汐未来工具沙箱设计——当小汐从 22 工具扩展到 MCP/插件时，必须有运行时隔离；② 但 Dify 的多租户容器化架构对小汐单用户过重，建议先用 Docker container 做粗粒度隔离即可；③ Dify 的 'New Agent' 开放-ended 工作模式（agent 有自己的文件系统和工作空间）是小汐从'对话陪伴'走向'干活'时需要的设计模式；④ 短期行动：在小汐添加代码执行工具时，立即引入 Docker 沙箱，不要等到工具数量爆炸后再补。

**来源**: https://dify.ai/blog/rethinking-dify-devops-for-the-agent-era | https://docs.dify.ai/en/self-host/use-dify/build/new-agent/overview | https://github.com/langgenius/dify/releases | https://www.imperva.com/blog/dify-when-your-ai-platform-becomes-the-attack-surface/

## 国产个人 Agent：OpenOcta 桌面级 + OpenBiliClaw 中文内容发现

OpenOcta（八爪鱼）是中国首个开源个人桌面级智能体（Apache-2.0），Go 语言自研，单一二进制文件内嵌 Control UI，生产环境无需 Node.js/Python。强调信创安全和本地数据掌控。功能包括长期记忆、本地知识库 RAG、编码工具、语音交互、本土 IM 对接。2026-07 腾讯云推荐为国产开源智能体代表。OpenBiliClaw（whiteguo233）则是专注中文互联网内容发现的自进化 Agent，深度集成 B站/小红书/抖音/知乎/微博，从用户习惯构建心理画像并主动推荐内容。两者代表了国内'个人 Agent'的两个分支：OpenOcta 走桌面操作系统入口路线，OpenBiliClaw 走内容理解+个性化路线。

**对小汐的启示**: 对小汐启示：① OpenOcta 的单二进制 Go 部署模式对小汐的部署简化有参考价值，但小汐 Python+React 技术栈已定型，不宜迁移语言；② OpenBiliClaw 的中文平台内容发现能力是小汐'贾维斯化'所需的工作能力之一——如果冷冷需要小汐帮忙找 B站视频或小红书攻略，这类集成比通用搜索更精准；③ 两者的'本土 IM 对接'思路提示小汐可考虑微信/钉钉渠道（但需评估合规和技术可行性）；④ 这两个项目的记忆系统细节不透明，不宜直接引用其记忆架构，但其产品定位验证了'中文个人 Agent'的市场需求。

**来源**: https://github.com/openocta/openocta | https://developer.cloud.tencent.com/article/2709016?policyId=1003 | https://github.com/whiteguo233/OpenBiliClaw | https://openocta.com/blog/wx-0f68b94cea3c

## Open WebUI + AnythingLLM：自托管 LLM 平台的可复用基础设施

Open WebUI（126K+ stars）是自托管 ChatGPT 替代界面，2026 年已发展为完整 AI 平台：Pipelines（自定义请求处理管线）、Functions（Python 扩展工具）、内置 RAG、Tools 系统、MCP 集成。AnythingLLM（60K+ stars）则以 Workspace 为核心组织单元，每个 workspace 可有独立 LLM 覆盖、独立向量库、独立 agent 配置；2026-06 v1.14.2 更新了记忆整合逻辑（candidate updates revise existing memories rather than creating duplicates）。两者都是'通用 LLM 前端'而非'陪伴 Agent'，但它们的自托管基础设施（RAG pipeline、workspace 隔离、工具扩展）是可复用组件。

**对小汐的启示**: 对小汐启示：① Open WebUI 的 Pipelines 模式（请求预处理/后处理钩子）与小汐 LangGraph 中间件思路类似，可参考其 UX 设计让用户更方便地配置管线；② AnythingLLM 的 workspace 记忆去重逻辑（update existing vs create new）与小汐 dreaming-lite 的近义合并功能对标，其实现可作为 bug fix 参考；③ 两者的 MCP 集成经验可为小汐未来从硬编码工具迁移到 MCP 提供路线图；④ 但两者都无人格/情绪/主动性，小汐不应变成另一个通用 LLM 前端——差异化在于'活的陪伴'而非'更好的 ChatGPT 壳'。

**来源**: https://github.com/open-webui/open-webui | https://openwebui.com/ | https://github.com/mintplex-labs/anything-llm | https://docs.anythingllm.com/features/memories | https://docs.anythingllm.com/changelog/v1.14.2

## Operit 移动端 + Hermes 自进化 Skills：能力扩展的两条路径

Operit 是 Android 端开源 AI Agent（Kotlin），v1.11.0，支持云/本地模型、内置 Ubuntu 24 终端环境、智能记忆系统、语音交互、浏览器/文件/项目工作空间工具调用。2026 年被 Vellum/Sofarbot 等列入最佳开源个人助手榜单。Hermes-Agent（NousResearch）则是 2026-06 热门的'与你一起成长的 Agent'通用框架，强调持续学习和记忆积累。两者分别代表了移动端 Agent 和成长型 Agent 的方向。Operit 的独特价值在于 Android 系统级权限（文件、终端、浏览器），Hermes 的价值在于 Skills 自进化和复杂调度。

**对小汐的启示**: 对小汐启示：① Operit 的 Android 系统级能力提示小汐未来可能需要移动端入口——如果冷冷希望小汐在手机上也能'做事'而非仅聊天，PWA + Web Push 是当前路径，但长远可能需要原生 App 或 Termux 集成；② Hermes 的'自进化 Skills'理念与小汐 22 硬编码工具的短板直接对应——小汐需要工具发现/注册机制，Hermes 的实现可作为参考；③ Operit 内置 Ubuntu 终端的思路与小汐'贾维斯化'需要的代码执行能力对标，但安全隔离是前提（参见 Dify finding）；④ 这两个项目都不做情感陪伴，再次印证小汐在'人格×记忆×干活'三角中的独特位置。

**来源**: https://github.com/AAswordman/Operit | https://www.vellum.ai/blog/best-open-source-personal-ai-assistants | https://www.sofarbot.com/opensource/OqFn91yOCLyZ | https://zhuanlan.zhihu.com/p/2049799880470745105

## 项目

- **OpenClaw** (https://github.com/openclaw/openclaw) [302K-335K stars, GitHub 最快增长项目, 2026-02 转基金会] — Markdown 文件记忆 + SOUL.md 人格文件化 + HEARTBEAT.md 主动任务清单 + Skills 热插拔是最直接可借鉴的架构模式；但多渠道 Gateway 对单用户小汐过度设计
- **Letta (MemGPT)** (https://github.com/letta-ai/letta) [活跃开发, 自托管+Constellation 云双模式] — Core/Recall/Archival 三层记忆 + Agent 自编辑记忆的工具调用模式可直接指导小汐记忆架构升级；ADE 可视化调试值得简化移植
- **Graphiti (Zep 开源引擎)** (https://github.com/getzep/graphiti) [Apache-2.0, 支持 Neo4j/FalkorDB/Neptune] — 双时态(valid_from/valid_to)概念可在 SQLite 中轻量实现，无需引入图数据库；直接增强小汐 superseded 链的时态精度
- **Cognee** (https://github.com/topoteretes/cognee) [开源, Python SDK, 支持 LanceDB] — LanceDB 作为文件级嵌入式向量库是小汐补向量检索的最小侵入方案；ECL 流水线过重不宜照搬
- **LangMem** (https://github.com/langchain-ai/langmem) [LangChain 官方 SDK, 2025-02 发布] — 与小汐 LangGraph 技术栈零摩擦集成，Semantic/Episodic/Procedural 三分法可补齐小汐记忆分类的理论框架；最高优先级集成候选
- **SillyTavern MemoryBooks** (https://github.com/aikohanasaki/SillyTavern-MemoryBooks) [活跃维护至 2026-08, 角色扮演社区实战验证] — Lorebook-as-memory 的关键词触发注入 + scan_depth 优先级控制可作为小汐六因子召回之外的精确匹配通道参考
- **OpenOcta** (https://github.com/openocta/openocta) [国产, Apache-2.0, Go 单二进制] — 验证了中文个人桌面 Agent 市场需求；本土 IM 对接思路可供小汐渠道扩展参考
- **OpenBiliClaw** (https://github.com/whiteguo233/OpenBiliClaw) [国人开发, 本地私有, 自进化架构] — 中文互联网内容发现(B站/小红书/抖音)是小汐贾维斯化的工作能力补充；心理画像深化思路与小汐关系成长段互补

## 论文

- **Zep: A Temporal Knowledge Graph Architecture for Agent Memory** (arXiv:2501.13956) 2025 — Zep 的双时态知识图谱在 DMR 和 LongMemEval 上比 MemGPT 准确率提升最高 18.5%，同时降低延迟。核心创新是每条边携带 validity 元数据，原生建模事实时间有效性。但 benchmark 为厂商自报，需独立复现验证。
- **A Security Analysis of the OpenClaw AI Agent Framework** (arXiv:2603.27517) 2026 — 安全分析论文识别 OpenClaw 七个交互组件（Channel System/Gateway/Plug-ins & Skills 等）的攻击面，警告其深度系统访问在企业场景的风险。对小汐的安全设计有直接参考价值。

## 建议

- 【P0-最高优先】评估 LangMem SDK 集成可行性：小汐已用 LangGraph，LangMem 是同生态记忆 SDK，集成成本最低。先在测试环境验证 Semantic/Episodic/Procedural 三分法是否能改善当前六因子召回偏重 episodic 的问题。预计工作量 1-2 周。（理由：同生态零摩擦 > 引入外部服务）
- 【P0-最高优先】引入 LanceDB 作为嵌入式向量检索层：解决小汐'无向量检索'短板的最小侵入方案。文件级、零外部依赖、Python 原生，可与现有 SQLite 并存。先对 active memory 做向量索引实验，验证是否显著提升话题命中召回质量。（理由：这是外部报告已识别的核心短板，LanceDB 是调研中发现的最轻量方案）
- 【P1-高优先】将人格锚点和心跳任务从代码常量迁移到可热编辑文件（SOUL.md / HEARTBEAT.md 模式）：主人可随时调整人格和主动任务而无需改代码重启。这是对 OpenClaw 最核心设计模式的直接借鉴，且改造成本低。（理由：单人维护项目应最大化配置灵活性、最小化代码改动频率）
- 【P1-高优先】在 SQLite 记忆中增加 valid_from/valid_to 字段：借鉴 Graphiti 双时态概念但不引入图数据库。用 SQL 查询模拟事实有效期追踪，增强 superseded 链的回放精度和时序推理能力。预计 2-3 天 schema 迁移 + 查询适配。（理由：小汐已有时态意识但不够系统化，这是低成本高收益的改进）
- 【P2-中优先】参考 Dify 沙箱隔离模式，在为小汐添加代码执行/新工具时立即引入 Docker 容器隔离：不要等到工具数量爆炸后再补安全。即使当前 22 硬编码工具风险可控，新增 MCP/插件时必须同步加沙箱。（理由：安全债务累积速度远超偿还速度）
- 【P2-中优先】研究 SillyTavern MemoryBooks 的 lorebook 关键词触发注入机制，作为六因子召回之外的精确匹配补充通道：当用户提到特定关键词时直接注入对应记忆，比语义召回更确定、更快。（理由：混合召回在 RP 社区已被实战验证有效）
- 【P3-低优先/观察】跟踪 OpenBiliClaw 的中文平台内容发现能力，评估是否可作为小汐的工作能力插件：如果冷冷有 B站/小红书内容检索需求，这比通用搜索更精准。但需等待项目成熟度提升。（理由：需求驱动，非架构急需）
- 【P3-低优先/不推荐】不引入 Neo4j/FalkorDB 等图数据库、不采用 Mem0/Zep 托管服务、不模仿 AIRI 的重前端路线：这些对小汐单用户自托管 SQLite 架构来说过重或偏离核心价值。（理由：保持轻量、聚焦差异化）

## 警示

- WebFetch 在本次调研中全线被网络策略拦截，所有架构细节均来自 WebSearch 摘要、官方文档标题/片段、Reddit/博客二次解读；未直接阅读源码或完整论文 PDF
- Mem0/Zep/Cognee 的 benchmark 数字多为厂商自报（vendor-reported），2026-05 'Benchmark Theatre' 文章明确警告 LOCOMO>90% / LongMemEval>90% 缺乏独立复现；本报告中引用的 Zep 63.8% vs Mem0 49.0% 来自第三方对比站 RankSquire/Particula，相对可信但仍非学术同行评审
- OpenClaw star 数（302K-335K）包含大量投机性关注，Reddit r/LocalLLaMA 帖子指出 '唯一可靠用例是每日新闻摘要和个人自动化'；高星≠生产就绪
- AIRI 的记忆/RAG 系统在 GitHub Issue #387 中被承认'目前缺失'，营销话术与实际实现有差距；本报告已区分 working vs aspirational
- 中文项目（OpenOcta/OpenBiliClaw）信息主要来自腾讯云/CSDN/知乎推荐文，缺乏深度技术审计；其记忆系统细节不透明

