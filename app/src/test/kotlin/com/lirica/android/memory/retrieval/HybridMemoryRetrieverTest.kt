package com.lirica.android.memory.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.database.memory.MemoryScorer
import com.lirica.android.database.memory.MemoryVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HybridMemoryRetrieverTest {

    private lateinit var database: LiricaDatabase
    private lateinit var userMemoryDao: UserMemoryDao
    private lateinit var embeddingModel: LiricaEmbeddingModel
    private lateinit var vectorStore: MemoryVectorStore
    private lateinit var retriever: HybridMemoryRetriever

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = LiricaDatabase.buildInMemory(context)
        userMemoryDao = database.userMemoryDao()

        val modelFile = File("src/main/assets/models/bge-small-zh/model_quantized.onnx")
        val tokenizerFile = File("src/main/assets/models/bge-small-zh/tokenizer.json")
        embeddingModel = LiricaEmbeddingModel.create(modelFile, tokenizerFile, numThreads = 2)

        vectorStore = MemoryVectorStore(database.memoryEmbeddingDao())
        retriever = HybridMemoryRetriever(
            embeddingModel = embeddingModel,
            vectorStore = vectorStore,
            userMemoryDao = userMemoryDao,
            vectorWeight = 1.0f
        )
    }

    @After
    fun tearDown() {
        embeddingModel.close()
        database.close()
    }

    /**
     * 核心验收用例：PRD §7 批次 2.5 "周董" 用例能召回 "喜欢周杰伦"
     * 对比纯字符 2-gram 话题重叠与向量融合召回能力
     */
    @Test
    fun testJayChouSemanticRecallContrast() = runBlocking {
        val now = System.currentTimeMillis()

        val memJay = UserMemoryEntity(
            id = "mem_jay",
            content = "喜欢周杰伦",
            category = "音乐偏好",
            importance = 8,
            createdAt = now - 3600_000L
        )
        val memWeather = UserMemoryEntity(
            id = "mem_weather",
            content = "今天天气不错",
            category = "日常闲聊",
            importance = 3,
            createdAt = now - 7200_000L
        )
        val memCoffee = UserMemoryEntity(
            id = "mem_coffee",
            content = "喜欢喝热美式咖啡",
            category = "饮食习惯",
            importance = 5,
            createdAt = now - 10800_000L
        )
        val memCity = UserMemoryEntity(
            id = "mem_city",
            content = "住在深圳南山科技园",
            category = "居住地",
            importance = 7,
            createdAt = now - 14400_000L
        )

        userMemoryDao.insert(memJay)
        userMemoryDao.insert(memWeather)
        userMemoryDao.insert(memCoffee)
        userMemoryDao.insert(memCity)

        // 1. 纯字符 2-gram 重叠度对比验证：
        // "周董" 与 "喜欢周杰伦" 无任何 2-gram 重合，传统关键词/字符方法完全失灵
        val query = "周董"
        val bigramOverlap = MemoryScorer.bigramOverlap(query, "${memJay.content} ${memJay.category}")
        assertEquals(
            "纯字符 2-gram 无法匹配同义词/代称'周董'与'周杰伦'，重叠度必定为 0",
            0.0f,
            bigramOverlap,
            0.0001f
        )

        // 2. 建立端侧向量索引
        retriever.indexMemories(listOf(memJay, memWeather, memCoffee, memCity))

        // 3. 执行向量检索融合
        val results = retriever.retrieve(query = "周董", topK = 1)

        assertEquals(1, results.size)
        assertEquals("mem_jay", results[0].id)
        assertEquals("喜欢周杰伦", results[0].content)

        // 4. 详细得分断言
        val scoredResults = retriever.retrieveScored(query = "周董", topK = 4)
        val topScored = scoredResults.first()
        assertEquals("mem_jay", topScored.memory.id)
        assertTrue(
            "语义相似度应显著大于 0.4",
            topScored.vectorSimilarity > 0.4f
        )
        // 相比其他无关项，mem_jay 得分遥遥领先
        val weatherScored = scoredResults.find { it.memory.id == "mem_weather" }
        assertNotNull(weatherScored)
        assertTrue(
            "相关记忆总分应远高于无关记忆",
            topScored.totalScore > weatherScored!!.totalScore + 0.3f
        )
    }

    /**
     * 验证检索融合流程：向量召回 top-20 结合 MemoryScorer 六维精排
     */
    @Test
    fun testVectorRecallTop20AndMemoryScorerRerank() = runBlocking {
        val now = System.currentTimeMillis()
        val memories = (1..25).map { i ->
            UserMemoryEntity(
                id = "m_$i",
                content = if (i == 5) "非常喜欢听周董的晴天" else "普通日常记忆编号 $i",
                category = "生活",
                importance = if (i == 5) 9 else 3,
                createdAt = now - (i * 10000L)
            )
        }
        for (m in memories) {
            userMemoryDao.insert(m)
        }
        retriever.indexMemories(memories)

        val results = retriever.retrieve(query = "周杰伦", topK = 3)
        assertEquals(3, results.size)
        assertEquals("m_5", results[0].id)
        assertTrue(results[0].content.contains("周董的晴天"))
    }

    /**
     * 空 query 时平滑降级
     */
    @Test
    fun testEmptyQueryFallback() = runBlocking {
        val now = System.currentTimeMillis()
        val m1 = UserMemoryEntity(id = "e1", content = "重要记忆", importance = 10, createdAt = now)
        val m2 = UserMemoryEntity(id = "e2", content = "普通记忆", importance = 1, createdAt = now)
        userMemoryDao.insert(m1)
        userMemoryDao.insert(m2)

        val results = retriever.retrieve(query = "", topK = 2)
        assertEquals(2, results.size)
        assertEquals("e1", results[0].id) // 重要度高者排在前面
    }

    /**
     * 空数据库安全测试
     */
    @Test
    fun testEmptyDatabaseSafe() = runBlocking {
        val results = retriever.retrieve(query = "任意内容", topK = 5)
        assertTrue(results.isEmpty())
    }

    /**
     * DualTrackMemoryManager 与 HybridMemoryRetriever 协同装配测试
     */
    @Test
    fun testDualTrackMemoryManagerWiring() = runBlocking {
        val manager = DualTrackMemoryManager(database, retriever)
        val mem = UserMemoryEntity(
            id = "w1",
            content = "喜欢看周星驰电影",
            category = "电影",
            createdAt = System.currentTimeMillis()
        )
        userMemoryDao.insert(mem)
        retriever.indexMemory(mem)

        val retrieved = manager.retrieve(query = "星爷的喜剧", topK = 1)
        assertEquals(1, retrieved.size)
        assertEquals("w1", retrieved[0].id)
    }

    /**
     * 全量向量重算测试 (入梦流水线对齐)
     */
    @Test
    fun testRecomputeAllVectors() = runBlocking {
        val m1 = UserMemoryEntity(id = "rc1", content = "记忆一", createdAt = System.currentTimeMillis())
        val m2 = UserMemoryEntity(id = "rc2", content = "记忆二", createdAt = System.currentTimeMillis())
        userMemoryDao.insert(m1)
        userMemoryDao.insert(m2)

        assertEquals(0, vectorStore.size())
        retriever.recomputeAllVectors()
        assertEquals(2, vectorStore.size())
    }
}
