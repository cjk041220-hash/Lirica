package com.xiaoxi.android.automation.healing

interface SelfHealingWatchdog {
    fun inspectStep(
        fingerprint: Long,
        currentPackage: String,
        targetPackage: String?,
        actionSummary: String,
        pageTexts: List<String> = emptyList()
    ): WatchdogVerdict

    fun recordRecoveryAttempt(anomaly: AnomalyType)
    fun reset()
}

class SelfHealingWatchdogImpl(
    private val maxStuckCount: Int = 3,
    private val maxRecoveryAttempts: Int = 3,
    private val launcherPackages: Set<String> = DEFAULT_LAUNCHER_PACKAGES
) : SelfHealingWatchdog {

    companion object {
        val DEFAULT_LAUNCHER_PACKAGES = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.mi.android.globallauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher"
        )

        val POPUP_KEYWORDS = listOf(
            "跳过", "关闭", "知道了", "同意", "残忍离开", "取消", "确定", "允许", "拒绝",
            "close", "skip", "dismiss", "cancel", "ok", "allow", "deny"
        )
    }

    private val stepHistory = mutableListOf<ExecutionStep>()
    private val recoveryAttempts = mutableMapOf<AnomalyType, Int>()
    private var consecutiveSameFingerprint = 1
    private var lastFingerprint: Long? = null

    override fun inspectStep(
        fingerprint: Long,
        currentPackage: String,
        targetPackage: String?,
        actionSummary: String,
        pageTexts: List<String>
    ): WatchdogVerdict {
        val currentStep = stepHistory.size + 1

        // Check 1: Unexpected Exit to Launcher
        if (targetPackage != null && currentPackage != targetPackage && launcherPackages.contains(currentPackage)) {
            val attempts = recoveryAttempts.getOrDefault(AnomalyType.UNEXPECTED_EXIT, 0)
            if (attempts >= maxRecoveryAttempts) {
                return WatchdogVerdict.Unrecoverable("Exceeded max recovery attempts ($maxRecoveryAttempts) for UNEXPECTED_EXIT to launcher")
            }
            return WatchdogVerdict.Recoverable(
                anomaly = AnomalyType.UNEXPECTED_EXIT,
                suggestedAction = HealingAction.PhysicalBacktrack(1),
                attemptCount = attempts + 1
            )
        }

        // Check 2: Popup Blocking
        val matchedPopupKeyword = pageTexts.firstOrNull { text ->
            POPUP_KEYWORDS.any { keyword -> text.equals(keyword, ignoreCase = true) || text.contains(keyword, ignoreCase = true) }
        }
        if (matchedPopupKeyword != null && currentStep > 1) {
            val attempts = recoveryAttempts.getOrDefault(AnomalyType.POPUP_BLOCKING, 0)
            if (attempts >= maxRecoveryAttempts) {
                return WatchdogVerdict.Unrecoverable("Exceeded max recovery attempts ($maxRecoveryAttempts) for POPUP_BLOCKING")
            }
            return WatchdogVerdict.Recoverable(
                anomaly = AnomalyType.POPUP_BLOCKING,
                suggestedAction = HealingAction.DismissPopup(matchedPopupKeyword),
                attemptCount = attempts + 1
            )
        }

        // Check 3: Consecutive Identical Fingerprints (Stuck Loop)
        if (lastFingerprint != null && lastFingerprint == fingerprint) {
            consecutiveSameFingerprint++
        } else {
            consecutiveSameFingerprint = 1
        }
        lastFingerprint = fingerprint

        stepHistory.add(ExecutionStep(currentStep, fingerprint, currentPackage, actionSummary))

        if (consecutiveSameFingerprint >= maxStuckCount) {
            val attempts = recoveryAttempts.getOrDefault(AnomalyType.STUCK_LOOP, 0)
            if (attempts >= maxRecoveryAttempts) {
                return WatchdogVerdict.Unrecoverable("Exceeded max recovery attempts ($maxRecoveryAttempts) for STUCK_LOOP (same state $consecutiveSameFingerprint times)")
            }
            val suggested = if (attempts == 0) {
                HealingAction.ReGround("UI state unchanged after multiple actions, re-grounding elements")
            } else {
                HealingAction.PhysicalBacktrack(1)
            }
            return WatchdogVerdict.Recoverable(
                anomaly = AnomalyType.STUCK_LOOP,
                suggestedAction = suggested,
                attemptCount = attempts + 1
            )
        }

        return WatchdogVerdict.NormalProgress(currentStep, fingerprint)
    }

    override fun recordRecoveryAttempt(anomaly: AnomalyType) {
        recoveryAttempts[anomaly] = recoveryAttempts.getOrDefault(anomaly, 0) + 1
    }

    override fun reset() {
        stepHistory.clear()
        recoveryAttempts.clear()
        consecutiveSameFingerprint = 1
        lastFingerprint = null
    }
}
