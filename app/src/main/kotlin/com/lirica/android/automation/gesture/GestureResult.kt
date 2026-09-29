package com.lirica.android.automation.gesture

sealed interface GestureResult {
    data class Completed(val durationMs: Long) : GestureResult
    data class Cancelled(val reason: String = "System cancelled gesture") : GestureResult
    object ServiceUnavailable : GestureResult
    data class TimedOut(val timeoutMs: Long) : GestureResult
    data class Error(val throwable: Throwable) : GestureResult
}
