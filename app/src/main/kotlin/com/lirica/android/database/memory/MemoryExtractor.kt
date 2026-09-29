package com.lirica.android.database.memory

import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.memory.retrieval.HybridMemoryRetriever
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 长期记忆抽取器（Track A 写入端 - 带节流门控与水位线管理）
 *
 * 遵循 P8 规范：加"距上次抽取 >3 分钟或积攒 ≥3 轮"的节流门，抽取时附带最近 N 条未抽取消息的批量上下文，
 * 抽取成功后记录 watermark 并重置累积状态。
 * 任何环节失败均静默返回，绝不影响对话主链路。
 */
class MemoryExtractor(
    private val llmClient: LiricaLlmClient,
    private val userMemoryDao: UserMemoryDao,
    private val llmConfig: LlmConfig,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    val throttleIntervalMs: Long = 180_000L,
    val minTurnsAccumulated: Int = 3,
    private val hybridRetrieverProvider: (() -> HybridMemoryRetriever?)? = null
) {

    companion object {
        /** 用户文本低于该长度不值得发起一次 LLM 抽取调用 */
        const val MIN_USER_TEXT_LENGTH = 8

        /** 单条记忆最大长度，超过视为幻觉产物丢弃 */
        const val MAX_MEMORY_LENGTH = 200

        /**
         * 连续失败退避基数：第 n 次连续失败后，下次自动触发需等待 60s·2^(n-1)，
         * 上限 [FAILURE_BACKOFF_MAX_MS]。弱网下"回填→重发→再失败"每轮对话都会打一次
         * 必然失败的 LLM 请求（重试风暴），退避让批次在队列里自然等待而非立刻重试。
         */
        const val FAILURE_BACKOFF_BASE_MS = 60_000L

        /** 退避上限：封顶 15 分钟，避免长时间弱网下退避无限拉长、积压批次越滚越大 */
        const val FAILURE_BACKOFF_MAX_MS = 15 * 60_000L

        private val EXTRACTION_SYSTEM_PROMPT = """
            你是记忆抽取器。从对话的用户发言中提取值得长期记住的用户事实（偏好、习惯、人际关系、作息、计划、身份信息等）。
            规则：
            1. 只提取关于用户本人的客观事实，不要提取助手的回复内容
            2. 没有值得记录的信息时返回空列表
            3. 严格只输出 JSON，不要输出任何其他文字或 markdown 代码块标记，格式：
               {"memories":[{"content":"事实描述","category":"分类","importance":5}]}
            4. category 可选值：偏好/作息/人际关系/计划/身份/其他；importance 为 1-10 整数
            5. 每条 content 以"用户"开头，不超过 60 字
        """.trimIndent()
    }

    private val pendingTurns = mutableListOf<String>()
    private var batchStartTimestamp: Long = 0L
    private var lastExtractionTimestamp: Long = 0L

    /** LLM 抽取连续失败计数；成功后归零。退避窗口由它驱动 */
    private var consecutiveFailures: Int = 0

    /** 最近一次失败的时间戳，配合 [consecutiveFailures] 计算退避窗口 */
    private var lastFailureTimestamp: Long = 0L

    fun getPendingTurnCount(): Int = synchronized(this) { pendingTurns.size }

    fun getLastExtractionTime(): Long = synchronized(this) { lastExtractionTimestamp }

    fun getConsecutiveFailureCount(): Int = synchronized(this) { consecutiveFailures }

    fun resetWatermark() = synchronized(this) {
        pendingTurns.clear()
        batchStartTimestamp = 0L
        lastExtractionTimestamp = 0L
        consecutiveFailures = 0
        lastFailureTimestamp = 0L
    }

    @Serializable
    data class ExtractedMemory(
        val content: String,
        val category: String = "其他",
        val importance: Int = 5
    )

    @Serializable
    data class ExtractionResult(
        val memories: List<ExtractedMemory> = emptyList()
    )

    /**
     * 抽取并入库。
     * 支持节流判定：仅当 force = true、积攒轮次 >= minTurnsAccumulated 或超出 throttleIntervalMs 冷却期时真正发起 LLM 请求。
     * 用户文本过短且无历史积攒、未达节流条件、LLM 失败、解析失败时返回 0，静默不影响调用方。
     */
    suspend fun maybeExtract(
        userText: String,
        assistantText: String = "",
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): Int {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() && !force) return 0
        if (trimmed.length < MIN_USER_TEXT_LENGTH && pendingTurns.isEmpty() && !force) return 0

        val batchContext: String
        var failedBatchStart: Long = 0L
        synchronized(this) {
            if (trimmed.isNotEmpty()) {
                if (pendingTurns.isEmpty()) {
                    batchStartTimestamp = nowMs
                }
                pendingTurns.add(trimmed)
            }

            val count = pendingTurns.size
            val timeElapsed = if (batchStartTimestamp > 0) nowMs - batchStartTimestamp else 0L

            // 退避闸门：连续失败后的冷却期内不自动触发，批次继续积攒（数据不丢，
            // 只是不立刻重发）。force = true 为显式人工动作，不受退避限制。
            val backoffActive = consecutiveFailures > 0 && !force &&
                (nowMs - lastFailureTimestamp) < backoffMillis(consecutiveFailures)

            val shouldTrigger = !backoffActive && (
                force ||
                    (count >= minTurnsAccumulated) ||
                    (batchStartTimestamp > 0 && timeElapsed >= throttleIntervalMs && count > 0)
                )

            if (shouldTrigger && pendingTurns.isNotEmpty()) {
                batchContext = pendingTurns.joinToString("\n---\n")
                failedBatchStart = batchStartTimestamp
                pendingTurns.clear()
                batchStartTimestamp = 0L
                lastExtractionTimestamp = nowMs
            } else {
                batchContext = ""
            }
        }

        if (batchContext.isEmpty()) {
            return 0
        }

        return try {
            val response = requestExtraction(batchContext)
            val stored = parseAndStore(response)
            // LLM 调用本身已成功（解析失败不算失败），退避计数归零
            synchronized(this) { consecutiveFailures = 0 }
            stored
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // LLM 请求失败（网络瞬态错误等）：回填批次，避免整批对话上下文永久丢失（方案 #13），
            // 同时递增失败计数进入指数退避——弱网下不再每轮对话都重发一次必然失败的请求。
            // 注意 parseAndStore 内部的解析失败返回 0 不走此路径。
            synchronized(this) {
                consecutiveFailures += 1
                lastFailureTimestamp = nowMs
            }
            restoreFailedBatch(batchContext, failedBatchStart)
            0
        }
    }

    /** 第 failures 次连续失败后的退避窗口：60s 起指数翻倍，封顶 15 分钟。internal 供单测断言 */
    internal fun backoffMillis(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 4)
        return (FAILURE_BACKOFF_BASE_MS shl shift).coerceAtMost(FAILURE_BACKOFF_MAX_MS)
    }

    /**
     * LLM 抽取失败时把批次内容拆回原 turn 插到队首，保持时间顺序。
     * 批次起点取更早者：若请求期间已有新批次开始（其起点更晚），恢复失败批次起点可保证
     * 节流窗口从最早的未抽取内容连续计算。
     */
    private fun restoreFailedBatch(batchContext: String, failedBatchStart: Long) {
        if (batchContext.isBlank()) return
        val turns = batchContext.split("\n---\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (turns.isEmpty()) return
        synchronized(this) {
            pendingTurns.addAll(0, turns)
            if (failedBatchStart > 0 && (batchStartTimestamp == 0L || failedBatchStart < batchStartTimestamp)) {
                batchStartTimestamp = failedBatchStart
            }
        }
    }

    private suspend fun requestExtraction(batchContext: String): String {
        val messages = listOf(
            ChatMessage(role = "system", content = EXTRACTION_SYSTEM_PROMPT),
            ChatMessage(role = "user", content = "用户近期发言内容：\n$batchContext")
        )
        val sb = StringBuilder()
        llmClient.streamChat(llmConfig, messages).collect { event ->
            when (event) {
                is LlmStreamEvent.Token -> sb.append(event.text)
                is LlmStreamEvent.Error -> throw event.throwable
                else -> {}
            }
        }
        return sb.toString()
    }

    private suspend fun parseAndStore(response: String): Int {
        val payload = extractJsonPayload(response) ?: return 0
        val result = runCatching {
            json.decodeFromString<ExtractionResult>(payload)
        }.getOrNull() ?: return 0

        val now = System.currentTimeMillis()
        val entities = result.memories
            .map { it.copy(content = it.content.trim()) }
            .filter { it.content.isNotEmpty() && it.content.length <= MAX_MEMORY_LENGTH }
            .distinctBy { it.content }
            .map { extracted ->
                UserMemoryEntity(
                    id = UUID.randomUUID().toString(),
                    content = extracted.content,
                    category = extracted.category.ifBlank { "其他" },
                    confidence = 0.8f,
                    importance = extracted.importance.coerceIn(1, 10),
                    createdAt = now,
                    source = "llm_extraction"
                )
            }
        if (entities.isEmpty()) return 0
        entities.forEach { userMemoryDao.insert(it) }

        // 批次 2.5/P0 贯通：抽取得到的新记忆异步建立端侧向量索引
        hybridRetrieverProvider?.invoke()?.let { retriever ->
            runCatching {
                retriever.indexMemories(entities)
            }
        }
        return entities.size
    }

    /**
     * 从 LLM 回复中剥离 markdown 代码围栏与闲聊文本，截取 JSON 对象本体
     */
    internal fun extractJsonPayload(response: String): String? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
            .find(trimmed)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }
}
