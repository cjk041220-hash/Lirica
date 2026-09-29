package com.lirica.android.memory.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

class OnnxInferenceExperimentTest {

    @Test
    fun testDirectInferenceAndSimilarity() {
        val modelFile = File("src/main/assets/models/bge-small-zh/model_quantized.onnx")
        val tokenizerFile = File("src/main/assets/models/bge-small-zh/tokenizer.json")
        val tokenizer = BertWordPieceTokenizer.fromTokenizerJson(tokenizerFile.readText())

        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        val session = env.createSession(modelFile.absolutePath, sessionOptions)

        fun embedText(text: String): FloatArray {
            val tokenized = tokenizer.tokenize(text)
            val seqLen = tokenized.size.toLong()
            val shape = longArrayOf(1L, seqLen)

            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.inputIds), shape)
            val attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.attentionMask), shape)
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.tokenTypeIds), shape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor
            )

            val result = session.run(inputs)
            val outputTensor = result.get(0) as OnnxTensor
            val value = outputTensor.value as Array<Array<FloatArray>>
            val batch0 = value[0] // shape: [seqLen, 512]

            // Mean pooling with attention_mask
            val pooled = FloatArray(512)
            var validCount = 0f
            for (i in tokenized.attentionMask.indices) {
                if (tokenized.attentionMask[i] == 1L) {
                    validCount += 1f
                    val tokenVec = batch0[i]
                    for (d in 0 until 512) {
                        pooled[d] += tokenVec[d]
                    }
                }
            }
            if (validCount > 0f) {
                for (d in 0 until 512) {
                    pooled[d] /= validCount
                }
            }

            // L2 normalize
            var sumSq = 0f
            for (d in 0 until 512) {
                sumSq += pooled[d] * pooled[d]
            }
            val norm = sqrt(sumSq).coerceAtLeast(1e-9f)
            for (d in 0 until 512) {
                pooled[d] /= norm
            }

            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
            outputTensor.close()
            result.close()

            return pooled
        }

        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
            }
            return dot
        }

        val emb1 = embedText("喜欢周杰伦")
        val emb2 = embedText("爱听周董的歌")
        val emb3 = embedText("今天天气不错")

        assertEquals(512, emb1.size)
        assertEquals(512, emb2.size)
        assertEquals(512, emb3.size)

        val simRelated = dotProduct(emb1, emb2)
        val simUnrelated = dotProduct(emb1, emb3)

        println("sim(喜欢周杰伦, 爱听周董的歌) = $simRelated")
        println("sim(喜欢周杰伦, 今天天气不错) = $simUnrelated")

        assertTrue("Related similarity should be higher than unrelated", simRelated > simUnrelated)

        session.close()
    }
}
