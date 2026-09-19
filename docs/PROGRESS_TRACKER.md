# 小汐 2.0 (Project Aether) 开发进度看板 (Progress Tracker)

> **当前阶段**：Phase 0 · 新基建确立与通信打通  
> **最后更新**：2026-09-19  
> **整体技术栈**：100% Native Kotlin + Jetpack Compose + Ktor SSE + Room SQLite + Coroutines

---

## 阶段规划总览

| 阶段 | 核心目标 | 状态 | 进度 |
|:---|:---|:---:|:---:|
| **Phase 0** | 新基建确立与通信打通 (Native骨架 / 权限声明 / Ktor BYOK客户端 / ST角色卡解码) | **已全绿验收** | 4/4 |
| **Phase 1** | 心智引擎与记忆系统原生化 (Russell情绪 / 双轨记忆 / PHI重锚 / 8轮防漂移) | **待启动** | 0/4 |
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
