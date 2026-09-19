# 小汐 2.0 (Project Aether) 开发进度看板 (Progress Tracker)

> **当前阶段**：Phase 0 · 新基建确立与通信打通  
> **最后更新**：2026-09-19  
> **整体技术栈**：100% Native Kotlin + Jetpack Compose + Ktor SSE + Room SQLite + Coroutines

---

## 阶段规划总览

| 阶段 | 核心目标 | 状态 | 进度 |
|:---|:---|:---:|:---:|
| **Phase 0** | 新基建确立与通信打通 (Native骨架 / 权限声明 / Ktor BYOK客户端 / ST角色卡解码) | **进行中** | 0/4 |
| **Phase 1** | 心智引擎与记忆系统原生化 (Russell情绪 / 双轨记忆 / PHI重锚 / 8轮防漂移) | 待启动 | 0/4 |
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
- [ ] 状态：待开始
- **交付内容**：
  - Ktor HTTP Client 配置 (ContentNegotiation, CIO/OkHttp engine, SSE plugin / parsing)
  - BYOK (Bring Your Own Key) 配置数据结构与多端点支持 (OpenAI / DeepSeek / Claude / 自定义)
  - DeepSeek 参数清洗逻辑 (强制清洗 `top_p=0` 陷阱，默认 temp 0.95, presence_penalty 0.25)
  - 流式 SSE 解析器 (支持 Token 级流式吐出、Tool Calls 增量组装与错误恢复)
  - 100% 单元测试覆盖
- **测试证据**：待执行
- **变异验证**：待执行
- **审查结论**：待审查

### Task 0.4: 标准 SillyTavern V2/V3 PNG 角色卡元数据二进制解码器
- [ ] 状态：待开始
- **交付内容**：
  - 纯 Kotlin 二进制 PNG 解析器 (定位与解析 PNG `tEXt` / `zTXt` / `iTXt` 数据块)
  - Base64 解码与 JSON 解析 (兼容 V2 `chara` 与 V3 `ccv3` 规范)
  - 字段提取与缺省降级 (`system_prompt`, `post_history_instructions`, `character_book`, 基础属性)
  - 100% 单元测试覆盖与真实/合成 PNG 卡二进制测试用例
- **测试证据**：待执行
- **变异验证**：待执行
- **审查结论**：待审查
