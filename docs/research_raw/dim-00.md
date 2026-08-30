# 记忆系统前沿

## Letta Sleep-Time Compute 与 Memory Block 自编辑机制

Letta/MemGPT 在 arXiv:2504.13171（2025-04）提出 sleep-time compute：主 agent 空闲时由后台 'sleep-time agent' 接管 memory blocks 进行整合、预计算、重写状态，使同一任务所需 test-time compute 下降约 5×。Memory blocks 是 in-context 的固定大小结构化槽位（core memory / archival / recall），由 LLM 通过专用 tool 自我编辑，而非外部写入。filesystem-as-memory 实验（MemFS）让 agent 用标准文件操作读写 markdown 笔记，在 LoCoMo 上达到约 74%，超过许多专用记忆工具——证明对中小规模记忆而言，结构化文本 + LLM 自管理比复杂索引更有效。该机制的核心洞察是：把'整合'从请求路径移出，利用闲置 token 预算做离线反思，与人类睡眠期海马体-皮层巩固高度对应。

**对小汐的启示**: 对小汐极高价值：已有 dreaming-lite 周期整合就是 sleep-time compute 的雏形，但仍在请求路径内同步执行。建议拆分为独立后台任务，利用心跳 5 任务的空闲时段（如凌晨 3-6 点）运行专门的 'consolidation agent'，让其读取近 24h 原始对话 + active 记忆，输出改写后的 core memory blocks。memory block 模式可直接映射到 SQLite：建一张 persona_blocks 表（block_name/text/version/updated_at），用 prompt 让 LLM 输出 JSON patch 而非全文重写，降低写放大。filesystem-as-memory 74% 的结果暗示：小汐当前纯 SQLite + 六因子召回已经够用，不必急于引入向量库；应优先把 'self-editing protocol' 做实（块粒度锁、版本链、回滚）。不建议照搬 Letta 的多 agent 协作内存共享机制，单用户场景是过度工程。

**来源**: https://arxiv.org/abs/2504.13171 | https://www.letta.com/blog/sleep-time-compute/ | https://www.letta.com/blog/memory-blocks/ | https://github.com/letta-ai/sleep-time-compute

## Mem0 Graph/Temporal Memory 与 Benchmark 争议

Mem0 在 arXiv:2504.19413（2025-04）宣称 LongMemEval 93.4%、LoCoMo 91.6%，2026-07 又更新至 94.4%/92.5%。架构含 graph memory（Mem0g，实体-关系三元组）、temporal memory（时间戳 + 失效标记）、extract-and-retrieve 流水线（LLM 抽取事实 → 去重合并 → 存入图 + 向量双索引）。然而独立复现严重缩水：Mnemoverse（2026-07）在 Maximem harness 下仅得 73.8%；Reddit 社区 clean eval 同样未复现 93.4%；Zep 反向质疑 Mem0 跑 MemGPT 的数字 'puzzling'。争议根源：(1) harness 可能泄漏测试集；(2) 把 token 压缩率混淆为准确率；(3) LoCoMo 本身已饱和且偏事实召回。Mem0 的 temporal memory 实现细节未完全开源，graph memory 依赖 FalkorDB/Neo4j。

**对小汐的启示**: 对小汐的警示大于借鉴：(1) 不要迷信任何单一 benchmark 数字，包括本调研中的；(2) Mem0 的 extract-and-retrieve 流水线值得借鉴——小汐当前后台提取已是类似思路，但缺少'事实级去重合并'这一步，可在 dreaming-lite 中加入 LLM-as-judge 判断两条记忆是否表达同一事实并合并；(3) graph memory 对单用户 SQLite 是过度工程，小汐的关系复杂度远低于多用户企业场景；(4) temporal memory 的'失效标记而非删除'思想与小汐 superseded 链完全一致，说明方向正确，继续保持；(5) 绝不引入 FalkorDB/Neo4j 这种重型依赖，违背单进程自托管原则。

**来源**: https://arxiv.org/abs/2504.19413 | https://preuve.ai/blog/ai-memory-systems-statistics-2026 | https://www.reddit.com/r/AIMemory/comments/1uczgzi/mem0_publishes_934_on_longmemeval_the_harness_has/ | https://blog.getzep.com/lies-damn-lies-statistics-is-mem0-really-sota-in-agent-memory/

## Zep/Graphiti Bi-Temporal 模型与 Edge Invalidation

Zep/Graphiti（arXiv:2501.13956, 2025-01）的核心创新是 bi-temporal 模型：每条边同时记录 valid_time（事实在世界中为真的时间段）和 ingestion_time（系统获知该事实的时间）。矛盾处理不是删除旧边，而是将旧边标记为 invalidated/superseded，保留完整历史轨迹供回溯查询。时序推理基于这两条时间轴做区间交集运算，避免 LLM 主观判断谁对谁错。原版 Zep 论文曾宣称 LoCoMo 84%，后被 Mem0 质疑并在 GitHub issue #5 中修正为 75.14%±0.17（Mem0 方复现为 58.44%，双方仍有争议）。Graphiti 官方绑定 Neo4j，无原生 SQLite 后端；但其数据模型本质是三张表（nodes/edges/temporal_metadata），完全可用关系型模拟。

**对小汐的启示**: 对小汐高价值但需轻量化改造：(1) 小汐已有 superseded 链，本质上就是 edge invalidation 的单链表简化版，方向完全正确；(2) 可增量升级：在 memories 表加 valid_from/valid_to/ingested_at 三列，ingested_at 已有（created_at），valid_from 默认为 created_at，当检测到矛盾时新记录的 valid_from=now，旧记录的 valid_to=now，不删旧记录——这就是 bi-temporal 的最小可行实现，零额外依赖；(3) 不要引入 Neo4j，单用户场景用 SQLite 三列表模拟即可，查询用 SQL JOIN + 时间区间过滤，千条级性能无忧；(4) Zep 的'矛盾由时间裁决而非 LLM 裁决'原则值得采纳，可减少 dreaming-lite 中 LLM 误判导致的事实丢失。

**来源**: https://arxiv.org/abs/2501.13956 | https://www.getzep.com/ai-agents/temporal-knowledge-graph/ | https://github.com/getzep/zep-papers/issues/5 | https://medium.com/@pankaj_pandey/graphiti-give-every-fact-in-our-knowledge-graph-an-expiration-date-090f0de93ff2

## A-MEM Zettelkasten 式结构化记忆

A-MEM（arXiv:2502.12110, 2025-02）受 Zettelkasten 笔记法启发，每条记忆不是扁平字符串，而是结构化 note：包含 key insight (K_i)、grounding context (G_i)、cross-reference links (X_i)、tags、keywords。新记忆入库时，LLM 自动生成这些字段并主动扫描历史记忆建立链接，形成自组织知识网络。与 Generative Agents 的 reflection 不同，A-MEM 强调'结构化存储'而非'事后抽象'，每条记忆自带元数据使其可被多维度检索。GitHub 开源实现（agiresearch/A-mem）显示该结构在 PersonalLLM 等基准上优于 flat memory stream。核心代价是每次写入需额外 1-2 次 LLM 调用生成元数据 + 链接分析。

**对小汐的启示**: 对小汐中等价值、可渐进实施：(1) 小汐当前记忆已是分层（active/core/archived），但每层内部仍是半扁平文本。可在 core memory 层引入 A-MEM 式结构：给每条核心记忆加 tags[]、related_memory_ids[]、insight_summary 字段，写入时由 LLM 一并生成；(2) cross-reference 链接可实现'联想召回'：当某条记忆被命中时，其 linked memories 自动进入候选池，补充六因子召回的盲区；(3) 但不要全量照搬：小汐千条级记忆不需要 Zettelkasten 的全网链接密度，建议只对 importance≥7 的记忆启用完整 A-MEM 结构化，低重要性记忆保持轻量；(4) 额外 LLM 调用成本可控（单用户日均写入<50条），但需在后台提取阶段完成，不能阻塞响应路径。

**来源**: https://arxiv.org/abs/2502.12110 | https://github.com/agiresearch/a-mem | https://arize.com/glossary/agentic-memory-a-mem/

## MemoryOS/MemOS 记忆操作系统框架

MemoryOS（arXiv:2506.06326, EMNLP 2025 Oral）和 MemOS（arXiv:2507.03724, 2025-07）是两个独立项目，都试图把记忆当作操作系统级资源管理。MemoryOS 侧重个性化 agent，采用三层存储（short/medium/long-term）+ 四模块（storage/update/retrieval/generation），引入遗忘曲线动态调度；MemOS 侧重系统级框架，三层架构（API/scheduling/storage）统一管理记忆生命周期（generation→activation→fusion→archiving→expiration）。两者的共同点是把'忘记'视为一等公民操作，而非被动溢出。MemoryOS 在 LoCoMo 上有实测提升但未公布绝对数字；MemOS 更偏理论框架，尚无公开 benchmark。两者都假设多租户/多 agent 场景，对小汐单用户过重。

**对小汐的启示**: 对小汐概念价值高于直接移植：(1) '遗忘是一等公民'的思想与小汐分层衰减一致，但小汐缺'主动遗忘决策'——目前衰减是时间驱动的被动过程，可加入'冲突驱动遗忘'：当新事实 supersede 旧事实且旧事实已 30 天未被访问，自动降级到 archived；(2) MemoryOS 的 forgetting curve 参数化（Ebbinghaus 拟合）可作为小汐时间衰减因子的理论校准依据，替代当前的手工调参；(3) 不要引入完整 OS 抽象层，单用户 SQLite 项目套 OS 壳会增加调试复杂度而无实际收益；(4) MemOS 的 lifecycle state machine（generation→activation→fusion→archiving→expiration）可作为小汐记忆状态机的设计参考，把当前隐式的状态转换显式化为状态图。

**来源**: https://arxiv.org/abs/2506.06326 | https://arxiv.org/abs/2507.03724 | https://github.com/BAI-LAB/MemoryOS | https://github.com/MemTensor/MemOS

## HippoRAG/HippoRAG2 海马体式索引与多跳推理

HippoRAG（NeurIPS 2024, arXiv:2405.14831）受海马体索引理论启发，用 Knowledge Graph + Personalized PageRank（PPR）替代向量索引做 RAG，实现单步多跳推理。HippoRAG2（ICML 2025）进一步从 RAG 演进为 memory 系统，强调对新知识的连接识别与持续学习。关键机制：新文档入库时先由 LLM 抽取实体/关系入图，查询时以 query 实体为种子跑 PPR 扩散，一次性获得多跳相关节点，无需迭代检索。在 multi-hop QA 基准上优于传统 RAG。但 PPR 需要图遍历，对 Neo4j 等图存储有依赖；SQLite 模拟 PPR 需手写 BFS/DFS，千节点级可行但代码复杂度高。

**对小汐的启示**: 对小汐选择性借鉴：(1) PPR 式多跳推理对小汐的'联想召回'有启发，但小汐的记忆主要是情景记忆而非知识图谱，多跳需求弱；(2) 如果未来小汐需要从'主人说过 X'推导到'X 关联的 Y 事件'，可在 SQLite 中实现简化版 PPR：以命中记忆为种子，沿 related_memory_ids 链接做 2 跳 BFS，按距离衰减加权，无需完整图算法；(3) 不建议引入 HippoRAG 的完整 KG 构建管线，小汐的场景不需要通用知识索引；(4) HippoRAG2 的'新知识连接识别'可与 A-MEM 的 cross-reference 合并实施，一次 LLM 调用同时完成结构化 + 链接。

**来源**: https://arxiv.org/abs/2405.14831 | https://github.com/osu-nlp-group/hipporag | https://icml.cc/virtual/2025/poster/45585

## MEM1 Constant Memory Budget 与主动整合范式

MEM1（arXiv:2506.15841, 2025-06）用端到端 RL 训练 agent 在 constant memory budget 下完成长程任务，核心是强制 agent 学会'主动整合而非被动堆积'。与传统 Generative Agents 的无限 memory stream 不同，MEM1 设定固定大小的记忆槽，agent 必须决定什么留、什么丢、什么合并，RL reward 来自任务成功率。这解决了 Generative Agents 随时间增长的上下文膨胀问题。同期 MARS（arXiv:2503.19271）和 RMM（ACL 2025）分别从 reflective self-improvement 和 prospective/retrospective reflection 两个角度改进记忆效率。这些工作共同指向一个趋势：2025 后的记忆研究从'存更多'转向'存得更精'。

**对小汐的启示**: 对小汐战略级启示：(1) 小汐当前记忆上限不明确，dreaming-lite 蒸馏≤500字是好的起点，但缺少'硬容量约束下的最优选择'机制。建议设定 core memory 硬上限（如 200 条或 50KB），超限时触发 forced consolidation 而非简单 FIFO 淘汰；(2) MEM1 的 RL 训练对单人项目不可行（需要大量 rollout），但其'constant memory'原则可通过规则近似：importance×recency×access_frequency 综合评分最低的 N 条自动降级；(3) RMM 的 prospective reflection（预判未来可能需要什么）可融入小汐的早安/晚安任务：在生成早安消息前，先做一次'今日可能用到的记忆'预检索，预热 context；(4) 不要把 MEM1 当作可部署系统，它是理论验证，但其设计哲学直接指导小汐的记忆预算管理。

**来源**: https://arxiv.org/html/2506.15841v2 | https://arxiv.org/html/2503.19271v1 | https://aclanthology.org/2025.acl-long.413.pdf

## ACL 2026 记忆综述与 Episodic/Semantic/Procedural 三分法

ACL Findings 2026 综述（arXiv:2605.06716）和配套 survey（arXiv:2603.07670）提出统一的记忆演化框架：Storage → Management → Experience 三阶段，以及 episodic/semantic/procedural 三分法。Episodic = 具体事件流（what happened），Semantic = 抽象事实/画像（what is true），Procedural = 技能/习惯（how to do）。现有系统大多只做好 episodic，semantic 靠粗粒度 summary，procedural 几乎空白。综述还指出 2025-2026 的关键缺口：(1) 缺乏真实世界长期评估；(2) 记忆更新的一致性保证不足；(3) 隐私/遗忘权在架构层面未被考虑。配套 paper list（FeishuLuo/Evolving-LLM-Agent-Memory-Survey）收录 200+ 篇相关工作。

**对小汐的启示**: 对小汐的定位校准：(1) 小汐的 active/core 记忆主要是 episodic，dreaming-lite 蒸馏的画像是 semantic 雏形，procedural 记忆（如'冷冷喜欢什么样的安慰方式'）目前隐含在 PERSONA_ANCHOR 中但未显式建模。建议新增 procedural_memory 表，记录交互模式偏好（pattern/preference/evidence_count/last_validated），由 dreaming-lite 周期性从 episodic 中归纳；(2) 综述指出的'更新一致性'问题在小汐 SQLite 中天然有事务保证，这是相对 Mem0/Zep 分布式架构的隐藏优势；(3) 隐私/遗忘权对小汐意义特殊：作为单人陪伴 agent，'被遗忘的权利'应由主人显式控制，可在 UI 上加'忘记这件事'按钮，触发 hard delete + cascade supersession；(4) 不要试图实现综述中的所有分类，先做好 episodic+semantic 两层的清晰分离。

**来源**: https://arxiv.org/abs/2605.06716 | https://arxiv.org/abs/2603.07670 | https://github.com/FeishuLuo/Evolving-LLM-Agent-Memory-Survey

## Generative Agents Reflection 原始机制与衰减公式

Generative Agents（Park et al., 2023, arXiv:2304.03442）的 reflection 机制是当代 agent 记忆的奠基之作。检索评分公式：score = α_recency × recency + α_importance × importance + α_relevance × relevance。Recency 用指数衰减 γ=0.995^(hours_since_access)；Importance 由 LLM 在记忆创建时打 1-10 分；Relevance 用 embedding cosine similarity。Reflection 触发条件：最近事件的 importance 累加和超过阈值，而非固定时间间隔。生成的 reflection 作为新记忆对象回存，带自己的 importance 分，支持递归抽象。消融实验证明去掉 reflection 后行为可信度显著下降。该架构的已知缺陷：记忆无限增长、relevance 依赖 embedding 质量、reflection 内容易重复。

**对小汐的启示**: 对小汐的直接对照：(1) 小汐的六因子召回（时间衰减×访问热度×话题命中×importance×情绪亲和度×新鲜度）已经是 Generative Agents 三因子的超集，且加入了情绪维度，更适合陪伴场景；(2) 小汐缺的是 reflection 的'阈值触发'机制——当前 dreaming-lite 是固定周期，建议叠加 importance 累加触发：当近期对话的重要性总和超过阈值时，提前触发一次 micro-reflection，不必等到下一个周期；(3) Generative Agents 的 γ=0.995 衰减系数可作为小汐时间衰减参数的参考锚点（换算为日衰减约 0.88），用于校准当前手工调参；(4) 小汐已规避了'无限增长'缺陷（分层衰减+dreaming 蒸馏），这是相对原始 Generative Agents 的进步。

**来源**: https://arxiv.org/abs/2304.03442 | https://memx.app/glossary/generative-agents/ | https://dl.acm.org/doi/fullHtml/10.1145/3586183.3606763

## Benchmark 争议与 LoCoMo/LongMemEval 局限性

Benchmark 争议是 2025-2026 记忆领域的最大噪音源。LoCoMo（Snap Research, ACL 2024）和 LongMemEval 是两大主流基准，但均存在严重局限：(1) 合成对话为主，与真实陪伴场景分布偏移大；(2) 偏事实召回，忽略情感/程序性记忆；(3) 已饱和，2026 年中 top 系统 LoCoMo>92%、LongMemEval>94%；(4) 厂商互撕导致数字不可信（Zep 84%→75%/58%；Mem0 93.4%→73.8% 独立复现）。社区回应：LongMemEval-V2（2026-05）增加动态增量评估；RealMem（ACL Findings 2026）用真实对话证明现有系统在 real-world 上远不如 benchmark 表现；LocoMo-Plus（2026-02）补充 beyond-factual 认知维度。Letta 2025-08 博客直言 'current memory benchmarks may not be very meaningful'，filesystem-only 方案就能打到高分。

**对小汐的启示**: 对小汐的方法论警告：(1) 绝不要用 LoCoMo/LongMemEval 分数衡量小汐的记忆质量，它们测的不是陪伴 agent 需要的能力；(2) 小汐应建立自己的评估体系：基于 20 组探针访谈式评测（已有），扩展为 longitudinal probe（跟踪同一话题跨周的一致性）、emotional congruence probe（情绪联动是否准确）、procedural probe（偏好学习是否正确）；(3) 可以参考 RealMem 的真实对话评估方法论，但不能直接用其数据集（英文+客服场景）；(4) benchmark 饱和的事实说明：对小汐千条级中文记忆而言，'追上 SOTA'是个伪目标，'让冷冷觉得她记得对'才是真指标。

**来源**: https://github.com/getzep/zep-papers/issues/5 | https://arxiv.org/html/2605.12493v1 | https://aclanthology.org/2026.findings-acl.703.pdf | https://www.letta.com/blog/benchmarking-ai-agent-memory/

## 千条级中文记忆向量检索的真实收益评估

千条级中文记忆是否需要向量检索？2025-2026 的实践共识趋于否定。多个来源（Medium 'When to Ditch Your Vector DB for Simple BM25'、Alex Garcia sqlite-vec hybrid search 博客、SitePoint local-first RAG）确认：<10k 文档时 BM25（FTS5）在延迟（<1ms vs 10-100ms+embedding）和精确匹配上优于纯向量检索。中文特殊性：BM25 依赖分词（jieba/pkuseg），配置不当会劣于向量；但若分词正确，精确实体匹配（人名/地名/专有名词）远胜向量。Hybrid（FTS5 + sqlite-vec + RRF）是推荐折中，但对千条级往往 FTS5 alone 就够。sqlite-vec 和阿里 Zvec（2026 开源）使 SQLite 内向量搜索成为可能，但仅在语义模糊查询（如'那次让我难过的事'）时有增益。C-Pack/BGE 系列是中文 embedding 首选。

**对小汐的启示**: 对小汐的关键决策点：(1) 当前纯 SQLite 六因子召回不含向量，在千条级中文场景下大概率已经是最优解，不要因为'别人都用向量'而引入；(2) 如果确实遇到语义召回瓶颈（如'那次在海边的事'无法命中含'沙滩''潮汐'的记忆），再加 sqlite-vec 作为 fallback，不要替换 FTS5；(3) 实施路径：先用 jieba 配好 FTS5 中文分词（如果还没做），这比加向量的 ROI 高得多；(4) 若未来记忆增长到万条级以上，再评估 sqlite-vec hybrid，届时 embedding 生成成本也更低；(5) 绝不引入独立向量数据库（Pinecone/Qdrant/Weaviate），违背单进程自托管原则。

**来源**: https://alexgarcia.xyz/blog/2024/sqlite-vec-hybrid-search/index.html | https://medium.com/@ThinkingLoop/when-to-ditch-your-vector-db-for-simple-bm25-b4f044f1076b | https://www.sitepoint.com/local-first-rag-vector-search-in-sqlite-with-hamming-distance/ | https://www.researchgate.net/publication/382190819_C-Pack_Packed_Resources_For_General_Chinese_Embeddings

## Filesystem-as-Memory 与 Markdown+SQLite 轻量范式

Filesystem-as-memory 在 2025-2026 成为轻量级 agent 记忆的强势选项。arXiv:2607.26637（2026-07）正式研究了 LLM agent 用 markdown 目录树作为长期记忆的模式；voxos.ai 报告 >60k 项目用纯文本文件做 coding agent 记忆；Towards Data Science 的 memweave 项目展示 Markdown+SQLite 零向量库方案。Letta 的 MemFS 在 LoCoMo 上用文件系统操作胜过专用记忆工具。这些工作的共同论点是：LLM 本身就是最好的索引引擎，给它结构化文本比给它向量相似度分数更符合认知直觉。Oracle 2026-02 博客提醒：filesystem 的并发写入风险在多 agent 场景需注意，但单用户无此问题。

**对小汐的启示**: 对小汐的架构验证：(1) 小汐的 SQLite 方案本质上是'结构化文件系统'的数据库等价物，比纯 markdown 更有事务保证和查询灵活性，方向正确；(2) 可借鉴 memweave 的'auditable as code'理念：让小汐的记忆变更可 diff、可 review，例如每次 dreaming-lite 输出一份 changelog markdown 到 logs/ 目录，方便主人审计；(3) 不建议从 SQLite 退回到纯文件系统，但可以在 SQLite 之上暴露一个'虚拟文件视图'（如 /memory/core/*.md），让人类侧也能用文本编辑器浏览；(4) filesystem-as-memory 的成功再次确认：对小汐规模，记忆质量的瓶颈不在检索算法而在'记忆内容的结构化程度'，应继续投资 dreaming-lite 的整合质量而非检索索引。

**来源**: https://arxiv.org/abs/2607.26637 | https://towardsdatascience.com/memweave-zero-infra-ai-agent-memory-with-markdown-and-sqlite-no-vector-database-required/ | https://voxos.ai/blog/how-to-give-ai-coding-agents-long-term-m/index.html | https://blogs.oracle.com/developers/comparing-file-systems-and-databases-for-effective-ai-agent-memory-management

## 建议

- 【P0 - 立即实施】Bi-temporal 最小化：在 memories 表加 valid_from/valid_to/ingested_at 三列，实现矛盾事实的时序标记而非删除。零依赖、半天工作量、直接提升记忆一致性。理由：Zep/Graphiti 验证了该模型的有效性，小汐已有 superseded 链是天然基础。
- 【P0 - 立即实施】Sleep-time compute 拆分：把 dreaming-lite 从请求路径移到独立后台任务，利用凌晨空闲时段运行 consolidation agent，输出 memory block patch。理由：Letta 实证 5× test-time compute 节省，小汐已有 heartbeat 基础设施。
- 【P1 - 本月内】Core memory 结构化升级：对 importance≥7 的记忆引入 A-MEM 式字段（tags/related_ids/insight_summary），写入时由后台 LLM 生成。理由：低成本提升联想召回质量，无需向量库。
- 【P1 - 本月内】FTS5 中文分词校准：确认 jieba/pkuseg 分词器正确配置，跑一组中文精确匹配测试。理由：千条级中文场景下 BM25 ROI 远高于向量检索，是分词问题而非索引问题。
- 【P2 - 下季度】Procedural memory 显式建模：新建 procedural_memory 表记录交互模式偏好，由 dreaming-lite 从 episodic 归纳。理由：填补综述指出的 procedural 空白，提升'懂主人'的体感。
- 【P2 - 下季度】Core memory 硬容量约束 + forced consolidation：设定上限（如 200 条），超限时按综合评分降级最低条目。理由：MEM1 证明 constant memory 优于无限增长，防止核心记忆稀释。
- 【P3 - 观望】暂不引入向量检索：除非 FTS5 分词校准后仍证实语义召回瓶颈，否则不加 sqlite-vec。理由：千条级中文场景 BM25 占优，向量是负 ROI。
- 【P3 - 观望】暂不引入图数据库/外部记忆服务：Neo4j/FalkorDB/Mem0 Cloud/Zep Cloud 均不适合单用户自托管。理由：违背单进程原则，运维成本远超收益。
- 【持续】自建评估体系扩展：在 20 组探针基础上增加 longitudinal/emotional/procedural 三类 probe，不用 LoCoMo/LongMemEval 数字作为优化目标。理由：benchmark 争议证明外部数字不可信，陪伴 agent 的质量只能由主人感知定义。

## 警示

- arXiv 直连被网络策略阻止，论文细节通过 HTML 镜像、博客摘要与搜索结果交叉验证；具体章节数字可能因版本差异略有出入
- 厂商自报 benchmark（Mem0 93.4%、Zep 84%）已被独立复现大幅缩水，本报告区分了'厂商宣称'与'第三方复现'两套数字
- 小汐当前记忆规模约千条级，以下结论对该量级成立；若未来增长到十万条以上，向量检索的收益曲线会变化
- LoCoMo/LongMemEval 均为合成对话 benchmark，与小汐真实单人陪伴场景有显著分布偏移，benchmark 分数不等于体感质量

