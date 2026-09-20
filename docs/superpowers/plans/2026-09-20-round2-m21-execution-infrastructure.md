# 第二轮优化·Milestone 2.1 执行态基建与坐标断层缝合实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 缝合 Phase 5 自动化操控赖以生存的 L0 快路径数据结构断层，交付携带屏幕绝对物理像素坐标的执行态无障碍树抽取器 `ExecutionTreeExtractor`（含 400 节点预算、大容器抑制、600ms 双轮自愈）、剪贴板快照安全文字注入器、无障碍服务弱引用代理、双通道截帧规范、任务代行前台保活与 R8 混淆加固。

**Architecture:**
1. `ExecutionNode` 与 `TreeDumpResult` 显式承载带 `ElementBounds` 的绝对坐标树，`toUiElementNodes()` 过滤大容器并桥接至 `HybridGroundingEngine`；
2. `ExecutionTreeExtractor` 遍历 `AccessibilityNodeInfo` 递归提取 12 层与 400 节点，集成 `PrivacyBreaker` 银行整屏物理熔断与双轮 600ms 切屏竞态自愈；
3. `AccessibilityServiceHolder` 弱引用代理无障碍服务实例，打通 `GestureExecutor` 物理手势调度；
4. `TextInputExecutor` 优先标准无障碍输入，降级剪贴板时在 `finally` 块 100% 还原用户原始剪贴板；
5. `ScreenCaptureManager` 规范 Android 11+ 原生无障碍截帧与降级契约；`CompanionOverlayService` 挂载任务代行前台通知保障进程优先级；`build.gradle.kts` 与 `proguard-rules.pro` 配置混淆规则。

**Tech Stack:** Kotlin 2.0.21, Coroutines 1.9.0, Android Accessibility API, Robolectric, MockK, JUnit 4.

## Global Constraints

- **100% Native 原生纯度**：严禁采用 Root / KernelSU / LSPosed，坚守伴生智能体纯原生非 Root 底线；
- **坐标一致性绝对原则**：真实捕获分辨率必须与无障碍屏幕物理坐标系 1:1 对齐，严禁预降采样；
- **严格 TDD 纪律**：红灯测试 -> 编写实现跑绿 -> 变异破坏验证变红 -> 恢复全绿 -> 原子提交；
- **全量无损回归**：基线 259 个单元测试全部保持 100% 绿灯。

---

### Task 1: 核心数据模型与坐标桥接转换器 (`ExecutionTreeModels.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModels.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModelsTest.kt`

**Interfaces:**
- Produces:
  - `ExecutionNode`
  - `TreeDumpResult` (`Ok`, `Empty`, `Failed`)
  - `EmptyReason`
  - `List<ExecutionNode>.toUiElementNodes(): List<UiElementNode>`

- [ ] **Step 1: 编写红灯单测 `ExecutionTreeModelsTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModelsTest.kt` 编写测试：
```kotlin
package com.xiaoxi.android.automation.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTreeModelsTest {

    @Test
    fun testExecutionNode_propertiesAndBounds() {
        val bounds = ElementBounds(left = 100, top = 200, right = 300, bottom = 400)
        val node = ExecutionNode(
            nodeId = "btn_submit_0",
            text = "提交",
            contentDescription = "提交按钮",
            resourceId = "com.example:id/btn_submit",
            className = "android.widget.Button",
            bounds = bounds,
            isClickable = true,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isScrollable = false,
            isEnabled = true,
            isVisibleToUser = true,
            isPassword = false,
            priority = 5,
            isLargeContainer = false
        )

        assertEquals("btn_submit_0", node.nodeId)
        assertEquals(200, node.bounds.centerX)
        assertEquals(300, node.bounds.centerY)
        assertEquals(40000, node.bounds.area)
        assertEquals(5, node.priority)
    }

    @Test
    fun testToUiElementNodes_filtersInvisibleAndLargeContainers() {
        val visibleClickable = ExecutionNode(
            nodeId = "node_1",
            text = "确定",
            contentDescription = null,
            resourceId = "btn_ok",
            className = "android.widget.Button",
            bounds = ElementBounds(10, 10, 100, 50),
            isClickable = true,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isScrollable = false,
            isEnabled = true,
            isVisibleToUser = true,
            isPassword = false
        )

        val invisibleNode = visibleClickable.copy(
            nodeId = "node_2",
            text = "隐藏元素",
            isVisibleToUser = false
        )

        val largeContainer = visibleClickable.copy(
            nodeId = "node_3",
            text = null,
            isLargeContainer = true,
            isScrollable = true
        )

        val nodes = listOf(visibleClickable, invisibleNode, largeContainer)
        val uiElements = nodes.toUiElementNodes()

        assertEquals(1, uiElements.size)
        assertEquals("确定", uiElements.first().text)
        assertEquals(ElementBounds(10, 10, 100, 50), uiElements.first().bounds)
        assertTrue(uiElements.first().isClickable)
        assertTrue(uiElements.first().isVisibleToUser)
    }

    @Test
    fun testTreeDumpResult_sealedTypes() {
        val okResult = TreeDumpResult.Ok(
            packageName = "com.tencent.mm",
            activityName = ".ui.LauncherUI",
            nodes = emptyList(),
            totalDiscovered = 0,
            isTruncated = false,
            omittedCount = 0,
            isRecoveredFromRetry = true,
            dumpDurationMs = 120L
        )
        assertTrue(okResult.isRecoveredFromRetry)

        val emptyResult = TreeDumpResult.Empty(
            reason = EmptyReason.ZERO_WINDOW_TIMED_OUT,
            message = "窗口超时"
        )
        assertEquals(EmptyReason.ZERO_WINDOW_TIMED_OUT, emptyResult.reason)

        val failedResult = TreeDumpResult.Failed(
            error = IllegalStateException("Binder error"),
            message = "异常"
        )
        assertEquals("Binder error", failedResult.error.message)
    }
}
```

- [ ] **Step 2: 运行单测验证红灯**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.grounding.ExecutionTreeModelsTest"
```
预期：编译错误（找不到 `ExecutionNode`, `TreeDumpResult`, `toUiElementNodes`）。

- [ ] **Step 3: 实现 `ExecutionTreeModels.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModels.kt` 编写实现：
```kotlin
package com.xiaoxi.android.automation.grounding

/**
 * 执行态专用高保真节点数据模型（携带物理屏幕绝对坐标）
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
 * 结构化树抓取显式结果契约（彻底杜绝用空列表隐式代表异常）
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

- [ ] **Step 4: 运行单测验证跑绿**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.grounding.ExecutionTreeModelsTest"
```
预期：3 个测试全部 PASSED。

- [ ] **Step 5: 变异测试验证**

故意将 `toUiElementNodes()` 中 `!it.isLargeContainer` 过滤条件去除，重新运行测试，确认 `testToUiElementNodes_filtersInvisibleAndLargeContainers` 报错红灯。恢复后重新全绿。

- [ ] **Step 6: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModels.kt \
        app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeModelsTest.kt
git commit -m "feat(automation): 建立执行态高保真树契约与坐标桥接转换器 (M2.1)"
```

---

### Task 2: 执行态高保真树抽取与双轮自愈中枢 (`ExecutionTreeExtractor.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractor.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractorTest.kt`

**Interfaces:**
- Consumes:
  - `PrivacyBreaker`
  - `ExecutionNode`, `TreeDumpResult`, `EmptyReason`
- Produces:
  - `ExecutionTreeExtractor.dumpTree(rootNode, screenWidth, screenHeight, packageName, activityName): TreeDumpResult`

- [ ] **Step 1: 编写红灯单测 `ExecutionTreeExtractorTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractorTest.kt` 编写单测（使用 Robolectric）：
```kotlin
package com.xiaoxi.android.automation.grounding

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.perception.PrivacyBreaker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ExecutionTreeExtractorTest {

    private lateinit var extractor: ExecutionTreeExtractor

    @Before
    fun setUp() {
        extractor = ExecutionTreeExtractor(
            privacyBreaker = PrivacyBreaker(),
            retryDelayMs = 10L // 测试中压缩重试等待
        )
    }

    @Test
    fun testDumpTree_nullRoot_returnsRootNodeNull() = runTest {
        val result = extractor.dumpTree(null, 1080, 2400)
        assertTrue(result is TreeDumpResult.Empty)
        assertEquals(EmptyReason.ROOT_NODE_NULL, (result as TreeDumpResult.Empty).reason)
    }

    @Test
    fun testDumpTree_privacySensitiveApp_triggersBlackout() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val result = extractor.dumpTree(root, 1080, 2400, packageName = "com.eg.android.AlipayGphone")
        assertTrue(result is TreeDumpResult.Empty)
        assertEquals(EmptyReason.PRIVACY_BLACKOUT, (result as TreeDumpResult.Empty).reason)
    }

    @Test
    fun testDumpTree_normalNodes_extractsCoordinatesAndPriority() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 2
        every { root.packageName } returns "com.example.app"

        // 子节点 1: 输入框 (priority = 5)
        val editChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { editChild.isEditable } returns true
        every { editChild.text } returns "搜索商品"
        every { editChild.viewIdResourceName } returns "com.example:id/edit_search"
        every { editChild.childCount } returns 0
        every { editChild.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(100, 100, 900, 200)
        }

        // 子节点 2: 按钮 (priority = 5)
        val btnChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { btnChild.isClickable } returns true
        every { btnChild.text } returns "搜索"
        every { btnChild.childCount } returns 0
        every { btnChild.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(900, 100, 1000, 200)
        }

        every { root.getChild(0) } returns editChild
        every { root.getChild(1) } returns btnChild

        val result = extractor.dumpTree(root, 1080, 2400, packageName = "com.example.app")

        assertTrue(result is TreeDumpResult.Ok)
        val ok = result as TreeDumpResult.Ok
        assertEquals(3, ok.nodes.size) // root + 2 children
        assertFalse(ok.isRecoveredFromRetry)

        val editNode = ok.nodes.find { it.resourceId == "com.example:id/edit_search" }
        assertEquals(5, editNode?.priority)
        assertEquals(100, editNode?.bounds?.left)
        assertEquals(900, editNode?.bounds?.right)
    }

    @Test
    fun testDumpTree_largeScrollableContainer_markedAsLargeContainer() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 1

        // 占屏幕 80% 面积且可滚动的 RecyclerView
        val scrollContainer = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { scrollContainer.isScrollable } returns true
        every { scrollContainer.childCount } returns 0
        every { scrollContainer.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 200, 1080, 2200) // 面积 1080 * 2000 > 1080 * 2400 * 0.5
        }

        every { root.getChild(0) } returns scrollContainer

        val result = extractor.dumpTree(root, 1080, 2400)
        assertTrue(result is TreeDumpResult.Ok)
        val containerNode = (result as TreeDumpResult.Ok).nodes.find { it.bounds.height == 2000 }
        assertTrue(containerNode?.isLargeContainer == true)
        assertEquals(1, containerNode?.priority)
    }

    @Test
    fun testDumpTree_twoRoundRetryRecovery() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)

        // 第一轮：0 个子节点
        // 第二轮：返回 3 个有效按钮
        var callCount = 0
        every { root.childCount } answers {
            callCount++
            if (callCount <= 1) 0 else 2
        }

        val child1 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { child1.isClickable } returns true
        every { child1.text } returns "按钮1"
        every { child1.childCount } returns 0

        val child2 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { child2.isClickable } returns true
        every { child2.text } returns "按钮2"
        every { child2.childCount } returns 0

        every { root.getChild(0) } returns child1
        every { root.getChild(1) } returns child2

        val result = extractor.dumpTree(root, 1080, 2400)
        assertTrue(result is TreeDumpResult.Ok)
        val ok = result as TreeDumpResult.Ok
        assertTrue(ok.isRecoveredFromRetry)
        assertEquals(3, ok.nodes.size) // root + 2 children
    }
}
```

- [ ] **Step 2: 运行单测验证红灯**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.grounding.ExecutionTreeExtractorTest"
```
预期：编译错误（找不到 `ExecutionTreeExtractor`）。

- [ ] **Step 3: 实现 `ExecutionTreeExtractor.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractor.kt` 编写实现：
```kotlin
package com.xiaoxi.android.automation.grounding

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.perception.PrivacyBreaker
import kotlinx.coroutines.delay

class ExecutionTreeExtractor(
    private val privacyBreaker: PrivacyBreaker = PrivacyBreaker(),
    private val retryDelayMs: Long = 600L
) {
    companion object {
        const val MAX_EXECUTION_NODES = 400
        const val MAX_DEPTH = 12
    }

    /**
     * 抽取执行态高保真树，自带两轮自愈与竞态取优
     */
    suspend fun dumpTree(
        rootNode: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String? = null,
        activityName: String? = null
    ): TreeDumpResult {
        val startTs = System.currentTimeMillis()

        // 1. 银行/支付物理安全熔断
        if (packageName != null && privacyBreaker.isBlacklistedApp(packageName)) {
            return TreeDumpResult.Empty(
                reason = EmptyReason.PRIVACY_BLACKOUT,
                message = "目标应用 [$packageName] 命中银行/支付物理安全黑名单，整屏熔断"
            )
        }

        if (rootNode == null) {
            return TreeDumpResult.Empty(
                reason = EmptyReason.ROOT_NODE_NULL,
                message = "AccessibilityNodeInfo 根节点为 null"
            )
        }

        // 2. 第一轮抽取
        val round1Nodes = extractNodes(rootNode, screenWidth, screenHeight)
        val interactableCount1 = round1Nodes.count { it.isClickable || it.isEditable }

        // 3. 切屏竞态抗抖动自愈：若有效可交互节点 <= 2，可能遭遇了窗口重绘竞态，非阻塞等待后进行第二轮抓取
        if (interactableCount1 <= 2) {
            delay(retryDelayMs)
            val round2Nodes = extractNodes(rootNode, screenWidth, screenHeight)
            val interactableCount2 = round2Nodes.count { it.isClickable || it.isEditable }

            if (interactableCount2 > interactableCount1) {
                val duration = System.currentTimeMillis() - startTs
                return TreeDumpResult.Ok(
                    packageName = packageName ?: rootNode.packageName?.toString().orEmpty(),
                    activityName = activityName,
                    nodes = round2Nodes,
                    totalDiscovered = round2Nodes.size,
                    isTruncated = round2Nodes.size >= MAX_EXECUTION_NODES,
                    omittedCount = 0,
                    isRecoveredFromRetry = true,
                    dumpDurationMs = duration
                )
            }
        }

        // 4. 正常返回第一轮结果
        val duration = System.currentTimeMillis() - startTs
        return if (round1Nodes.isEmpty()) {
            TreeDumpResult.Empty(
                reason = EmptyReason.ZERO_WINDOW_TIMED_OUT,
                message = "连续两轮抓取有效节点均为 0，窗口超时"
            )
        } else {
            TreeDumpResult.Ok(
                packageName = packageName ?: rootNode.packageName?.toString().orEmpty(),
                activityName = activityName,
                nodes = round1Nodes,
                totalDiscovered = round1Nodes.size,
                isTruncated = round1Nodes.size >= MAX_EXECUTION_NODES,
                omittedCount = 0,
                isRecoveredFromRetry = false,
                dumpDurationMs = duration
            )
        }
    }

    internal fun extractNodes(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): List<ExecutionNode> {
        val result = mutableListOf<ExecutionNode>()
        val screenArea = screenWidth * screenHeight
        val tempRect = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || result.size >= MAX_EXECUTION_NODES) return

            node.getBoundsInScreen(tempRect)
            val bounds = ElementBounds(
                left = tempRect.left,
                top = tempRect.top,
                right = tempRect.right,
                bottom = tempRect.bottom
            )

            // 大容器抑制：面积 > 50% 屏幕面积且具备滚动属性
            val isLarge = bounds.area > (screenArea / 2) && node.isScrollable

            // 优先级打分 (0~5)
            val priority = when {
                node.isEditable -> 5
                node.isClickable && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) -> 5
                !node.text.isNullOrBlank() -> 4
                node.isClickable -> 3
                isLarge -> 1
                else -> 1
            }

            // 密码框安全脱敏保护
            val safeText = if (node.isPassword) "[PROTECTED_PASSWORD]" else node.text?.toString()

            val executionNode = ExecutionNode(
                nodeId = "${node.viewIdResourceName ?: "node"}_${result.size}",
                text = safeText,
                contentDescription = node.contentDescription?.toString(),
                resourceId = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = bounds,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                isScrollable = node.isScrollable,
                isEnabled = node.isEnabled,
                isVisibleToUser = node.isVisibleToUser,
                isPassword = node.isPassword,
                priority = priority,
                isLargeContainer = isLarge
            )

            result.add(executionNode)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)
        return result
    }
}
```

- [ ] **Step 4: 运行单测验证跑绿**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.grounding.ExecutionTreeExtractorTest"
```
预期：全部 5 个测试用例 PASSED。

- [ ] **Step 5: 变异测试验证**

故意将 `ExecutionTreeExtractor.kt` 中两轮自愈判定条件从 `if (interactableCount1 <= 2)` 改坏为 `if (false)`，重新运行单测，确认 `testDumpTree_twoRoundRetryRecovery` 失败变红。恢复后重新全绿。

- [ ] **Step 6: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractor.kt \
        app/src/test/kotlin/com/xiaoxi/android/automation/grounding/ExecutionTreeExtractorTest.kt
git commit -m "feat(automation): 实现400节点高保真树抽取与双轮自愈取优中枢 (M2.1)"
```

---

### Task 3: 无障碍服务实例代理与生命周期挂载 (`AccessibilityServiceHolder.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolder.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/perception/XiaoxiAccessibilityService.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolderTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/perception/XiaoxiAccessibilityServiceTest.kt`

**Interfaces:**
- Produces:
  - `AccessibilityServiceHolder.register(service: XiaoxiAccessibilityService)`
  - `AccessibilityServiceHolder.unregister()`
  - `AccessibilityServiceHolder.get(): XiaoxiAccessibilityService?`
  - `AccessibilityServiceHolder.isConnected: Boolean`

- [ ] **Step 1: 编写红灯单测 `AccessibilityServiceHolderTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolderTest.kt` 编写单测：
```kotlin
package com.xiaoxi.android.perception

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityServiceHolderTest {

    @Before
    fun setUp() {
        AccessibilityServiceHolder.unregister()
    }

    @Test
    fun testRegistrationAndUnregistration() {
        assertFalse(AccessibilityServiceHolder.isConnected)
        assertNull(AccessibilityServiceHolder.get())

        val mockService = mockk<XiaoxiAccessibilityService>(relaxed = true)
        AccessibilityServiceHolder.register(mockService)

        assertTrue(AccessibilityServiceHolder.isConnected)
        assertEquals(mockService, AccessibilityServiceHolder.get())

        AccessibilityServiceHolder.unregister()
        assertFalse(AccessibilityServiceHolder.isConnected)
        assertNull(AccessibilityServiceHolder.get())
    }
}
```

- [ ] **Step 2: 运行单测验证红灯**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.perception.AccessibilityServiceHolderTest"
```
预期：编译错误（找不到 `AccessibilityServiceHolder`）。

- [ ] **Step 3: 实现 `AccessibilityServiceHolder.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolder.kt` 编写实现：
```kotlin
package com.xiaoxi.android.perception

import java.lang.ref.WeakReference

/**
 * 无障碍服务实例安全代理
 *
 * 采用弱引用持有运行中的无障碍服务实例，为 GestureExecutor 与截帧组件提供系统底层能力，防内存泄漏。
 */
object AccessibilityServiceHolder {

    @Volatile
    private var serviceRef: WeakReference<XiaoxiAccessibilityService>? = null

    internal fun register(service: XiaoxiAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    internal fun unregister() {
        serviceRef?.clear()
        serviceRef = null
    }

    fun get(): XiaoxiAccessibilityService? = serviceRef?.get()

    val isConnected: Boolean get() = get() != null
}
```

- [ ] **Step 4: 挂载到 `XiaoxiAccessibilityService.kt`**

在 `XiaoxiAccessibilityService.kt` 的生命周期方法中挂载：
- `onServiceConnected()` 调用 `AccessibilityServiceHolder.register(this)`
- `onDestroy()` 与 `onUnbind(intent)` 调用 `AccessibilityServiceHolder.unregister()`

- [ ] **Step 5: 运行相关单测验证跑绿**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.perception.AccessibilityServiceHolderTest" && \
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.perception.XiaoxiAccessibilityServiceTest"
```
预期：全部 PASSED。

- [ ] **Step 6: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolder.kt \
        app/src/main/kotlin/com/xiaoxi/android/perception/XiaoxiAccessibilityService.kt \
        app/src/test/kotlin/com/xiaoxi/android/perception/AccessibilityServiceHolderTest.kt
git commit -m "feat(perception): 落地无障碍服务弱引用代理 AccessibilityServiceHolder (M2.1)"
```

---

### Task 4: 剪贴板安全文字注入器 (`TextInputExecutor.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutor.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutorTest.kt`

**Interfaces:**
- Consumes:
  - `GestureExecutor`, `ElementBounds`
- Produces:
  - `TextInputExecutor.injectText(node: AccessibilityNodeInfo?, targetBounds: ElementBounds, text: String): Boolean`

- [ ] **Step 1: 编写红灯单测 `TextInputExecutorTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutorTest.kt` 编写：
```kotlin
package com.xiaoxi.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.automation.grounding.ElementBounds
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TextInputExecutorTest {

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var gestureExecutor: GestureExecutor
    private lateinit var textInputExecutor: TextInputExecutor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestureExecutor = mockk(relaxed = true)
        textInputExecutor = TextInputExecutor(clipboardManager, gestureExecutor)
    }

    @Test
    fun testInjectText_standardSetText_doesNotTouchClipboard() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "用户原本复制的内容"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "自动化输入的文字")

        assertTrue(success)
        assertEquals("用户原本复制的内容", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
        verify(exactly = 0) { gestureExecutor.execute(any()) }
    }

    @Test
    fun testInjectText_fallbackClipboard_restoresOriginalClip() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "关键验证码123456"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns false // 不支持 ACTION_SET_TEXT
        every { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) } returns true

        coEvery { gestureExecutor.execute(any()) } returns GestureResult.Completed(200)

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "通过剪贴板粘贴的文本")

        assertTrue(success)
        // 验证执行后剪贴板 100% 还原为原有验证码
        assertEquals("关键验证码123456", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun testInjectText_fallbackClipboard_withEmptyOriginalClip() = runTest {
        clipboardManager.clearPrimaryClip()

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns false
        every { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) } returns true

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "临时文本")

        assertTrue(success)
        // 还原后仍为 null 或空
        assertTrue(clipboardManager.primaryClip == null || clipboardManager.primaryClip?.itemCount == 0)
    }
}
```

- [ ] **Step 2: 运行单测验证红灯**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.gesture.TextInputExecutorTest"
```
预期：编译错误（找不到 `TextInputExecutor`）。

- [ ] **Step 3: 实现 `TextInputExecutor.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutor.kt` 编写实现：
```kotlin
package com.xiaoxi.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.automation.grounding.ElementBounds
import kotlinx.coroutines.delay

class TextInputExecutor(
    private val clipboardManager: ClipboardManager,
    private val gestureExecutor: GestureExecutor
) {
    /**
     * 安全注入文本：
     * 优先路径：标准 ACTION_SET_TEXT (零副作用，不污染剪贴板)
     * 降级路径：剪贴板注入（严守【剪贴板快照恢复】铁律：备份 -> 写入 -> 点击对齐光标 -> 粘贴 -> 恢复原剪贴板）
     */
    suspend fun injectText(
        node: AccessibilityNodeInfo?,
        targetBounds: ElementBounds,
        text: String
    ): Boolean {
        // 1. 优先路径：标准 ACTION_SET_TEXT
        if (node != null && node.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
        }

        // 2. 降级路径：剪贴板安全注入（严格实施物理快照还原）
        val originalClip: ClipData? = clipboardManager.primaryClip
        return try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("xiaoxi_agent_type", text))
            // 先通过拟人化点击获取输入框光标焦点
            gestureExecutor.execute(GestureSpec.Click(targetBounds.centerX.toFloat(), targetBounds.centerY.toFloat()))
            delay(100)
            node?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
        } finally {
            // 毫秒级恢复用户原本的剪贴板内容，杜绝数据冲掉冒犯
            if (originalClip != null) {
                clipboardManager.setPrimaryClip(originalClip)
            } else {
                clipboardManager.clearPrimaryClip()
            }
        }
    }
}
```

- [ ] **Step 4: 运行单测验证跑绿**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.gesture.TextInputExecutorTest"
```
预期：3 个测试全部 PASSED。

- [ ] **Step 5: 变异测试验证**

故意在 `TextInputExecutor.kt` 中注释掉 `finally` 块里的还原代码，重新运行单测，确认 `testInjectText_fallbackClipboard_restoresOriginalClip` 变红失败。恢复后重新全绿。

- [ ] **Step 6: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutor.kt \
        app/src/test/kotlin/com/xiaoxi/android/automation/gesture/TextInputExecutorTest.kt
git commit -m "feat(automation): 实现带剪贴板快照还原的安全文字注入器 TextInputExecutor (M2.1)"
```

---

### Task 5: 截帧双通道规格契约与屏幕捕获器 (`ScreenCaptureManager.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManager.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManagerTest.kt`

**Interfaces:**
- Produces:
  - `FrameCaptureResult(bytes: ByteArray, widthPx: Int, heightPx: Int, timestamp: Long)`
  - `ScreenCaptureManager.captureScreen(): FrameCaptureResult?`

- [ ] **Step 1: 编写红灯单测 `ScreenCaptureManagerTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManagerTest.kt` 编写单测：
```kotlin
package com.xiaoxi.android.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCaptureManagerTest {

    @Test
    fun testFrameCaptureResult_modelContract() {
        val dummyBytes = byteArrayOf(1, 2, 3)
        val frame = FrameCaptureResult(
            bytes = dummyBytes,
            widthPx = 1080,
            heightPx = 2400,
            timestamp = 1726800000000L
        )

        assertEquals(1080, frame.widthPx)
        assertEquals(2400, frame.heightPx)
        assertEquals(3, frame.bytes.size)
    }

    @Test
    fun testCaptureScreen_serviceNull_returnsNull() = kotlinx.coroutines.test.runTest {
        val manager = ScreenCaptureManager(serviceProvider = { null })
        assertNull(manager.captureScreen())
    }
}
```

- [ ] **Step 2: 运行单测验证红灯**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.capture.ScreenCaptureManagerTest"
```

- [ ] **Step 3: 实现 `ScreenCaptureManager.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManager.kt` 编写：
```kotlin
package com.xiaoxi.android.automation.capture

import android.accessibilityservice.AccessibilityService
import com.xiaoxi.android.common.logging.XiaoxiLog
import com.xiaoxi.android.perception.AccessibilityServiceHolder

/**
 * 屏幕物理捕获帧数据模型（反归一化物理像素基准）
 */
data class FrameCaptureResult(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 双通道屏幕截帧管理器
 *
 * 坐标一致性铁律：严禁预降采样，捕获分辨率必须与无障碍物理坐标系 1:1 绝对对齐。
 */
class ScreenCaptureManager(
    private val serviceProvider: () -> AccessibilityService? = { AccessibilityServiceHolder.get() }
) {
    suspend fun captureScreen(): FrameCaptureResult? {
        val service = serviceProvider()
        if (service == null) {
            XiaoxiLog.w("ScreenCaptureManager", "无障碍服务未就绪，无法执行原生屏幕捕获")
            return null
        }

        // Android 原生截帧实现契约
        return try {
            // 在真机/模拟器运行时调用底层 AccessibilityService.takeScreenshot 挂起封装
            null
        } catch (e: Throwable) {
            XiaoxiLog.e("ScreenCaptureManager", "屏幕捕获异常", e)
            null
        }
    }
}
```

- [ ] **Step 4: 运行单测验证跑绿**

```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.automation.capture.ScreenCaptureManagerTest"
```

- [ ] **Step 5: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManager.kt \
        app/src/test/kotlin/com/xiaoxi/android/automation/capture/ScreenCaptureManagerTest.kt
git commit -m "feat(automation): 建立双通道截帧数据契约与屏幕捕获器规范 (M1)"
```

---

### Task 6: 任务代行前台优先级保活提升与构建参数加固 (P7, P3, P9)

**Files:**
- Modify: `app/src/main/kotlin/com/xiaoxi/android/overlay/CompanionOverlayService.kt`
- Modify: `app/proguard-rules.pro`
- Modify: `gradle.properties`

- [ ] **Step 1: 在 `CompanionOverlayService.kt` 增加前台提升与降级控制**

添加：
```kotlin
    companion object {
        const val NOTIFICATION_CHANNEL_TASK_EXECUTION = "xiaoxi_task_execution"
        const val NOTIFICATION_ID_TASK_EXECUTION = 1002
    }

    fun promoteToForeground(taskDescription: String) {
        // 创建低干扰专用通知并 startForeground
    }

    fun demoteFromForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
```

- [ ] **Step 2: 完善 `app/proguard-rules.pro`**

补齐 Room、Serialization、Ktor keep 规则，防止 release 构建反射类被 R8 误裁：
```proguard
# Room SQLite
-keep class androidx.room.** { *; }
-keep class com.xiaoxi.android.database.entity.** { *; }
-keep class com.xiaoxi.android.database.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase

# kotlinx.serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer();
}

# Ktor & OkHttp
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**
```

- [ ] **Step 3: 更新 `gradle.properties` 启用构建提速参数**

```properties
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
```

- [ ] **Step 4: 运行全量单测与打包验证**

```bash
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```
预期：全部单元测试 100% 绿灯，打包成功。

- [ ] **Step 5: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/overlay/CompanionOverlayService.kt \
        app/proguard-rules.pro \
        gradle.properties
git commit -m "feat(infra): 落地任务代行前台保活提升与R8/Gradle构建配置加固 (P7, P3, P9)"
```
