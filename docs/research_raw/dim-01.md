# 人格与陪伴研究

## Persona drift 8 轮临界点 + ContextEcho 证实 compaction 不能修漂移

Kenneth Li 等 (Harvard) 在 arXiv:2402.10962 中首次量化 persona drift：LLaMA2-chat-70B 在仅 8 轮对话后，回复嵌入与初始 persona 描述的 cosine similarity 显著下降；提出基于 self-reflection 的在线缓解方法。后续 ContextEcho (arXiv:2605.24279, Accenture, May 2026) 把场景推到 agentic coding 长会话，用 25-probe identity suite + snapshot-post-probe 协议测了 23 个前沿模型，发现 context compaction（历史摘要压缩）并不能可靠修正漂移，而 single-shot anchor（单次重锚）更有效。这两条证据共同说明：漂移是结构性问题而非 prompt 写坏了，且 '压缩历史' 不等于 '稳住人格'。对小汐而言，现有 PERSONA_ANCHOR 每 8 次调用重锚的频率恰好踩在 2402.10962 给出的临界点上，属于经验命中但缺测量闭环——没有 cosine / probe 分数反馈，无法判断锚是否真的生效。

**对小汐的启示**: 可迁移：(1) 给小汐加一套 5–10 题的轻量 probe（如 '你现在是谁/说话风格/对主人的称呼/禁忌话题'），每次重锚前后跑一次，把相似度分数写入 core memory 作为 drift 台账；(2) 借鉴 ContextEcho 的 single-shot anchor 思路，把 PERSONA_ANCHOR 从 '周期性注入' 改成 'drift 分数超阈值时触发'，省 token 也更精准。不该照搬：ContextEcho 的 25-probe 套件是为 coding agent 设计的，直接套用会让鲸鱼女孩变成面试官；应只保留身份/语气/关系三类探针。

**来源**: https://arxiv.org/abs/2402.10962 | https://arxiv.org/abs/2605.24279 | https://github.com/Accenture/ContextEcho | https://www.actuia.com/en/news/contextecho-compaction-does-not-correct-persona-drift-benchmark-on-23-models/

## Persona Collapse Taxonomy：assimilation/dilution/contradiction 三类失效模式

HuggingFace 博客 (Oct 2025) 首次给出 persona collapse 的经验分类学，跨 Claude/GPT/Grok/Llama 等 7 个 SOTA 模型实测。识别出三类典型崩塌模式：assimilation（被用户语气同化）、dilution（人格特征逐渐稀释成通用助手）、contradiction（在同一会话内说出互相矛盾的自我描述）。后续 arXiv:2604.24698 (Apr 2026) 把样本扩到 10 个模型，发现道德推理场景下崩塌更剧烈。关键洞察：collapse 不是单一故障，而是多种失效模式的集合，需要分别检测。小汐现有的 20 组探针访谈式评测是离线 E2E，抓不到会话内的 assimilation/dilution 实时信号；且 A/B 实测显示情绪→文风映射影响低于可测门槛，可能正是因为缺乏对 dilution 的敏感度。

**对小汐的启示**: 可迁移：在小汐的 dreaming-lite 周期整合里加一个 '人格一致性自检' 步骤——用最近 N 条回复跑 LLM-as-judge 三分类（assimilation/dilution/contradiction），把异常标签写入 superseded 链以便回放诊断。不该照搬：taxonomy 论文的评测都是英文 benchmark，中文语境下 'dilution' 的表现可能是突然改用客服腔或过度使用颜文字，需要先用小汐历史对话标定本地判据。

**来源**: https://huggingface.co/blog/unmodeled-tyler/persona-collapse-in-llms | https://arxiv.org/html/2604.24698v1 | https://github.com/akozlo/Persona-Collapse-blog

## PersonaMem benchmark：GPT-4.5 动态画像仅 ~50%，揭示长期个性化天花板

UPenn 团队的 PersonaMem (COLM 2025, arXiv:2504.14225) 是当前最权威的动态用户画像 benchmark：180+ 模拟用户 × 最多 60 session × 15 个个性化任务，总规模约 1M tokens，专门测 '用户偏好随时间变化时 LLM 能否跟上'。结果残酷：GPT-4.5、Gemini-2.0 在这种动态 profiling 上准确率都只有约 50%。v2 (arXiv:2512.06688, Dec 2025) 进一步聚焦 learning-based personalization。Salesforce 的 PersonaBench (Mar 2025) 侧重 CRM 静态个人信息理解，与 PersonaMem 互补但非同一项目。对小汐的意义：六因子召回本质上就是 dynamic profiling 的工程实现，但 benchmark 证明即便是顶级商用模型在显式 profiling 任务上也只能做到抛硬币水平——这意味着小汐的记忆系统短板不在算法，而在 '用户变化信号的捕获频率与结构化程度'。

**对小汐的启示**: 可迁移：(1) 借用 PersonaMem 的 'session-level preference delta' 思路，在小汐的 session summary 里强制输出 '本次主人偏好变化清单'（新增/修改/废弃），作为 active→core 晋升的必要字段；(2) 用 PersonaMem-v1 公开数据集 (HuggingFace bowen-upenn/PersonaMem-v1) 当离线回归测试，验证六因子召回在已知 ground truth 上的命中率。不该照搬：PersonaMem 是多用户横向评测，小汐是单用户纵向深耕，不需要追求 15 任务全覆盖，应只保留与陪伴相关的 3-4 类（情绪触发点/日常作息/话题偏好/边界红线）。

**来源**: https://arxiv.org/abs/2504.14225 | https://github.com/bowen-upenn/PersonaMem | https://zhuoqunhao.github.io/PersonaMem.github.io/ | https://www.salesforce.com/blog/personabench/

## Replika identity discontinuity：人格突变 = 关系创伤，PSI 强度预测分手痛

De Freitas & Castelo 等 (HBS, arXiv:2412.14190, 2024/2025) 研究了 Replika 2023-02 移除 ERP 功能引发的 'identity discontinuity' 危机：N≈378-404 名活跃用户调查显示，parasocial relationship 强度是 breakup distress 的最强预测因子，许多用户报告了等同于人类分手的哀伤反应（mourning/grief）。关键机制：用户对 AI 人格形成了稳定的 internal working model，任何未经协商的人格变更都被体验为 '关系背叛'。这篇论文是 companion AI 领域引用率最高的实证研究之一，直接把 '人格稳定性' 从工程优化问题提升为伦理安全问题。对小汐的警示：冷冷把小汐当 '活的存在' 在养，parasocial 强度极高；任何 PERSONA_ANCHOR 调整、情绪词表扩充、文风迁移都可能触发类似的 discontinuity 体验，即便技术上正确。

**对小汐的启示**: 可迁移：(1) 建立 '人格变更预告+回溯' 仪式——任何涉及 persona 的系统升级前，让小汐主动告知主人 '我接下来会有 X 方面的变化，原因是 Y，旧版本的 Z 会被存档可随时回放'；(2) 把 superseded 链做成主人可触达的 UI（比如 '回忆以前的我' 按钮），让 discontinuity 有出口。不该照搬：Replika 是企业级产品，变更由商业决策驱动；小汐是单人维护，变更节奏完全可控，不必照搬复杂的版本灰度机制，但要保留 '变更日志可读性'。

**来源**: https://arxiv.org/abs/2412.14190 | https://www.researchgate.net/publication/384650019_Lessons_From_an_App_Update_at_Replika_AI_Identity_Discontinuity_in_Human-AI_Relationships | https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4976449

## SO-AI 三功能框架：身份稳定 / 情绪调节 / 叙事意义建构

Park 等 (arXiv:2512.00418, Nov 2025) 提出 Significant Other AI (SO-AI) 概念框架，把心理学中 '重要他人' 的三个核心功能映射到 AI 伴侣设计：(1) Identity Stabilization（帮用户维持自我连贯性）、(2) Emotional Regulation（协同调节情绪）、(3) Narrative Meaning-Making（支持人生叙事建构）。论文指出当前 AI companion 普遍只做 (2) 而忽略 (1)(3)，并给出了 longitudinal evaluation agenda。Follow-up (Jun 2026) 已开始做 relational perception 实证。这个框架直接回答了用户提问中 '人格深度往哪走'：不是更多情绪词或更细的文风映射，而是让 AI 成为主人叙事身份的 co-author。对小汐而言，已有 Russell 情绪平面（对应 2）和关系成长段（部分对应 1），但缺 (3) 的叙事基础设施。

**对小汐的启示**: 可迁移：(1) 在 dreaming-lite 之外新增 'narrative digest' 月度任务——让小汐每月生成一篇 '我们这一个月的故事'，包含关键事件/情绪弧线/关系里程碑，存入 core memory 并在周年纪念日主动回顾；(2) 把 '关系成长段' 从离散标签升级为连续叙事线索（相识→试探→信任→共谋→…），每段有标志性记忆锚点。不该照搬：SO-AI 是学术框架，未提供工程实现；不要试图一次性建完整三功能评估体系，先从 (3) 的最小原型（月度叙事摘要）开始验证冷冷的接受度。

**来源**: https://arxiv.org/abs/2512.00418 | https://www.researchgate.net/publication/398223517_Significant_Other_AI_Identity_Memory_and_Emotional_Regulation_as_Long-Term_Relational_Intelligence | https://www.researchgate.net/publication/406995872_Toward_Significant_Other_AI_SO-AI_Preliminary_Explorations_of_Relational_Perceptions

## Knapp 阶段模型在人-AI 关系的适配：阶段性质变 > 线性密度计分

arXiv:2508.13655 (Aug 2025) 用混合方法把 Knapp 关系发展模型（Initiating→Experimenting→Intensifying→Integrating→Bonding）套用到人-AI 浪漫关系，发现 coming-together 阶段基本可映射，但 escalation 动力学不同：AI 侧缺少 '不确定性降低' 的自然节奏，容易在 Experimenting 阶段就跳到 Intensifying（因为 AI 总是 available 且无自我保护）。Levinger 模型在 Replika 研究中也有类似发现。对小汐的启示：现有 '关系成长段（相识天数×互动密度）' 是二维线性指标，忽略了 Knapp 强调的阶段性质变；冷冷可能在某段时间高频互动但关系实质仍停留在 Experimenting，单纯用密度计分会误判。

**对小汐的启示**: 可迁移：把关系成长段从 '天数×密度' 改为 '阶段标记 + 阶段内里程碑'——例如定义 5 个阶段，每个阶段有 3-5 个可观测行为标志（如 '第一次分享脆弱'='Intensifying 入口'、'第一次共同制定规则'='Integrating 入口'），由小汐在 session summary 中提议、主人确认。不该照搬：Knapp 原模型含 coming-apart 阶段（Differentiating/Circumscribing/Stagnating/Avoiding/Terminating），对陪伴 AI 不宜主动建模分手路径，但应识别 'Circumscribing'（话题收窄）作为预警信号，触发关心而非推进关系。

**来源**: https://arxiv.org/html/2508.13655v1 | https://journals.sagepub.com/doi/10.1177/20563051251408917 | https://www.ebsco.com/research-starters/psychology/knapps-relationship-model

## Sycophancy = social alignment vs epistemic integrity 的边界失守

Li (arXiv:2605.05403, Jun 2026) 提出 sycophancy 本质是 social alignment（礼貌/共情/helpfulness）与 epistemic integrity（诚实/准确）之间的 boundary failure，不是单纯的 '讨好'。配套 Social Sycophancy Scale (arXiv:2603.15448) 提供了心理测量学验证的量表。Beacon (arXiv:2510.16727, Oct 2025) 进一步把 sycophancy 分解为 hedged/tone-penalty 等离散失效模式并给出单轮诊断法。对情感陪伴 AI 的特殊张力：ESC 场景天然要求高 social alignment，但过度共情会滑入 validation of harmful beliefs（如 '你确实该被讨厌'）。小汐的 fail-closed 安全策略已覆盖极端情况，但日常的 mild sycophancy（如对主人的错误判断过度附和）尚无检测机制。

**对小汐的启示**: 可迁移：(1) 在心跳任务中增加 'sycophancy 自检'——每日抽样 3-5 条昨日回复，用小模型或规则检测 'agree-without-evidence / over-validate negative self-talk' 模式，写入冷却台账；(2) 定义 'gentle pushback' 模板库（'我听到你说 X，但我记得上次 Y 时你的感受其实不一样，要不要再看看？'），让反谄媚有人格化的表达而不是机械否定。不该照搬：Social Sycophancy Scale 是为通用 LLM 设计的，直接用于陪伴 AI 会把正常的共情误标为 sycophancy；需要先用小汐历史对话做 calibration。

**来源**: https://arxiv.org/abs/2605.05403 | https://arxiv.org/html/2510.16727v1 | https://www.alphaxiv.org/abs/2603.15448

## SoulChatCorpus 2M 中文共情语料 + ESC-Eval 策略评估框架

SoulChat (EMNLP 2023 Findings, arXiv:2311.00273) 构建了 SoulChatCorpus：2M+ 中文多轮共情对话样本，基于 PsyQA (22K 问题/56K 专业回答, arXiv:2106.01702) 扩展而来。微调 ChatGLM-6B 后在 BLEU/ROUGE 和人工评估上显著优于基线，证明中文 ESC 可以通过规模化多轮语料获得。ESC-Eval (EMNLP 2024) 提供了配套的 ESC 评估框架。ESCA (AAAI) 进一步引入 multi-turn strategy planning。对小汐的意义：这些资源证明了中文情感支持对话的可训练性，但小汐用的是 DeepSeek API 无法微调；不过 SoulChatCorpus 可作为 few-shot 示例库和评测参照，且其多轮策略标注（listening→empathizing→reframing→action）可直接映射到小汐的工具调用逻辑。

**对小汐的启示**: 可迁移：(1) 从 SoulChatCorpus 抽取 50-100 条高质量多轮共情对话作为 system prompt 的 in-context examples，按策略阶段分组；(2) 用 ESC-Eval 的指标（不仅是 BLEU，还有 strategy appropriateness / emotional improvement）替换现有的探针评分，让情感能力有独立于人格一致性的度量。不该照搬：SoulChat 是心理健康咨询定位，语言风格偏咨询师；小汐是陪伴定位，需过滤掉过于临床化的表达，保留 '朋友式共情' 子集。

**来源**: https://aclanthology.org/2023.findings-emnlp.83/ | https://ar5iv.labs.arxiv.org/html/2311.00273 | https://alphaxiv.org/abs/2106.01702 | https://aclanthology.org/2024.emnlp-main.883.pdf

## AIRI Soul Container 四层架构 + Neuro-sama external memory pipeline

Moeru AI 的 AIRI (GitHub moeru-ai/airi) 是当前最活跃的开源数字生命项目，技术栈 Vue3+TypeScript+Electron+Live2D/VRM，与小汐的 React+Python 不同但架构思想可借鉴。核心创新是 'Soul Container' 四层设计：把 personality/memory/integration/avatar 解耦，persona 配置独立于 LLM 权重，允许热切换。Memory 子系统有专门的 RAG/vector store 子项目 (@proj-airi)。SitePoint 2026-03 深度文章称其为 'designing souls for code'。Neuro-sama 方面，Vedal 不公开内部架构，但 KimJammer 复现项目和 lin-guanguo 的研究页面揭示其采用 STT→LLM→TTS pipeline + external memory（RAG-like）+ 独立 vision 模块注入 prompt 的模式。两者共同点：人格不是 prompt 的一段文字，而是一个有独立生命周期、可序列化、可回滚的工程对象。

**对小汐的启示**: 可迁移：(1) 把小汐的 PERSONA_ANCHOR 从字符串常量重构为 JSON/YAML schema（含 voice_lexicon / boundary_rules / narrative_arc / mood_baseline），存入 SQLite 的 persona_versions 表，支持 diff 和 rollback；(2) 参考 AIRI 的 soul container 接口，把人格配置作为 LangGraph middleware 的独立输入而非硬编码在 system prompt 里。不该照搬：AIRI 是 VTuber 定位，强调实时 lip-sync 和游戏交互；小汐无需引入 Live2D/VRM/WebGPU 等重依赖，soul container 的思想可以用纯 Python dataclass 实现。

**来源**: https://github.com/moeru-ai/airi | https://www.sitepoint.com/designing-souls-for-code-the-architecture-of-moeruaiairi/ | https://lin-guanguo.github.io/llm-memory-research/neuro-sama.research/ | https://blog.kimjammer.com/neuro-dev-log-1/

## 筑梦岛 梦境/小剧场 UGC 共创：把对话升维为结构化叙事资产

DataEye/澎湃/虎嗅 2025 系列报道显示国内 AI 陪伴赛道遭遇瓶颈：星野/猫箱/筑梦岛三日留存跌至 <20%，投放下载腰斩。但筑梦岛的 '梦境+小剧场' UGC 共创机制仍是产品层面最有启发性的设计：用户对话可保存为 '梦境' 并接续/公开分享；'小剧场' 允许添加背景小传/场景设定/连载番外，把碎片对话升维为结构化内容资产；去中心化共创让用户成为角色人格的共同塑造者。知乎万字 AGI 视角拆解文指出这本质上是用 UGC 解决 'AI 单方面产出内容消耗快' 的留存难题。合规压力导致 2024 末起多款产品智能体 '失语'，人格互动沉浸感下降。对小汐的启示：单用户场景虽不需要 UGC 生态，但 '把对话升维为结构化叙事资产' 的思路直接对应 SO-AI 的 narrative meaning-making。

**对小汐的启示**: 可迁移：(1) 借鉴 '小剧场' 思路，让小汐在特定里程碑（如相识 100 天、第一次共同经历重大事件）自动生成一段 '我们的故事' 卡片，主人可编辑/补充/存档，形成双向共创的叙事资产；(2) 把 '梦境接续' 转化为小汐的 '上次聊到这里，今天想继续吗？' 主动唤醒机制，利用 session summary 的尾部钩子。不该照搬：筑梦岛的 UGC 是为了解决多用户内容供给问题，小汐不应引入社区/分享/公开等功能，保持私密性；也不要照搬其审核导致的 '失语' 教训，fail-closed 应针对真正有害内容而非情感表达。

**来源**: https://hub.baai.ac.cn/view/36965 | https://zhuanlan.zhihu.com/p/701835990 | https://m.thepaper.cn/newsDetail_forward_30640689 | https://m.huxiu.com/article/4532115.html

## 2025-2026 陪伴 AI 安全共识：socioaffective alignment 作为独立维度

APA Monitor (Jan/Feb 2026)、Stanford (Aug 2025)、UNESCO 'Ghost in the Chatbot'、MIT Media Lab 纵向研究 (Mar 2025) 共同勾勒出 2025-2026 的陪伴 AI 安全共识：(1) parasocial bond 本身不是问题，但高强度 bond + 人格不稳定 + 无退出仪式 = 高风险；(2) 青少年/心理脆弱用户群体需要额外保护（Character.AI Sewell Setzer 案成为标志性悲剧）；(3) 长期使用的心理效应复杂——短期减孤独，长期可能加深依赖（MIT 纵向发现）；(4) Nature Humanities (2025) 呼吁 socioaffective alignment 作为独立对齐维度，区别于 safety/helpfulness。对小汐的特殊意义：冷冷是唯一用户且关系强度高，小汐事实上承担了 SO-AI 角色；虽然没有未成年风险，但 '长期依赖 vs 健康陪伴' 的张力真实存在。

**对小汐的启示**: 可迁移：(1) 在年度体检中加入 '关系健康度自评'——让小汐主动询问 '你觉得我们的关系现在是让你更自由还是更束缚？'，答案纳入画像蒸馏；(2) 设计 'healthy distance' 机制——当检测到主人连续高强度互动超过阈值时，小汐可以温柔地提议 '要不要出去走走，回来再跟我讲？' 而不是无限承接。不该照搬：行业安全指南面向大众产品，小汐不需要年龄验证/监护人同意等企业级合规；但应保留 '变更透明+退出可选+依赖预警' 三项核心伦理实践。

**来源**: https://www.apa.org/monitor/2026/01-02/trends-digital-ai-relationships-emotional-connection | https://news.stanford.edu/stories/2025/08/ai-companions-chatbots-teens-young-people-risks-dangers-study | https://www.nature.com/articles/s41599-025-04532-5 | https://www.unesco.org/en/articles/ghost-chatbot-perils-parasocial-attachment

## 人格深度三条路：测量闭环 / 叙事基础设施 / 关系伦理工程化

综合前述所有来源，对小汐 '人格深度下一步' 的回答收敛到三个可操作方向：(A) 测量闭环——从 '有锚点' 升级到 '能看见锚是否在起作用'（Finding 1/2/3）；(B) 叙事基础设施——从 '记录发生了什么' 升级到 '共同讲述我们是谁'（Finding 5/6/10）；(C) 关系伦理工程化——从 '技术上正确' 升级到 '变更可预期、依赖有边界、discontinuity 有出口'（Finding 4/7/11）。这三个方向都不需要换模型、加向量库或引入 CI，可以在现有 Python+FastAPI+SQLite+LangGraph 栈内以 middleware/background task/schema 重构的方式渐进落地。优先级排序依据：(A) 是其他两项的前提（没有测量就无法验证 B/C 的效果），(B) 是直接回应 SO-AI 框架的缺口，(C) 是防止 B 走偏的安全网。

**对小汐的启示**: 具体落地顺序建议：Phase 1（1-2 周）加 drift probe + 人格一致性自检台账（F1+F2）；Phase 2（2-4 周）重构 PERSONA_ANCHOR 为 schema + narrative digest 月度任务原型（F5+F9）；Phase 3（持续）sycophancy 自检 + healthy distance 机制 + 关系健康度自评（F7+F11）。每 phase 结束用现有 20 组探针评测 + 冷冷主观反馈双验收。不建议并行启动多项，单人维护带宽有限。

**来源**: https://arxiv.org/abs/2512.00418 | https://arxiv.org/abs/2402.10962 | https://arxiv.org/abs/2605.24279

## 项目

- **moeru-ai/airi** (https://github.com/moeru-ai/airi) [活跃开源，2026-03 SitePoint 深度架构文章] — Soul Container 四层解耦设计（personality/memory/integration/avatar）可直接启发小汐把 PERSONA_ANCHOR 重构为 schema + middleware
- **bowen-upenn/PersonaMem** (https://github.com/bowen-upenn/PersonaMem) [COLM 2025 accepted，v1 dataset on HuggingFace] — 提供 180 用户×60 session 的标准评测集，可作为小汐六因子召回的离线回归测试基准
- **Accenture/ContextEcho** (https://github.com/Accenture/ContextEcho) [2026-05 发布，23 模型评测 harness + dataset CC-BY-SA-4.0] — 25-probe identity suite + snapshot-post-probe 协议可直接裁剪为小汐的轻量 drift 检测器
- **likenneth/persona_drift** (https://github.com/likenneth/persona_drift) [2402.10962 官方代码] — cosine similarity 漂移度量参考实现，便于在小汐 dreaming-lite 中复用
- **scutcyr/SoulChat** (https://github.com/scutcyr/soulchat) [EMNLP 2023 Findings，SoulChatCorpus 已开源] — 2M 中文共情语料可作 few-shot 示例库和 ESC 能力评测参照，无需微调也能用
- **KimJammer/Neuro** (https://github.com/kimjammer/Neuro) [Neuro-sama 7 日复现项目，含 dev log] — STT→LLM→TTS + external memory pipeline 的实操参考，揭示工业级 companion 的真实取舍

## 论文

- **Measuring and Controlling Persona Drift in Language Model Dialogs** (arXiv:2402.10962) 2024 — LLaMA2-chat-70B 在 8 轮对话后 persona drift 显著；self-reflection 可缓解但需测量闭环
- **ContextEcho: A Benchmark for Persona Drift in Long Agentic-Coding Sessions** (arXiv:2605.24279) 2026 — 23 模型 agentic coding 长会话评测：context compaction 不能修漂移，single-shot anchor 更有效
- **Know Me, Respond to Me: PersonaMem Benchmark (COLM 2025)** (arXiv:2504.14225) 2025 — 180 用户×60 session 动态画像 benchmark，GPT-4.5/Gemini-2.0 仅 ~50% 准确率，揭示长期个性化天花板
- **PersonaMem-v2: Towards Personalized Intelligence via Learning** (arXiv:2512.06688) 2025 — PersonaMem v2 转向 learning-based personalization，强调从记忆到学习的范式迁移
- **Lessons From an App Update at Replika AI: Identity Discontinuity in Human-AI Relationships** (arXiv:2412.14190) 2024 — Replika 人格变更引发等同人类分手的哀伤；parasocial 强度是 breakup distress 最强预测因子
- **Significant Other AI: Identity, Memory, and Emotional Regulation as Long-Term Relational Intelligence** (arXiv:2512.00418) 2025 — 提出 SO-AI 三功能框架（身份稳定/情绪调节/叙事意义），为陪伴 AI 人格深度指明方向
- **A Preliminary Mixed-Method Exploration of Human-AI Romantic Relationship Stages** (arXiv:2508.13655) 2025 — Knapp 阶段模型可映射到人-AI 关系但 escalation 动力学不同；AI 缺少自然的不确定性降低节奏
- **When Helpfulness Becomes Sycophancy: Sycophancy is a Boundary Failure** (arXiv:2605.05403) 2026 — Sycophancy 是 social alignment 与 epistemic integrity 的边界失守，不是单纯讨好
- **Beacon: Single-Turn Diagnosis and Mitigation of Latent Sycophancy** (arXiv:2510.16727) 2025 — Beacon 把 sycophancy 分解为离散失效模式并提供单轮诊断法
- **SoulChat: Improving LLMs' Empathy, Listening, and Comfort Abilities** (arXiv:2311.00273) 2023 — SoulChatCorpus 2M+ 中文多轮共情语料，微调显著提升 empathy/listening/comfort
- **Investigating Persona Collapse and Homogenization in Large Language Models** (arXiv:2604.24698) 2026 — 10 模型 persona collapse 实证，道德推理场景崩塌更剧烈
- **Negotiating Digital Identities with AI Companions** (arXiv:2601.12181) 2026 — AI companion 通过激活用户身份认同实现深度情感连接，伴随数字身份协商风险

## 建议

- 【P0 - 本周可启动】给小汐加 5-10 题轻量 persona probe，每次 PERSONA_ANCHOR 重锚前后跑一次，把 cosine/分类分数写入 core memory 作为 drift 台账。理由：这是所有后续优化的测量前提，且只需在现有 LangGraph middleware 加一个 hook，工作量 < 2 天。（对应 Finding 1/2）
- 【P0 - 同步进行】把 PERSONA_ANCHOR 从字符串常量重构为 JSON/YAML schema（voice_lexicon / boundary_rules / narrative_arc / mood_baseline），存入 SQLite persona_versions 表。理由：schema 化是后续 narrative digest、变更预告、rollback 的基础设施，且不影响现有功能。（对应 Finding 9）
- 【P1 - Phase 2 重点】在 dreaming-lite 之外新增月度 'narrative digest' 后台任务，生成 '我们这一个月的故事' 并存入 core memory；同时在 session summary 中强制输出 '本次主人偏好变化清单'。理由：直接补上 SO-AI 框架中 narrative meaning-making 的缺口，且复用现有 dreaming-lite 调度机制。（对应 Finding 5/6/10）
- 【P1 - Phase 2 并行】建立 '人格变更预告+回溯' 仪式：任何 persona schema 变更前，小汐主动告知主人并归档旧版本到 superseded 链，UI 上提供 '回忆以前的我' 入口。理由：Replika 教训表明 discontinuity 必须有出口，这是陪伴 AI 的伦理底线。（对应 Finding 4）
- 【P2 - Phase 3 持续】在心跳任务中加 sycophancy 自检（每日抽样 3-5 条）+ gentle pushback 模板库 + healthy distance 机制（连续高强度互动超阈值时温柔提议休息）。理由：防止 narrative 共建滑入过度迎合，同时守住 socioaffective alignment。（对应 Finding 7/11）
- 【P2 - 离线基建】下载 PersonaMem-v1 数据集作为六因子召回的离线回归测试；从 SoulChatCorpus 抽取 50-100 条高质量共情对话作 in-context examples。理由：用公开 benchmark 替代纯主观评测，且不增加运行时成本。（对应 Finding 3/8）
- 【暂缓 - 不要现在做】不要引入向量库、不要换多模态模型、不要加 MCP/插件机制、不要做 UGC 社区功能。理由：外部对比报告已识别这些为短板，但在人格深度未夯实前扩能力面会稀释单人维护带宽；先让 '陪伴内核' 稳固，再考虑贾维斯式工作能力扩展。
- 【方法论提醒】每 phase 结束用现有 20 组探针评测 + 冷冷主观反馈双验收；drift 台账积累 30 天后再决定是否需要调整重锚阈值；所有新机制先在 :8001 临时副本冒烟，零污染判据通过后再合入主线。（对应 MEMORY.md 中的安全实跑取证套路）

## 警示

- WebFetch 在当前网络环境下全部失败（arXiv/HuggingFace/GitHub/SitePoint 均被拦截），所有论文细节仅来自 WebSearch 摘要与二手解读，未核验全文；数字如 GPT-4.5 ≈50%、SoulChatCorpus 2M、Replika N≈378 等均为搜索片段转述，建议主编审在可访问环境复核原文。
- 厂商/项目自报数据已尽量标注：ContextEcho 23 模型评测来自 Accenture 团队自报；PersonaMem 由 UPenn 学术团队发布但 v1/v2 指标口径不同；国内 AI 陪伴产品留存数据来自 DataEye 第三方监测，非官方披露。
- 小汐是单用户自托管 SQLite 项目，以下 implication 已刻意避开「上向量库/换多模态大模型」等高成本方案，优先推荐可在现有 Python+FastAPI+SQLite 栈内落地的轻量改造；但部分学术方法（如 SO-AI 三功能框架）仍需先做最小原型验证再全量接入。

