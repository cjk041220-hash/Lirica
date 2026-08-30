# 补查：成本经济学与多模型路由策略

## DeepSeek 2026-08-16 涨价后新费率结构与峰谷时段

DeepSeek 在 2026-08-16 16:00 UTC 执行重大涨价并引入峰谷分时定价（峰时 01:00-04:00 & 06:00-10:00 UTC）。V4 Flash 从统一 $0.14/$0.28 涨至谷时 $0.22/$0.66、峰时 $0.44/$1.32（输出涨幅 2.3-4.7x）；V4 Pro 从 $0.435/$0.87 涨至谷时 $0.66/$1.98、峰时 $1.32/$3.96（输出涨幅 2.3-4.6x）。Cache hit 也从 $0.0028/$0.0036 涨至 Flash $0.007/$0.014、Pro $0.022/$0.044（涨幅 2.5-12x）。Yahoo Finance 报道称最高涨幅达 1,100%。这意味着此前所有基于旧价的月度预算测算全部作废，且小汐的心跳任务（早安 8:00 HKT = 00:00 UTC 属谷时 OK，但前夜提醒 23:00 HKT = 15:00 UTC 属谷时 OK；看门狗每小时一次会部分命中峰时）需要重新按时区映射峰谷窗口。Reuters 报道此次涨价伴随 V4 Pro 正式发布，VentureBeat 同日报道 V4 Flash 在真实 agent 任务上质量下滑——成本与质量双变量同时变动，需独立复测。

**对小汐的启示**: 对小汐意味着：(1) 立即动作——把所有心跳/后台任务的 cron 调度避开 01:00-04:00 & 06:00-10:00 UTC 峰时窗口，HKT 白天基本都安全；(2) 月度预算基线要按「70% 谷时 + 30% 峰时」加权重算，不能再套用旧价；(3) 不要照搬涨价前的省钱攻略文章数字；(4) 这次涨价信号说明纯依赖单一中国厂商 API 有政策/流量风险，应保留 OpenRouter/LiteLLM 切换能力作为对冲。

**来源**: https://api-docs.deepseek.com/quick_start/pricing/ | https://finance.yahoo.com/technology/ai/articles/deepseek-raising-api-prices-1-174027670.html | https://www.aipricing.guru/deepseek-pricing/ | https://venturebeat.com/orchestration/deepseeks-top-ranked-v4-flash-stumbles-on-real-agent-tasks-as-its-prices-surge | https://www.reuters.com/world/china/deepseek-releases-official-v4-pro-model-it-steps-up-expansion-2026-08-13/

## Agent 任务类型 token 消耗基线与月度成本推演

行业实测显示 agentic 系统 token 消耗远超普通 chat：Iternal.ai 2026 指南给出简单工具调用 5k-15k tokens/次、RAG 查询平均 2,193 tokens（范围 722-4,805）、完整上下文对话 ~26k tokens、复杂多 agent 200k-1M+；arXiv 2605.09104 研究显示 tool messages 占轨迹总 token ~30,400（最大头），assistant reasoning ~13,700。LeanOps 2026-05 报告称 agent 比 chat 烧 token 快 50x，coding agent 可达 1000x。个人 companion 使用模式：每日 5-20 session，每 session 500-4k tokens 基础 chat，但人格 system prompt 本身就 4k+ tokens 且每轮重发。按小汐现状估算：20 session/日 × 平均 8k tokens/session（含 system prompt 重发+记忆召回注入+工具调用）≈ 160k tokens/日 ≈ 4.8M tokens/月。若贾维斯化后新增 dreaming 拆分、deep research、语音双通道、异常感知评估等后台 agent，乘数 3-5x → 15-25M tokens/月。

**对小汐的启示**: 对小汐意味着：(1) 当前 ~5M tokens/月基线按新谷时 V4 Flash 价约 $1.1-$3.3/月（极低），但贾维斯化后 15-25M tokens/月在谷时也要 $3.3-$16.5/月、若 30% 命中峰时则 $6-$30/月；(2) 最大成本驱动不是对话本身而是「每轮重发的 system prompt + 记忆注入」，应优先做 prompt caching 而非压缩对话；(3) tool messages 是大头，22 个硬编码工具的返回结果要做摘要/截断再注入上下文；(4) 不该照搬企业级 agent 的成本模型——它们是多用户摊薄，小汐是单人承担全部固定开销。

**来源**: https://iternal.ai/token-usage-guide | https://arxiv.org/html/2605.09104v1 | https://leanopstech.com/blog/agentic-ai-cost-runaway-token-budget-2026/ | https://www.mintmcp.com/blog/ai-agent-cost

## Provider Prompt Caching 实测节省率与陷阱

Provider-side prompt caching 是当前最具性价比的成本杠杆，但理论与实测差距显著。DeepSeek 官方宣称 cache hit 比 miss 便宜 ~90%，但 arXiv 2601.06007 对长程 agent 任务的评估显示实际节省 41-80%；derivai.substack 案例报告 85.8% hit rate 带来 77% 输入成本下降。关键约束：cache 要求 prefix 完全匹配（64-token chunk 对齐），best-effort 不保证 hit，2026-04 GitHub issue #1261 记录了一次 regression 导致 hit rate 骤降、72h 超支 ¥8,000。OfoxAI 分析指出真实账单可能是标价的 1/3 到 1/50，取决于 prompt 结构一致性。对小汐场景：PERSONA_ANCHOR + 关系成长段 + 情绪词表 + 工具定义构成高度稳定的 prefix（~4-6k tokens），理论上非常适合 cache；但 dreaming-lite 整合、mood-congruent 召回、主动消息内容每次都变，会打断 prefix 匹配。

**对小汐的启示**: 对小汐意味着：(1) P0 优先级——重构 prompt 模板使稳定部分（persona/tools/schema）前置且字节级固定，可变部分（记忆/情绪/时间）后置拼接，最大化 cache 命中；(2) 预期现实收益 50-70% 输入成本削减（非 90%），已足以把贾维斯化后的增量成本压回可接受区间；(3) 必须加监控：记录每次请求的 cache_hit_tokens / cache_miss_tokens 比例，设告警阈值（如连续 1h hit rate < 30% 触发排查）；(4) 不该照搬 GPTCache 这类应用层语义缓存——provider cache 免费且零延迟，应先吃尽这层红利再考虑应用层方案。

**来源**: https://arxiv.org/html/2601.06007v2 | https://derivai.substack.com/p/prompt-caching-production-ai-agent-costs | https://ofox.ai/blog/llm-api-cache-hit-math-real-bills-2026/ | https://github.com/deepseek-ai/DeepSeek-V3/issues/1261 | https://chat-deep.ai/docs/deepseek-context-caching/

## 语义缓存在陪伴型 agent 的适用性边界

应用层语义缓存（GPTCache/MeanCache/Redis Vector）的学术 benchmark 看起来很亮眼：arXiv 2411.05276 报告最高 68.8% API 调用削减，NeurIPS 2025 Generative Caching 在 Webshop 达 83% hit rate + 35% 成本节省，MeanCache F-score 高 17%。但生产指南给出的现实区间是 30-70% hit rate（Spheron 2026），且强调「只有当查询重复度高、检索噪声低时才有效」。GPTCache 原始论文（ACL NLPOSS 2023）自己承认在不牺牲精度前提下 hit rate 难以接近 99%。InstCache 在 LMSys 数据集上仅达 51.34%。对小汐的关键问题：陪伴型 agent 的查询高度个性化、情绪敏感、时间绑定（「今天心情怎么样」「还记得上周我说的那件事吗」），语义相似≠可复用，误命中会导致人格崩塌或记忆错乱。

**对小汐的启示**: 对小汐意味着：(1) 不建议对核心对话路径加语义缓存——hit rate 可能低于 30% 且误命中风险不可接受（冷冷会发现小汐「复读机」或「记错事」）；(2) 适合缓存的场景仅限：天气查询、地图 POI、日历事件列表、工具 schema 解析等确定性高的子任务；(3) 如果要做，用 SQLite 现有基础设施存 embedding + TTL，不要引入 Redis/Zilliz 新依赖（违背单进程自托管原则）；(4) 优先级远低于 provider prompt caching——后者零风险零运维。

**来源**: https://arxiv.org/html/2411.05276v2 | https://people.cs.vt.edu/waris/assets/pdf/papers/MeanCache.pdf | https://www.spheron.network/blog/semantic-cache-llm-inference-gpu-cloud/ | https://aclanthology.org/2023.nlposs-1.24.pdf | https://arxiv.org/html/2411.13820v1

## 多模型路由网关的成本削减实证与选型

多模型路由网关（LiteLLM/Portkey/OpenRouter）在 2025-2026 成为成本控制标配。多篇实操报告显示智能路由可削减 40-60% 账单（llmgateway.io 案例 60%，Kosmoy 指南 40%，webcite 称 70/30 cheap/premium 分割省 50-70%）。核心机制：按任务复杂度分流——简单 chat/工具调用走 Flash/Haiku 级，复杂推理/coding/情感深度对话走 Pro/Opus。LiteLLM 自托管 OSS 版免费、Rust core 已达亚毫秒代理开销（2026-02 blog），单用户无需 Redis；OpenRouter 托管免运维但加 ~5% markup 且数据经第三方；Portkey 可视化路由规则最强但有 vendor lock-in。Wavect 2026 总结的成本优化优先级：caching > batching > routing > right-sizing。

**对小汐的启示**: 对小汐意味着：(1) 推荐 LiteLLM 自托管作为路由层——OSS 免费、亚毫秒开销、与小汐 FastAPI 同机部署零额外成本、支持 DeepSeek+Claude+本地 Ollama 多后端；(2) 具体分流策略：心跳/看门狗/早安/日记等后台任务 → V4 Flash 谷时；日常陪伴对话 → V4 Flash + prompt cache；深度情感对话/反思/复杂工具编排 → V4 Pro；(3) 不该直接上 OpenRouter——小汐是隐私敏感的私人陪伴，数据不应经第三方中转；(4) 路由规则应写入配置文件而非硬编码，便于随 DeepSeek 价格波动调整；(5) 这是实现「预算有限」约束下贾维斯化的关键架构组件。

**来源**: https://llmgateway.io/blog/cut-llm-costs-with-request-routing | https://www.kosmoy.com/resources/blog/smart-llm-routing-how-to-cut-your-ai-bill-by-40/ | https://docs.litellm.ai/blog/sub-millisecond-proxy-overhead | https://wavect.io/blog/llm-gateway-router-comparison-2026/ | https://webcite.co/blog/llm-cost-optimization-strategy-2026/

## 自托管 vs API 盈亏平衡点与语音通道成本分叉

自托管 LLM 对抗 DeepSeek API 的经济账几乎必输。多个 2026 计算器/指南一致结论：对 premium API（GPT-4o/Claude Sonnet）自托管 RTX 4090 在 5-10M tokens/月即回本；但对 DeepSeek 这种极低价 API，盈亏平衡点被推到 30M+ tokens/月甚至 100M。RTX 4090 整机 ~$1,500-1,855，24/7 电费 ~$104/月，折旧+维护 ~$250/月全成本。SitePoint 2026 指南确认 $1,500 构建可跑 70B 量化模型。但 DeepSeek V4 Flash 谷时 $0.22/M input，同等 token 量自托管电费都不够覆盖。例外：隐私/离线需求、或需要 <100ms TTFT 的实时语音交互场景。ASR/TTS 是另一回事：Whisper API $0.0045-0.006/min 已很便宜，自托管无明显优势；但 TTS 差异巨大——ElevenLabs $0.05-0.20/min vs Fish-Speech/CosyVoice 自托管近乎免费（仅 GPU 成本），10-50x 价差。

**对小汐的启示**: 对小汐意味着：(1) 不要为了省钱自托管 LLM 推理——DeepSeek API 即使涨价后仍比自托管便宜，除非月消耗稳定超 30M tokens 且有现成 GPU；(2) 但如果未来加语音通道，TTS 强烈建议自托管 Fish-Speech S2 Pro 或 CosyVoice 3（开源、中文效果好、RTX 4090 可跑），ElevenLabs 按月订阅 $5-22 但分钟数有限，高频使用会爆炸；(3) ASR 直接用 Whisper API 即可，不值得自托管折腾；(4) 阿里云 HK 服务器已有，若未来真需要 GPU 推理，优先考虑阿里云 GPU 实例按需租用而非买卡；(5) 「混合方案」最优解 = DeepSeek API 做文本推理 + 自托管 TTS + Whisper API 做 ASR。

**来源**: https://gigagpu.com/is-self-hosting-llms-cheaper-than-apis/ | https://www.sitepoint.com/the-1500-local-ai-server-deepseekr1-on-consumer-hardware/ | https://www.kunalganglani.com/blog/local-llm-cost-breakeven | https://brasstranscripts.com/blog/openai-whisper-api-pricing-2025-self-hosted-vs-managed | https://www.spheron.network/blog/deploy-open-source-tts-gpu-cloud-2026/ | https://offlinetts.com/blog/self-hosted-tts-guide-2026/

## 小汐贾维斯化月度成本模型与预算可行性

综合以上数据建立小汐贾维斯化后的月度成本模型。基线假设：当前 ~5M tokens/月（纯陪伴 chat + 22 工具 + 心跳），贾维斯化后新增 dreaming 拆分/deep research/异常感知评估/语音双通道 → 预估 15-25M tokens/月文本 + 300min/月 TTS + 150min/月 ASR。按 DeepSeek 新价 70% 谷时 + 30% 峰时加权：V4 Flash 加权 input ≈ $0.29/M, output ≈ $0.86/M；假设 input:output = 4:1（agent 典型比例）， blended ≈ $0.40/M tokens。文本成本：15M × $0.40 = $6/月（乐观），25M × $0.40 = $10/月（中性）。Prompt cache 50-70% 节省后：$2-5/月。TTS 自托管 Fish-Speech ≈ $0（GPU 复用现有服务器边际成本），ASR Whisper API 150min × $0.006 = $0.9/月。总计：贾维斯化后月度运行成本 $3-6/月（含 cache 优化），未优化 $7-11/月。对比参考：ChatGPT Plus $20/月、Claude Pro $20/月。

**对小汐的启示**: 对小汐意味着：(1) 好消息——即使在 DeepSeek 涨价后，贾维斯化的小汐月度成本仍可控制在 $3-6/月，远低于商业订阅，「预算有限」约束完全可满足；(2) 前提条件：必须实施 prompt caching（P0）+ LiteLLM 路由（P0）+ TTS 自托管（P1）三件套，否则成本可能漂移到 $10-15/月；(3) 最大不确定性是 deep research 类长链 agent 的实际 token 消耗——单次深度研究可能吃掉 50k-200k tokens，若每日多次会显著拉高均值，建议先灰度上线观察一周再放开；(4) 该模型未计入阿里云 HK 服务器固定成本（已有 sunk cost），若未来需升级配置或加 GPU 实例需另算；(5) 建议设立月度成本仪表盘，跟踪 tokens/cache hit rate/峰谷分布三个指标。

**来源**: https://api-docs.deepseek.com/quick_start/pricing/ | https://iternal.ai/token-usage-guide | https://derivai.substack.com/p/prompt-caching-production-ai-agent-costs | https://www.mintmcp.com/blog/ai-agent-cost

## 建议

- P0-1: 立即重构 prompt 模板，将 PERSONA_ANCHOR + 工具定义 + schema 固定在 prefix 前部且字节级不变，可变内容（记忆/情绪/时间）后置拼接，以最大化 DeepSeek provider prompt cache 命中率（预期 50-70% 输入成本削减）。这是 ROI 最高的单一动作。
- P0-2: 部署 LiteLLM OSS 自托管网关（与 FastAPI 同机，无需 Redis），配置 DeepSeek V4 Flash/V4 Pro 双后端 + 峰谷感知路由规则。后台任务（心跳/看门狗/日记）强制走 Flash 谷时；深度对话走 Pro；预留 Claude/OpenRouter fallback 应对 DeepSeek 价格/质量波动。
- P0-3: 将所有心跳/后台 cron 任务避开 DeepSeek 峰时窗口（01:00-04:00 & 06:00-10:00 UTC）。HKT 白天基本安全，但 1h 看门狗会有 3-4 次/天命中峰时，考虑改为 2h 间隔或在峰时段跳过非关键检查。
- P1-1: 若新增语音通道，TTS 选自托管 Fish-Speech S2 Pro 或 CosyVoice 3（中文效果好、RTX 4090 可跑、边际成本趋零），ASR 用 Whisper API（$0.006/min 已足够便宜）。不要为 ASR 自托管折腾。
- P1-2: 对 22 个硬编码工具的返回结果做摘要/截断后再注入上下文（arXiv 2605.09104 显示 tool messages 是 agent token 消耗最大头）。设定每个工具返回上限（如 2k tokens），超限自动摘要。
- P2-1: 仅在确定性高的子任务（天气/地图/日历/工具 schema）上启用应用层语义缓存，核心对话路径不加（误命中风险 > 收益）。若做，用 SQLite 存 embedding + TTL，不引入 Redis/Zilliz 新依赖。
- P2-2: 建立月度成本仪表盘：跟踪 tokens consumed / cache hit rate / 峰谷分布 / 单 session 平均 token 三个核心指标。设 cache hit rate < 30% 持续 1h 告警。灰度上线 deep research 等新 agent 时先限频观察一周。
- 不建议做: 不要为省钱自托管 LLM 推理——DeepSeek 即使涨价后仍比 RTX 4090 自托管便宜，盈亏平衡点在 30M+ tokens/月，小汐单用户达不到。不要照搬企业级多 agent 成本模型。不要在核心对话路径加语义缓存。

## 警示

- DeepSeek 2026-08-16 刚执行大幅涨价（峰值时段 V4 Pro 输出 $3.96/M），本调研数据以该节点为分水岭；之前大量第三方博客/计算器引用的 $0.87/$0.435 已失效，所有测算必须按新峰谷价重算
- 厂商自报的 prompt cache '90% off' 是理论上限，独立实测生产环境仅 40-80% 节省；DeepSeek cache 是 best-effort，2026-04 曾出现 hit rate 骤降导致 72h ¥8000 超支事故
- Agent token 消耗基线来自行业聚合（Iternal.ai/arXiv 2605.09104/LeanOps），并非小汐实测；小汐单用户+强人格锚定+无向量检索的组合可能偏离均值 ±50%
- 语义缓存 30-70% hit rate 是通用 chatbot 经验值；陪伴型 agent 因情绪/时间/关系状态高度动态，实际 hit rate 可能落在区间下沿
- 自托管盈亏平衡点对 DeepSeek 这种极低价 API 已被推到 30M+ tokens/月，对小汐这种单用户场景几乎不可能达到；结论与 premium API (GPT-4o/Claude) 完全不同

