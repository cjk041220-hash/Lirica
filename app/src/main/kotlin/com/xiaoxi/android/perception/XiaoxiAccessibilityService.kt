package com.xiaoxi.android.perception

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class XiaoxiAccessibilityService : AccessibilityService() {

    companion object {
        const val NOTIFICATION_TIMEOUT_MS: Long = 150L
        const val IS_ACCESSIBILITY_TOOL: Boolean = false
        const val CAN_PERFORM_GESTURES: Boolean = true
        const val CAN_RETRIEVE_WINDOW_CONTENT: Boolean = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // L0 fast filtering will be connected in Phase 3
    }

    override fun onInterrupt() {
        // Handle interruption
    }
}
