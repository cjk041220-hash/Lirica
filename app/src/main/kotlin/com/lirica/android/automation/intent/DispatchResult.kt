package com.lirica.android.automation.intent

import android.content.Intent

/**
 * Intent 通道派发显式结果契约。
 *
 * 2026-10-06 M5.1 扩展（P0-1 安全收口）：新增 [ConfirmationRequired] 与
 * [PermissionRequired] 两种"未失败但未落地"的中间态。
 * 之所以不复用 SecurityRejected——把"等你确认"渲染成"被拒绝"会让
 * 心智层向用户撒谎（诚实叙述 HonestNarrator 的前提是结果语义精确）。
 */
sealed interface DispatchResult {
    data class Success(val intent: Intent, val targetComponent: String? = null) : DispatchResult

    data class TargetNotFound(val reason: String) : DispatchResult

    /** 安全闸门物理熔断或参数非法 */
    data class SecurityRejected(val reason: String) : DispatchResult

    /** 非幂等外部通信需用户二次确认（token 经 SafetyPolicyEngine 核销） */
    data class ConfirmationRequired(
        val token: String,
        val promptMessage: String
    ) : DispatchResult

    /** 缺少运行时/特殊权限，需引导用户授权 */
    data class PermissionRequired(
        val permissionName: String,
        val reason: String,
        val settingsAction: String? = null
    ) : DispatchResult

    data class ExecutionError(val throwable: Throwable) : DispatchResult
}
