# 评估：TencentDB Agent Memory 适不适合 Lirica

- 日期：2026-09-27
- 结论：**不适合接入，但值得抄两处思路。**
- 判定依据：官方文档 + 仓库资料（外部），以及实读 `E:\Lirica-android` 源码（内部）。

---

## 一、先给结论

| 问题 | 结论 |
|---|---|
| 能不能接进 Lirica | 不能，除非把 Lirica 从纯端侧改成"端侧 + 云端服务器"架构 |
| 值不值得接 | 不值。它解决的核心问题，Lirica 已经在端侧自建且更贴合场景 |
| 有没有可借鉴的地方 | 有，2 处：**L2 场景归纳层**、**结论→原文的可下钻溯源** |
| 那它该用在哪 | 用在你**自己的开发工作流**上（Lirica 仓库的 CodeGraph / Wiki / Skill），不是用在 Lirica 产品里 |

一句话：**这是一个给"多用户、多 Agent、企业级团队"设计的记忆中台；Lirica 是单用户、端侧、强调隐私的伴生体。方向不一致。**

---

## 二、TencentDB Agent Memory 是什么

腾讯云数据库团队 2026 年 5 月开源的 Agent 记忆引擎，MIT 协议，仓库 `TencentCloud/TencentDB-Agent-Memory`。

核心能力三层：

1. **短期记忆**：当前任务窗口的上下文管理 + 冷数据卸载 + 关键状态保留
2. **长期记忆**：L0 原始对话 → L1 原子事实 → L2 场景记忆 → L3 核心记忆 的四层金字塔
3. **团队记忆**：Memory Hub，四类资产（Chat Memory / Skill / Wiki / CodeGraph）+ Owner、版本、可见性、共享授权

检索：关键词（BM25）+ 向量语义 + RRF 融合排序。
底座：默认本地 SQLite + sqlite-vec 零依赖部署，可进阶接腾讯云向量数据库 TCVDB。
接入路径：OpenClaw 插件、Hermes Gateway（Docker）、HTTP v2 REST API + Python/TypeScript SDK。

官方给出的效果（腾讯实验室，2026 年 4 月）：

| 指标 | 提升 |
|---|---|
| PersonaMem 总准确率 | 47.85% → 76.10% |
| 事实召回率 | 29.63% → 79.07% |
| WideSearch Token 消耗 | 221.31M → 85.64M（−61.38%） |
| WideSearch 成功率 | 33% → 50% |

效果本身是实的。问题不在它好不好，在于它跟 Lirica 不是一个场景。

---

## 三、Lirica 现在有什么（实读源码）

| 能力 | 位置 | 说明 |
|---|---|---|
| 双轨 + 念头池 + 世界观轨协调 | `database/memory/DualTrackMemoryManager.kt:27` | 六张心智表的统一入口 |
| 混合检索 | `memory/retrieval/HybridMemoryRetriever.kt:46` | 向量召回 top-20 → 六维精排 → top-N |
| 六维打分 | `database/memory/MemoryScorer.kt` | 时间衰减 + 访问热度 + 最近使用 + 重要度 + 话题重叠 + **情绪亲和度** |
| 向量存储 | `database/memory/MemoryVectorStore.kt:14` | 512 维、暴力余弦、内存缓存 + Room 持久化 |
| 原子事实抽取 | `database/memory/MemoryExtractor.kt:23` | LLM 抽取 + 节流门控（>3 分钟或 ≥3 轮）+ 失败指数退避（60s·2^(n-1)，封顶 15 分钟） |
| 记忆实体 | `database/entity/UserMemoryEntity.kt:21` | confidence / importance / valence / arousal / status(active·superseded·archived·core) / supersededBy / prevContent / source / validFrom / validTo |
| 高层认知 | `database/entity/WorldviewEntity.kt` | 多情境反复验证的深层规律 + supportingEvidenceCount + confidence |
| 记忆巩固 | `memory/consolidation/SubconsciousDreamCoordinator.kt` 等 | 梦系统，含入梦后全量向量重算 |

---

## 四、逐层对照

| Agent Memory 层 | Lirica 对应 | 状态 |
|---|---|---|
| L0 原始对话 | 会话轨 `ChatDialogue` | 已有 |
| L1 原子事实 | `UserMemoryEntity` | 已有，**且比它更细**（情绪效价/唤醒度、双时间戳时序、矛盾覆盖链） |
| L2 场景记忆 | 无 | **缺失 · 唯一真空缺** |
| L3 核心记忆 | `WorldviewEntity` | 已有（证据计数 + 置信度，语义等价） |
| 短期记忆·上下文窗口 | `DualTrackMemoryManager.assembleDynamicContext:199` | 已有 |
| 短期记忆·冷数据卸载 | 无 | 端侧单用户，收益低 |
| 混合检索 | `HybridMemoryRetriever` | 已有（它用 BM25+向量+RRF，Lirica 用向量+六维认知精排） |
| 团队记忆 / Memory Hub | 无 | **不需要**，且方向与产品定位相反 |
| Wiki / CodeGraph / Skill | 无 | **不需要**，这三类是给研发团队用的 |

---

## 五、为什么接不进去（四条硬冲突）

### 1. 运行时根本不同

Agent Memory 是 **Node.js ≥ 22.16 + Docker** 的服务。Lirica 是 **Native Android Kotlin + Compose + Room**，仓库里已无 `backend/` 目录（旧 Python 后端已删除）。

Android 上跑不了 Node 服务。要接只有一条路：**部署一台服务器，App 走 HTTP 调它**。这意味着 Lirica 从"端侧独立应用"变成"依赖云端服务的客户端"——架构性质变了。

### 2. 没有 Kotlin / Android SDK

官方给的是 Python / TypeScript SDK + HTTP v2 REST。Android 侧要自己用 Ktor 写完整客户端，包括序列化、鉴权、重试、离线降级。这是一整套新代码，不是引入一个依赖。

### 3. 隐私方向相反

Agent Memory 的主打价值之一是**团队记忆共享 + 权限审计 + 合规溯源**（"经验留在团队里，新成员直接读档"）。

Lirica 有 `PrivacyBreaker` 物理断流、端侧 `DefaultSecureKeyStore`、BYOK 网关——整个设计取向是"最私密的记忆留在本地"。把陪伴对话的原文和画像送到共享型记忆中台，等于把产品最核心的信任资产交出去。

**这条不是技术问题，是产品定位问题，无法靠工程手段绕过。**

### 4. 蒸馏管线要外部 LLM

四层提炼（L0→L1→L2→L3）是异步 LLM pipeline，需要配 `TDAI_LLM_API_KEY`。Lirica 的 `MemoryExtractor` 已经用用户自己的 BYOK 网关做抽取了。再叠一层外部 LLM 依赖，成本和隐私都变差。

### 补充：sqlite-vec 你已经排除过一次了

`MemoryVectorStore.kt:11` 的注释原文：

> 避免 sqlite-vec 在 Android/Robolectric 上因 libsqlite 限制引发动态链接灾难。

**sqlite-vec 正是 Agent Memory 的默认存储底座。** 这条选型在 Lirica 里已经被明确评估并排除过一次，理由是 Android 上的动态链接问题。现在再把它换个壳引进来，理由并不成立。

---

## 六、真正值得抄的两处（不引依赖，只抄思路）

### 1. L2 场景归纳层——这是唯一的真缺口

现在的链路是 L0（会话）→ L1（原子事实）→ L3（世界观认知），**中间缺"从多条同类原子事实归纳出场景模式"这一层**。

L3 是"跨情境反复验证的深层规律"，门槛高、沉淀慢；L1 是散点事实。中间没有"模式"这一档，会导致一类记忆无处安放，例如：

- "每次加班到十点以后，他会变得话少" —— 不是单条事实，也不是世界观，是**多次事件归纳出的情境模式**
- "提到某个话题时他会转移" —— 跨多次对话的行为模式
- "周末上午他更愿意聊轻松的事" —— 时段 × 话题的规律

这类东西现在只能硬塞进 L1（太碎）或等它升到 L3（太慢）。

**建议做法**：新增 `ScenarioPatternEntity`（category / pattern / 支撑原子记忆 id 列表 / 触发条件 / 置信度 / 观测次数），在梦系统巩固流程里做归纳（复用现有 `SubconsciousDreamCoordinator` 的调度，不新增定时任务）。这是 Agent Memory L2 的思路，但实现完全端侧、几十行 Room 实体 + 一次 LLM 归纳调用。

**注意**：`MemoryScorer` 做的是**召回排序**，不是**归纳**。别把两者混为一谈，加排序分解决不了这一层。

### 2. 结论 → 原文的可下钻溯源

`UserMemoryEntity` 有 `source: String?` 字段，但只是个字符串，没形成可下钻的链接结构。

Agent Memory 的做法是每条上层结论保留指向下层的链接（node_id 双向锚定），可以沿 L3 → L2 → L1 → L0 逐级下钻到原始对话。

对 Lirica 的实际价值：**当她引用一条记忆时，能追溯到是哪次对话说的**。这直接支撑"活人感"——能说出"你上次提过这事，是上周二晚上"，而不是凭空冒出一句断言。

**建议做法**：把 `source` 从 String 升级为结构化引用（dialogueId + turnIndex + 时间戳），并给 `WorldviewEntity` 补上支撑证据 id 列表（现在只有 `supportingEvidenceCount` 一个数字，没有指向哪几条）。

---

## 七、那 Agent Memory 该用在哪

它支持 **CodeBuddy / Claude Code** 集成，四类资产里 Wiki、CodeGraph、Skill 三类本来就是给研发工作流设计的。

放在你身上，实际用途：

- **CodeGraph**：索引 Lirica 仓库的符号与调用关系，做改动影响面分析。你仓库 516 个单测、几十个模块，改 `MemoryScorer` 之前先问"哪些用例会红"，这个是真有用的
- **Wiki**：把架构决策（为什么选暴力余弦而不是 sqlite-vec、为什么双轨而不是单轨）沉淀成可检索页面
- **Skill**：把"跑全量单测并解析 XML 求和""交付前 grep 旧名字校验归零"这类可复用流程固化

**这是它真正的主场。用在 Lirica 产品里是错配，用在你开发 Lirica 的过程里是对位。**

---

## 八、决策建议

| 选项 | 建议 |
|---|---|
| 接入 Agent Memory 到 Lirica | **不做**。四条硬冲突，其中隐私方向冲突不可调和 |
| 抄 L2 场景归纳层 | **做**。唯一真缺口，端侧自研成本可控 |
| 抄可下钻溯源 | **做**。直接增强"活人感"，改动小 |
| 装 Agent Memory 用于 Lirica 开发工作流 | **可以试**。Docker 一键起，MIT 协议，零成本验证 |

---

## 附：本次评估的信息来源

- 腾讯云官方文档《Memory 介绍》 https://cloud.tencent.com/document/product/1813/132100 （最近更新 2026-08-11）
- 腾讯云 Agent 记忆服务产品页 https://cloud.tencent.com.cn/product/agm
- 开源仓库 https://github.com/TencentCloud/TencentDB-Agent-Memory （MIT）
- 第三方横评（ima.qq.com 知识库条目）：指出接入路径偏封闭、向量检索需自配 Embedding、**异步蒸馏 pipeline 在 <100 轮对话时收益不明显**
- Lirica 源码：路径见第三节表格，均为本次实读
