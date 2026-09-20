# 第二轮优化·Milestone 2.1 技术规格设计：执行态基建与坐标断层缝合

> **文档标识**：`docs/superpowers/specs/2026-09-20-round2-m21-execution-infrastructure-design.md`  
> **制定日期**：2026-09-20  
> **所属阶段**：小汐 2.0（Project Aether）第二轮系统级架构优化 · Milestone 2.1  
> **涉及主方案与评审项**：4.1 执行态无障碍树抽取与接地桥接中枢、M1（双通道截帧规格）、P3（R8 混淆与资源收缩）、P7（悬浮窗代行前台优先级）、P9（Gradle 构建性能参数）  
> **技术基准**：100% Native Kotlin + Coroutines + Android SDK (API 26~35)  
> **当前测试基线**：259 tests passed (100% green)  

---

## 1. 目标与设计原则

### 1.1 核心使命
彻底打通从 Android 底层无障碍服务到 `HybridGroundingEngine` 之间的物理坐标断层，打造高保真、带真实屏幕像素坐标、具备抗切屏竞态自愈能力的执行态树抽取中枢；为文字注入配备剪贴板毫秒级快照恢复；为手势引擎接入无障碍弱引用服务代理；为长程任务代行提供前台优先级护航。

### 1.2 架构铁律
1. **纯前台协同执行（严守非 Root 伴生底线）**：严禁采用 KernelSU / LSPosed Hook `system_server` 等破坏性方案，所有交互在物理主屏上直接执行，配合悬浮透明流水与二次确认门；
2. **双树抽样严格分离**：
   - 感知态树（`NodeExtractor`）：低功耗、`depth <= 4`、最多 64 节点、无物理坐标，仅用于静默情境感知；
   - 执行态树（`ExecutionTreeExtractor`）：高保真、`depth <= 12`、上限 400 节点预算、带完整 `bounds: ElementBounds`、优先级打分、大容器抑制；
3. **坐标一致性绝对原则**：真实捕获分辨率必须与无障碍屏幕绝对像素坐标系 1:1 对齐，严禁预降采样导致非线性坐标漂移；
4. **用户数据无损与隐私零容忍**：降级使用剪贴板时必须备份并在 `finally` 块 100% 恢复；银行/支付应用直接物理整屏熔断。

---

## 2. 模块技术规格

### 2.1 数据契约与坐标桥接 (`ExecutionTreeModels.kt`)

包名：`com.xiaoxi.android.automation.grounding`  
文件：`app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModels.kt`

```kotlin
package com.xiaoxi.android.automation.grounding

/**
 * 执行态专用高保真节点模型（携带物理屏幕绝对坐标）
 */
data class ExecutionNode(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: ElementBounds,            // 物理屏幕绝对像素坐标矩形 (left, top, right, bottom)
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isVisibleToUser: Boolean,
    val isPassword: Boolean,
    val priority: Int = 0,                // 0~5 优先级评分 (输入框=5, 可点击文本=5, 纯文本=4, 容器=1)
    val isLargeContainer: Boolean = false // 面积 > 0.5 屏幕的可滚动容器 (用于抑制误触)
)

/**
 * 显式树抓取结果契约（杜绝用空列表隐式代表失败）
 */
sealed interface TreeDumpResult {
    data class Ok(
        val packageName: String,
        val activityName: String?,
        val nodes: List<ExecutionNode>,
        val totalDiscovered: Int,
        val isTruncated: Boolean,
        val omittedCount: Int,
        val isRecoveredFromRetry: Boolean = false,
        val dumpDurationMs: Long
    ) : TreeDumpResult

    data class Empty(
        val reason: EmptyReason,
        val message: String
    ) : TreeDumpResult

    data class Failed(
        val error: Throwable,
        val message: String
    ) : TreeDumpResult
}

enum class EmptyReason {
    ZERO_WINDOW_TIMED_OUT,   // 窗口切换竞态重试耗尽
    PRIVACY_BLACKOUT,        // 银行/密码应用物理安全熔断
    ROOT_NODE_NULL,          // 系统返回 root 为空
    SCREEN_OFF_OR_LOCKED     // 息屏或处于锁屏界面
}

/**
 * 桥接扩展函数：将高保真执行树节点直接转换为接地引擎契约
 */
fun List<ExecutionNode>.toUiElementNodes(): List<UiElementNode> {
    return this.filter { it.isVisibleToUser && !it.isLargeContainer }
        .map { node ->
            UiElementNode(
                text = node.text,
                contentDescription = node.contentDescription,
                resourceId = node.resourceId,
                className = node.className,
                bounds = node.bounds,
                isClickable = node.isClickable,
                isVisibleToUser = node.isVisibleToUser
            )
        }
}
```

---

### 2.2 执行态高保真树抽取与双轮自愈 (`ExecutionTreeExtractor.kt`)

包名：`com.xiaoxi.android.automation.grounding`  
文件：`app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractor.kt`

*   **节点预算扩展**：`MAX_EXECUTION_NODES = 400`，`MAX_DEPTH = 12`；
*   **大容器抑制规则**：若节点面积占屏幕面积超过 50% 且具备可滚动特性（`isScrollable == true`），将其标记为 `isLargeContainer = true`，并将 priority 降为最低档（1）；
*   **切屏动画竞态双轮抓取取优**：
    1. 第一轮抓取：若提取的有效可交互节点数（`isClickable || isEditable`）$\le 2$，判定可能处于切屏过渡；
    2. 触发自愈：非阻塞等待 `delay(600)` 后执行第二轮抓取；
    3. 若第二轮节点数明显多于第一轮，采用第二轮结果并标记 `isRecoveredFromRetry = true`；若两轮均为空，返回明确的 `TreeDumpResult.Empty(EmptyReason.ZERO_WINDOW_TIMED_OUT)`。
*   **银行/支付整屏物理熔断**：调用 `PrivacyBreaker.isBlacklistedApp(packageName)`，命中立即熔断返回 `TreeDumpResult.Empty(PRIVACY_BLACKOUT)`。

---

### 2.3 剪贴板安全文字注入器与无障碍服务弱引用代理

#### ① `AccessibilityServiceHolder.kt`
包名：`com.xiaoxi.android.perception`  
文件：`app/src/main/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolder.kt`
- 采用弱引用托管正在运行的 `XiaoxiAccessibilityService` 实例；
- 在 `XiaoxiAccessibilityService.onServiceConnected` 注册，在 `onUnbind` / `onDestroy` 注销；
- 暴露 `get(): XiaoxiAccessibilityService?` 与 `val isConnected: Boolean`。

#### ② `TextInputExecutor.kt`
包名：`com.xiaoxi.android.automation.gesture`  
文件：`app/src/main/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutor.kt`
- 优先路径：标准 `ACTION_SET_TEXT`；
- 降级路径：剪贴板注入：
  1. 快照备份：`val originalClip = clipboardManager.primaryClip`；
  2. 注入新文本并点击对齐光标：`gestureExecutor.execute(GestureSpec.Click(...))`；
  3. 分发 `ACTION_PASTE`；
  4. `finally` 块中毫秒级恢复用户原本剪贴板，彻底杜绝冲掉用户数据。

---

### 2.4 双通道截帧契约与前台服务保活升级 (M1 & P7)

#### ① 截帧规格模型与管理器 (`ScreenCaptureManager.kt`)
包名：`com.xiaoxi.android.automation.capture`
- `FrameCaptureResult(bytes: ByteArray, widthPx: Int, heightPx: Int, timestamp: Long)`
- `ScreenCaptureManager`：
  - API 30+ (Android 11+)：调用 `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, ...)`；
  - 降级路径：提供 `MediaProjection` 契约抽象接口。

#### ② 悬浮窗任务代行前台优先级提升 (`CompanionOverlayService.kt`)
- 新增 `promoteToForeground(taskDescription: String)` 与 `demoteFromForeground()`；
- 使用 `NOTIFICATION_CHANNEL_EXECUTION`（静音、低干扰），在长程代行时锁定前台服务优先级，防止被杀。

#### ③ 构建混淆加固 (P3 & P9)
- `gradle.properties` 开启 configuration cache 与 parallel；
- `app/build.gradle.kts` release 开启 `isMinifyEnabled = true` 与 `isShrinkResources = true`；
- `app/proguard-rules.pro` 补齐 Room、kotlinx-serialization 与 Ktor keep 规则。
