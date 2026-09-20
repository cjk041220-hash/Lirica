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
