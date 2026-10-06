package com.lirica.android.automation.verify

import com.lirica.android.automation.grounding.ExecutionNode
import com.lirica.android.perception.FastbotStateHasher
import com.lirica.android.perception.ViewSignature
import kotlinx.coroutines.delay

/**
 * 页面指纹快照（M5.2 双采样抗噪收敛点 · 主方案5.0 §5.1.4 / P2-3）。
 */
data class PageFingerprint(
    val hash: Long,
    val packageName: String,
    val activityName: String,
    val nodeCount: Int,
    val sampledAtMs: Long
) {
    /** 有效指纹：0 保留给"无指纹"语义（与既有 pageFingerprint 实现对齐） */
    val isValid: Boolean get() = hash != 0L

    companion object {
        val NONE = PageFingerprint(0L, "", "", 0, 0L)
    }
}

/** 一次页面采样（可空语义由调用方处理） */
data class PageSample(
    val packageName: String,
    val activityName: String,
    val nodes: List<ViewSignature>
)

/** 目标节点的状态印记（用于"页面没变但控件状态变了"的细粒度核销） */
data class NodeStateToken(
    val enabled: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val text: String?
)

/** 动作核销结论 */
sealed interface ActionVerification {
    /** 观察到生效：页面指纹变化 或 目标控件状态变化 */
    data class EffectObserved(
        val reason: EffectReason,
        val before: PageFingerprint?,
        val after: PageFingerprint?
    ) : ActionVerification

    /** 双采样稳定但页面与控件状态均未变化：动作未生效 */
    data class NoVisibleEffect(
        val before: PageFingerprint?,
        val after: PageFingerprint?
    ) : ActionVerification

    /** 连续采样不一致：页面仍在震荡（加载/动画/轮播），此时不得判定"未生效" */
    data class Unstable(val samples: List<PageFingerprint>) : ActionVerification

    /** 采样不可用（无障碍树为空、息屏、隐私熔断） */
    data class Unavailable(val reason: String) : ActionVerification

    enum class EffectReason {
        PAGE_FINGERPRINT_CHANGED,
        NODE_STATE_CHANGED
    }
}

/**
 * 双采样抗噪指纹服务（主方案5.0 §5.1.4）。
 *
 * 机制（严格按方案）：
 * 1. 操作后间隔 [settleDelayMs]（默认 300ms）连续采样；
 * 2. **两次采样一致**才视为页面稳定，避免把动画中间帧当成最终态；
 * 3. 稳定后与动作前指纹比较，并结合目标控件 enabled/checked 状态改变判定生效；
 * 4. 采样始终不一致时返回 [ActionVerification.Unstable]——这是关键设计：
 *    把"没看清"与"没生效"分开，否则加载慢的页面会被误判为失败并触发自愈重试，
 *    白白烧掉 3 次自愈额度与 120 秒任务预算。
 */
class StateFingerprintService(
    private val sampler: suspend () -> PageSample?,
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {

    /** 单次采样并计算指纹；采样不可用时返回 null */
    suspend fun sampleOnce(): PageFingerprint? {
        val sample = sampler() ?: return null
        if (sample.nodes.isEmpty()) return null
        val hash = FastbotStateHasher.computeViewFingerprint(
            activityName = sample.activityName,
            packageName = sample.packageName,
            views = sample.nodes
        )
        return PageFingerprint(
            hash = hash,
            packageName = sample.packageName,
            activityName = sample.activityName,
            nodeCount = sample.nodes.size,
            sampledAtMs = timeProvider()
        )
    }

    /** 连续两次一致即判定稳定；返回稳定指纹或 null（震荡/不可用） */
    suspend fun awaitStable(): StabilityOutcome {
        val samples = mutableListOf<PageFingerprint>()
        var previous: PageFingerprint? = null
        for (i in 0 until maxSamples) {
            val current = sampleOnce()
            if (current == null) {
                if (i == 0) return StabilityOutcome.Unavailable
                return StabilityOutcome.Unstable(samples.toList())
            }
            samples.add(current)
            if (previous != null && previous.hash == current.hash) {
                return StabilityOutcome.Stable(current, samples.size)
            }
            previous = current
            if (i < maxSamples - 1) sleeper(settleDelayMs)
        }
        return StabilityOutcome.Unstable(samples.toList())
    }

    /**
     * 动作后核销。
     *
     * @param before 动作前指纹（可为 null，表示动作前未采样）
     * @param nodeProbe 目标控件状态探针（可选）：页面指纹不变但控件状态变化时判生效
     */
    suspend fun verifyAfterAction(
        before: PageFingerprint?,
        nodeProbe: (suspend () -> NodeStateToken?)? = null,
        nodeStateBefore: NodeStateToken? = null
    ): ActionVerification {
        sleeper(settleDelayMs)
        when (val stability = awaitStable()) {
            is StabilityOutcome.Unavailable -> return ActionVerification.Unavailable("双采样不可用：无障碍树为空或页面不可读")
            is StabilityOutcome.Unstable -> return ActionVerification.Unstable(stability.samples)
            is StabilityOutcome.Stable -> {
                val after = stability.fingerprint
                val pageChanged = before != null && before.isValid && before.hash != after.hash
                if (pageChanged) {
                    return ActionVerification.EffectObserved(
                        reason = ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED,
                        before = before,
                        after = after
                    )
                }
                if (nodeProbe != null) {
                    val nodeAfter = nodeProbe()
                    if (nodeAfter != null && nodeStateBefore != null && nodeAfter != nodeStateBefore) {
                        return ActionVerification.EffectObserved(
                            reason = ActionVerification.EffectReason.NODE_STATE_CHANGED,
                            before = before,
                            after = after
                        )
                    }
                }
                return ActionVerification.NoVisibleEffect(before = before, after = after)
            }
        }
    }

    sealed interface StabilityOutcome {
        data class Stable(val fingerprint: PageFingerprint, val sampleCount: Int) : StabilityOutcome
        data class Unstable(val samples: List<PageFingerprint>) : StabilityOutcome
        data object Unavailable : StabilityOutcome
    }

    companion object {
        /** 方案 §5.1.4 规定的双采样间隔 */
        const val DEFAULT_SETTLE_DELAY_MS: Long = 300L

        const val DEFAULT_MAX_SAMPLES: Int = 3
    }
}

/**
 * 执行树节点 → 视图签名（执行闭环侧的适配层）。
 *
 * 放在 verify 包而非 perception：perception 是对系统 Binder 的浅拷贝域，
 * 不应反向依赖 automation 的执行树模型（否则会形成包级循环依赖）。
 */
fun List<ExecutionNode>.toViewSignatures(): List<ViewSignature> = map { node ->
    ViewSignature(
        text = node.text,
        contentDescription = node.contentDescription,
        className = node.className,
        viewId = node.resourceId,
        isClickable = node.isClickable,
        isEditable = node.isEditable,
        isPassword = node.isPassword,
        isEnabled = node.isEnabled,
        isChecked = node.isChecked,
        isSelected = false
    )
}
