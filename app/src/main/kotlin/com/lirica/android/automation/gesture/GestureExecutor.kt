package com.lirica.android.automation.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.lirica.android.automation.gatekeeper.FinancialGatekeeper
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gatekeeper.GatekeeperDecision
import com.lirica.android.automation.gatekeeper.ProposedAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface GestureExecutor {
    suspend fun execute(gesture: GestureSpec, timeoutMs: Long = 3000L): GestureResult
    fun buildPath(gesture: GestureSpec): Path
}

/**
 * 手势派发观察器（M6.0 P1-2 接线：抢占自触发抑制 + 手势异常降级通道）。
 *
 * 抽成接口而不是直接依赖 [com.lirica.android.automation.verify.UserInterruptionDetector]，
 * 是为了保持 gesture 包纯 JVM 可测（单测注入空实现即可）。
 */
interface GestureDispatchObserver {
    /** 我方手势即将派发：登记自触发抑制窗口（防止把手势回投事件判成用户抢机） */
    fun onAutomationGestureDispatched(estimatedDurationMs: Long)

    /** 派发回调异常（onCancelled / dispatch 返回 false）——可能存在真实手指在屏 */
    fun onGestureAnomaly()
}

class GestureExecutorImpl(
    private val serviceProvider: () -> AccessibilityService?,
    private val trajectoryCalculator: BezierTrajectoryCalculator = BezierTrajectoryCalculator(),
    private val gatekeeper: FinancialGatekeeper = FinancialGatekeeperImpl(),
    private val currentPackageProvider: () -> String? = { serviceProvider()?.rootInActiveWindow?.packageName?.toString() },
    /** M6.0 P1-2：抢占接线观察器；null = 不接线（既有单测零改动） */
    private val dispatchObserver: GestureDispatchObserver? = null
) : GestureExecutor {

    var lastHonestReason: String? = null
        private set

    override fun buildPath(gesture: GestureSpec): Path {
        val path = Path()
        when (gesture) {
            is GestureSpec.Click -> {
                path.moveTo(gesture.x, gesture.y)
            }
            is GestureSpec.LongPress -> {
                path.moveTo(gesture.x, gesture.y)
            }
            is GestureSpec.Swipe -> {
                if (gesture.humanoidCurve) {
                    val points = trajectoryCalculator.generateCubicBezierPoints(
                        start = Point2D(gesture.startX, gesture.startY),
                        end = Point2D(gesture.endX, gesture.endY)
                    )
                    if (points.isNotEmpty()) {
                        path.moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            path.lineTo(points[i].x, points[i].y)
                        }
                    } else {
                        path.moveTo(gesture.startX, gesture.startY)
                        path.lineTo(gesture.endX, gesture.endY)
                    }
                } else {
                    path.moveTo(gesture.startX, gesture.startY)
                    path.lineTo(gesture.endX, gesture.endY)
                }
            }
            is GestureSpec.Drag -> {
                path.moveTo(gesture.startX, gesture.startY)
                path.lineTo(gesture.endX, gesture.endY)
            }
        }
        return path
    }

    override suspend fun execute(gesture: GestureSpec, timeoutMs: Long): GestureResult {
        // 0. 执行前置安全门控校验 (FinancialGatekeeper)
        val targetPkg = gesture.targetPackage?.takeIf { it.isNotBlank() }
            ?: currentPackageProvider()
            ?: ""

        val actionType = if (gesture.isTextInput) {
            "INPUT"
        } else {
            when (gesture) {
                is GestureSpec.Click -> "CLICK"
                is GestureSpec.LongPress -> "LONG_CLICK"
                else -> "GESTURE"
            }
        }

        val proposed = ProposedAction(
            actionType = actionType,
            targetText = gesture.targetText,
            targetResourceId = gesture.targetResourceId,
            isPasswordTarget = gesture.isPasswordTarget,
            currentPackage = targetPkg
        )

        val decision = gatekeeper.evaluate(proposed)
        if (decision is GatekeeperDecision.PhysicalBlocked) {
            lastHonestReason = decision.honestReason
            return GestureResult.Cancelled(decision.honestReason)
        }

        val service = serviceProvider() ?: return GestureResult.ServiceUnavailable

        val path = buildPath(gesture)
        val strokeDuration = when (gesture) {
            is GestureSpec.Click -> gesture.durationMs
            is GestureSpec.LongPress -> gesture.durationMs
            is GestureSpec.Swipe -> gesture.durationMs
            is GestureSpec.Drag -> gesture.holdDurationMs + gesture.dragDurationMs
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, strokeDuration)
        val gestureDescription = GestureDescription.Builder().addStroke(stroke).build()

        // M6.0 P1-2：派发前登记自触发抑制窗口——dispatchGesture 注入的手势同样会
        // 产生 TYPE_TOUCH_INTERACTION_START，不登记就会"她一动手就以为用户抢了手机"
        dispatchObserver?.onAutomationGestureDispatched(strokeDuration)

        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<GestureResult> { cont ->
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(GestureResult.Completed(strokeDuration))
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            // 系统取消注入手势 = 真实手指在屏的强信号（降级通道 B）
                            dispatchObserver?.onGestureAnomaly()
                            if (cont.isActive) cont.resume(GestureResult.Cancelled("System cancelled gesture"))
                        }
                    }

                    val dispatched = service.dispatchGesture(gestureDescription, callback, null)
                    if (!dispatched && cont.isActive) {
                        // 派发被拒绝同样可能是用户正在触屏
                        dispatchObserver?.onGestureAnomaly()
                        cont.resume(GestureResult.Cancelled("dispatchGesture returned false"))
                    }
                }
            }
            result ?: GestureResult.TimedOut(timeoutMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M6.0 P1-7：协程取消必须原样上抛，不能被粉饰成普通失败
            throw e
        } catch (e: Exception) {
            GestureResult.Error(e)
        }
    }
}
