package com.xiaoxi.android.emotion

import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Task 1.2: Russell 情绪空间 (Valence-Arousal) 2D 动力学状态机单元测试
 */
class EmotionEngineTest {

    private lateinit var engine: EmotionEngine
    private val baseline = EmotionVector(0.20f, 0.10f)

    @Before
    fun setUp() {
        engine = EmotionEngine(
            baseline = baseline,
            dampingBeta = 0.05f,
            sensitivityAlpha = 1.0f
        )
    }

    @Test
    fun testInitialStateIsBaseline() {
        val current = engine.currentVector
        assertEquals(baseline.valence, current.valence, 0.001f)
        assertEquals(baseline.arousal, current.arousal, 0.001f)
    }

    @Test
    fun testStimulusImpulseResponse() {
        // 施加正向愉悦冲激（如用户送礼物或夸奖）
        val stimulus = EmotionVector(0.60f, 0.40f)
        engine.applyStimulus(stimulus)

        val updated = engine.currentVector
        // 期望值计算：E_new = E_0 + alpha * Stimulus - beta * (E_0 - E_0) = (0.2 + 0.6, 0.1 + 0.4) = (0.8, 0.5)
        assertEquals(0.80f, updated.valence, 0.01f)
        assertEquals(0.50f, updated.arousal, 0.01f)

        // 离散映射应落入高能量正向微表情（如 "开心"）
        val mood = engine.currentMood
        assertEquals(MoodState.HAPPY, mood)
        assertEquals("开心", mood.displayName)
        assertEquals("happy", mood.spriteTag)
    }

    @Test
    fun testNegativeStimulusAndSulking() {
        // 施加负面偏执冲激（如用户敷衍、冒犯或不讲理）
        val stimulus = EmotionVector(-0.65f, -0.30f)
        engine.applyStimulus(stimulus)

        val updated = engine.currentVector
        assertTrue("Valence should be negative", updated.valence < 0f)

        // 离散映射应落入 "闹别扭" 或 "发呆" 等相应负面/防御微表情
        val mood = engine.currentMood
        assertTrue(
            "Should map to negative or low-arousal state like SULKING or CONCERNED",
            mood == MoodState.SULKING || mood == MoodState.CONCERNED || mood == MoodState.DAZE
        )
    }

    @Test
    fun testLongTermSilenceDecayTowardsBaseline() {
        // 先给一个极强的冲激推向极端开心兴奋 (0.9, 0.9)
        engine.applyStimulus(EmotionVector(0.8f, 0.8f))
        assertTrue(engine.currentVector.valence > 0.7f)

        // 模拟长程静默（120 个阻尼衰减步长，无外部刺激）
        engine.stepDamping(steps = 120)

        val settled = engine.currentVector
        // 经过长程阻尼衰减，必须平滑回归到人格基线附近
        assertEquals(baseline.valence, settled.valence, 0.02f)
        assertEquals(baseline.arousal, settled.arousal, 0.02f)
    }

    @Test
    fun testClampingBounds() {
        // 极端冲激输入，绝对不能突破 [-1.0, 1.0] 的情绪物理盒
        engine.applyStimulus(EmotionVector(10.0f, -10.0f))
        val clamped = engine.currentVector
        assertEquals(1.0f, clamped.valence, 0.001f)
        assertEquals(-1.0f, clamped.arousal, 0.001f)

        engine.applyStimulus(EmotionVector(-20.0f, 20.0f))
        val clampedOpposite = engine.currentVector
        assertEquals(-1.0f, clampedOpposite.valence, 0.001f)
        assertEquals(1.0f, clampedOpposite.arousal, 0.001f)

        // NaN 异常防护验证
        val nanVector = EmotionVector(Float.NaN, Float.NaN, Float.NaN).clamped()
        assertEquals(0.0f, nanVector.valence, 0.001f)
        assertEquals(0.0f, nanVector.arousal, 0.001f)
        assertEquals(0.0f, nanVector.dominance, 0.001f)
    }

    @Test
    fun testNearestNeighborMappingDeterminism() {
        // 精确验证已知情绪微表情坐标映射
        assertEquals(MoodState.HAPPY, MoodState.fromVector(EmotionVector(0.85f, 0.55f)))
        assertEquals(MoodState.EXCITED, MoodState.fromVector(EmotionVector(0.60f, 0.90f)))
        assertEquals(MoodState.DAZE, MoodState.fromVector(EmotionVector(0.05f, -0.35f)))
        assertEquals(MoodState.SLEEPY, MoodState.fromVector(EmotionVector(-0.10f, -0.80f)))
        assertEquals(MoodState.CONCERNED, MoodState.fromVector(EmotionVector(-0.55f, 0.25f)))
        assertEquals(MoodState.SULKING, MoodState.fromVector(EmotionVector(-0.45f, -0.15f)))
        assertEquals(MoodState.CONFUSED, MoodState.fromVector(EmotionVector(-0.15f, 0.45f)))
    }

    @Test
    fun testGaussianSamplingProximity() {
        // 在 "开心" 坐标附近进行高斯概率抽样
        val target = EmotionVector(0.85f, 0.55f)
        val samples = (1..100).map {
            MoodState.sampleGaussian(target, sigma = 0.25f)
        }

        // 绝大多数采样应落在开心及其近邻（兴奋、活泼、期待），远对径（如困倦）概率趋零
        val happyCount = samples.count { it == MoodState.HAPPY }
        val sleepyCount = samples.count { it == MoodState.SLEEPY }

        assertTrue("Target near HAPPY should sample HAPPY frequently (>30%)", happyCount > 30)
        assertEquals("Opposite quadrant SLEEPY should never be sampled from HAPPY", 0, sleepyCount)
    }
}
