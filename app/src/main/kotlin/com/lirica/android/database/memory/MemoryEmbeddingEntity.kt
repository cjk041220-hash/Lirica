package com.lirica.android.database.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 记忆向量索引实体 (Track A 用户记忆与 Track B 自叙经历的端侧 512 维向量表)
 *
 * 存储 bge-small-zh-v1.5 输出的 512 维浮点向量。
 * 紧凑序列化为 2048 字节的二进制 BLOB (Little-Endian Float32 Array)，
 * 避免文本化浮点数带来的空间浪费与序列化开销。
 */
@Entity(
    tableName = "memory_embeddings",
    indices = [
        Index(value = ["track"], name = "idx_memory_embeddings_track")
    ]
)
data class MemoryEmbeddingEntity(
    @PrimaryKey
    @ColumnInfo(name = "memory_id")
    val memoryId: String,

    @ColumnInfo(name = "track")
    val track: String = TRACK_USER,

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 将二进制 BLOB 反序列化为 512 维 FloatArray
     */
    fun toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(embedding.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryEmbeddingEntity
        return memoryId == other.memoryId &&
                track == other.track &&
                embedding.contentEquals(other.embedding) &&
                updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + track.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    companion object {
        const val TRACK_USER = "user"
        const val TRACK_SELF = "self"

        /**
         * 将 512 维 FloatArray 编码为二进制实体
         */
        fun fromFloatArray(
            memoryId: String,
            track: String,
            vector: FloatArray,
            updatedAt: Long = System.currentTimeMillis()
        ): MemoryEmbeddingEntity {
            val byteBuffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asFloatBuffer().put(vector)
            return MemoryEmbeddingEntity(
                memoryId = memoryId,
                track = track,
                embedding = byteBuffer.array(),
                updatedAt = updatedAt
            )
        }
    }
}
