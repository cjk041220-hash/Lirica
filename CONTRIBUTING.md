# Lirica (Project Aether) 团队协作与贡献指南

欢迎加入 **Lirica** 的开发！  
Lirica 是一个运行在 Android 原生端侧的自主人格伴生智能体（Autonomous On-Device Companion & OS Agent）。为了让多人协作顺畅高效、杜绝代码冲突与暗病引入，请在开工前花 5 分钟阅读本指南。

---

## 目录
1. [技术栈与架构基线](#1-技术栈与架构基线)
2. [本地开发环境准备](#2-本地开发环境准备)
3. [进度对齐与任务认领（唯一真相源）](#3-进度对齐与任务认领唯一真相源)
4. [Git 分支与协作工作流](#4-git-分支与协作工作流)
5. [团队核心军规（五条红线）](#5-团队核心军规五条红线)
6. [代码模块速查（功能写在哪里）](#6-代码模块速查功能写在哪里)
7. [合并请求 (PR) 检查清单](#7-合并请求-pr-检查清单)

---

## 1. 技术栈与架构基线

- **开发语言**：100% Native Kotlin（协程 Flow / StateFlow / 挂起函数）。
- **界面层**：Jetpack Compose + Material 3（声明式 UI，主界面 + 跨应用常驻悬浮窗）。
- **数据持久化**：Room SQLite（`lirica_aether.db`，双轨记忆库、未完成念头、会话历史）。
- **网络通信**：Ktor Client（SSE 增量流式解析 + BYOK 多模型适配网关）。
- **后台守护**：Android 前台服务 (`KeepAliveService`, `specialUse`) + WorkManager。
- **自动化底座**：AccessibilityService（拟人手势/读屏）+ Shizuku（ADB 特权手势/按键穿透）。
- **视觉感知**：Google ML Kit 中文离线 OCR + 归一化坐标接地。

> ⚠️ **注意**：本项目无任何 Python / Web 前端 / Chaquopy 依赖，为纯粹的 Android 原生工程。

---

## 2. 本地开发环境准备

### 2.1 依赖环境
- Android Studio Ladybug (2024.2+) 或更高版本。
- JDK 17（推荐 Azul Zulu 或 Android Studio 自带 JBR 17）。
- Android SDK 34 (minSdk 26, targetSdk 34)。

### 2.2 本地密钥配置
项目在未配置 API Key 时允许正常构建，但为了运行主聊天链路，请在项目根目录创建 `local.properties`（该文件已入 `.gitignore`，**绝对不要提交**）：
```properties
# 填写你的有效模型 Key（支持 DeepSeek / OpenAI 兼容中继）
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 2.3 验证环境可用性
克隆仓库后，先在命令行运行两道质检：
```bash
./gradlew testDebugUnitTest  # 运行全量单元测试（当前基线 516 项全绿）
./gradlew assembleDebug      # 验证 APK 编译打包正常
```
两项均通过（`BUILD SUCCESSFUL`）即代表本地环境完全就绪。

---

## 3. 进度对齐与任务认领（唯一真相源）

为了避免两人改了同一个模块产生严重代码冲突，我们以 **`docs/PROGRESS_TRACKER.md`** 作为唯一的进度对齐看板。

1. **认领任务**：在开工前，先在 `docs/PROGRESS_TRACKER.md` 对应任务后注明认领人与日期，如：
   ```markdown
   - [ ] T1.8 Onboarding 权限引导向导 (认领: @伙伴名, 2026-09-24)
   ```
2. **大动作提前打招呼**：若需修改核心全局编排器（如 `HelixGuiCoordinator`、`ChatViewModel`、`PhiMiddleware`），请务必在沟通群里提前同步，避免并行开发造成合并死锁。
3. **完成验收**：功能自测全绿后，在提交 PR 时将看板勾选为已完成，并更新单测总数。

---

## 4. Git 分支与协作工作流

仓库采用清晰的 **分支 + Pull Request (PR)** 机制，**严禁直接推送代码到 `master` 分支**。

```
master (受保护基线，永远保持可随时发版打包与全绿状态)
  └── feat/m3x-xxx 或 fix/xxx (伙伴的特性分支)
```

### 标准协作四步法：

#### 第一步：从最新 master 创建分支
```bash
git checkout master
git pull origin master
git checkout -b feat/m34-hardware-bus  # 分支命名规范：feat/功能名 或 fix/缺陷名
```

#### 第二步：遵循 TDD 模式开发
- 先明确输入输出，补齐对应单元测试（推荐放在 `app/src/test/kotlin/`）。
- 编写业务实现，直至测试变绿。

#### 第三步：提交前本地三重核验
在执行 `git push` 前，必须按顺序执行以下三步：
```bash
# 1. 确保所有单测全绿（不能少于既有 516 项基线）
./gradlew testDebugUnitTest

# 2. 确保 APK 打包没有隐式资源/配置故障
./gradlew assembleDebug

# 3. 垫平历史，消除分叉
git fetch origin master
git rebase origin/master
```

#### 第四步：推送并提 PR
```bash
git push -u origin feat/m34-hardware-bus
```
提交信息推荐采用 Conventional Commits 格式：
- `feat(模块)`: 新增功能
- `fix(模块)`: 修复缺陷
- `test(模块)`: 补充测试
- `refactor(模块)`: 代码重构（不改变外部行为）

---

## 5. 团队核心军规（五条红线）

这五条红线是前期演进中踩坑沉淀下来的**底线原则**，任何违反的代码均不允许合入：

### 🔴 红线一：516 项测试基线全绿
- 任何时候，合入 master 的代码单测必须 100% 通过（当前基线 516 tests，0 failures）。
- “谁搞红谁自愈”：绝不允许带着测试报错提 PR 或把测试故意 `@Ignore` 绕过。

### 🔴 红线二：100% 零信任物理隐私熔断
- 伴生体拥有轻量读屏与自动化能力，安全是第一生命线。
- 任何感知与操控动作，必须通过 `PrivacyBreaker` 与 `FinancialGatekeeper` 双闸门。
- **物理断流原则**：当处于金融支付、银行账户、密码输入法等黑名单应用时，感知截屏与文字提取必须直接返回 `null`，手势注入物理切断，绝不允许将用户敏感隐私输入大模型。

### 🔴 红线三：中文 / Emoji 文本输入分流铁律
- Shizuku 底层 Linux 的 `input text` 命令不支持 CJK 中文字符与 Emoji 符号。
- **分流铁律**：包含汉字或 Emoji 的输入，**必须 100% 路由至无障碍通道**（通过 `ACTION_SET_TEXT` 或剪贴板快照安全还原）；Shizuku 仅用于纯 ASCII 字符、快捷键与物理手势。

### 🔴 红线四：“宣称即代码”，性能数字不凭空写
- 文档中宣称的能力（`README.md` 能力标注表），必须严格对照代码与单测，禁止“提前宣称未写完的能力”。
- 凡未在真实目标机型上实测的性能数据（如延时、内存占用），一律标注“实测待后补”，绝不凭空脑补数字。

### 🔴 红线五：本地密钥与私密数据绝不上库
- 个人测试 Key、API 令牌、签名证书密码，一律放在本地 `local.properties`。
- 绝不允许将测试用真实 Key 写进常量、BuildConfig 或代码注释中提交至远端仓库。

---

## 6. 代码模块速查（功能写在哪里）

代码位于 `app/src/main/kotlin/com/xiaoxi/android/` 下，职责分层非常明确：

| 包名 | 职责说明 | 典型核心类 |
|:---|:---|:---|
| `automation/` | 手机操控、手势代行、宏动作分块、混合接地、金融守门与自愈看门狗 | `HelixGuiCoordinator`, `NativeToolAgent`, `HybridGroundingEngine`, `ShizukuExecutionBackend` |
| `perception/` | 轻量环境感知、无障碍读屏、通知过滤、前台应用追踪、隐私断流器 | `XiaoxiAccessibilityService`, `PrivacyBreaker`, `UsageStatsTracker` |
| `persona/` | 人格心智、PHI 语气重锚、8 轮抗漂移调度、反客服腔过滤器、酒馆角色卡解码 | `PhiMiddleware`, `DriftShieldScheduler`, `HumannessFilter`, `CharacterCardDecoder` |
| `emotion/` | Russell 效价-唤醒度 (Valence-Arousal) 二维连续情绪动力学状态机 | `EmotionEngine`, `MoodState` |
| `database/` | Room SQLite 数据访问对象与实体、双轨记忆打分与召回 | `XiaoxiDatabase`, `DualTrackMemoryManager`, `MemoryScorer` |
| `memory/` | 夜间潜意识入梦、记忆去重、时间绝对化、信念修正与自叙经历沉淀 | `SubconsciousDreamCoordinator`, `BeliefRevisionEngine`, `DreamEmergenceSynthesizer` |
| `life/` | 生活工具生态（免 Key 天气查询、系统日程提醒约定、考前深夜关心感知） | `WeatherTool`, `EventLifeTool`, `CareHintDetector`, `CareLedger` |
| `network/` | 大模型通信、BYOK 多模型网关（OpenAI 兼容 / Anthropic）、流式 SSE 解码 | `XiaoxiLlmGateway`, `XiaoxiLlmClient`, `SecureKeyStore` |
| `ui/` | Jetpack Compose 表现层：全屏聊天、Onboarding 权限向导、模型设置页 | `ChatScreen`, `OnboardingScreen`, `SettingsScreen` |
| `overlay/` | 跨应用常驻悬浮伴生体、情绪呼吸光晕、破冰微气泡、代行状态卡片 | `CompanionOverlayService`, `OverlayBubble`, `OverlayExpandPanel`, `IcebreakerBubble` |

---

## 7. 合并请求 (PR) 检查清单

在点击提交 PR 前，请自行核对以下项目：

- [ ] 本地运行 `./gradlew testDebugUnitTest` 全绿通过（当前基线 516 项）。
- [ ] 本地运行 `./gradlew assembleDebug` 打包成功。
- [ ] 针对新增功能或 Bug 修复补充了对应的单元测试。
- [ ] 未改动包名 `com.xiaoxi.android` 与数据库名 `lirica_aether.db`。
- [ ] 没有将个人 API Key 或敏感文件引入 Git 追踪。
- [ ] 涉及 UI 文本和角色提示词时，使用新产品名 **Lirica**。
- [ ] 同步更新了 `docs/PROGRESS_TRACKER.md` 的对应任务状态。
