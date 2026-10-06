package com.lirica.android.automation.feedback

/**
 * 具身视觉反馈事件（主方案5.0 §三 Layer 1「[视觉反馈] ActionVisualFeedbackListener
 * 解耦抛出触控尾迹与气泡状态」）。
 *
 * 架构裁决（人类架构师定案 · 方案A 拟人具身）：
 * **底层执行引擎与 UI 表达彻底解耦**——执行引擎只负责抛出语义事件，
 * 悬浮窗、气泡、尾迹动画由后续接入的 UI 层自行订阅渲染。
 * 因此本文件不 import 任何 Android UI 类型，可在纯 JVM 单测里穷举。
 */
sealed interface ActionVisualFeedback {

    /** 事件发生时刻（毫秒时间戳） */
    val timestampMs: Long

    /** 触控尾迹：拟人贝塞尔手势的起终点，供悬浮层绘制"她的手落在哪里" */
    data class TouchTrace(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long,
        val kind: TouchKind,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : ActionVisualFeedback

    /** 悬浮气泡状态机（微表情收敛） */
    data class BubbleState(
        val state: EmbodiedState,
        /** 面向用户的一句话（可为 null，表示安静态不打扰） */
        val speech: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : ActionVisualFeedback

    /** 步骤进度（供悬浮胶囊显示"第 n 步"） */
    data class StepProgress(
        val stepIndex: Int,
        val totalSteps: Int,
        val description: String,
        val isRecovering: Boolean = false,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : ActionVisualFeedback

    /** 宏回放进度（M5.3 经验轨迹回放时的可见性） */
    data class MacroReplayProgress(
        val macroId: String,
        val stepIndex: Int,
        val totalSteps: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : ActionVisualFeedback
}

enum class TouchKind {
    TAP,
    LONG_PRESS,
    SWIPE,
    DRAG
}

/**
 * 具身态枚举。
 *
 * [USER_INTERRUPTED] 是 M5.2 用户抢占熔断的对外表达：手势打架时先收敛为
 * 安静观察态，等用户显式"继续"，而不是抢屏。
 */
enum class EmbodiedState {
    IDLE,
    OBSERVING,
    THINKING,
    ACTING,
    REPLAYING,
    SUCCESS,
    FAILED,
    USER_INTERRUPTED
}

/**
 * 反馈监听器契约。
 *
 * 铁律：实现**绝不允许抛异常穿透到执行引擎**——UI 层崩溃不能让代行任务失败。
 * 该约束由 [ActionFeedbackHub] 统一兜底，实现方无需自行 try/catch。
 */
fun interface ActionVisualFeedbackListener {
    fun onFeedback(event: ActionVisualFeedback)
}

/** 空实现（未接入悬浮窗时的默认装配） */
object NoOpActionVisualFeedbackListener : ActionVisualFeedbackListener {
    override fun onFeedback(event: ActionVisualFeedback) = Unit
}

/**
 * 多播反馈中枢：异常隔离 + 订阅者增删。
 *
 * 组件扫描是快照式（CopyOnWriteArrayList）：渲染线程增删订阅者不会
 * 影响正在派发的事件序列，也不会抛 ConcurrentModificationException。
 */
class ActionFeedbackHub(
    listeners: List<ActionVisualFeedbackListener> = emptyList()
) : ActionVisualFeedbackListener {

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ActionVisualFeedbackListener>()

    init {
        listeners.forEach { this.listeners.add(it) }
    }

    fun register(listener: ActionVisualFeedbackListener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: ActionVisualFeedbackListener) {
        listeners.remove(listener)
    }

    val listenerCount: Int get() = listeners.size

    override fun onFeedback(event: ActionVisualFeedback) {
        for (listener in listeners) {
            // 隔离：任一 UI 订阅者抛异常都不得击穿执行主链路
            runCatching { listener.onFeedback(event) }
        }
    }
}
