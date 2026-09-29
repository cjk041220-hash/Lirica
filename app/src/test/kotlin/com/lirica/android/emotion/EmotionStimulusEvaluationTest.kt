package com.lirica.android.emotion

import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionStimulusEvaluationTest {

    private val engine = EmotionEngine()

    @Test
    fun testPraiseStimulus_elevatesValenceAndArousal() {
        val stimulus = engine.evaluateTextStimulus("Lirica你真棒，太喜欢你了！")
        assertTrue("夸奖应带来高正向效价", stimulus.valence >= 0.20f)
        assertTrue("夸奖应带来愉悦唤醒", stimulus.arousal >= 0.15f)
    }

    @Test
    fun testTiredStimulus_lowersArousalForQuietAccompaniment() {
        val stimulus = engine.evaluateTextStimulus("今天加班好累啊，感觉整个人都要散架了")
        assertTrue("疲倦共情应降低唤醒度，提供静谧陪伴", stimulus.arousal < 0f)
    }

    @Test
    fun testAngryStimulus_yieldsNegativeValence() {
        val stimulus = engine.evaluateTextStimulus("真烦人，气死我了，讨厌")
        assertTrue("愤怒或冲突输入应呈现负向效价", stimulus.valence < 0f)
        assertTrue("冲突应伴随警觉唤醒", stimulus.arousal > 0.10f)
    }
}
