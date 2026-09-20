# 小汐 2.0 (Project Aether) 开发进度看板 (Progress Tracker)

> **当前阶段**：Phase 3 · 获权“视奸”感知中枢落地 (前台追踪 / 通知监听 / 无障碍轻量提取 / 隐私熔断)  
> **最后更新**：2026-09-20  
> **整体技术栈**：100% Native Kotlin + Jetpack Compose + Ktor SSE + Room SQLite + Coroutines

---

## 阶段规划总览

| 阶段 | 核心目标 | 状态 | 进度 |
|:---|:---|:---:|:---:|
| **Phase 0** | 新基建确立与通信打通 (Native骨架 / 权限声明 / Ktor BYOK客户端 / ST角色卡解码) | **已全绿验收** | 4/4 |
| **Phase 1** | 心智引擎与记忆系统原生化 (Russell情绪 / 双轨记忆 / PHI重锚 / 8轮防漂移 / 反AI腔过滤) | **已全绿验收** | 4/4 |
| **Phase 2** | 原生表现层与常驻悬浮伴生体 (Compose主界面 / 悬浮伴生体 / 气泡交互 / 保活) | **已全绿验收** | 4/4 |
| **Phase 3** | 获权“视奸”感知中枢落地 (前台追踪 / 通知监听 / 无障碍轻量提取 / 隐私熔断) | 待启动 | 0/4 |
| **Phase 4** | 主动性裁决器与自主生活上线 (沉默裁决 / 自主经历日记 / 未完成念头池) | 待启动 | 0/4 |
| **Phase 5** | 手机操控与任务代行 (Intent直达 / 无障碍手势 / VLM Grounding / 安全守门) | 待启动 | 0/5 |

---

## Phase 0 任务拆解与执行记录

### Task 0.1: 归档清理旧资产与建立纯原生 Android Kotlin 项目骨架
- [x] 状态：已完成 (2026-09-19)
- **交付内容**：
  - 彻底清理旧 Web/Capacitor 资产及一次性脚本
  - 配置根工程 `settings.gradle.kts`、`build.gradle.kts` 与 `gradle.properties`（启用 AndroidX、Non-transitive R class、内存参数与阿里云镜像加速）
  - 创建 `app` 模块标准目录结构与 `app/build.gradle.kts`（Kotlin 2.0.21, Compose BOM 2024.09.03, Ktor 2.3.12, Room 2.6.1, Coroutines 1.9.0, Serialization 1.7.3, MockK, Turbine）
  - 创建 `XiaoxiApplication.kt`、`MainActivity.kt`
  - 验证 `./gradlew test` (全绿) 与 `./gradlew assembleDebug` (打包成功)
- **测试证据**：
  - `./gradlew test`: `BUILD SUCCESSFUL in 2s` (49 actionable tasks, 5 executed, 44 up-to-date)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 16s`
- **变异验证**：
  - 故意将 `SkeletonTest.kt` 中 `appName` 期望值改坏为 `"wrong-app"`，运行 `./gradlew test` 确认变为红灯 (`SkeletonTest > testProjectIdentity FAILED: org.junit.ComparisonFailure`)，恢复后重新全绿。
- **审查结论**：纯 Kotlin 原生技术栈确立，无 Python / Chaquopy 侵入，配置规范。

### Task 0.2: 配置 AndroidManifest.xml 核心权限与组件声明
- [x] 状态：已完成 (2026-09-19)
- **交付内容**：
  - 声明核心权限清单：`INTERNET`, `ACCESS_NETWORK_STATE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`
  - 声明无障碍感知与手势执行服务 (`XiaoxiAccessibilityService`)，配备标准合规配置 `res/xml/accessibility_service_config.xml`（`notificationTimeout="150"`, `canRetrieveWindowContent="true"`, `canPerformGestures="true"`, `isAccessibilityTool="false"`）
  - 声明全局通知监听服务 (`XiaoxiNotificationListenerService`)
  - 声明跨应用常驻伴生悬浮窗服务 (`CompanionOverlayService`) 与前台保活服务 (`KeepAliveService`)，遵循 Android 14+ `specialUse` 前台服务类型与 subtype 说明
  - 编写与运行 `ManifestComponentsTest`，全量验证权限、XML 属性契约与服务继承关系
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 5 tests completed, 0 failed, `BUILD SUCCESSFUL in 3s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 1s`
- **变异验证**：
  - 故意将 `XiaoxiAccessibilityService.NOTIFICATION_TIMEOUT_MS` 从 `150L` 改坏为 `500L`，运行 `./gradlew testDebugUnitTest` 确认变红报错 (`ManifestComponentsTest > testAccessibilityServiceConfigurationContract FAILED`)，恢复后重新全绿。
- **审查结论**：严格对齐《端侧低能耗感知漏斗方案》L0 硬过滤与《移动端GUI方案》Level 1 手势要求，合规与权限完备。

### Task 0.3: 基于 Ktor 的 BYOK 大模型通信客户端 (SSE + 参数清洗)
- [x] 状态：已完成 (2026-09-19)
- **交付内容**：
  - 建立大模型通信数据契约（`LlmConfig`, `LlmProvider`, `ChatMessage`, `ChatCompletionRequest`, `ChatCompletionChunk`, `LlmStreamEvent`）
  - 落地 DeepSeek 采样参数清洗器（`LlmParamSanitizer`）：物理剔除 `top_p <= 0` 的 400 报错陷阱，自适应注入 `temperature = 0.95` 与 `presence_penalty = 0.25` 维持角色生命感与鲜活度
  - 落地 SSE 流式增量解析器（`SseStreamParser`）与 Tool Calls 增量组装器（`ToolCallAssembler`），完整支持 Token 级流式发射与函数调用拆装
  - 落地基于 Ktor 的 BYOK 异步非阻塞客户端（`XiaoxiLlmClient`）：支持流式 POST 请求、SSE 解码、HTTP 状态码（401/429）友好拦截与自定义异常 `LlmApiException`
  - 编写与运行 `LlmClientTest`，包含真实 MockEngine 端到端流式响应、DeepSeek 陷阱清洗断言与 401 拦截单测
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 12 tests completed, 0 failed, `BUILD SUCCESSFUL in 3s`
  - `./gradlew test`: 12 tests completed, 0 failed, `BUILD SUCCESSFUL in 3s`
- **变异验证**：
  - 故意改坏 `LlmParamSanitizer.kt` 中的清洗逻辑，取消对 `top_p <= 0.0` 的过滤，运行 `./gradlew testDebugUnitTest`，两个核心单测均变红报错（`AssertionError: DeepSeek top_p <= 0 必须被清洗为 null` 与 `请求体中严禁包含 top_p: 0`），恢复逻辑后重新全绿通过。
- **审查结论**：100% Native Kotlin 异步协程 Flow，无内存泄漏，无 AI 客服腔，遵循参数规范与错误熔断。

### Task 0.4: 标准 SillyTavern V2/V3 PNG 角色卡元数据二进制解码器
- [x] 状态：已完成 (2026-09-19)
- **交付内容**：
  - 角色卡跨版本核心领域模型设计（`CharacterCard`, `CharacterBook`, `CharacterBookEntry`, `CharacterCardEnvelope`, `CharacterCardData`），完全对齐 SillyTavern `chara_card_v2` 与 `chara_card_v3` 规范
  - 纯 Kotlin 原生二进制 PNG 流式/块式编解码器（`CharacterCardDecoder`）：
    - 严密校验 8 字节 PNG 魔法头（`0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A`）
    - 快速定位与提取 `tEXt`、`zTXt` 数据块（支持 Deflate 解压）
    - 自动识别并解析 Base64 编码与 UTF-8 JSON 负载（兼容 `chara` 与 `ccv3` 规范）
    - 提供双向无损编码器：支持将领域模型自动打包为携带标准 `ccv3` tEXt 块与 CRC32 校验码的标准 PNG 图片
  - 动态宏替换引擎（`MacroExpander`）：支持 `{{char}}`, `{{user}}`, `{{current_time}}`, `{{current_app}}`, `{{battery_level}}`, `{{idle_duration}}` 实时环境插值
  - 编写与运行 `CharacterCardDecoderTest`，涵盖 V2 JSON 解码、V2 PNG 提取、V3 ccv3 提取、双向 Round-Trip 编解码一致性验证、宏展开及非法 PNG 异常校验
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 18 tests completed, 0 failed, `BUILD SUCCESSFUL in 3s`
  - `./gradlew test`: 18 tests completed, 0 failed, `BUILD SUCCESSFUL in 3s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s`
- **变异验证**：
  - 故意改坏 `MacroExpander.kt` 中的宏替换逻辑（将 `charName` 替换为 `"MUTATED"`），运行 `./gradlew testDebugUnitTest` 确认变红报错（`CharacterCardDecoderTest > testMacroExpansion FAILED`），恢复后重新全绿通过。
- **审查结论**：严格对齐 SillyTavern 官方规范，二进制解析无第三方臃肿依赖，单测覆盖完整，无内存泄露。

---

## 阶段验收里程碑 M0 (Phase 0 全绿达成)
- **验收时间**：2026-09-19
- **验收结论**：Task 0.1 ~ Task 0.4 全部通过 TDD 与变异验证，工程构建与测试 100% 成功，APK 打包正常。根据指令，正式具备切入 **Phase 1（心智引擎与记忆系统原生化）** 条件。

---

## Phase 1 任务拆解与对接指引 (Next Session Action Plan)

> **核心使命**：将小汐 1.0 的核心灵魂资产（反 AI 客服腔、语气重锚、情绪动力学、双轨记忆）用 100% Native Kotlin 原生重构，全面消除对旧 Python 后端的依赖。

### Task 1.1: Room 数据库落地与双轨记忆表结构
- [x] 状态：已完成 (2026-09-19)
- **前置必读资料**：`docs/局部调研——RESEARCH_AGENT_MEMORY_ARCHITECTURE.md`（双轨记忆、自叙经历表、未完成念头池、时间衰减与矛盾覆盖）
- **参考代码**：`backend/memory/manager.py`（算法公式参考）
- **交付内容**：
  - `UserMemoryEntity` & `UserMemoryDao`：用户事实与画像、置信度、时间戳、`superseded_by` 矛盾覆盖标记、按日 hitCount 去重
  - `SelfExperienceEntity` & `SelfExperienceDao`：Agent 自主上网/见闻的第一人称心得与分享流
  - `UnspokenThoughtEntity` & `UnspokenThoughtDao`：观察用户产生的碎片念头池、未说出口想法、紧急度排序与过期自然遗忘
  - `ChatDialogueEntity` & `ChatDialogueDao`：会话多轮历史持久化、消息类型与统计
  - `XiaoxiDatabase`：Room 数据库实例、单例工厂与内存测试工厂
  - 单元测试与变异验证（`XiaoxiDatabaseTest` 覆盖 CRUD、矛盾覆盖标记、并发事务安全）
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 23 tests completed, 0 failed, `BUILD SUCCESSFUL in 16s`
  - `./gradlew test`: 23 tests completed, 0 failed, `BUILD SUCCESSFUL in 16s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 8s`
- **变异验证**：
  - 故意将 `UnspokenThoughtDao.kt` 中的 `discardExpiredThoughts` 过期过滤条件从 `< :currentTimestamp` 反转修改为 `> :currentTimestamp`，运行 `./gradlew testDebugUnitTest` 确认变红报错（`XiaoxiDatabaseTest > testUnspokenThoughtUrgencyAndDecayFlow FAILED: org.junit.ComparisonFailure`），恢复逻辑后重新全绿通过。
- **审查结论**：严格遵循双轨记忆架构规范与 Room 原生实践，全量通过单元测试与变异验证。

### Task 1.2: Russell 情绪空间 (Valence-Arousal) 2D 动力学状态机
- [x] 状态：已完成 (2026-09-19)
- **前置必读资料**：`docs/局部调研——RESEARCH_AGENT_EMOTION_EVOLUTION.md`（Russell 2D 环形情感模型、情感惯性与漂移阻尼衰减）
- **参考代码**：`backend/heartbeat_moods.py`
- **交付内容**：
  - 连续二维情绪向量空间 `EmotionVector(valence, arousal, dominance)`，提供严格物理边界钳制与 2D 欧式距离计算
  - 离散微表情状态机 `MoodState`（包含 16 种情绪状态：开心、兴奋、活泼、好奇、期待、满足、发呆、懒洋洋、困倦、关切、闹别扭、困惑、平静、安心、轻松、想念），包含对应 spriteTag 与文风提示词
  - 最近邻（Nearest Neighbor）确定性微表情映射与高斯加权概率采样（Gaussian Proximity Sampling）双模检索
  - 情绪动力学微分更新引擎 `EmotionEngine`（数学模型：$\Delta E = \alpha \cdot \text{Stimulus} - \beta \cdot (E - E_0)$），支持外部冲激响应、长程静默向人格基线衰减及 `StateFlow` 响应式状态暴露
  - 单元测试与变异验证（`EmotionEngineTest` 覆盖基线初始化、冲激响应、长程静默阻尼回退、边界钳制、最近邻与高斯采样）
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 30 tests completed, 0 failed, `BUILD SUCCESSFUL in 12s`
  - `./gradlew test`: 30 tests completed, 0 failed, `BUILD SUCCESSFUL in 12s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 3s`
- **变异验证**：
  - 故意将 `EmotionEngine.kt` 中 `applyStimulus` 的冲激更新计算改坏（将冲激增量符号反转为 `stimulus * -sensitivityAlpha`），运行 `./gradlew testDebugUnitTest` 确认 4 个核心单测变红报错（`testNegativeStimulusAndSulking`, `testStimulusImpulseResponse`, `testClampingBounds`, `testLongTermSilenceDecayTowardsBaseline` 均 `FAILED`），恢复后重新全绿通过。
- **审查结论**：严格符合 Russell 环形模型与 ALMA 三层架构设计，消除情绪跳变，保障情绪动力学平滑演化。

### Task 1.3: PHI 语气重锚中间件与 8 轮抗漂移调度器
- [x] 状态：已完成 (2026-09-19)
- **前置必读资料**：`docs/主方案——小汐2.0开发计划书_原生自主人格伴生智能体_2026-09-19.md`（第 5.1 节 人格稳定性核心：PHI 语气重锚 + 8轮漂移防护）
- **参考代码**：`backend/agent/soul.py`（小汐 1.0 的 PHI 提示词模板、语气约束与抗客服腔沉淀）
- **交付内容**：
  - `DriftShieldScheduler`（8 轮抗漂移调度器）：
    - 维护线程安全的会话轮次计数器（支持会话隔离与轮次追踪）
    - 周期性判定（`turnCount % 8 == 0`）：在第 8、16、24... 轮触发时，自动激活并注入压缩版核心人格约束清单（Drift Shield Payload）
    - 提供通用解耦的默认抗漂移护盾设定（`DEFAULT_DRIFT_SHIELD_PAYLOAD`），完全面向通用用户
  - `PhiMiddleware`（PHI 语气重锚中间件）：
    - 严格遵循上下文装配流顺序：`[System Prompt / 角色卡基础设定] -> [动态环境与记忆槽] -> [对话历史] -> [Post-History Instructions (PHI)]`
    - 利用 Transformer 模型的近因偏置（Recency Bias），将核心反 AI 客服腔口吻约束与 Task 1.2 的即时情绪文风（`MoodState.styleHint`）锚定于消息数组的最末端（LLM 生成前的最后一条系统指令），杜绝长上下文下的注意力稀释与客服腔漂移
    - 集成 `MacroExpander` 动态宏展开引擎，支持从 `CharacterCard` 自动提取与展开 `post_history_instructions`
    - 提供 `assemble`（多阶段消息装配）与 `processRequest`（请求体就地注入）双模 API
  - 单元测试与变异验证（`PhiMiddlewareTest`）：
    - 10 个测试用例覆盖装配顺序、尾部 PHI 绝对位置、反客服腔语气铁律、情绪文风注入、8 轮周期触发边界、会话隔离与并发线程安全、角色卡宏展开及请求体就地注入
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 40 tests completed, 0 failed, `BUILD SUCCESSFUL in 5s`
  - `./gradlew test`: 40 tests completed, 0 failed, `BUILD SUCCESSFUL in 13s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 3s`
- **变异验证**：
  - 变异 1：故意将 `DriftShieldScheduler.kt` 中的周期判定条件从 `turnCount % interval == 0` 反转修改为 `turnCount % interval != 0`，运行 `./gradlew testDebugUnitTest` 确认 4 个核心单测变红报错（`testDriftShieldPeriodicTriggerBoundaries`, `testDriftShieldSessionIsolationAndTurnTracking`, `testPhiAssemblyWithDriftShieldTriggeredOnTurn8And16`, `testProcessChatCompletionRequest` 均 `FAILED`），恢复后重新全绿。
  - 变异 2：故意将 `PhiMiddleware.kt` 中的尾部重锚插入位置改坏，插入到消息列表首部（`assembledList.add(0, ...)`），运行 `./gradlew testDebugUnitTest` 确认 6 个核心单测变红报错（`testContextAssemblySequenceOrder`, `testTailPhiRecencyBiasAndAntiCustomerServiceTone`, `testMoodStateStyleHintIntegration` 等均 `FAILED`），恢复后重新全绿。
- **审查结论**：严格对齐 SillyTavern PHI 规范与 8 轮抗漂移学术理论（arXiv:2402.10962），100% Native Kotlin 实现，无 Python/Chaquopy 依赖，全量通过单元测试与双重变异验证。

### Task 1.4: 反 AI 腔过滤器 (Humanness Gap Filter)
- [x] 状态：已完成 (2026-09-19)
- **前置必读资料**：`docs/主方案——小汐2.0开发计划书_原生自主人格伴生智能体_2026-09-19.md`（第 5.1.2 节 人格稳定性与抗漂移四大机制）
- **参考代码**：`backend/agent/soul.py`（小汐 1.0 反客服腔、真人口语张力与不说教铁律）
- **交付内容**：
  - `HumannessFilter`（反 AI 腔与自然人味过滤器）：
    - 严密覆盖四大违禁类型：`AI_IDENTITY`（AI身份与模型自称声明）、`CUSTOMER_SERVICE`（机械客服套话与应答）、`EMPTY_FORMULA`（空泛敷衍与万能鸡汤）、`ROBOTIC_PREACHY`（机械免责与说教声明）
    - 提供精准无误伤的正则表达式黑名单（避免对“JK在搞AI研究”、“好的”、“明白”等正常日常自然口语产生误报）
    - 双层防护机制：
      - **本地手术式降级改写（Local Surgical Rewriting）**：精准剔除违禁词前缀与祝愿尾巴，保留实质自然对话；在纯机械拒答（清空）时，根据当前 `MoodState` 触发符合人设的自然保底回应（`getFallbackForMood`）
      - **二次自纠环（Secondary Self-Correction Loop）**：支持异步协程 `filterWithCorrectionLoop`，向 LLM 注入定位明确的违禁检测与语气自纠提示词；多次自纠仍违禁时自动落入本地确定性保底改写
    - 结构化过滤结果 `FilterResult`：暴露通过状态、原始文本、改写文本、违禁列表及改写标记
  - 单元测试与变异验证（`HumannessFilterTest`）：
    - 9 个单测覆盖四大违禁类型识别、正常自然口语零误杀放行、本地手术式清洗改写、纯机械回复情绪保底回退、二次自纠环成功与多次超限保底、结构化详尽报告
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 49 tests completed, 0 failed, `BUILD SUCCESSFUL in 9s`
  - `./gradlew test`: 49 tests completed, 0 failed, `BUILD SUCCESSFUL in 14s`
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s`
- **变异验证**：
  - 变异 1：故意将 `HumannessFilter.kt` 中 `detectViolations` 改坏为直接返回空列表（`return emptyList()`），运行 `./gradlew testDebugUnitTest` 确认 6 个核心单测变红报错（`testDetectAiIdentityViolations`, `testDetectCustomerServiceViolations`, `testDetectEmptyFormulaAndPreachyViolations`, `testFilterResultDetailedReporting` 等均 `FAILED`），恢复后重新全绿。
  - 变异 2：故意将 `HumannessFilter.kt` 中 `rewriteLocally` 的保底回退分支禁用（`if (false)`），运行 `./gradlew testDebugUnitTest` 确认 `testPureRoboticResponseTriggersInCharacterFallback` 变红报错，恢复后重新全绿。
- **审查结论**：100% Native Kotlin 实现，严格遵循 TDD 流程，彻底清除 AI 客服腔与机械说教，全量单测与双重变异验证 100% 通过。

---

## 阶段验收里程碑 M1 (Phase 1 全绿达成与深度巡检修复)
- **验收与巡检时间**：2026-09-20
- **全系统深度审查与缺陷修复记录**：
  1. **双轨记忆 6 因子相关性评分与二段分层召回引擎落地 (`MemoryScorer` & `DualTrackMemoryManager`)**：
     - 补齐了 Phase 1 曾遗漏的记忆高层调度引擎，完整实现时间衰减（半衰期 7 天）、访问热度对数饱和、最近使用衰减、重要性加权、字符 2-gram 话题重合度与情绪亲和度（Bower 情绪一致性软调制）。
     - 落地二段分层召回（话题命中优先浮现，未占满时由时序/重要性兜底补充），提供向 `PhiMiddleware` 动态上下文装配的标准管道。
  2. **自适应角色卡抗漂移护盾 (`PhiMiddleware`)**：
     - 修复了加载自定义 SillyTavern 角色卡时第 8 轮仍硬编码注入“小汐...鲸族少女”导致的严重人设污染，支持从角色卡动态生成专属抗漂移约束。
  3. **世界书词条激活器 (`LorebookActivator`)**：
     - 落地 SillyTavern V3 `character_book` 原生解析与激活引擎，支持 constant 恒定条目、primary/secondary selective 关键词扫描、大小写敏感控制与 insertion_order 排序，无缝注入 Prompt 动态环境槽。
  4. **反 AI 腔过滤器强化与 DeepSeek 客服腔拦截 (`HumannessFilter`)**：
     - 补齐了“希望...对您/你有所帮助”、“有什么可以帮您”、“如果还有其他疑问，欢迎随时提问”、“随时为您效劳”等主流 LLM 高频客服套话与 AI 助手身份自称声明；
     - 修复了局部手术改写后遗留“。，”等冲突标点的排版缺陷；支持自定义外部人设保底提供者。
  5. **二进制解码与 I/O 防御性加固 (`CharacterCardDecoder` & `MacroExpander`)**：
     - 废弃 `dis.available() > 0` 易在 Android 文件/管道流中过早终止的隐患，重构为基于 `IEND` 与 `EOFException` 的健壮块解析循环；
     - 支持 RFC 2045 带换行符的 MIME Base64 格式与非 Base64 纯 JSON 直通解码；拓展 `<CHAR>`、`{{time}}`、`{{date}}` 等常用酒馆宏。
  6. **大模型通信端点增强 (`XiaoxiLlmClient`)**：
     - 清除死代码 `lastFinishReason`，提供同步/挂起聚合方法 `suspend fun chat(...)`，便利自纠环与快速单轮调用。
  7. **数值物理盒 NaN 防护 (`EmotionVector`)**：
     - 为 `clamped()` 增加 `isNaN()` 防御，防止非法刺激数据导致情绪距离与微表情分类崩溃。
- **最终测试证据**：
  - `./gradlew testDebugUnitTest`: 67 tests completed, 0 failed, `BUILD SUCCESSFUL in 10s` (100% pass)
  - `./gradlew testReleaseUnitTest`: 67 tests completed, 0 failed, `BUILD SUCCESSFUL in 11s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 4s`
- **验收结论**：Phase 0 与 Phase 1 的所有功能点、设计规格、边缘容错与算法数学模型已达到 100% 工业级落地水准，无任何 Chaquopy/Python 残留，具备高质量切入 **Phase 2（原生表现层与常驻悬浮伴生体）** 条件。

---

## Phase 2 任务拆解与对接指引 (Next Session Action Plan)

> **核心使命**：基于 Jetpack Compose 构建小汐 2.0 的原生表现层与跨应用常驻悬浮伴生体，打通全屏主界面、悬浮微表情动画渲染与全局保活机制。

### Task 2.1: Compose 全屏主界面与对话流渲染 (ChatScreen & ViewModel)
- [x] 状态：已完成 (2026-09-20)
- **交付内容**：
  - `ChatUiState` & `ChatMessageItem`：完整的响应式 UI 状态契约，支持双轨记忆命中指示模型 `MemoryBadgeInfo`、微表情跟随与心智调试参数
  - `ChatViewModel`：响应式协调器，打通 `XiaoxiLlmClient`（SSE 流式打字机逐字累加）、`DualTrackMemoryManager`（双轨记忆二段分层召回与上下文装配）、`EmotionEngine`（微表情与情绪向量响应式同步）、`PhiMiddleware`（PHI 尾部语气重锚）与 `HumannessFilter`（反客服腔过滤）
  - `ChatScreen`：沉浸式 Compose 全屏对话流主界面，实现轻量 Markdown 格式解析渲染（粗体、行内代码、段落分行）、打字机流式自动滚底、微表情头像伴随
  - `MemoryBadge`：双轨记忆命中指示徽章与详情弹窗，区分用户事实与自主经历见闻
  - `MoodDebugPanel`：心智调试展开面板，实时观察与调试 Russell 2D 情绪坐标 (Valence, Arousal, Dominance)
  - `CharacterCardDialog`：SillyTavern 角色卡查看与导入切换弹窗，无缝集成 `CharacterCardDecoder`
  - 更新 `MainActivity` 托管 `ChatScreen`；更新 `XiaoxiApplication` 单例持有心智中枢依赖
  - `ChatViewModelTest` 涵盖 6 个高质量 JVM 单元测试
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 73 tests completed, 0 failed, `BUILD SUCCESSFUL in 9s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 3s`
- **变异验证**：
  - 变异点 1：故意注释掉 `ChatViewModel.kt` 流式循环中的 Token 累加逻辑 `accumulated.append(event.text)`，运行单测触发红灯报错（`ChatViewModelTest > testSendMessageStreamingAccumulation FAILED: org.junit.ComparisonFailure at ChatViewModelTest.kt:98`）；恢复后重新全绿。
  - 变异点 2：故意破坏 `ChatViewModel.kt` 中占位助手消息的记忆徽章挂载（强制置为 `null`），运行单测触发红灯报错（`ChatViewModelTest > testMemoryBadgeAssociation FAILED: java.lang.AssertionError at ChatViewModelTest.kt:138`）；恢复后重新全绿。
- **审查结论**：100% 原生 Jetpack Compose + Kotlin Coroutines，无 Dummy Code，完全对齐 Phase 1 心智中枢，全量单测与变异验证通过。

### Task 2.2: 跨应用常驻伴生悬浮窗 (CompanionOverlayService & OverlayBubble)
- [x] 状态：已完成 (2026-09-20)
- **交付内容**：
  - `OverlayPositionCalculator`：100% 纯 Kotlin 贴边吸附（左右屏幕边缘自适应目标计算）、可见边界安全钳制与越界弹性阻尼数学算法
  - `OverlayBubble`：跨应用悬浮伴生体 Compose 组件，自适应渲染 16 种 Russell 情绪微表情映射（光晕色彩、动态呼吸频率与状态标签），支持实时拖拽手势与平滑吸附
  - `CompanionOverlayService`：深度完善 `LifecycleService`，严守【WindowManager 挂载 Compose 铁律】（绑定 `ViewTreeLifecycleOwner`、`ServiceSavedStateRegistryOwner`、`DisposeOnDetachedFromWindow`），完善 `SYSTEM_ALERT_WINDOW` 权限防御判定与安全的销毁解绑
  - `OverlayPositionCalculatorTest`：4 个用例验证贴边吸附分界、边界钳制与阻尼运算
  - `CompanionOverlayServiceContractTest`：2 个用例验证 Robolectric 悬浮窗属性与 START_STICKY 服务契约
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 79 tests completed, 0 failed, `BUILD SUCCESSFUL in 8s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s`
- **变异验证**：
  - 破坏点：故意将 `OverlayPositionCalculator.kt` 中 `calculateSnapTargetX` 的吸附中点判定逻辑从 `< screenWidth / 2` 反转修改为 `> screenWidth / 2`
  - 单测红灯证据：运行单测触发红灯报错：
    `OverlayPositionCalculatorTest > testSnapToRightEdgeWhenOnRightHalf FAILED: java.lang.AssertionError at OverlayPositionCalculatorTest.kt:39`
    `OverlayPositionCalculatorTest > testSnapToLeftEdgeWhenOnLeftHalf FAILED: java.lang.AssertionError at OverlayPositionCalculatorTest.kt:22`
  - 恢复全绿：恢复判定条件后运行 `./gradlew testDebugUnitTest` 重新 79 个单测全绿通过
- **审查结论**：严格遵循 WindowManager Compose 挂载生命周期铁律，算法纯粹解耦，全量单测与变异验证通过。

### Task 2.3: 悬浮窗半展开与快速交互面板 (OverlayExpandPanel)
- [x] 状态：已完成 (2026-09-20)
- **交付内容**：
  - `OverlayExpandUiState` & `OverlayExpandViewModel`：悬浮窗交互状态机，打通 `UnspokenThoughtDao`（主动破冰念头提取与自然过期清理）、`XiaoxiLlmClient`（轻量流式打字机回复）、`EmotionEngine`（即时情绪微表情同步）与 `HumannessFilter`（反客服腔清洗）
  - `IcebreakerBubble`：浮现于伴生体上方的内心独白破冰小气泡，带气泡尖角与微型忽略入口，支持点击直接代入对话
  - `OverlayExpandPanel`：半展开轻量交互面板，集成实时微表情徽章、快速流式打字机对话历史与单行发送输入框，支持一键打开全屏 `MainActivity`
  - `CompanionOverlayService` 联动：集成 `OverlayExpandViewModel`，实现 WindowManager 尺寸动态更新与根据输入框焦点动态切换 `FLAG_NOT_FOCUSABLE`
  - `OverlayExpandViewModelTest`：5 个高质量测试用例验证破冰过滤、过期抑制、念头点击代入、面板折叠展开与快速流式累加
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 84 tests completed, 0 failed, `BUILD SUCCESSFUL in 8s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s`
- **变异验证**：
  - 破坏点：故意将 `OverlayExpandViewModel.kt` 中 `refreshIcebreaker` 的未完成念头查询改坏为 `limit = 0`
  - 单测红灯证据：运行单测触发红灯报错：
    `OverlayExpandViewModelTest > testRefreshIcebreaker_filtersExpiredAndPicksTopUrgent FAILED: java.lang.AssertionError at OverlayExpandViewModelTest.kt:78`
  - 恢复全绿：恢复 `limit = 1` 后重新全量单测全绿通过
- **审查结论**：严格实现未完成念头破冰与轻量快速输入面板，交互无缝，WindowManager 穿透与焦点切换顺畅，全量单测与变异验证通过。

### Task 2.4: 进程保活与系统事件唤醒网络 (KeepAlive & Wakeup)
- [x] 状态：已完成 (2026-09-20)
- **交付内容**：
  - `KeepAliveService`：Android 14+ 规范前台保活服务，显式绑定 `FOREGROUND_SERVICE_SPECIAL_USE` 与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，创建 `IMPORTANCE_LOW` 低能耗静音通知渠道与常驻伴生状态通知，支持直达 `MainActivity` 与启动悬浮伴生体快捷操作
  - `BootReceiver`：系统唤醒广播接收器，监听 `ACTION_BOOT_COMPLETED` 与 `ACTION_MY_PACKAGE_REPLACED`，实现开机与应用版本覆盖更新后的前台伴生服务自启唤醒；在 `AndroidManifest.xml` 中完成合规静态声明
  - `BatteryOptimizationHelper`：纯 Kotlin 电池优化与厂商自启动白名单工具，支持小米 (HyperOS/MIUI)、华为 (HarmonyOS/EMUI)、OPPO (ColorOS)、vivo (OriginOS)、魅族、三星等主流定制 ROM 的电池白名单与自启动管理页探测，具备标准 Android 原生电池优化页与应用详情页的健壮优雅降级
  - `KeepAliveServiceTest`：4 个用例验证通知渠道配置、通知组件属性与 START_STICKY 服务契约
  - `BootReceiverTest`：3 个用例验证开机/包替换意图拦截与 KeepAliveService 启动
  - `BatteryOptimizationHelperTest`：4 个用例验证白名单检测逻辑与厂商 Intent 探测列表
  - `ManifestComponentsTest`：更新并验证 BootReceiver 与对应系统广播权限契约
- **测试证据**：
  - `./gradlew testDebugUnitTest`: 95 tests completed, 0 failed, `BUILD SUCCESSFUL in 8s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s`
- **变异验证**：
  - 破坏点：故意在 `BootReceiver.kt` 中注释掉对 `Intent.ACTION_BOOT_COMPLETED` 的匹配与处理
  - 单测红灯证据：运行单测触发红灯报错：
    `BootReceiverTest > testBootCompletedStartsKeepAliveService FAILED: java.lang.AssertionError at BootReceiverTest.kt:36`
  - 恢复全绿：恢复匹配逻辑后全量单测 95/95 重新 100% 全绿通过
- **审查结论**：严格对齐 Android 14+ 前台服务合规标准与系统广播规范，多厂商定制白名单自适应降级完备，全量单测与变异验证通过。

---

## 阶段验收里程碑 M2 (Phase 2 全绿达成)
- **验收时间**：2026-09-20
- **核心成果**：
  1. **Compose 全屏主界面与对话流渲染 (Task 2.1)**：
     - 打通 `ChatViewModel` 与 Phase 1 心智引擎四大中枢（`XiaoxiLlmClient` SSE 打字机流式、`DualTrackMemoryManager` 双轨二段分层召回与记忆指示徽章、`EmotionEngine` 2D 情绪实时跟随、`PhiMiddleware` 语气重锚与 `HumannessFilter` 反客服腔过滤）；
     - 沉浸式 `ChatScreen` 对话流，支持 Markdown 渲染、微表情头像伴随、心智调试面板与 SillyTavern 角色卡弹窗。
  2. **跨应用常驻伴生悬浮窗 (Task 2.2)**：
     - 严守【WindowManager 挂载 Compose 铁律】，为 `ComposeView` 正确注入 `ViewTreeLifecycleOwner`、`ServiceSavedStateRegistryOwner`、`ViewModelStoreOwner` 与 `DisposeOnDetachedFromWindow`；
     - 纯数学算法 `OverlayPositionCalculator` 交付屏幕边缘自适应吸附与拖拽弹性阻尼；
     - `OverlayBubble` 自适应渲染 16 种 Russell 情绪微表情映射与情绪光晕律动。
  3. **悬浮窗半展开与快速交互面板 (Task 2.3)**：
     - `OverlayExpandViewModel` 从 `UnspokenThoughtDao` 筛选最高紧急度未决念头，以 `IcebreakerBubble` 破冰小气泡浮现于伴生体上方，支持自然过期清理；
     - `OverlayExpandPanel` 半展开轻量面板，支持无需切屏的悬浮窗微型流式气泡对话；
     - 动态切换 WindowManager 软键盘输入焦点与手势穿透。
  4. **进程保活与系统事件唤醒网络 (Task 2.4)**：
     - Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE` 规范前台保活服务与低能耗通知渠道；
     - `BootReceiver` 开机与更新自启广播；
     - `BatteryOptimizationHelper` 主流厂商电池不优化白名单跳转工具。
- **全量测试与打包证据**：
  - `./gradlew testDebugUnitTest`: 95 tests completed, 0 failed, `BUILD SUCCESSFUL in 8s` (100% pass)
  - `./gradlew assembleDebug`: `BUILD SUCCESSFUL in 2s` (0 error, 0 warning)
- **验收结论**：Phase 2 所有 4 个任务（Task 2.1 ~ 2.4）全部通过 TDD 与变异验证，无任何 Chaquopy/Python 遗留，完全满足 100% Native 原生工业级标准，正式达成 **Phase 2 (M2 里程碑)** 全绿验收！具备随时切入 **Phase 3（获权“视奸”感知中枢落地）** 条件。

---

## Phase 3 任务拆解与对接指引 (Next Session Action Plan)

> **核心使命**：落地端侧低能耗“视奸”感知漏斗体系，实现对用户前台 App 状态、全局通知事件与关键无障碍焦点的无感感知，配备银行级隐私熔断器。

### Task 3.1: 前台应用使用追踪与上下文感知 (UsageStatsTracker & ScreenStateWatcher)
- [ ] 状态：待开始
- **交付内容**：
  - 基于 `UsageStatsManager` 的前台应用轮询与切换感知，支持 `PACKAGE_USAGE_STATS` 权限检测与跳转引导
  - `ScreenStateWatcher`：监听屏幕亮灭（`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`）与解锁（`ACTION_USER_PRESENT`），精准统计用户设备活跃/空闲时长

### Task 3.2: 全局通知捕获与低能耗关键信息过滤 (XiaoxiNotificationListenerService & NotificationFilter)
- [ ] 状态：待开始
- **交付内容**：
  - 完善 `XiaoxiNotificationListenerService`，捕获系统 `StatusBarNotification`
  - 落地 `NotificationFilter`（过滤系统常驻、下载进度、已读静音，提取社交通讯/日程关键有效通知）

### Task 3.3: 无障碍前台节点轻量抽取与焦点感知 (XiaoxiAccessibilityService Node Extractor)
- [ ] 状态：待开始
- **交付内容**：
  - 完善 `XiaoxiAccessibilityService`，实现基于 `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` 与 `TYPE_VIEW_FOCUSED` 的轻量文本提取
  - 深度限高（树深度 <= 4）与字符限长截断，彻底避免全量 dump 造成的卡顿与高功耗

### Task 3.4: 隐私敏感熔断与感知流水线整合 (PerceptionPipeline & PrivacyBreaker)
- [ ] 状态：待开始
- **交付内容**：
  - `PrivacyBreaker`：银行、支付、密码输入框、隐私相册硬编码包名黑名单与无障碍密码框自动熔断清空
  - `PerceptionPipeline`：汇总前台 App、通知事件、屏幕状态与输入框焦点，汇入事件流并驱动情绪发动机与未完成念头生成

---

## 新窗口极速交接备忘录 (Quick Start For Next Agent)

1. **环境与命令**：
   - 运行全量单测：`./gradlew testDebugUnitTest`（当前全绿，95 tests passed）
   - 验证打包：`./gradlew assembleDebug`（当前 100% 成功，0 错误 0 警告）
   - Android SDK 路径位于 `local.properties`（`C:\Users\Lenovo\AppData\Local\Android\Sdk`）
   - Java 21 环境，Gradle 8.10.2
2. **严守铁律**：
   - **绝不使用 Chaquopy**：100% Native Kotlin + Coroutines + Room + Compose。
   - **TDD 先行**：先写测试 → 确认红灯 → 编码跑绿 → 故意改坏破坏分支做**变异验证**（确认变红） → 恢复全绿 → 原子提交。
   - **代码编写前必读局部方案**：严禁写 Dummy Code。
3. **最新 Git 状态**：
   - 当前工作区分支：`master`。
   - 最新进展：Phase 2 全绿验收达成（M2 里程碑），95 个单测 100% 通过。
4. **接手第一动作**：
   - 阅读 `docs/PROGRESS_TRACKER.md` 中 Task 3.1 规范。
   - 启动 Phase 3：落地 Task 3.1 前台应用使用追踪与屏幕状态监听。

