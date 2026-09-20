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
