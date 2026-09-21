package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.UserMemoryEntity
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MemoryExtractorTest {

    private lateinit var userMemoryDao: UserMemoryDao
    private lateinit var extractor: MemoryExtractor
    private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        userMemoryDao = mockk(relaxed = true)
    }

    private fun buildExtractor(responseData: String): MemoryExtractor {
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseData,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }
        return MemoryExtractor(
            llmClient = XiaoxiLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1
        )
    }

    private fun sseResponse(vararg contents: String): String {
        val sb = StringBuilder()
        contents.forEach { c ->
            // content 作为 JSON 字符串值嵌入 SSE 载荷，必须转义引号与反斜杠
            val escaped = c.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$escaped\"}}]}\n\n")
        }
        sb.append("data: [DONE]\n\n")
        return sb.toString()
    }

    @Test
    fun testMaybeExtract_parsesJsonAndStores() = runTest {
        coEvery { userMemoryDao.insert(any()) } returns 1L
        val extractor = buildExtractor(
            sseResponse(
                "{\"memories\":",
                "[{\"content\":\"用户喜欢在晚上跑步\",\"category\":\"作息\",\"importance\":7}]}",
                ""
            )
        )

        val count = extractor.maybeExtract("我最近每天晚上都会去江边跑步，感觉很解压。")

        assertEquals(1, count)
        coVerify(exactly = 1) {
            userMemoryDao.insert(withArg { entity: UserMemoryEntity ->
                assertEquals("用户喜欢在晚上跑步", entity.content)
                assertEquals("作息", entity.category)
                assertEquals(7, entity.importance)
                assertEquals("llm_extraction", entity.source)
            })
        }
    }

    @Test
    fun testMaybeExtract_stripsMarkdownFence() = runTest {
        coEvery { userMemoryDao.insert(any()) } returns 1L
        // \n 以 JSON 转义序列形式嵌入 content，真实场景下围栏跨行输出经 SSE 传输同理
        val extractor = buildExtractor(
            sseResponse("```json\\n{\"memories\":[{\"content\":\"用户养了一只猫\"}]}\\n```")
        )

        val count = extractor.maybeExtract("我家猫今天又把我的键盘踩了一遍，太调皮了。")

        assertEquals(1, count)
        coVerify(exactly = 1) {
            userMemoryDao.insert(withArg { entity: UserMemoryEntity ->
                assertEquals("用户养了一只猫", entity.content)
                assertEquals("其他", entity.category)
            })
        }
    }

    @Test
    fun testMaybeExtract_shortUserText_skipsLlmCall() = runTest {
        val extractor = buildExtractor(sseResponse("{}"))

        val count = extractor.maybeExtract("好的")

        assertEquals(0, count)
        coVerify(exactly = 0) { userMemoryDao.insert(any()) }
    }

    @Test
    fun testMaybeExtract_invalidJson_returnsZeroSilently() = runTest {
        val extractor = buildExtractor(sseResponse("这不是 JSON"))

        val count = extractor.maybeExtract("今天天气不错，我们去公园走走吧。")

        assertEquals(0, count)
        coVerify(exactly = 0) { userMemoryDao.insert(any()) }
    }

    @Test
    fun testMaybeExtract_emptyMemories_returnsZero() = runTest {
        val extractor = buildExtractor(sseResponse("{\"memories\":[]}"))

        val count = extractor.maybeExtract("帮我看看明天天气怎么样？")

        assertEquals(0, count)
        coVerify(exactly = 0) { userMemoryDao.insert(any()) }
    }

    @Test
    fun testMaybeExtract_deduplicatesAndClampsImportance() = runTest {
        coEvery { userMemoryDao.insert(any()) } returns 1L
        val extractor = buildExtractor(
            sseResponse(
                "{\"memories\":[{\"content\":\"用户喜欢咖啡\",\"importance\":99}," +
                    "{\"content\":\"用户喜欢咖啡\",\"importance\":3}]}"
            )
        )

        val count = extractor.maybeExtract("我每天早上都要喝一杯咖啡才能清醒过来。")

        assertEquals(1, count)
        coVerify(exactly = 1) {
            userMemoryDao.insert(withArg { entity: UserMemoryEntity ->
                assertEquals(10, entity.importance)
            })
        }
    }

    @Test
    fun testMaybeExtract_llmFailure_restoresBatchAndRetries() = runTest {
        // 先网络失败、后成功：验证失败批次回填后能随下一次触发被重新抽取，不丢失对话上下文
        var shouldFail = true
        val mockEngine = MockEngine { _ ->
            if (shouldFail) throw RuntimeException("network down")
            respond(
                content = sseResponse(
                    "{\"memories\":",
                    "[{\"content\":\"用户喜欢在晚上跑步\",\"category\":\"作息\"}]}"
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }
        val extractor = MemoryExtractor(
            llmClient = XiaoxiLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1
        )
        coEvery { userMemoryDao.insert(any()) } returns 1L

        // 第一次：LLM 网络失败，返回 0 但批次必须回填
        val firstCount = extractor.maybeExtract("我最近每天晚上都会去江边跑步，感觉很解压。")
        assertEquals(0, firstCount)
        assertEquals("失败批次必须回填到 pendingTurns", 1, extractor.getPendingTurnCount())

        // 第二次：网络恢复，回填的批次内容应随重试被成功抽取
        shouldFail = false
        val secondCount = extractor.maybeExtract("今晚又去江边跑了五公里，状态回来了。")
        assertEquals(1, secondCount)
        coVerify(exactly = 1) {
            userMemoryDao.insert(withArg { entity: UserMemoryEntity ->
                assertEquals("用户喜欢在晚上跑步", entity.content)
            })
        }
        assertEquals("抽取成功后队列清空", 0, extractor.getPendingTurnCount())
    }

    @Test
    fun testMaybeExtract_llmFailureDuringNewBatch_restoresAtFrontKeepsOrder() = runTest {
        // 失败回填后，若新 turn 已先入队，旧批次内容必须插到队首保持时间顺序
        var shouldFail = true
        val mockEngine = MockEngine { _ ->
            if (shouldFail) throw RuntimeException("network down")
            respond(
                content = sseResponse("{\"memories\":[{\"content\":\"用户喜欢咖啡\"}]}"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(testJson)
            }
        }
        val extractor = MemoryExtractor(
            llmClient = XiaoxiLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 2
        )
        coEvery { userMemoryDao.insert(any()) } returns 1L

        // 第一轮失败（单 turn 触发用 force）
        val firstCount = extractor.maybeExtract("第一批：我最喜欢的编程语言是 Kotlin。", force = true)
        assertEquals(0, firstCount)
        assertEquals(1, extractor.getPendingTurnCount())

        // 新 turn 入队（此时队列：[新 turn, 失败批次]）
        extractor.maybeExtract("第二批：我今天早上喝了豆浆。")

        // 第二次触发成功，断言批次顺序正确（队首是失败批次的旧内容）
        shouldFail = false
        extractor.maybeExtract("第三批：随便凑个触发条件。", force = true)
        coVerify(exactly = 1) {
            userMemoryDao.insert(withArg { entity: UserMemoryEntity ->
                assertEquals("用户喜欢咖啡", entity.content)
            })
        }
        // 三条 turn 都已消耗（含回填的失败批次与两轮新 turn）
        assertEquals(0, extractor.getPendingTurnCount())
    }

    @Test
    fun testExtractJsonPayload_variants() {
        val extractor = buildExtractor(sseResponse("{}"))

        // 直接 JSON
        assertEquals("{\"a\":1}", extractor.extractJsonPayload("{\"a\":1}"))
        // markdown 围栏
        assertEquals("{\"a\":1}", extractor.extractJsonPayload("```json\n{\"a\":1}\n```"))
        // 前后闲聊
        assertEquals("{\"a\":1}", extractor.extractJsonPayload("好的，结果如下：{\"a\":1} 以上。"))
        // 无 JSON
        assertNull(extractor.extractJsonPayload("没有任何大括号"))
        assertNull(extractor.extractJsonPayload(""))
    }
}
