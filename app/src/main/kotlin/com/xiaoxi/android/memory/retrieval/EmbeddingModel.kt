package com.xiaoxi.android.memory.retrieval

/**
 * 文本向量化模型接口 (端侧神经向量化契约)
 *
 * 为记忆检索 (D21)、行动语义接地 (D23) 提供端侧 Embedding 输出。
 */
interface EmbeddingModel : AutoCloseable {

    /** 向量维度，bge-small-zh-v1.5 固定为 512 */
    val dimension: Int get() = 512

    /**
     * 对单句输入计算 L2 单位归一化 Embedding 向量
     */
    fun embed(text: String): FloatArray

    /**
     * 批量计算输入文本 Embedding 向量
     */
    fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }
}
