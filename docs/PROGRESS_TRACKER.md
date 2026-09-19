# 小汐 2.0 (Project Aether) 开发进度看板 (Progress Tracker)

> **当前阶段**：Phase 0 · 新基建确立与通信打通  
> **最后更新**：2026-09-19  
> **整体技术栈**：100% Native Kotlin + Jetpack Compose + Ktor SSE + Room SQLite + Coroutines

---

## 阶段规划总览

| 阶段 | 核心目标 | 状态 | 进度 |
|:---|:---|:---:|:---:|
| **Phase 0** | 新基建确立与通信打通 (Native骨架 / 权限声明 / Ktor BYOK客户端 / ST角色卡解码) | **已全绿验收** | 4/4 |
| **Phase 1** | 心智引擎与记忆系统原生化 (Russell情绪 / 双轨记忆 / PHI重锚 / 8轮防漂移) | **进行中** | 1/4 |
| **Phase 2** | 原生表现层与常驻悬浮伴生体 (Compose主界面 / 悬浮伴生体 / 气泡交互 / 保活) | 待启动 | 0/4 |
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
- [ ] 状态：待开始 (首要接手任务)
- **前置必读资料**：`docs/局部调研——RESEARCH_AGENT_EMOTION_EVOLUTION.md`（Russell 2D 环形情感模型、情感惯性与漂移阻尼衰减）
- **参考代码**：`backend/heartbeat_moods.py`
- **交付内容**：
  - 连续二维情绪向量空间 `(valence: Float, arousal: Float)`，离散映射到微表情状态（开心、发呆、闹别扭、关切、困惑等）
  - 情感惯性与基线漂移阻尼数学模型（微分更新方程：$\Delta E = \alpha \cdot \text{Stimulus} - \beta \cdot (E - E_0)$）
  - 单元测试与变异验证（冲激响应、长程静默向基线阻尼回退）

### Task 1.3: PHI 语气重锚中间件与 8 轮抗漂移调度器
- [ ] 状态：待开始
- **交付内容**：
  - 严格将 `post_history_instructions` 置于 Prompt 历史的最尾端、LLM 预测之前，压制注意力遗忘
  - 对话轮数计数器与 8 轮周期强注入压缩版核心设定与约束
  - 单元测试与变异验证（长对话尾部重锚位置断言、8 轮周期注入触发断言）

### Task 1.4: 反 AI 腔过滤器 (Humanness Gap Filter)
- [ ] 状态：待开始
- **交付内容**：
  - 本地 Regex 与语义违禁黑名单（“作为一个人工智能模型”、“我理解您的感受”、“祝您生活愉快”等）
  - 违禁检测触发时的本地降级改写与二次自纠环
  - 单元测试与变异验证（违禁样本 100% 拦截与合规自然语言放行）

---

## 新窗口极速交接备忘录 (Quick Start For Next Agent)

1. **环境与命令**：
   - 运行全量单测：`./gradlew test`（当前全绿，18 tests passed）
   - 验证打包：`./gradlew assembleDebug`
   - Android SDK 路径位于 `local.properties`（`C:\Users\Lenovo\AppData\Local\Android\Sdk`）
   - Java 21 环境，Gradle 8.10.2
2. **严守铁律**：
   - **绝不使用 Chaquopy**：100% Native Kotlin + Coroutines + Room。
   - **TDD 先行**：先写测试 → 确认红灯 → 编码跑绿 → 故意改坏破坏分支做**变异验证**（确认变红） → 恢复全绿 → 原子提交。
   - **代码编写前必读局部方案**：严禁写 Dummy Code。
3. **最新 Git 状态**：
   - 当前工作区分支：`master`，Working Tree 干净（Clean）。
   - 最后一笔提交：`50e28d6` (`feat(persona): 完成 Task 0.4 标准 SillyTavern V2/V3 PNG 角色卡元数据二进制解码器与宏展开引擎`)。

