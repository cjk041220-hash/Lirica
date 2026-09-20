# 外部项目借鉴评估：AcidGr / agent-mobile-use（Android 虚拟副屏无感后台控制底座）

> **评估日期**：2026-09-20
> **评估对象**：`E:\sonetto-android`（小汐 2.0 / Project Aether，Phase 4 已达成，Phase 5「手机操控与任务代行」待启动）
> **被评估外部项目**：https://github.com/AcidGr/agent-mobile-use
> **评估方式**：仓库元数据与源码实测（README / `ksu-module/system/bin/vd` / `vd-tool-java/src/com/agent/ToolMain.java`）+ 官方平台文档核验 + 与你现有代码的逐条对照

---

## 0. 结论先行

1. **值得借鉴，但只借「工程细节」，不借「架构」。** 它那条「虚拟副屏做执行沙箱」的路，技术上必须靠 KernelSU root + LSPosed hook `system_server` 绕过 Android 的跨屏限制；这条路既**交付不到她的手机上**，也**不应该**交付到一台装着支付与聊天 App 的日常机里（见 §2 与 §4）。

2. **真正能直接抄的是 5 个工程细节**（按价值排序）：① 截帧与缩放＝坐标一致性纪律；② 树 dump 的两轮自愈 + 预算排序 + 显式截断元数据；③ 节点预算的实测数量证据；④ 两条平台硬事实（MediaProjection 每次会话都要用户同意、状态栏常驻投射提示）；⑤ 「枚举即必要的 ExecutionNodeTree」——反过来暴露了你现在的一个 P0。

3. **你现在有一个比「要不要上副屏」更前置的 P0**：感知侧抽出来的树**没有坐标**，而接地引擎**依赖坐标**，两者之间没有转换层。这条不修，Phase 5 后面所有执行链路都是空转。详见 §3.1。

---

## 1. 事实核查：这个项目到底是什么

### 1.1 仓库元数据（实测，2026-09-20）

| 项 | 实测值 |
|:---|:---|
| 仓库 | `AcidGr/agent-mobile-use` |
| 描述 | Industrial-grade headless virtual display & background Mobile Use foundation for Android |
| 主语言 / 协议 | Java（+ Go 写的 `vd_server`） / **MIT** |
| Star / Fork | **25 / 4** |
| 创建 / 最后推送 | **2026-09-17 / 2026-09-20**（创建仅 3 天） |
| 实验环境 | **仅 ColorOS 16 / Android 16（内核 6.12）+ KernelSU + LSPosed**，真机单一 envidada |

### 1.2 它的真实实现方式（读源码，不是读 README）

README 把它包装成一套「工业级底座」，但把 `vd` 脚本和 `ToolMain.java` 读完之后，真实结构是**一层很薄的 shell 包装**：

| 命令 | 真实底层调用 | 出处 |
|:---|:---|:---|
| `vd tap <x> <y>` | `input -d <did> tap <x> <y>` | `ksu-module/system/bin/vd` 的 `tap)` 分支 |
| `vd swipe ...` | `input -d <did> swipe x1 y1 x2 y2 [dur]` | 同上 `swipe)` 分支 |
| `vd key <keycode>` | `input -d <did> keyevent <code>` | 同上 `key)` 分支 |
| `vd screenshot` | `screencap -d <sf_id> -p <path>` | 同上 `screenshot)` 分支 |
| `vd launch <pkg>` | `am start -n <act> --display <did>` | 同上 `launch_app()` |
| `vd tree` | `app_process` + 反射 `UiAutomation` 递归抓树，输出 JSON | `ToolMain.java` 的 `dumpTree()` |
| `vd type "<文本>"` | **剪贴板 `setPrimaryClip` + `input -d <did> keyevent 279`（PASTE）** | `ToolMain.java` 的 `injectType()` |
| （README 未列的隐藏命令）`clicknode` | `AccessibilityNodeInfo.performAction(ACTION_CLICK)` | `ToolMain.java` 的 `clickNode()` |

**关键认知**：所谓「工业级」，本质 = **root shell 权限下把 ADB 能干的事搬到第二块屏**。它没有碰 `InputManager.injectInputEvent` 反射，没有自研注入协议；`tap/swipe/key` 全部是标准的 `input -d`。「105 步手绘」「0.53 秒注入多段动作」的性能，来自 **root shell 里循环 spawn `input` 进程**，不是某种算法优势——而这条路径你用无障碍 `dispatchGesture` 大概率**更快也更省电**（无进程创建开销，见 §3.5）。

### 1.3 未核实 / 未查到（不补看起来合理的数字）

- **未逐行验证** `vd-server-go/main.go` 的 JPEG 帧缓存实现，README 中的性能数字（仅采信但标记为「作者自述」）。
- **「0.53 秒极速注入多段动作」未在 README 与源码中找到出处**，目前只能判定为**视频口径**。
- **`input -d <displayId>` 的最低支持 Android 版本未查证**（不确定是否需 API 29+）。
- 作者自己在 README 的「兼容性」一节承认：微信一类第三方加固 App 在**没有真实物理触摸板的虚拟副屏**上，系统会压制无障碍节点生成（`UiAutomation` 拿到空树），只能退回「截屏 + 视觉推理」这条路。这是这类架构的固有边界，不是可以被绕过去的问题。

---

## 2. 架构层：为什么「虚拟副屏做执行沙箱」这条路你不能走

### 2.1 平台限制是硬性的（有官方文档）

- AOSP 官方多屏文档明确写道：*"apps can launch **their own activities only** on a virtual display of their creation in Android 10"*；系统放行只有 `INTERNAL_SYSTEM_WINDOW` 或 `ACTIVITY_EMBEDDING` 两条特权路径。
  https://source.android.google.cn/docs/core/display/multi_display/activity-launch
- `ActivityOptions.setLaunchDisplayId()` 官方 API 文档同样写明：在私有 display 上启动会抛异常。
  https://developer.android.com/reference/android/app/ActivityOptions

**推论**：要让「美团 / 微信」这类**第三方 App**渲染到你自建的隐藏屏上，**必须绕过系统限制**——这就是它必须 KernelSU + LSPosed hook `ActivityTaskSupervisor` / `ActivityRecord` 的根因。**没有任何免 root 的捷径能搞定第三方 App。**

### 2.2 三条否决理由

| # | 理由 | 说明 |
|:---|:---|:---|
| 1 | **交付不可能** | 小汐要装在她日常用的手机上。国产机 bootloader 基本锁死，root 既不可能也不该做。 |
| 2 | **破坏目标场景** | 一旦 root + hook `system_server`，银行、支付宝、微信支付普遍会触发风控（或直接拒绝运行）。而「替她办事」这个愿景里，恰恰是这些 App 最有价值。等于为了解决一个前提，放弃全部收益。 |
| 3 | **维护成本不可持续** | 它必须为每个 ROM 单独改 `BOOTCLASSPATH`（README 自己承认 OPPO 专属路径 `oplus-framework.jar` 绑死）。你要适配的是**她那一台机器**，不是 OPPO 一台机器。 |

### 2.3 那条「免 root」的中间路线值不值得试

存在一条参照路线：**无线 ADB 配对拿到 shell（uid 2000）权限**，OpenCyvis 这类项目就是这么做的（`PrivilegeBackend` 抽象：`RemoteBackend` 走 ADB shell，`SystemBackend` 走 platform 签名）。

**判断**：可以在**你自己的开发机**上试，用来验证算法、采集样本；**不要写进产品**。原因是：无线调试需要在「开发者选项 → 无线调试」里拿 6 位配对码，且 Android 在若干场景下会重置会话；让一个非技术用户每隔几周重配一次，产品体验直接崩。

---

## 3. 工程细节层：5 件可以直接抄的事（对你现有代码）

> 所有对你代码的引用均为 `file:line` 实测。

### 3.1 【P0】你的感知树没有坐标，但接地引擎要坐标（断层）

这是本次评估最需要先修的一条，与外部项目无关，是它启发我发现的。

| 位置 | 现状 |
|:---|:---|
| `app/src/main/kotlin/com/xiaoxi/android/perception/model/AccessibilityModels.kt:17-27` | `AccessibilityNodeSnapshot` 只有 `text / contentDescription / className / viewId / isClickable / isEditable / isPassword / isFocused / depth`，**没有 bounds 字段** |
| `app/src/main/kotlin/com/xiaoxi/android/perception/NodeExtractor.kt:53-57,97-119` | 提取时不取 `getBoundsInScreen`，预算 `MAX_DEPTH=4 / MAX_NODES=64 / MAX_CHAR=128` |
| `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/GroundingModels.kt:19-27` | `UiElementNode` **必须**有 `bounds: ElementBounds` |
| `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/GroundingModels.kt:41-47` | `toScreenPoint(screenWidth, screenHeight)` 依赖真实屏幕宽高做反归一化 |
| 全 `main` 源码 grep | **没有任何一处**做 `AccessibilityNodeSnapshot → UiElementNode` 的转换 |

**后果**：`HybridGroundingEngine.locate()` 的「L0 快路径」（`ACCESSIBILITY_EXACT`，confidence 0.95）目前**拿不到可执行坐标**，只能退化到视觉 VLM（更慢、更贵、更不稳）。你方案里「≤750ms L0 快路径」那条验收线，在数据结构上就不成立。

**建议动作**：新增独立的执行态数据契约（不要改 `AccessbilityNodeSnapshot`，它是给低能耗感知用的），例如 `ExecutionNodeTree(nodeId, text, desc, viewId, className, bounds, clickable, editable, checked, scrollable, enabled, visible, priority)` + `ExecutionTreeExtractor`（预算/去重/排序区别于 `NodeExtractor`），再写 `toUiElementNodes()` 单测。先把 L0 快路径接通，比堆任何高级能力都值钱。

### 3.2 【P1】截帧管线：JPEG + 缓存最近一帧 + 绝不预降采样

它的三条经验，有两条和你的方案（docs/局部调研——移动端GUI智能体 第 523-525 行已规划 `MediaProjection + ImageReader`，目标 20~35ms）方向一致，可以直接采信：

1. **PNG 编码是延迟主因**（作者实测自述）：`screencap -p` 全分辨率 PNG 约 **1.8 秒 CPU / 2.9MB**；守护进程持有输出 Surface 直接编 JPEG 约 **18ms / 270KB**，端到端 2520ms → 63ms。**结论**：喂给 VLM 的一律走 JPEG quality≈80，别走 PNG。
2. **绝对不要预先降采样**。它踩过坑并写进 README 原文：

   > ⚠️ JPEG 必须保持副屏全分辨率：截图工具是用返回图片的像素尺寸去推算投递给视觉模型时的缩放比例的，一旦这里预降采样，工具就会告诉模型一个错误的比例，导致点击坐标整体偏移。

   这条对你是**通用纪律**：`frame.width/height` 必须和图一起走完整个链路，`NormalizedRect.toScreenPoint()` 的输入必须来自**同一张被 VLM 看到的那张图**的像素尺寸，而不是 `resources.displayMetrics`。你自己方案里「不同机型/开启显示缩放时像素映射非线性漂移导致成功率暴跌 46.88 点」那条记录（`docs/局部调研——移动端GUI智能体_混合接地与自愈_开发方案.md:115`）是同一个问题的另一面。

**一个必须先接受的平台硬事实**：Android 14（API 34）起，**每次 MediaProjection 捕获会话都必须重新取得用户同意，token 单次使用**，重复使用同一 token 调 `createVirtualDisplay()` 会抛 `SecurityException`（官方行为变更文档）。

> https://developer.android.com/about/versions/14/behavior-changes-14?hl=zh-cn

由此两条落地约束：
- **设计上**：做成「一次授权 + 长驻会话」——用 `mediaProjection` 类型前台服务把会话吊住，按需从 `ImageReader` 取帧；配置变更用 `VirtualDisplay.resize()` + `setSurface()`，**不要重建会话**。
- **产品上**：Android 14+ 录屏时状态栏有常驻投射指示 + 前台服务通知。**不存在任何「用户毫无察觉的后台视觉」这条路。** 这件事必须摊开讲，而不是想办法绕过。

### 3.3 【P1】树 dump 的两轮自愈 + 预算排序 + 显式截断

它 `ToolMain.java` 里做对的四件事，正好补你现在的空缺：

| 它的做法 | 你的现状 | 建议 |
|:---|:---|:---|
| 零窗口重试 `DUMP_ATTEMPTS=3`（Activity 切换竞态导致 `getWindowsOnAllDisplays` 暂未返回窗口） | 无 | 照做 |
| **两轮 pass + 取优**：间隔 600ms 再扫一次，节点更多就用第二轮，更少就退回第一轮，命中自愈打 `recovered:1`（它记录微信同一屏连续返回 `0,0,0,68,0,70` 节点的间歇性空树） | `NodeExtractor.kt:63` 根节点为 null 直接返回 `emptyList()`，**与「本屏确实无可交互元素」无法区分**；`SelfHealingWatchdog.kt:45-111` 只覆盖流程级异常（意外回桌面 / 弹窗 / 卡死循环），不管「这次 dump 空了」 | 引入密封结果：`TreeDumpResult = Ok / Empty(reason) / Failed(error)`，禁止用空列表表达失败 |
| `MAX_NODES=1000` / 20000 字符预算 + `rankForBudget` 按 priority 0~5 排序后截断，并在 JSON 里显式回传 `truncated / act_total / act_sent / omitted` | `NodeExtractor.kt:53-57` `MAX_NODES=64` / depth 4，超出**静默丢弃**，上层不知道丢了什么 | 执行态独立预算 + 排序截断 + **显式 truncated 元数据** |
| `dedupeIdenticalNodes` + 抑制「>0.5 屏的可滚动容器」继承目标 | 无 | 照做，能显著减少「模型点到大容器」 |

**它给出的实测数量证据**（README/comment 记录）：高德 10403 字符 / **120 节点**，淘宝 11010 字符 / **125 节点**，均指单次 agent 得到的规模。也就是说——**你现在的 64 节点上限，在国民级 App 上是确定会漏目标的**。建议执行态预算提到 300~500 节点 + 优先级排序，感知态继续维持 64/深度 4（低能耗），两者用不同 Extractor。

### 3.4 【P2】文字注入：不要照抄它的「剪贴板 + PASTE」

它的免弹窗输入是：反射拿到 `clipboard` Binder → `setPrimaryClip` 写入 → `input -d <did> keyevent 279`（PASTE）。**副作用是不可见的：它会把她的剪贴板冲掉。**

对一个「单用户日常机上的伴侣」来说，这是可感知的冒犯行为——她刚复制了半条收货地址，被悄悄覆盖。

**建议**：以 `AccessibilityNodeInfo.ACTION_SET_TEXT`（配合 `ACTION_CLICK` 先拿焦点）为主路径；仅在不接受 `ACTION_SET_TEXT` 的输入框上才降级到剪贴板方案，且降级前保存原剪贴板内容、注入后恢复。这条也应该过一遍你已有的 `FinancialGatekeeper` 白名单语义——但注意它守的是金融，` oat, `需要新增的是「对外发送」和「改变用户数据」两类。

### 3.5 【P2】多段笔触批处理：你现在一次只用一条 stroke

`app/src/main/kotlin/com/xiaoxi/android/automation/gesture/GestureExecutor.kt:68-69` 每次 `GestureDescription.Builder().addStroke(stroke)` 只放一条。

Android 的 `GestureDescription` 支持多个 stroke，且 `GestureDescription.getMaxStrokeCount()` / `getMaxGestureDuration()` 定义了上限（官方 API 文档，API 24+）。**若多条 `StrokeDescription` 各自带 startTime 偏移可在一次 `dispatchGesture` 内顺序播放**，那么一次 `dispatch` 就能完成一条连续笔画/一串相邻操作，比起它 105 次 spawn `input` 进程的方案，无 root 也更快。

> ⚠️ **这条必须在真机上先做最小验证再落地**：「多 stroke 顺序播放」的行为在不同 Android 版本/ROM 上差异较大，官方未明确承诺跨版本一致性。**建议先写一个只有两个 stroke 的 demo Activity 在目标机上验证，通过后再改 `GestureExecutor`**，不要直接当既定事实写进生产代码。

---

## 4. 产品层判断：这个「偷着干活」的形态不属于小汐

三条理由，按重要性排：

1. **信任成本远高于功能收益。** 一个「在你完全不知道的情况下替你打开 App、点按屏幕、输入文字」的伴侣，在用户视角里和被装了监控软件几乎是同构的。尤其在你已经做了「感知中枢」（无障碍事件 + 通知监听）的前提下，再加一层「后台动手」，风险等级完全不同——这也是它自己在文档里写「这不是一个可以说『请相信我们』的地方」的原因。
2. **你的配置文件已经决定了答案。** 你已经有 `InterruptibilityEstimator`（打扰代价模型 + 60 分钟冷却）和 `FinancialGatekeeper`（金融熔断）。正确的演进方向是**把「裁决」从「要不要说话」扩展到「要不要动手」**，而不是让「不打扰」变成「不告诉你」。
3. **「无所谓感」不等于「无感知」。** 技术上你能做到的最好形态是「她不被打断」，而不是「她不知道」。前者是体贴，后者是欺骗。

**建议形态**：可见的任务卡 + 实时步骤流水 + 一键暂停 + 完成回执；涉及钱、涉及对外发送（消息/朋友圈/邮件）的操作必须预览确认——这与你 `IntentActionDispatcher.kt:91-108` 现有的 `DispatchResult` 分层（`SecurityRejected / TargetNotFound / ExecutionError / Success`）是同一套思路，扩一块 UI 就能落地。

---

## 5. 落地建议排序（可直接交给 CC 的规格级清单）

| 优先级 | 目标 | 动作要点 | 验收方式 |
|:---:|:---|:---|:---|
| **P0** | 打通 L0 快路径 | 新建 `automation/grounding/ExecutionNodeTree.kt`（含 `bounds`）+ `ExecutionTreeExtractor` + `toUiElementNodes()`，不改动现有感知链路 | 单测：给定一棵含 bounds 的树，断言 `locate()` 返回 `GroundingSource.ACCESSIBILITY_EXACT` 且坐标误差 ≤ 2px |
| **P1** | dump 结果可判别 | `TreeDumpResult` 密封类替换「空列表=失败」的隐式语义；零窗口重试 3 次；第二轮 600ms pass 取优 | 变异验证：把「空树」branch 改成返回 `Ok(empty)`，对应单测必须转红 |
| **P1** | 帧-坐标一致性 | `FrameCaptureResult(bytes, widthPx, heightPx, capturedAtMs)` 贯穿到 VLM 请求；断言 `toScreenPoint()` 用的是该 width/height 而非 displayMetrics | 单测：构造降采样帧，断言不会静默产出偏移坐标 |
| **P1** | 执行态树预算 | 300~500 节点 + dedupe + 抑制 >0.5 屏滚动容器 + priority 排序 + 显式 `truncated / omitted` | 拿美团/淘宝首页实测，断言关键可点击目标不丢失 |
| **P2** | 文字注入 | `ACTION_SET_TEXT` 主路径 + 剪贴板降级（带备份恢复） | 单测覆盖两条分支 + 断言剪贴板被还原 |
| **P2** | 多段手勢 | 先做双 stroke demo 真机验证，通过后再批量化 `GestureExecutor` | 真机 + Robolectric 双验；记录实际 `getMaxStrokeCount()` |
| **P2** | 执行准入裁决 | `InterruptibilityEstimator` / `FinancialGatekeeper` 复用到「是否允许动手」，不是只管「是否允许说话」 | 单测：游戏/会议/息屏 + 金融页场景下返回拒绝动手 |

---

## 6. 一句话总结

> 这个项目的**架构**是一个为作者自己的 OPPO 工程机定制的、需要 root 的执行沙箱，与你要交付的形态正交，**照抄即为负资产**；但它的**工程细节**（管道延迟、坐标一致性、dump 自愈与预算、剪贴板副作用）是一份现成的踩坑清单，**照抄即收益**。你当下最紧迫的不是「要不要上虚拟副屏」，而是 Phase 5 的 §3.1 那条 coordinate 断层。
