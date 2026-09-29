package com.lirica.android.memory.retrieval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * 基于 ONNX Runtime 与 BGE-Small-ZH-v1.5 的端侧向量化推导引擎
 *
 * 核心流水线：
 * 1. 纯 Kotlin BERT WordPiece 分词 (BertWordPieceTokenizer)
 * 2. 构造 [1, seqLen] int64 Tensor (input_ids, attention_mask, token_type_ids)
 * 3. ONNX Session 推理（线程数上限 2，防手机发热节流）
 * 4. Mean Pooling（依据 attention_mask 排除 padding，聚合 token 语义表征）
 * 5. L2 单位向量归一化 (Unit Normalization，使向量点积精确等价于余弦相似度)
 */
class LiricaEmbeddingModel(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    private val tokenizer: BertWordPieceTokenizer,
    private val closeEnvironmentOnClose: Boolean = false
) : EmbeddingModel {

    override val dimension: Int = 512

    @Synchronized
    override fun embed(text: String): FloatArray {
        val tokenized = tokenizer.tokenize(text)
        val seqLen = tokenized.size.toLong()
        val shape = longArrayOf(1L, seqLen)

        val inputIdsTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(tokenized.inputIds),
            shape
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(tokenized.attentionMask),
            shape
        )
        val tokenTypeIdsTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(tokenized.tokenTypeIds),
            shape
        )

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )

        try {
            session.run(inputs).use { result ->
                val outputTensor = result.get(0) as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val value = outputTensor.value as Array<Array<FloatArray>>
                val batch0 = value[0] // shape: [seqLen, 512]

                // Mean pooling 依 attention_mask 聚合
                val pooled = FloatArray(dimension)
                var validTokens = 0f
                for (i in tokenized.attentionMask.indices) {
                    if (tokenized.attentionMask[i] == 1L) {
                        validTokens += 1f
                        val tokenVec = batch0[i]
                        for (d in 0 until dimension) {
                            pooled[d] += tokenVec[d]
                        }
                    }
                }

                if (validTokens > 0f) {
                    for (d in 0 until dimension) {
                        pooled[d] /= validTokens
                    }
                }

                // L2 单位向量归一化
                var sumSq = 0f
                for (d in 0 until dimension) {
                    sumSq += pooled[d] * pooled[d]
                }
                val norm = sqrt(sumSq).coerceAtLeast(1e-9f)
                for (d in 0 until dimension) {
                    pooled[d] /= norm
                }

                return pooled
            }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
        }
    }

    override fun close() {
        session.close()
        if (closeEnvironmentOnClose) {
            environment.close()
        }
    }

    companion object {
        const val DEFAULT_MODEL_PATH = "models/bge-small-zh/model_quantized.onnx"
        const val DEFAULT_TOKENIZER_PATH = "models/bge-small-zh/tokenizer.json"
        const val DEFAULT_THREAD_COUNT = 2

        /**
         * 基于本地文件创建 EmbeddingModel 实例 (适合单测或本地调试)
         */
        fun create(
            modelFile: File,
            tokenizerFile: File,
            numThreads: Int = DEFAULT_THREAD_COUNT
        ): LiricaEmbeddingModel {
            val env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
            }
            val session = env.createSession(modelFile.absolutePath, sessionOpts)
            val tokenizer = BertWordPieceTokenizer.fromTokenizerJson(tokenizerFile.readText())
            return LiricaEmbeddingModel(
                session = session,
                environment = env,
                tokenizer = tokenizer,
                closeEnvironmentOnClose = false
            )
        }

        /**
         * 从 Android Assets 读取模型与词表初始化 (适合运行时生产环境)
         */
        fun createFromAssets(
            context: Context,
            modelAssetPath: String = DEFAULT_MODEL_PATH,
            tokenizerAssetPath: String = DEFAULT_TOKENIZER_PATH,
            numThreads: Int = DEFAULT_THREAD_COUNT
        ): LiricaEmbeddingModel {
            val env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
            }
            val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
            val session = env.createSession(modelBytes, sessionOpts)
            val tokenizer = context.assets.open(tokenizerAssetPath).use {
                BertWordPieceTokenizer.fromInputStream(it)
            }
            return LiricaEmbeddingModel(
                session = session,
                environment = env,
                tokenizer = tokenizer,
                closeEnvironmentOnClose = false
            )
        }

        /**
         * 从字节流初始化
         */
        fun createFromBytes(
            modelBytes: ByteArray,
            tokenizerInputStream: InputStream,
            numThreads: Int = DEFAULT_THREAD_COUNT
        ): LiricaEmbeddingModel {
            val env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
            }
            val session = env.createSession(modelBytes, sessionOpts)
            val tokenizer = BertWordPieceTokenizer.fromInputStream(tokenizerInputStream)
            return LiricaEmbeddingModel(
                session = session,
                environment = env,
                tokenizer = tokenizer,
                closeEnvironmentOnClose = false
            )
        }
    }
}
