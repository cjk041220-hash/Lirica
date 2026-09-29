package com.lirica.android.automation.intent

import android.content.Intent

sealed interface DispatchResult {
    data class Success(val intent: Intent, val targetComponent: String? = null) : DispatchResult
    data class TargetNotFound(val reason: String) : DispatchResult
    data class SecurityRejected(val reason: String) : DispatchResult
    data class ExecutionError(val throwable: Throwable) : DispatchResult
}
