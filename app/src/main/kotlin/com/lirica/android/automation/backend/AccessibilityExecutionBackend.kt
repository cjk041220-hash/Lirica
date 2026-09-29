package com.lirica.android.automation.backend

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.gesture.GestureExecutor
import com.lirica.android.automation.gesture.GestureResult
import com.lirica.android.automation.gesture.GestureSpec
import com.lirica.android.automation.gesture.TextInputExecutor
import com.lirica.android.automation.grounding.ElementBounds
import com.lirica.android.perception.AccessibilityServiceHolder

/**
 * 基于系统无障碍手势分发器与剪贴板还原的执行底座 (AccessibilityExecutionBackend)
 */
class AccessibilityExecutionBackend(
    private val gestureExecutor: GestureExecutor,
    private val textInputExecutor: TextInputExecutor,
    private val serviceProvider: () -> AccessibilityService? = { AccessibilityServiceHolder.get() }
) : ExecutionBackend {

    override suspend fun isAvailable(): Boolean {
        return serviceProvider() != null
    }

    override suspend fun click(x: Float, y: Float): Boolean {
        val result = gestureExecutor.execute(GestureSpec.Click(x, y))
        return result is GestureResult.Completed
    }

    override suspend fun longClick(x: Float, y: Float, durationMs: Long): Boolean {
        val result = gestureExecutor.execute(GestureSpec.LongPress(x, y, durationMs))
        return result is GestureResult.Completed
    }

    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val result = gestureExecutor.execute(
            GestureSpec.Swipe(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs
            )
        )
        return result is GestureResult.Completed
    }

    override suspend fun sendKeycode(keyCode: Int): Boolean {
        val service = serviceProvider() ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            KeyEvent.KEYCODE_POWER -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            else -> false
        }
    }

    override suspend fun inputText(text: String): Boolean {
        val root = serviceProvider()?.rootInActiveWindow
        val node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val bounds = if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            ElementBounds(rect.left, rect.top, rect.right, rect.bottom)
        } else {
            ElementBounds(0, 0, 0, 0)
        }
        return textInputExecutor.injectText(node, bounds, text)
    }
}
