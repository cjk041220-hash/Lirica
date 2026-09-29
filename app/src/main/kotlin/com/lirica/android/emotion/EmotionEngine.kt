package com.lirica.android.emotion

import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Russell 情绪空间 (Valence-Arousal) 2D 动力学状态机
 *
 * 核心数学微分更新方程：
 * ΔE = α · Stimulus - β · (E - E_0)
 *
 * @param baseline 人格基线情绪锚点 E_0，默认 Lirica 为 (0.20f, 0.10f)（轻度正向、稍醒）
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
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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

    /**
     * 根据对话文本语义动态评估情绪冲激向量 (D22 情绪混合架构)
     */
    fun evaluateTextStimulus(userText: String, replyText: String = ""): EmotionVector {
        val lowerUser = userText.lowercase()

        return when {
            // 夸奖、喜爱、开心
            listOf("喜欢", "爱你", "真棒", "厉害", "谢谢", "太好了", "哈哈", "可爱", "真好", "开心").any { lowerUser.contains(it) } ->
                EmotionVector(valence = 0.25f, arousal = 0.20f)

            // 疲惫、难受、压力、低落（心疼、低唤醒安抚）
            listOf("累", "辛苦", "难受", "难过", "伤心", "压力", "抑郁", "好烦", "无语", "挂了", "哭了").any { lowerUser.contains(it) } ->
                EmotionVector(valence = 0.05f, arousal = -0.15f)

            // 抱怨、生气、冲突
            listOf("讨厌", "垃圾", "气死", "烦人", "讨厌你").any { lowerUser.contains(it) } ->
                EmotionVector(valence = -0.20f, arousal = 0.25f)

            // 疑惑、好奇、探究
            listOf("为什么", "怎么", "什么", "能不能", "真的吗", "?", "？").any { lowerUser.contains(it) } ->
                EmotionVector(valence = 0.10f, arousal = 0.15f)

            else ->
                EmotionVector(valence = 0.05f, arousal = 0.05f)
        }
    }
}
