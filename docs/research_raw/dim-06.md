# 评测与可观测性

## LongMemEval 五类时间问题与 V2 环境经验范式

LongMemEval V1（ICLR 2025）包含 500 道人工策划问题、约 57M tokens 对话数据、每题关联约 53 个会话。评测五种长期记忆能力：①Information Extraction（单会话信息提取）、②Multi-Session Reasoning（跨会话综合推理）、③Knowledge Updates（用户偏好/事实的时间更新）、④Temporal Reasoning（时间顺序推理）、⑤Cross-Session Preference（特定会话边界内的偏好回忆）。V2（arXiv:2605.12493, 2026-05）转向「资深同事」范式，451 道题，评估 agent 能否积累环境特定经验（如避开反复出现的 gotcha），AgentRunbook-C 达 72.5%。头部系统在 V1 Knowledge Updates 上达 90-99%，但基线模型仅 57-83%。对小汐而言，这五种分类直接映射其六因子召回系统的不同弱点：Knowledge Updates 对应 superseded 链、Temporal Reasoning 对应时间衰减、Multi-Session 对应 dreaming-lite 整合效果。

**对小汐的启示**: 小汐可从 V1 五类中各抽 5-10 题构建 25-50 题的自有 golden set，用冷冷真实对话历史作为 haystack。不必跑完整 benchmark，而是把五类当作「记忆回归测试的分类框架」。V2 的环境经验范式更适合未来工具能力扩展时再引入。注意 V1 题目是英文的，需要翻译/改写为中文并验证等价性。

**来源**: https://arxiv.org/html/2410.10813v1 | https://github.com/xiaowu0162/longmemeval | https://arxiv.org/html/2605.12493v1 | https://xiaowu0162.github.io/long-mem-eval/

## 记忆评测三大 benchmark 对比与厂商自报陷阱

LoCoMo（Snap Research, arXiv:2402.17753, 2024-02）是最早的长期对话记忆 benchmark，包含多会话 QA 和事件摘要任务。BEAM（ICLR 2026, GitHub:mohammadtavakoli78/BEAM）扩展到百万 token 级别，100 段对话、2000 道验证问题，分四个规模桶直到 10M tokens，使用 LLM judge 打分。关键争议：Mem0 自报 LoCoMo 67.13%，但 Zep 独立复现 Mem0 仅得 ~49-57.5%；Zep 自报 84% 被 Mem0 复现为 58.44%。Maximem 第三方测 Mem0 LongMemEval 57.5%、Zep 63.8%，而 Mem0 官方 compare 页面数字更高。这种互撕说明：①benchmark harness 本身未标准化、②检索配置/prompt 模板差异巨大、③厂商有动机选最优配置报告。

**对小汐的启示**: 小汐是自研 SQLite 记忆系统，不应盲目对标这些商业系统的绝对分数。正确用法是：①借鉴 LoCoMo 的多会话 QA 题型结构来设计自己的回归测试；②永远不要引用任何单一厂商自报分数作为选型依据；③如果未来要对比向量检索方案，必须在同一 harness、同一 golden set 上跑 A/B，而非比较不同论文的 headline 数字。

**来源**: https://arxiv.org/abs/2402.17753 | https://github.com/mohammadtavakoli78/BEAM | https://mem0.ai/blog/ai-memory-benchmarks-in-2026 | https://blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/ | https://www.developersdigest.tech/blog/best-ai-agent-memory-providers-2026

## PersonaMem 与人格一致性自动指标体系

PersonaMem（COLM 2025, GitHub:bowen-upenn/PersonaMem）包含 180+ 模拟用户-LLM 交互历史，评估 LLM 动态用户画像推断和个性化回复能力。PersonaMem-v2（arXiv:2512.06688, 2025-12）扩展到 1000 用户×300 场景。PersonaGym（EMNLP 2025 Findings）评估 persona agent 在上下文丰富交互中的对齐度。NeurIPS 2025 论文「Consistently Simulating Human Personas」（arXiv:2511.00222）定义了三个自动一致性指标：①Prompt-to-Line（人设描述→每句输出的忠实度）、②Line-to-Line（相邻轮次连贯性）、③Q&A Consistency（被直接询问人设特征时的一致性）。ResearchGate 2026-08 论文进一步提出 RDI/EDD/DDI/ERS 四维角色漂移指标。这些指标都可离线计算，不依赖 API。

**对小汐的启示**: 小汐已有 20 组探针访谈实跑，这是极好的基础。建议将 NeurIPS 2025 的三个自动指标集成到现有探针流程中：每次探针结果出来后，自动计算 Prompt-to-Line（PERSONA_ANCHOR vs 每句输出）、Line-to-Line（相邻回复连贯度）、Q&A（探针直接问「你是谁/你喜欢什么」的回答与人设文档的对齐度）。这比纯人工评审更可重复、更可追踪漂移趋势。RDI/EDD 等可作为进阶指标。

**来源**: https://github.com/bowen-upenn/PersonaMem | https://arxiv.org/html/2512.06688v1 | https://aclanthology.org/2025.findings-emnlp.368/ | https://arxiv.org/abs/2511.00222 | https://www.researchgate.net/publication/394298208_Enhancing_Persona_Consistency_for_LLMs'_Role-Playing_using_Persona-Aware_Contrastive_Learning

## τ-bench pass^k 可靠性指标与 Agent 能力 benchmark 选型

τ-bench（Sierra Research, ICLR 2025, arXiv:2406.12045）评估 Tool-Agent-User 交互，覆盖 retail 和 airline 两个领域。核心创新是 pass^k 指标：要求 agent 在 k 次独立试验中全部成功，衡量可靠性而非峰值能力。一个 90% 准确率的 agent 可能 pass^10 极低。评测使用 LLM 驱动的用户模拟器产生动态多轮对话，agent 必须调用域特定 API 并严格遵守策略规则，数据库状态会随操作真实变化。GAIA（2026 综述见 decodethefuture.org）测真实世界问答，但 LLM judge 错误率和偏差已被审计指出。BrowseComp（OpenAI, 2025-04）测 web 浏览搜索能力，EvoBrowseComp（arXiv:2606.13120, 2026-06）扩展到演化环境。HELMET（Princeton NLP, arXiv:2410.02694）覆盖长上下文七类应用任务。

**对小汐的启示**: 小汐有 22 个硬编码工具，最适合借鉴 τ-bench 的方法论而非直接跑其数据集。建议：①为小汐最常用的 5-8 个工具（天气/地图/日历/提醒/搜索）各写 3-5 个带策略约束的测试场景；②实现简化版 pass^k（k=3 即可），每次 prompt 变更后跑一遍看可靠性是否下降；③用户模拟器可用 DeepSeek 自身扮演冷冷，但需注意自我模拟的偏差。GAIA/HELMET/BrowseComp 对陪伴型 agent 优先级较低，除非未来扩展通用工作能力时再考虑。

**来源**: https://arxiv.org/abs/2406.12045 | https://openreview.net/forum?id=roNSXZpUDN | https://hippocampus-garden.com/pass_k/ | https://github.com/sierra-research/tau-bench | https://princeton-nlp.github.io/HELMET/

## LLM-as-judge 效度陷阱与校准方法论

多项 2025-2026 研究确认 LLM-as-judge 与人类总体一致率约 80%（MT-Bench 等），但这掩盖了严重问题：①与 lay user 一致率 80% 但与 domain expert 一致率显著更低（ACM 2025 dl.acm.org/doi/10.1145/3708359.3712091）；②存在 75% position bias 和 self-preference（koji.so 2026 决策指南）；③verbosity bias 导致冗长回答得分偏高；④任务复杂度越高一致率越低（ACL 2025 winlp-main.37）。LangChain 2026-03 校准指南强调 rubric-based calibration + human-in-the-loop corrections 是必须的。DataRobot 2025-08 明确警告不可盲信无验证框架的 LLM judge。在人格评测场景中，这些问题更严重，因为「好的人格表现」本身就是主观的。

**对小汐的启示**: 小汐现有的 SDD A/B 盲测纪律是正确的防线。建议：①LLM-as-judge 只用于初筛和趋势监控，不做最终裁决；②为人格评测建立明确的 rubric（如「温暖度」「边界感」「鲸鱼女孩特质保持度」各 1-5 分定义），而非让 LLM 自由评判；③每月用冷冷的真实反馈校准一次 judge prompt；④对于关键变更（如 PERSONA_ANCHOR 调整），必须走人工 A/B 而非纯 LLM judge。DeepEval 的 GEval 支持自定义 rubric + CoT，是实现这一点的现成工具。

**来源**: https://dl.acm.org/doi/10.1145/3708359.3712091 | https://www.koji.so/docs/llm-as-a-judge-vs-human-evaluation | https://www.langchain.com/resources/llm-as-a-judge | https://www.datarobot.com/blog/llm-judges/ | https://galileo.ai/blog/llm-as-a-judge-vs-human-evaluation

## 自托管可观测平台选型：Phoenix vs Langfuse vs Helicone

Arize Phoenix（GitHub:Arize-ai/phoenix）是当前最适合小汐的可观测方案：单进程运行、OpenTelemetry 原生、无需 API key 或云账号、无事件上限、pip install 即可启动。所有数据留在本地，完全满足「对话内容不出服务器」的隐私要求。相比之下，Langfuse v3（2026-01 被 ClickHouse 收购）需要 PostgreSQL + ClickHouse + Redis 三件套，最低 4 vCPU / 16GB RAM，与小汐同机部署会争抢资源。Helicone 是轻量网关方案，改 base URL 即可接入，成本/延迟可视化优秀，但缺乏深度 agent tracing 和内置评测。OpenLLMetry 是 instrumentation 库而非平台，可与上述任一后端搭配。MLflow AI Monitoring 也提供开源 drift detection + eval 能力。

**对小汐的启示**: 推荐小汐采用 Arize Phoenix 作为主可观测平台：①与小汐同机部署，零额外基础设施；②通过 OpenTelemetry SDK 接入 LangGraph middleware，自动记录每次模型调用、工具执行、记忆召回的 trace；③利用 Phoenix 内置的 LLM-as-judge eval 做在线质量监控；④数据全在本地 SSD，满足隐私要求。Langfuse v3 在当前单机预算下不推荐。如果未来需要多人协作或 prompt 管理 UI，再考虑迁移到 Langfuse。Helicone 可作为补充的成本监控层。

**来源**: https://github.com/arize-ai/phoenix | https://www.morphllm.com/comparisons/arize-phoenix-vs-langfuse | https://qaskills.sh/blog/langfuse-vs-arize-phoenix | https://langfuse.com/self-hosting/deployment/docker-compose | https://github.com/orgs/langfuse/discussions/12722

## DeepEval + Promptfoo：pytest 原生 LLM 评测与 CI 成本控制

DeepEval（GitHub:confident-ai/deepeval）是 pytest 原生的 LLM 评测框架，与小汐现有 930 pytest 用例无缝集成。核心特性：①GEval 指标支持自定义 rubric + CoT 评判任意维度；②golden dataset 概念区分「原始标注」和「可执行测试用例」；③支持 Ollama/vLLM 等本地模型作为 judge，无需外部 API；④内置 AnswerRelevancyMetric/FaithfulnessMetric/HallucinationMetric 等 14+ 指标。Promptfoo（GitHub:promptfoo/promptfoo, 20k+ stars）是 CLI/YAML 驱动的替代方案，强项是 red teaming、安全测试、GitHub Action 原生缓存（避免 CI 重跑浪费 token）、cost/latency budget gate。Ragas 专注 RAG 检索质量指标。三者定位不同：DeepEval=pytest 单元测试、Promptfoo=CI 回归+安全、Ragas=RAG 专项。

**对小汐的启示**: 小汐应优先采用 DeepEval，因为它与现有 pytest 生态零摩擦。具体步骤：①pip install deepeval；②把现有 20 组人格探针改写为 DeepEval test case，用 GEval + 自定义人格 rubric 自动评分；③为记忆召回写 AnswerRelevancyMetric + FaithfulnessMetric 测试；④judge 模型用 DeepSeek 本地部署（小汐已有），不走外部 API，保隐私。Promptfoo 作为第二优先级，用于 prompt 变更时的 CI 回归 gate 和安全 red teaming。Ragas 暂不需要（小汐无向量 RAG）。

**来源**: https://github.com/confident-ai/deepeval | https://deepeval.com/docs/metrics-llm-evals | https://deepeval.com/docs/evaluation-datasets | https://www.promptfoo.dev/docs/integrations/github-action/ | https://kinde.com/learn/ai-for-software-engineering/ai-devops/ci-cd-for-evals-running-prompt-and-agent-regression-tests-in-github-actions/

## Persona drift 自动检测指标与 Shadow Mode 在线监控

Persona drift 自动检测已形成学术指标体系：①Time-to-First-Drift (TtFD)（Scilit 2026-05）衡量从对话开始到首次偏离人设的轮次数；②NeurIPS 2025 三指标（Prompt-to-Line/Line-to-Line/Q&A Consistency）可逐轮计算；③ResearchGate 2026-08 四维指标 RDI（Role Drift Index）/EDD（Emotional Discrepancy Distance）/DDI（Dialogue Divergence Index）/ERS（Emotional Response Stability）。Shadow mode 实践（tianpan.co 2026-04, futureagi.com 2026-05）：复制生产请求到新/旧两个版本并行处理，新版响应丢弃但记录，对比两版输出差异。Canary 则是百分比流量切换+自动回滚。对小汐来说，shadow mode 可以在 prompt/模型变更时用：让新旧 PERSONA_ANCHOR 同时处理同一请求，比较输出一致性。

**对小汐的启示**: 小汐可实现一个轻量 persona drift monitor：①每次对话结束后，用 NeurIPS 2025 三指标自动评分并存入 SQLite（新增 persona_drift_scores 表）；②设置 TtFD 阈值告警（如 <10 轮即漂移则标记异常）；③prompt 变更时跑 shadow mode：用最近 20 条真实对话同时过新旧 prompt，比较三指标差异，差异超阈值则阻断上线。这一切可在小汐现有 Python+SQLite 栈内完成，无需外部服务。学术研究中的 NLI 模型可用小模型（如 bge-reranker）替代以控制资源。

**来源**: https://www.scilit.com/publications/0a3787bce8fc17ab2a89cbf9b9032f2e | https://arxiv.org/html/2402.10962v1 | https://arxiv.org/abs/2511.00222 | https://tianpan.co/blog/2026-04-09-llm-gradual-rollout-shadow-canary-ab-testing | https://futureagi.com/blog/llm-eval-shadow-traffic-canary-2026/

## 自建 Golden Dataset 方法论与 Companion Agent 特殊考量

Golden dataset 最佳实践（综合 qaskills.sh/galtea.ai/mastra.ai/maxim.ai/booking.ai/openai.com 2025-2026 指南）：①从真实失败案例出发而非合成 happy path；②初始 20-30 条高质量样本覆盖关键失败模式，逐步扩展；③必须包含 edge case、负面约束（应拒绝的场景）、多轮序列；④当作代码版本管理，与 agent 代码同仓库；⑤定期用人类标注校准自动 judge；⑥companion agent 特别需要 persona/tone 样本和安全边界样本。Booking.com 实战经验强调避免 benchmark overfitting：golden set 应反映真实用户分布而非 benchmark 分布。OpenAI 2026 eval best practices 也强调从 production traces 采样。

**对小汐的启示**: 小汐应从冷冷过去几个月的真实对话日志中提取 golden set：①标记 10 条「完美回复」作为正例基准；②标记 10 条「明显失误」作为负例回归测试；③标记 5 条「边界试探」作为安全测试；④标记 5 条「跨会话记忆依赖」作为记忆测试。总计 30 条起步，存入 git 版本化的 JSON/YAML。每次 prompt/memory 变更后跑一遍这 30 条，用 DeepEval GEval 自动评分 + 月度人工复核。这比追求 benchmark 分数更有实际价值。

**来源**: https://qaskills.sh/blog/golden-dataset-llm-evaluation-guide | https://galtea.ai/blog/llm-evaluation-complete-guide | https://mastra.ai/articles/llm-evaluation | https://booking.ai/llm-evaluation-practical-tips-at-booking-com-1b038a0d6662 | https://developers.openai.com/api/docs/guides/evaluation-best-practices

## CI for LLM：Promptfoo 缓存机制与单人项目成本控制

Promptfoo GitHub Action（github.com/marketplace/actions/test-llm-outputs）原生支持：①PR 触发时仅在 prompt 文件变更时跑评测；②结果缓存避免重跑相同 prompt-input 组合，大幅降低 token 成本；③直接在 PR 评论中展示 before/after 对比。Kinde 2026 CI/CD for Evals 指南提供了完整的 cost/latency budget gate 模式：在 YAML 中设定 max_cost_per_run 和 max_latency_p95，超标即 fail。DeepEval 也可在 GitHub Actions 中跑，但缓存机制不如 Promptfoo 成熟。对于 Gitee 仓库（小汐现状），没有原生 Action，需要用 self-hosted runner + cron 或 webhook 模拟。单人项目成本控制关键：①用小模型做 judge（DeepSeek 本地）；②缓存一切可缓存的；③只在 merge 前跑完整 eval，PR 阶段只跑增量。

**对小汐的启示**: 小汐当前无 CI 且用 Gitee，建议分两步走：①Phase 1（立即可做）：在本地建一个 eval runner 脚本，每次提交前手动跑 DeepEval 30 条 golden set + 人格探针，耗时控制在 5 分钟内（用本地 DeepSeek）；②Phase 2（1-2 周内）：搭建 Gitea self-hosted runner 或用阿里云 ECS 定时任务，实现 push 触发自动 eval + 结果写入 SQLite/Phoenix。成本几乎为零（本地模型）。不建议现阶段引入 Promptfoo cloud 或任何付费 eval 服务。等 golden set 稳定后再考虑 Promptfoo 的缓存优势。

**来源**: https://www.promptfoo.dev/docs/integrations/github-action/ | https://github.com/marketplace/actions/test-llm-outputs | https://kinde.com/learn/ai-for-software-engineering/ai-devops/ci-cd-for-evals-running-prompt-and-agent-regression-tests-in-github-actions/ | https://medium.com/@alexrodriguesj/testing-llm-prompts-like-code-regression-evals-in-ci-cd-with-promptfoo-5242b4dcb9be

## 小汐最小评测/观测栈三层架构总结

综合所有调研，小汐的最小评测/观测栈应为三层架构：Layer 1（离线评测）= DeepEval + 30 条 golden set + 人格探针自动三指标 + 本地 DeepSeek judge，集成到现有 pytest，每次提交前跑；Layer 2（在线观测）= Arize Phoenix 单进程同机部署 + OpenTelemetry middleware 接入 LangGraph，记录每次调用的 trace/span/metric，内置 drift 看板；Layer 3（漂移监控）= persona_drift_scores 表 + TtFD 告警 + shadow mode 对比脚本。这三层全部本地化、零外部 API 依赖、零额外基础设施成本。930 pytest 继续保留作为确定性逻辑的守护网，LLM 评测作为非确定性输出的守护网，两者互补。

**对小汐的启示**: 实施优先级：①第一周：安装 Phoenix + 接入 OTel middleware（最高 ROI，立即获得可见性）；②第二周：构建 30 条 golden set + DeepEval 集成（从真实对话日志提取）；③第三周：实现 persona drift 三指标自动计算 + SQLite 存储；④第四周：shadow mode 脚本 + 本地 CI runner。总投入约 4 周单人兼职时间，零额外月费。不建议现阶段做的事：引入向量检索只为跑 benchmark、部署 Langfuse v3、订阅任何 SaaS eval 平台、追求通用 Agent benchmark 分数。

**来源**: https://github.com/arize-ai/phoenix | https://github.com/confident-ai/deepeval | https://arxiv.org/abs/2511.00222 | https://www.morphllm.com/comparisons/arize-phoenix-vs-langfuse

## 项目

- **Arize Phoenix** (https://github.com/arize-ai/phoenix) [GitHub 8k+ stars, Apache 2.0, active development] — 单进程、OTel 原生、零云依赖，是小汐当前最佳可观测选择。pip install arize-phoenix 即可启动，与小汐同机零负担
- **DeepEval** (https://github.com/confident-ai/deepeval) [GitHub 6k+ stars, Apache 2.0, pytest-native] — 与小汐 930 pytest 零摩擦集成，GEval 支持自定义 rubric + 本地模型 judge，golden dataset 管理完善
- **Promptfoo** (https://github.com/promptfoo/promptfoo) [GitHub 20k+ stars, MIT, CLI-first] — CI 缓存机制最成熟，red teaming 能力强，适合 Phase 2 引入做 prompt 回归 gate。Gitee 需 self-hosted runner
- **LongMemEval** (https://github.com/xiaowu0162/longmemeval) [GitHub xiaowu0162/longmemeval, academic, ICLR 2025] — 五类记忆能力分类框架直接可用于设计小汐自有记忆回归测试，不必跑完整 benchmark
- **tau-bench** (https://github.com/sierra-research/tau-bench) [GitHub sierra-research/tau-bench, ICLR 2025] — pass^k 可靠性指标方法论最值得借鉴，可为小汐 22 个工具自建子集评测
- **consistent-LLMs** (https://github.com/abdulhaim/consistent-LLMs) [GitHub abdulhaim/consistent-LLMs, NeurIPS 2025] — 三指标人格一致性度量参考实现，可直接改造为小汐 persona drift monitor
- **PersonaMem** (https://github.com/bowen-upenn/PersonaMem) [GitHub bowen-upenn/PersonaMem, COLM 2025] — 动态用户画像评测方法论可迁移到小汐画像蒸馏≤500字的验证

## 论文

- **LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory** (arXiv:2410.10813) 2024 — 500 题五种长期记忆能力评测框架，是记忆回归测试的最佳分类模板
- **LongMemEval-V2: Evaluating Long-Term Agent Memory Toward Experienced Colleagues** (arXiv:2605.12493) 2026 — V2 转向环境经验评测，451 题测 agent 能否成为资深同事，对小汐工具能力扩展有参考价值
- **LoCoMo: Evaluating Very Long-Term Conversational Memory of LLM Agents** (arXiv:2402.17753) 2024 — 首个长期对话记忆 benchmark，多会话 QA + 事件摘要，题型结构值得借鉴但分数不可跨系统比较
- **τ-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains** (arXiv:2406.12045) 2024 — pass^k 可靠性指标是陪伴 agent 最需要的度量——用户需要的是每次都可靠，不是偶尔很惊艳
- **Consistently Simulating Human Personas with Multi-Turn Dialogue** (arXiv:2511.00222) 2025 — Prompt-to-Line/Line-to-Line/Q&A 三指标可直接集成到小汐探针流程，实现人格一致性自动追踪
- **HELMET: How to Evaluate Long-Context Language Models Effectively and Thoroughly** (arXiv:2410.02694) 2024 — 长上下文七类应用任务评测，对小汐当前优先级较低但未来扩展通用能力时可参考
- **PersonaMem-v2: Towards Personalized Intelligence via Learning from Experience** (arXiv:2512.06688) 2025 — 1000 用户×300 场景个性化 benchmark，动态画像推断评测方法可迁移到小汐的画像蒸馏验证
- **Measuring and Controlling Persona Drift in Language Model Dialogs** (arXiv:2402.10962) 2024 — 无需人工/API 的 persona drift 量化方法，是小汐在线漂移监控的理论基础

## 建议

- 【P0-本周】部署 Arize Phoenix 作为可观测基座：pip install arize-phoenix，在小汐 LangGraph middleware 中加 OTel instrumentation，立即获得每次模型调用/工具执行/记忆召回的 trace 可视化。零成本、零外部依赖、数据全在本地。这是所有后续评测工作的前提——没有观测就没有改进方向。
- 【P0-下周】从冷冷真实对话日志中提取 30 条 golden set（10 完美+10 失误+5 边界+5 跨会话记忆），版本化存入仓库。这比任何 benchmark 都更能守护小汐的实际体验质量。同时安装 DeepEval，把 golden set 写成 pytest test case，用本地 DeepSeek 作 judge。
- 【P1-两周内】将 NeurIPS 2025 三指标（Prompt-to-Line/Line-to-Line/Q&A Consistency）集成到现有 20 组探针流程，实现人格一致性自动评分。新建 persona_drift_scores 表存储历史趋势。设置 TtFD < 10 轮自动告警。这让人格漂移从「凭感觉」变成「可量化」。
- 【P1-一个月内】搭建 Gitea self-hosted runner 或阿里云 ECS cron job，实现 push 触发自动 eval。Phase 1 只跑 golden set + 人格探针（<5min），Phase 2 加入 Promptfoo 做 prompt 变更回归 gate + 安全 red teaming。全程用本地 DeepSeek judge，零 API 费用。
- 【P2-按需】为小汐最常用的 5-8 个工具参照 τ-bench 方法论编写带策略约束的测试场景，实现简化版 pass^3 可靠性度量。这在工具能力扩展或更换底层模型时尤其重要。
- 【暂缓】不引入 Langfuse v3（太重）、不追求通用 Agent benchmark 分数（与小汐场景错位）、不引入向量检索仅为跑 benchmark、不订阅任何 SaaS eval 平台。等小汐从陪伴进化到通用 Agent 阶段再重新评估这些选项。

## 警示

- Mem0/Zep/Maximem 等记忆系统 benchmark 分数严重互撕，厂商自报与第三方复现差距可达 30+ 百分点，本报告中已标注来源性质
- LLM-as-judge 在人格/共情等主观维度上与人类专家一致性低于与lay user一致性，~80% 总体一致率掩盖了系统性偏差（position bias、self-preference、verbosity bias）
- Langfuse v3 已于 2026-01 被 ClickHouse 收购，架构从 PostgreSQL-only 变为 PostgreSQL+ClickHouse+Redis，资源需求翻倍，不再适合与小汐同机部署
- BrowseComp/τ-bench/GAIA 等通用 Agent benchmark 设计面向多工具复杂场景，单人项目自建子集时需警惕过拟合到子集分布
- Persona drift 自动检测指标（TtFD/RDI/EDD/DDI/ERS）均为学术原型，尚无生产级库可直接集成，需自行实现

