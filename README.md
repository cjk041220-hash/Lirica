# 🌌 小汐 2.0（Project Aether）

> **Android 原生自主人格伴生智能体（Autonomous On-Device Companion & OS Agent）**  
> 拥有独立灵魂、环境感知（获权视奸）与手机操控力的下一代移动端伴生 AI。

---

## 📖 项目概述

小汐 2.0 经历了从单一情感纪念物向**通用移动端伴生智能体**的战略重构。底层全面拥抱 Android 原生现代化技术栈（Kotlin + Jetpack Compose），告别传统的 Web 抽屉和被动聊天框。

### 四大核心能力
1. **自由自主人格（Customizable Persona）**：原生全兼容 **SillyTavern（酒馆）角色卡（V1/V2/V3）** 导入导出，内置 PHI 语气重锚与 8 轮抗漂移引擎，角色长聊不崩。
2. **获权情境感知（Ambient Sensing）**：在用户授权下，常驻感知前台应用、停留时长、屏幕文本结构与第三方系统通知，如同趴在屏幕边的伴侣。
3. **真实手机操控（Device Manipulation）**：结合 Android 无障碍服务（`AccessibilityService`）实现原子手势模拟与跨应用自动化代行；视觉定位与穿透执行在路线图中（见能力状态表）。
4. **自主生活与主动性（Proactive Living）**：具备“关于自己”的独立记忆轨（Self-Track），闲时自主上网阅读趣闻，结合情境感知在合适时机主动搭话与吐槽，异常感知关心（深夜/久未出现）带冷却台账。
5. **本地优先与隐私安全（Local-First）**：无中心化服务器，所有记忆与屏幕数据端侧闭环，具备金融与密码框物理熔断防护。用户自备 Key（BYOK）为 experimental 能力，尚未开放配置入口。

### 📊 能力状态标注表

> 规则：**每个对外宣称的能力必须逐条对照代码**，标注"已实现 / 实验中 / 规划中"。
> 此表因两次"文档宣称 vs 代码现实"偏差（BYOK、视觉定位）而设，修订需同步给出代码出处。

| 能力 | 状态 | 代码出处 |
|:--|:--|:--|
| SillyTavern 角色卡 V1/V2/V3 导入导出 | ✅ 已实现 | `persona/CharacterCardDecoder.kt` |
| PHI 语气重锚 / 8 轮抗漂移 | ✅ 已实现 | `persona/PhiMiddleware.kt`、`persona/DriftShieldScheduler.kt` |
| 工具调用态动作伴随语契约 | ✅ 已实现 | `persona/PhiMiddleware.kt`（TOOL_PERSONA_CONTRACT） |
| 无障碍轻量读屏 + 前台/通知感知 | ✅ 已实现 | `perception/*`（`XiaoxiAccessibilityService`、`UsageStatsTracker`、`XiaoxiNotificationListenerService`） |
| 感知文本隐私熔断（金融/密码框物理断流） | ✅ 已实现 | `perception/PrivacyBreaker.kt`（覆盖文本节点与截屏断流；截图像素级脱敏为规划中） |
| 无障碍手势代行（点击/滑动/文本注入） | ✅ 已实现 | `automation/gesture/*`、`automation/coordinator/HelixGuiCoordinator.kt` |
| 混合接地（无障碍树优先 → ML Kit 离线 OCR） | ✅ 已实现 | `automation/grounding/HybridGroundingEngine.kt`、`MlKitVisualDetector.kt`、`ScreenCaptureManager.kt` |
| 端侧静默截屏与文本提取（read_screen / 离线 OCR） | ✅ 已实现 | `automation/capture/ScreenCaptureManager.kt`、`automation/grounding/MlKitVisualDetector.kt` |
| 多模态视觉定位（AutoGLM / UI-TARS / 云端 VLM） | 🗺️ 规划中 | 见《主方案 3.0》M3.2，当前主要依靠无障碍与端侧 ML Kit OCR |
| Shizuku 穿透执行 / 动作分块宏 (execute_macro) | ✅ 已实现 | `automation/backend/*`（`ShizukuExecutionBackend`、`HybridExecutionManager`）、`NativeToolAgent.kt` |
| 真实应用包名解析与别名索引 (AppPackageResolver) | ✅ 已实现 | `automation/agent/AppPackageResolver.kt`、`AndroidManifest.xml` (`<queries>`) |
| 天气查询（Open-Meteo，免 Key） | ✅ 已实现 | `life/WeatherTool.kt` |
| 日程提醒（create_event / list_events） | ✅ 已实现 | `life/EventLifeTool.kt`（落 `scheduled_alarms` + 系统闹钟） |
| 异常感知关心（深夜/考前熬夜/久未出现） | ✅ 已实现 | `life/CareHintDetector.kt` + `life/CareLedger.kt`（冷却台账） |
| 网页搜索 / 地图 / 塔罗 / 生理周期 | 🗺️ 规划中 | backend 参考源有实现（`backend/tools/*`），Android 侧未迁移 |
| BYOK 多模型网关（OpenAI 兼容 / Anthropic） | 🗺️ 规划中 | 当前主链路直连 DeepSeek（`network/XiaoxiLlmClient.kt`）；`ByokSettingsRepository` 为实验中未接线 |
| 端侧向量检索（语义记忆召回） | 🗺️ 不立项 | 按评审意见改为触发器制（见《评审意见——主方案 3.0 补充改进清单》第七节 A） |

---

## 🗺️ 架构与演进路线

2.0 基础重构已完成（R8 混淆启用、工具闭环装配、365 项单测基线全绿），当前处于 **3.0 系统级演进阶段**（BYOK 网关 / 视觉感知 / 操控升维 / 人格深水区）：
* **核心开发蓝图**：详见 [`主方案3.0——原生自主人格伴生智能体第三轮系统级架构与演进方案_2026-09-22.md`](./docs/主方案3.0——原生自主人格伴生智能体第三轮系统级架构与演进方案_2026-09-22.md)
* **执行规划**：按《评审意见——主方案 3.0 补充改进清单》第八节修订路线执行：M3.1 BYOK 网关 → M3.0 活人感三件套 → M3.2 视觉感知与观测闭环 → M3.3 操控升维 → M3.4 人格深水区 → M3.6 生活工具生态（可选）

---

## 📂 项目结构（2.0 规划中）

```
xiaoxi-android/
├── 小汐2.0开发计划书_原生自主人格伴生智能体_2026-09-19.md  # 2.0 终极开发大纲与架构规格
├── backend/                                            # 原核心算法与心智逻辑参考源（待原生迁移）
│   ├── agent/soul.py                                   # 语气锚定、反 AI 腔意象过滤
│   ├── heartbeat_moods.py                              # Russell 效价-唤醒度情绪动力学
│   ├── memory/                                         # 记忆衰减评分、矛盾覆盖、自叙生成
│   └── tests/                                          # 核心心智与记忆回归测试套件
└── app/                                                # [Phase 0 启动] Android 原生工程（Kotlin + Compose）
```

---

## 🔑 API Key 配置（构建必需）

自 2026-09-21 起，缺失 `DEEPSEEK_API_KEY` 时构建会**直接失败**（不再回退占位符，防止占位 Key 烘进 APK）。三种配置方式，按优先级：

1. **本机开发（推荐）**：项目根目录 `local.properties` 添加一行（该文件不入库）：
   ```
   DEEPSEEK_API_KEY=sk-xxxxxxxx
   ```
2. **命令行临时传参**：`./gradlew assembleDebug -PDEEPSEEK_API_KEY=sk-xxxxxxxx`
3. **CI / 环境变量**：设置同名环境变量 `DEEPSEEK_API_KEY`。

> 注意：Key 会编译期注入 `BuildConfig` 并随 APK 分发，**请勿将打出的 APK 分发给他人**；运行时自备 Key（BYOK）能力见上方核心能力第 5 条（experimental）。

---

## 🛡️ 隐私与安全底线

- **无云端中继**：除用户自设的大模型 API 供应商外，应用零数据上传。
- **敏感区域物理切断**：检测到输入法密码框或金融支付类 App 时，感知服务自动物理断流，绝不记录任何敏感信息。
