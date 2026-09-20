package com.xiaoxi.android.emotion

import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Russell 情绪空间 (Valence-Arousal) 2D 动力学状态机
 *
 * 核心数学微分更新方程：
 * ΔE = α · Stimulus - β · (E - E_0)
 *
 * @param baseline 人格基线情绪锚点 E_0，默认小汐为 (0.20f, 0.10f)（轻度正向、稍醒）
 * @param dampingBeta 阻尼衰减系数 β ∈ (0, 1)，无外界刺激时向基线回归的速率
 * @param sensitivityAlpha 冲激敏感度 α > 0，外部事件转化为情绪增量的放大系数
 */
class EmotionEngine(
    val baseline: EmotionVector = EmotionVector(0.20f, 0.10f),
    val dampingBeta: Float = 0.05f,
    val sensitivityAlpha: Float = 1.0f
) {
    private val _stateFlow = MutableStateFlow(baseline)
    val stateFlow: StateFlow<EmotionVector> = _stateFlow.asStateFlow()

    private val _moodFlow = MutableStateFlow(MoodState.fromVector(baseline))
    val moodFlow: StateFlow<MoodState> = _moodFlow.asStateFlow()

    val currentVector: EmotionVector
        get() = _stateFlow.value

    val currentMood: MoodState
        get() = _moodFlow.value

    /**
     * 接收外部事件冲激并更新情绪状态：
     * ΔE = α · Stimulus - β · (E - E_0)
     * E_new = clamp(E + ΔE)
     */
    fun applyStimulus(stimulus: EmotionVector) {
        val current = _stateFlow.value
        val delta = (stimulus * sensitivityAlpha) - ((current - baseline) * dampingBeta)
        val next = (current + delta).clamped()
        _stateFlow.value = next
        _moodFlow.value = MoodState.fromVector(next)
    }

    /**
     * 模拟时间流逝下的长程静默阻尼衰减（向人格基线回归）：
     * E_next = E - β · (E - E_0)
     */
    fun stepDamping(steps: Int = 1) {
        var current = _stateFlow.value
        repeat(steps) {
            val delta = (current - baseline) * dampingBeta
            current = (current - delta).clamped()
        }
        _stateFlow.value = current
        _moodFlow.value = MoodState.fromVector(current)
    }

    /**
     * 重置到人格基线（用于早晨锚定或新会话周期初始化）
     */
    fun resetToBaseline() {
        _stateFlow.value = baseline
        _moodFlow.value = MoodState.fromVector(baseline)
    }

    /**
     * 获取当前情绪对应的文风与口吻提示语（用于 Prompt 注入）
     */
    fun getStyleHint(): String {
        return currentMood.styleHint
    }
}
