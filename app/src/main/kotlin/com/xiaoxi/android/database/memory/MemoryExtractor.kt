package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 长期记忆抽取器（Track A 写入端）
 *
 * 对话结束后异步调用：将本轮用户发言交给 LLM 做结构化事实抽取，
 * 解析 JSON 结果并写入 user_memories 表，补齐记忆系统"只读不写"的缺口。
 * 任何环节失败均静默返回，绝不影响对话主链路。
 */
class MemoryExtractor(
    private val llmClient: XiaoxiLlmClient,
    private val userMemoryDao: UserMemoryDao,
    private val llmConfig: LlmConfig,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    companion object {
        /** 用户文本低于该长度不值得发起一次 LLM 抽取调用 */
        const val MIN_USER_TEXT_LENGTH = 8

        /** 单条记忆最大长度，超过视为幻觉产物丢弃 */
        const val MAX_MEMORY_LENGTH = 200

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
     * 抽取并入库。返回本次成功写入的条数（测试与调试用）。
     * 用户文本过短、LLM 失败、解析失败时返回 0，静默不影响调用方。
     */
    suspend fun maybeExtract(userText: String, assistantText: String = ""): Int {
        val trimmed = userText.trim()
        if (trimmed.length < MIN_USER_TEXT_LENGTH) return 0

        return try {
            val response = requestExtraction(trimmed)
            parseAndStore(response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            0
        }
    }

    private suspend fun requestExtraction(userText: String): String {
        val messages = listOf(
            ChatMessage(role = "system", content = EXTRACTION_SYSTEM_PROMPT),
            ChatMessage(role = "user", content = "用户本轮发言：\n$userText")
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
