package com.lirica.android.database.memory

import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
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
            llmClient = LiricaLlmClient(httpClient = httpClient),
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
            llmClient = LiricaLlmClient(httpClient = httpClient),
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
        assertEquals("失败后进入退避计数", 1, extractor.getConsecutiveFailureCount())

        // 第二次：网络恢复，且 nowMs 已越过退避窗口，回填的批次内容应随自动重试被成功抽取
        shouldFail = false
        val secondCount = extractor.maybeExtract(
            "今晚又去江边跑了五公里，状态回来了。",
            nowMs = System.currentTimeMillis() + MemoryExtractor.FAILURE_BACKOFF_BASE_MS + 1_000L
        )
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
            llmClient = LiricaLlmClient(httpClient = httpClient),
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
    fun testMaybeExtract_failureBackoff_blocksAutoRetryUntilWindowElapses() = runTest {
        // 弱网重试风暴回归：连续失败后的退避窗口内，自动触发不得发起 LLM 请求；
        // 批次继续积攒不丢；force 显式调用绕过退避；成功后计数归零
        var engineCalls = 0
        var shouldFail = true
        val mockEngine = MockEngine { _ ->
            engineCalls++
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
            llmClient = LiricaLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1
        )
        coEvery { userMemoryDao.insert(any()) } returns 1L
        val baseNow = 1_000_000_000L

        // 第一次触发：失败 → 计数 1，批次回填
        assertEquals(0, extractor.maybeExtract("第一条：今晚吃了火锅，好撑。", nowMs = baseNow))
        assertEquals(1, extractor.getConsecutiveFailureCount())
        assertEquals(1, engineCalls)

        // 退避窗口内（<60s）来了新 turn：不得自动重发——LLM 引擎零调用，批次只积攒
        assertEquals(0, extractor.maybeExtract("第二条：还是想吃炸鸡。", nowMs = baseNow + 30_000L))
        assertEquals("退避期内不得发起 LLM 请求", 1, engineCalls)
        assertEquals("退避期内批次继续积攒不丢", 2, extractor.getPendingTurnCount())

        // force 显式调用绕过退避：立即触发，此时网络已恢复
        shouldFail = false
        val count = extractor.maybeExtract("第三条：显式触发抽取。", nowMs = baseNow + 40_000L, force = true)
        assertEquals(1, count)
        assertEquals(2, engineCalls)
        assertEquals("成功后退避计数归零", 0, extractor.getConsecutiveFailureCount())
        assertEquals(0, extractor.getPendingTurnCount())
    }

    @Test
    fun testBackoffMillis_exponentialGrowthWithCap() {
        val extractor = buildExtractor(sseResponse("{}"))
        assertEquals(60_000L, extractor.backoffMillis(1))
        assertEquals(120_000L, extractor.backoffMillis(2))
        assertEquals(240_000L, extractor.backoffMillis(3))
        assertEquals(480_000L, extractor.backoffMillis(4))
        // 第 5 次起 60s·2⁴ = 960s 已越过 15 分钟上限，被封顶压到 900s
        assertEquals(
            "第 5 次失败触及 15 分钟上限",
            MemoryExtractor.FAILURE_BACKOFF_MAX_MS,
            extractor.backoffMillis(5)
        )
        assertEquals(
            "上限之后不再增长",
            MemoryExtractor.FAILURE_BACKOFF_MAX_MS,
            extractor.backoffMillis(20)
        )
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

    @Test
    fun testDynamicLlmConfigProvider_updatesKeyOnDemand() = runTest {
        var currentApiKey = "initial-key"
        var capturedAuthHeader: String? = null

        val mockEngine = MockEngine { request ->
            capturedAuthHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = sseResponse("""{"memories":[]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(testJson) }
        }

        val dynamicExtractor = MemoryExtractor(
            llmClient = LiricaLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfigProvider = { LlmConfig(apiKey = currentApiKey) },
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1
        )

        // 第一次抽取
        dynamicExtractor.maybeExtract(userText = "我平时喜欢喝拿铁咖啡，每天早上都要一杯", force = true)
        assertEquals("Bearer initial-key", capturedAuthHeader)

        // 用户更新了 Key
        currentApiKey = "new-updated-key"
        dynamicExtractor.maybeExtract(userText = "我晚上睡前喜欢看侦探小说，特别是阿加莎的作品", force = true)
        assertEquals("Bearer new-updated-key", capturedAuthHeader)
    }

    // ---------- 开环建环去重（2026-10-02 审查 P1-4） ----------

    /** 内存版 OpenLoopDao：只服务建环去重断言 */
    private class FakeOpenLoopDao : com.lirica.android.database.dao.OpenLoopDao {
        val loops = mutableListOf<com.lirica.android.database.entity.OpenLoopEntity>()
        var insertCalls = 0
        var updateCalls = 0

        private fun active() = listOf(
            com.lirica.android.database.entity.OpenLoopEntity.STATUS_OPEN,
            com.lirica.android.database.entity.OpenLoopEntity.STATUS_TRACKING,
            com.lirica.android.database.entity.OpenLoopEntity.STATUS_TRIGGERED
        )

        override suspend fun insert(entity: com.lirica.android.database.entity.OpenLoopEntity): Long {
            loops.add(entity); insertCalls++; return loops.size.toLong()
        }
        override suspend fun insertAll(entities: List<com.lirica.android.database.entity.OpenLoopEntity>) {
            loops.addAll(entities)
        }
        override suspend fun update(entity: com.lirica.android.database.entity.OpenLoopEntity) {
            updateCalls++
            val idx = loops.indexOfFirst { it.id == entity.id }
            if (idx != -1) loops[idx] = entity
        }
        override suspend fun getById(id: String) = loops.find { it.id == id }
        override suspend fun getByStatus(status: String) = loops.filter { it.status == status }
        override suspend fun getOpenLoops(limit: Int) =
            loops.filter { it.status in active() }.sortedBy { it.targetTimeMs }.take(limit)
        override suspend fun getDueForTracking(currentTimeMs: Long) = emptyList<com.lirica.android.database.entity.OpenLoopEntity>()
        override suspend fun getActiveByTitle(title: String) =
            loops.firstOrNull { it.title == title && it.status in active() }
        override suspend fun updateStatus(id: String, newStatus: String) {
            val idx = loops.indexOfFirst { it.id == id }
            if (idx != -1) loops[idx] = loops[idx].copy(status = newStatus)
        }
        override suspend fun markPromptedIfActive(id: String, promptedMs: Long, newStatus: String): Int = 0
        override suspend fun expireOldLoops(expiryThresholdMs: Long): Int = 0
        override suspend fun cleanupClosedLoops(beforeMs: Long): Int = 0
    }

    private fun buildExtractorWithDao(responseData: String, loopDao: FakeOpenLoopDao): MemoryExtractor {
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseData,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(testJson) }
        }
        return MemoryExtractor(
            llmClient = LiricaLlmClient(httpClient = httpClient),
            userMemoryDao = userMemoryDao,
            llmConfig = LlmConfig(apiKey = "test-key"),
            json = testJson,
            throttleIntervalMs = 0L,
            minTurnsAccumulated = 1,
            openLoopDao = loopDao
        )
    }

    @Test
    fun testMaybeExtract_openLoop_sameBatchDuplicateTitles_mergedIntoOneLoop() = runTest {
        coEvery { userMemoryDao.insert(any()) } returns 1L
        val loopDao = FakeOpenLoopDao()
        val extractor = buildExtractorWithDao(
            sseResponse(
                """{"memories":[],"open_loops":[""" +
                    """{"title":"准备期末答辩","target_offset_hours":24,"urgency":1},""" +
                    """{"title":"准备期末答辩","target_offset_hours":48,"urgency":3}]}"""
            ),
            loopDao
        )

        val count = extractor.maybeExtract("我下周有个期末答辩，还没开始准备，好紧张。")

        // 返回值 = 实际落库数：0 条记忆 + 1 条去重后的开环（原始数组有 2 条，不得照抄）
        assertEquals(1, count)
        assertEquals("批内重复标题只建一个环", 1, loopDao.loops.size)
        assertEquals("批内合并保留紧急度最高的一条", 3, loopDao.loops[0].urgencyLevel)
        assertEquals(1, loopDao.insertCalls)
        assertEquals(0, loopDao.updateCalls)
    }

    @Test
    fun testMaybeExtract_openLoop_existingActiveLoopRefreshed_notDuplicated() = runTest {
        coEvery { userMemoryDao.insert(any()) } returns 1L
        val loopDao = FakeOpenLoopDao()
        val first = buildExtractorWithDao(
            sseResponse("""{"memories":[],"open_loops":[{"title":"准备期末答辩","target_offset_hours":24,"urgency":2}]}"""),
            loopDao
        )
        first.maybeExtract("我下周有个期末答辩。", force = true)
        assertEquals(1, loopDao.loops.size)
        assertEquals(1, loopDao.insertCalls)

        // 隔几轮用户重提同一事项（时间后挪、紧急度提高）：必须刷新既有环而非新建
        val second = buildExtractorWithDao(
            sseResponse("""{"memories":[],"open_loops":[{"title":"准备期末答辩","target_offset_hours":72,"urgency":3}]}"""),
            loopDao
        )
        second.maybeExtract("期末答辩的事我又想了想，准备时间要往后挪一挪。", force = true)

        assertEquals("跨批次重提同一事项不得重复建环", 1, loopDao.loops.size)
        assertEquals("存量环走更新而非插入", 1, loopDao.updateCalls)
        assertEquals(1, loopDao.insertCalls)
        assertEquals("紧急度只升不降", 3, loopDao.loops[0].urgencyLevel)
    }
}
