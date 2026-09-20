package com.xiaoxi.android.perception.model

/**
 * AOSP Binder 线程浅拷贝元组，严禁在 Binder 线程进行任何深拷贝或树遍历
 */
data class LightweightAccessibilityEvent(
    val eventType: Int,
    val packageName: String,
    val className: String? = null,
    val eventTime: Long = System.currentTimeMillis()
)

/**
 * 经过剪枝与截断的轻量无障碍节点快照
 * 严格只读隔离，绝不持有系统 AccessibilityNodeInfo 引用，杜绝内存泄漏与 GC 停顿
 */
data class AccessibilityNodeSnapshot(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val isFocused: Boolean = false,
    val depth: Int = 0
)

/**
 * 单屏聚合页面内容快照
 */
data class PageContentSnapshot(
    val packageName: String,
    val className: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val fingerprint: Long = 0L,
    val nodes: List<AccessibilityNodeSnapshot> = emptyList(),
    val focusedText: String? = null
)
