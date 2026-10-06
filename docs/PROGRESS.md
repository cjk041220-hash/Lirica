# PROGRESS.md —— 批次 2.5 执行进度记录

## 1. 模型资产下载记录

- **模型文件**: `app/src/main/assets/models/bge-small-zh/model_quantized.onnx`
  - **来源 URL**: `https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx`
  - **实际字节数**: 24,010,842 字节（约 22.9 MB）
  - **校验结果**: 通过（> 15MB 阈值，INT8 量化模型）
  - **Git 状态**: `.gitignore` 规则排除 `*.onnx`，不入库

- **Tokenizer 配置文件**: `app/src/main/assets/models/bge-small-zh/tokenizer.json`
  - **来源 URL**: `https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json`
  - **实际字节数**: 439,125 字节（约 428.8 KB）
  - **校验结果**: 通过（> 1KB 阈值，包含 21,128 词表与 tokenizer 配置）
  - **Git 状态**: 纳入版本库

## 2. ONNX Runtime 依赖引入

- **依赖项**: `com.microsoft.onnxruntime:onnxruntime-android:1.25.0`（以及 host 单测 `com.microsoft.onnxruntime:onnxruntime:1.25.0`）
- **选型说明**: 选用 1.x 官方最新稳定版，包含 Android 平台（arm64-v8a, armeabi-v7a, x86_64）原生推导支持与 Java/Kotlin 绑定层。
- **验证结果**: Gradle 依赖解析正常，全量 533 单测通过。

## 3. Tokenizer 调研与选型

- **选型结果**: 纯 Kotlin 实现 BERT WordPiece 分词器 (`BertWordPieceTokenizer`)
- **选型理由**:
  1. **零第三方依赖约束**：硬约束第 4 条严格禁止引入除 onnxruntime（与 sqlite-vec）外的依赖。DJL tokenizers (`ai.djl.huggingface:tokenizers`) 会引入多套 JNI/Rust 本地库与额外 jar，增大 APK 体积并带来 Android ABI 兼容性风险。
  2. **100% 可测性与全平台一致性**：纯 Kotlin 实现无任何原生动态链接库依赖，在 Android 设备运行时与 JVM / Robolectric 宿主单测环境下表现完全一致。
  3. **架构与性能极致轻量**：bge-small-zh-v1.5 基于标准 BERT 架构与 21,128 词表。纯 Kotlin 解析 `tokenizer.json` 耗时 <100ms，短文本分词耗时 <0.1ms，完美满足端侧热节流与低开销诉求。
- **功能覆盖**:
  - `cleanText` 规整与控制字符过滤
  - `handle_chinese_chars: true` CJK 中文字符逐字隔离拆分
  - 标点符号与空白拆分 (Basic Tokenization)
  - WordPiece 贪婪最大匹配与 `##` 子词拆分，未登录词退化为 `[UNK]`
  - `[CLS]=101` 与 `[SEP]=102` 首尾标记拼接，定长截断
- **验证结果**: 词表提取（21,128 tokens）、中英混排、截断、特殊标记、确定性单测全部通过。

## 4. EmbeddingModel 封装与单测验证

- **核心实现**: `LiricaEmbeddingModel` (实现 `EmbeddingModel` 契约)
- **技术要点**:
  1. `OrtSession.SessionOptions().setIntraOpNumThreads(2)`：严格限制 ONNX 推理线程数为 2，契合移动端热节流防降频硬指标。
  2. 流水线：`BertWordPieceTokenizer` 分词 → 构造 `input_ids`, `attention_mask`, `token_type_ids` int64 张量 → ONNX 推理输出 `last_hidden_state` [1, seqLen, 512] → 依据 `attention_mask` 进行 Mean Pooling 聚合 → L2 单位向量归一化。
  3. 单位向量模长归一化：将 512 维向量投影到单位超球面上，使得后续余弦相似度计算等价于向量点积，将检索计算量降至最低。
- **单测断言验证结果**:
  - **维度断言**: 准确输出 512 维 FloatArray
  - **确定性断言**: 同一文本输入两次产生完全一致的浮点向量（误差 < 1e-6）
  - **非零与单位模长断言**: 向量有效非零，L2 范数精确为 1.0 (±1e-3)
  - **相对相似度断言**:
    - `sim("喜欢周杰伦", "爱听周董的歌") = 0.6678`
    - `sim("喜欢周杰伦", "今天天气不错") = 0.3099`
    - 验证通过，语义强相关相比无关样本具有显著区分度 (> 0.35 余弦差距)
  - **推理延迟估算**: 平均单句推导耗时约 1.8ms/条（Robolectric / JVM 估算，待真机，远低于 200ms 上限）

## 5. VectorStore 选型与存储实现

- **选型结果**: 纯 Kotlin 暴力余弦向量检索 (`MemoryVectorStore`) + Room 持久化 (`memory_embeddings` 表)
- **选型理由**:
  1. **零 Native 依赖与无崩溃风险**：Android 系统自带的 `libsqlite.so` 出于安全限制默认禁用了 `sqlite3_enable_load_extension`，集成 sqlite-vec 需打包额外的完整自定义 SQLite native 库及针对各个 CPU 架构（arm64-v8a、armeabi-v7a、x86_64）的 `.so` 二进制，在 JVM / Robolectric 宿主单测环境下还需要 Windows / macOS 原生动态链接库，集成维护成本高且极易因动态链接失败导致崩溃。
  2. **性能完全过剩**：Lirica 单用户记忆体量为千条级（1,000 ~ 5,000 条）。在实测中，纯 Kotlin 对 5,000 条 512 维归一化向量进行点积检索与最小堆 Top-20 筛选，单次检索平均耗时仅 1.1ms，在千条级记忆下完全无需复杂的向量索引结构（如 HNSW 或 IVFPQ），兼具瞬时启动与内存零碎片优势。
  3. **架构正交与持久化一致性**：Room 数据库通过 `MemoryEmbeddingEntity` 与 `memory_embeddings` 表对向量进行 BLOB 紧凑存储（2048 字节 / 条），启动时通过 `initFromDatabase()` 装载进 `ConcurrentHashMap`，运行时向量增删改查线程安全且无缝契合 Room 事务与架构迁移。
- **存储与表结构**:
  - 表名: `memory_embeddings`
  - 主键: `memory_id` (TEXT)
  - 列: `track` (TEXT: 'user' / 'self'), `embedding` (BLOB, 2048字节小端序单精度浮点), `updated_at` (INTEGER)
  - 数据库迁移: `LiricaDatabase` 版本升为 3，提供 `MIGRATION_2_3`
- **单测断言验证结果**:
  - `MemoryVectorStoreTest`: Top-K 排序正确性、双轨过滤 (Track User / Self)、删除与清空、5000 条向量性能压测 (<10ms) 全部通过。
  - `MemoryEmbeddingDaoTest`: Room 持久化写入、读取反序列化、按 track 查询、冷启动载入全部通过。
  - `MigrationTest`: v1 -> v2 -> v3 迁移链完整性验证通过。

## 6. 检索融合接口实现与验证

- **核心实现**: `HybridMemoryRetriever`
- **检索流程**:
  1. **向量粗排 (Dense Vector Recall)**: 端侧 `LiricaEmbeddingModel` 计算输入 query 的 512 维归一化向量，在 `MemoryVectorStore` 中暴力点积检索召回 top-20 最优候选（严格遵照 PRD §3 D21 与批次 2.5 规格）。
  2. **认知精排 (Cognitive Re-ranking)**: 候选集无损接入既有 `MemoryScorer.calculateScore(...)` 进行六维加权评估（时间衰减、访问热度、最近使用、重要度、字符 2-gram 话题重叠、情绪亲和度），不修改 `MemoryScorer.kt` 既有代码，融合向量相似度进行综合评分排序。
  3. **截断与输出**: 取综合打分最高的前 top-N (topK) 实体输出。
  4. **入梦重算与双轨协同**: 提供 `recomputeAllVectors()` 与 `indexMemories()` 供入梦流水线对齐重算，并为 `DualTrackMemoryManager` 提供 `retrieve` 融合调用。
- **单测断言验证结果**:
  - **"周董"召回"喜欢周杰伦"对比测试 (`testJayChouSemanticRecallContrast`)**:
    - 纯字符 2-gram 话题重叠度 = 0.0（验证传统基于关键词/字符的分词方法完全失灵）
    - 端侧向量融合检索 `retrieve(query = "周董", topK = 1)` 成功精准召回 "喜欢周杰伦" 作为第一名
    - 相似度显著度断言通过（语义相似度 > 0.4，总分大幅领先无关记忆）
  - **Top-20 候选粗排与精排测试 (`testVectorRecallTop20AndMemoryScorerRerank`)**: 验证从 25+ 候选集中取 top-20 并由 MemoryScorer 正确精排。
  - **空 query 与空数据库鲁棒性测试**: 全部通过。
  - **双轨架构协同测试 (`testDualTrackMemoryManagerWiring`)**: `DualTrackMemoryManager.retrieve` 正确装配。
  - **入梦重算向量测试 (`testRecomputeAllVectors`)**: 向量重算全量写入通过。

## 7. 全量测试与验收汇总（批次 2.5）

- **初始基线**: 533 个测试
- **批次 2.5 交付后**: 562 个测试（净增 29 个测试用例）
- **测试通过率**: 100%（562 passed, 0 failed, 0 skipped/ignored）
- **硬约束检查**:
  1. 模型仅从 `hf-mirror.com` 下载 INT8 量化模型（24.0MB），经校验无损坏；
  2. 依赖仅引入 `com.microsoft.onnxruntime:onnxruntime-android:1.25.0`（宿主单测仅引入对应官方 JVM 运行时包）；
  3. 未修改 `MemoryScorer.kt` 既有实现；未修改 `proactivity/**`, `emotion/**`, `persona/**`, `automation/**`, `perception/**`, `network/**`, PRD, `docs/**`；
  4. 未引入任何其他额外第三方依赖，未执行删除文件、reset、checkout 或 push；
  5. `.gitignore` 正确配置忽略 `*.onnx`，仅小文本资产 `tokenizer.json` 入库。

## 8. 批次 3 · 失败叙述接口执行记录与验收汇总

- **核心目标**: 落地 PRD §1 D5 诚实失败原则与 §7 批次 3 规格，把"执行失败的坦白"从字段变成通道。
- **落地点与技术实现**:
  1. **HonestNarrator 话术模板层 (`automation/healing/HonestNarrator.kt`)**:
     - 涵盖 6 大核心场景（金融禁止区、通信代发、密码框、系统提权、接地失败/元素找不到、自愈耗尽）及通用兜底。
     - 每类内置 2-3 条变体，语气克制、无奈、不卖惨（符合 D5"这种无奈反而更有活人感"）；随机选取避免机械重复。
     - 话术采用结构化占位符（`{appName}`、`{targetElement}`、`{actionType}`）动态替换，末尾标注中性占位标语「（正式话术库待俊凯过目定稿）」。
  2. **Watchdog 接线 (`automation/healing/SelfHealingWatchdog.kt`, `HealingModels.kt`)**:
     - `WatchdogVerdict.Unrecoverable` 增加可选 `honestNarrative: String?` 字段。
     - `SelfHealingWatchdogImpl` 在三次重试或回退耗尽判定 Unrecoverable 时，自动注入并调用 `HonestNarrator` 产出人设化坦白文案。
  3. **Gatekeeper 拒绝路径与结果模型透传 (`HelixGuiCoordinator.kt`, `TaskExecutionModels.kt`)**:
     - `TaskExecutionState.Failed` 与 `TaskExecutionResult.Failure` 增加可选 `honestNarrative: String?` 字段。
     - `HelixGuiCoordinatorImpl` 注入 `HonestNarrator`，在 Gatekeeper `PhysicalBlocked` 分支将 `honestReason` 升级为经 `HonestNarrator` 渲染的完整坦白文案，同时在 `errorReason` 保留原始 `rule` 与 `reason` 供内部排查。
     - 全面覆盖隐私黑屏熔断 (`EmptyReason.PRIVACY_BLACKOUT`)、接地失败 (`GroundingResult.NotFound`)、看门狗耗尽、手势拦截、文本注入拦截等各失败路径。
  4. **日志纪律与隐私保护**:
     - `rule`、`technicalReason`、`targetPackage`、`scenario` 通过 `LiricaLog.w` 记入环形日志缓冲，`honestNarrative` 透传至用户可见输出。
     - `extraInfo`（含用户输入明文、密码、卡号等私密数据）严格物理隔离，绝不进入任何日志。
  5. **批次 2 备注修复**:
     - 成功清理 `ProhibitedZones.kt` 中 `com.tencent.wepay` 幻觉包名行，并同步更新单测集合大小断言。
- **全量测试与验收汇总**:
  - **批次 3 初始基线**: 562 个测试
  - **批次 3 交付后**: 578 个测试（净增 16 个测试用例，覆盖 6 大场景模板、变体机制、占位符替换、Unrecoverable 接线、协调器物理熔断透传、日志纪律与 extraInfo 防泄露断言）
  - **测试通过率**: 100%（578 passed, 0 failed, 0 skipped/ignored）
  - **硬约束检查**: 全部严格恪守，无超范围修改，未修改 `perception/**`, `proactivity/**`, `emotion/**`, `persona/**`, `memory/**`, `network/**`, PRD, `docs/**`, `FinancialGatekeeper.kt` 判定逻辑；未引入新依赖；未修改 `AndroidManifest.xml`；未执行 push/reset/checkout/文件删除。






