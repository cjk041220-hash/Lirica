package com.lirica.android.memory.retrieval

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

class LiricaEmbeddingModelTest {

    companion object {
        private lateinit var embeddingModel: LiricaEmbeddingModel

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val modelFile = File("src/main/assets/models/bge-small-zh/model_quantized.onnx")
            val tokenizerFile = File("src/main/assets/models/bge-small-zh/tokenizer.json")
            embeddingModel = LiricaEmbeddingModel.create(modelFile, tokenizerFile, numThreads = 2)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            embeddingModel.close()
        }

        private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
            }
            return dot
        }
    }

    @Test
    fun testDimensionAssertion() {
        assertEquals(512, embeddingModel.dimension)
        val vec = embeddingModel.embed("你好，小夕")
        assertEquals(512, vec.size)
    }

    @Test
    fun testDeterminism() {
        val text = "喜欢在下雨天喝热奶茶"
        val vec1 = embeddingModel.embed(text)
        val vec2 = embeddingModel.embed(text)

        assertEquals(512, vec1.size)
        assertEquals(512, vec2.size)
        for (i in 0 until 512) {
            assertEquals("Component $i must match", vec1[i], vec2[i], 1e-6f)
        }
    }

    @Test
    fun testNonZeroAssertion() {
        val vec = embeddingModel.embed("自我见闻与生活点滴")
        var sumSquares = 0f
        var nonZeroCount = 0
        for (v in vec) {
            assertFalse("Component should not be NaN", v.isNaN())
            if (v != 0f) nonZeroCount++
            sumSquares += v * v
        }
        assertTrue("Embedding must have non-zero elements", nonZeroCount > 100)
        // L2 归一化模长应接近 1.0
        assertEquals(1.0f, sumSquares, 1e-3f)
    }

    @Test
    fun testRelativeSimilarityAssertion() {
        val query = embeddingModel.embed("喜欢周杰伦")
        val related = embeddingModel.embed("爱听周董的歌")
        val unrelated = embeddingModel.embed("今天天气不错")

        val simRelated = cosineSimilarity(query, related)
        val simUnrelated = cosineSimilarity(query, unrelated)

        println("sim(喜欢周杰伦, 爱听周董的歌) = $simRelated")
        println("sim(喜欢周杰伦, 今天天气不错) = $simUnrelated")

        assertTrue(
            "语义强相关'周董'相似度 ($simRelated) 必须大于语义无关'天气不错' ($simUnrelated)",
            simRelated > simUnrelated
        )
        // 语义相关余弦相似度应有明显裕度 (> 0.2 差距)
        assertTrue("相关性显著区分", (simRelated - simUnrelated) > 0.2f)
    }

    @Test
    fun testBatchEmbedding() {
        val texts = listOf("记忆碎片1", "记忆碎片2", "记忆碎片3")
        val batch = embeddingModel.embedBatch(texts)
        assertEquals(3, batch.size)
        batch.forEach {
            assertEquals(512, it.size)
        }
    }

    @Test
    fun testInferenceLatencyEstimate() {
        // 预热 1 次
        embeddingModel.embed("预热推导")

        val start = System.currentTimeMillis()
        val count = 5
        for (i in 0 until count) {
            embeddingModel.embed("测试推理延迟样例第 $i 条：今天学习了 Android 知识")
        }
        val elapsed = System.currentTimeMillis() - start
        val avgLatency = elapsed.toDouble() / count
        println("bge-small-zh-v1.5 INT8 推理平均耗时: ${avgLatency}ms/条 (Robolectric/JVM 估算，待真机)")
        // 断言耗时在合理区间（通常在 PC JVM 上每条 10~50ms，远小于 200ms）
        assertTrue("平均耗时应低于 200ms", avgLatency < 200.0)
    }
}
