package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.memory.retrieval.HybridMemoryRetriever
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryExtractorVectorWiringTest {

    @Test
    fun testExtraction_indexesIntoHybridRetriever() = runTest {
        val userMemoryDao: UserMemoryDao = mockk(relaxed = true)
        val hybridRetriever: HybridMemoryRetriever = mockk(relaxed = true)

        val jsonResponse = "{\"memories\":[{\"content\":\"用户特别喜欢喝热奶茶\",\"category\":\"偏好\",\"importance\":8}]}"
        val escaped = jsonResponse.replace("\\", "\\\\").replace("\"", "\\\"")
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"$escaped\"}}]}\n\ndata: [DONE]\n\n"

        val mockEngine = MockEngine {
            respond(
                content = sse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val extractor = MemoryExtractor(
            llmClient = XiaoxiLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1,
            hybridRetrieverProvider = { hybridRetriever }
        )

        val count = extractor.maybeExtract("我冬天就喜欢喝一杯热腾腾的热奶茶", "热奶茶确实让人觉得很温暖呢！")
        assertEquals(1, count)

        // 验证持久化落库
        coVerify(exactly = 1) {
            userMemoryDao.insert(match { it.content == "用户特别喜欢喝热奶茶" })
        }

        // 验证端侧向量检索库建立索引
        coVerify(exactly = 1) {
            hybridRetriever.indexMemories(match { it.size == 1 && it[0].content == "用户特别喜欢喝热奶茶" })
        }
    }
}
