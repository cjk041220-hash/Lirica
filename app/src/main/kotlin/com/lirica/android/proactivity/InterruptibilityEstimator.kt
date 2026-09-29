package com.lirica.android.proactivity

import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState

/**
 * 可中断性打扰代价模型 Cost_int(t)
 */
interface InterruptibilityEstimator {
    fun estimateCost(snapshot: PerceptionSnapshot, lastInterruptionMs: Long, nowMs: Long): Double

    companion object {
        const val BASE_COST = 2.0
        const val MIN_COST = 0.5
        const val MAX_COST = 5.0
        const val COOLDOWN_MS = 60 * 60 * 1000L // 60 分钟冷却不应期
    }
}

class InterruptibilityEstimatorImpl : InterruptibilityEstimator {

    override fun estimateCost(
        snapshot: PerceptionSnapshot,
        lastInterruptionMs: Long,
        nowMs: Long
    ): Double {
        // 1. 银行/支付等金融隐私熔断保护，或正在通话中：直接封顶最高打扰代价
        if (snapshot.isPrivacyFusing || snapshot.hardwareEnvironment.isPhoneInCall) {
            return InterruptibilityEstimator.MAX_COST
        }

        var cost = InterruptibilityEstimator.BASE_COST

        // 2. 前台应用类型加成 / 扣减
        val pkg = snapshot.foregroundApp?.packageName?.lowercase() ?: ""
        cost += when {
            isLauncher(pkg) -> -1.0
            isGaming(pkg) -> 2.5
            isMeeting(pkg) -> 2.5
            isVideoOrStreaming(pkg) -> 1.5
            isCamera(pkg) -> 2.0
            isReadingOrDoc(pkg) -> 1.0
            else -> 0.0
        }

        // 3. 屏幕物理与交互状态
        cost += when (snapshot.screenState.state) {
            ScreenState.OFF -> 3.0
            ScreenState.ON -> 1.0
            ScreenState.UNLOCKED -> 0.0
        }

        // 4. 距上次主动打扰的冷却不应期衰减 (60 分钟)
        if (lastInterruptionMs > 0L && nowMs > lastInterruptionMs) {
            val elapsed = nowMs - lastInterruptionMs
            if (elapsed < InterruptibilityEstimator.COOLDOWN_MS) {
                val remainingRatio = 1.0 - (elapsed.toDouble() / InterruptibilityEstimator.COOLDOWN_MS)
                cost += remainingRatio * 2.0
            }
        }

        // 4.5 硬件物理态感知（耳机/音频播放）
        if (snapshot.hardwareEnvironment.isAudioPlaying) {
            cost += 1.0
        }
        if (snapshot.hardwareEnvironment.isHeadsetPlugged) {
            cost += 0.5
        }

        // 5. 边界夹紧
        return cost.coerceIn(InterruptibilityEstimator.MIN_COST, InterruptibilityEstimator.MAX_COST)
    }

    private fun isLauncher(pkg: String): Boolean {
        return pkg.contains("launcher") || pkg.contains("home") || pkg == "android"
    }

    private fun isGaming(pkg: String): Boolean {
        return pkg.contains("game") || pkg.contains("tmgp") || pkg.contains("mihoyo") ||
            pkg.contains("netease") || pkg.contains("honor") || pkg.contains("pubg")
    }

    private fun isMeeting(pkg: String): Boolean {
        return pkg.contains("meeting") || pkg.contains("zoom") || pkg.contains("voov") ||
            pkg.contains("teams") || pkg.contains("feishu") || pkg.contains("dingtalk")
    }

    private fun isVideoOrStreaming(pkg: String): Boolean {
        return pkg.contains("video") || pkg.contains("bilibili") || pkg.contains("iqiyi") ||
            pkg.contains("youku") || pkg.contains("douyin") || pkg.contains("kuaishou")
    }

    private fun isCamera(pkg: String): Boolean {
        return pkg.contains("camera")
    }

    private fun isReadingOrDoc(pkg: String): Boolean {
        return pkg.contains("reader") || pkg.contains("doc") || pkg.contains("novel") ||
            pkg.contains("office")
    }
}
