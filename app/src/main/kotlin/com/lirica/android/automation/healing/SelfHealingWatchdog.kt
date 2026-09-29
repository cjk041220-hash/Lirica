package com.lirica.android.automation.healing

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
    private val launcherPackages: Set<String> = DEFAULT_LAUNCHER_PACKAGES,
    private val honestNarrator: HonestNarrator = HonestNarratorImpl()
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

        /**
         * 弹窗关闭/跳过候选词表：
         * 严格排除「同意/允许/确定/allow/ok」等赋权授权类词汇，
         * 绝不自动代行系统权限授予或用户服务协议同意。
         */
        val POPUP_KEYWORDS = listOf(
            "跳过", "关闭", "知道了", "残忍离开", "取消", "拒绝",
            "close", "skip", "dismiss", "cancel", "deny"
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
                return buildUnrecoverableVerdict(
                    reason = "Exceeded max recovery attempts ($maxRecoveryAttempts) for UNEXPECTED_EXIT to launcher",
                    targetPackage = targetPackage
                )
            }
            return WatchdogVerdict.Recoverable(
                anomaly = AnomalyType.UNEXPECTED_EXIT,
                suggestedAction = HealingAction.PhysicalBacktrack(1),
                attemptCount = attempts + 1
            )
        }

        // Check 2: Popup Blocking (严格豁免系统权限控制器与安装器，绝不自动操作权限对话框)
        val isSystemSecurityOrPermission = currentPackage.contains("permissioncontroller") ||
            currentPackage.contains("packageinstaller") ||
            currentPackage == "com.miui.securitycenter"

        if (!isSystemSecurityOrPermission) {
            val matchedPopupKeyword = pageTexts.firstOrNull { text ->
                POPUP_KEYWORDS.any { keyword -> text.equals(keyword, ignoreCase = true) || text.contains(keyword, ignoreCase = true) }
            }
            if (matchedPopupKeyword != null && currentStep > 1) {
                val attempts = recoveryAttempts.getOrDefault(AnomalyType.POPUP_BLOCKING, 0)
                if (attempts >= maxRecoveryAttempts) {
                    return buildUnrecoverableVerdict(
                        reason = "Exceeded max recovery attempts ($maxRecoveryAttempts) for POPUP_BLOCKING",
                        targetPackage = targetPackage ?: currentPackage
                    )
                }
                return WatchdogVerdict.Recoverable(
                    anomaly = AnomalyType.POPUP_BLOCKING,
                    suggestedAction = HealingAction.DismissPopup(matchedPopupKeyword),
                    attemptCount = attempts + 1
                )
            }
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
                return buildUnrecoverableVerdict(
                    reason = "Exceeded max recovery attempts ($maxRecoveryAttempts) for STUCK_LOOP (same state $consecutiveSameFingerprint times)",
                    targetPackage = targetPackage ?: currentPackage
                )
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

    private fun buildUnrecoverableVerdict(reason: String, targetPackage: String?): WatchdogVerdict.Unrecoverable {
        val narrative = honestNarrator.narrate(
            FailureContext(
                scenario = FailureScenario.SELF_HEALING_EXHAUSTED,
                technicalReason = reason,
                targetPackage = targetPackage
            )
        )
        return WatchdogVerdict.Unrecoverable(reason = reason, honestNarrative = narrative)
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
