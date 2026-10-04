package com.lirica.android.memory.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.database.memory.MemoryEmbeddingEntity
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
class TrackBSelfExperienceVectorRetrievalTest {

    private lateinit var database: LiricaDatabase
    private lateinit var userMemoryDao: UserMemoryDao
    private lateinit var selfExperienceDao: SelfExperienceDao
    private lateinit var embeddingModel: LiricaEmbeddingModel
    private lateinit var vectorStore: MemoryVectorStore
    private lateinit var retriever: HybridMemoryRetriever
    private lateinit var memoryManager: DualTrackMemoryManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = LiricaDatabase.buildInMemory(context)
        userMemoryDao = database.userMemoryDao()
        selfExperienceDao = database.selfExperienceDao()

        val modelFile = File("src/main/assets/models/bge-small-zh/model_quantized.onnx")
        val tokenizerFile = File("src/main/assets/models/bge-small-zh/tokenizer.json")
        embeddingModel = LiricaEmbeddingModel.create(modelFile, tokenizerFile, numThreads = 2)

        vectorStore = MemoryVectorStore(database.memoryEmbeddingDao())
        retriever = HybridMemoryRetriever(
            embeddingModel = embeddingModel,
            vectorStore = vectorStore,
            userMemoryDao = userMemoryDao,
            vectorWeight = 1.0f,
            selfExperienceDao = selfExperienceDao
        )
        memoryManager = DualTrackMemoryManager(database, retriever)
    }

    @After
    fun tearDown() {
        embeddingModel.close()
        database.close()
    }

    /**
     * 核心测试：验证 Track B 自叙经历能够成功被向量化索引并通过语义 query 召回
     */
    @Test
    fun testSelfExperienceSemanticRecall() = runBlocking {
        val now = System.currentTimeMillis()

        val expCoffee = SelfExperienceEntity(
            id = "exp_coffee",
            title = "午后咖啡",
            content = "下午趁着阳光去街角买了一杯冰美式，坐在长椅上吹着微风。",
            domainTag = "日常生活",
            createdAt = now - 3600_000L
        )

        val expCode = SelfExperienceEntity(
            id = "exp_code",
            title = "代码思考",
            content = "今天一直在琢磨算法的时间复杂度，稍微有些头疼。",
            domainTag = "工作感悟",
            createdAt = now - 7200_000L
        )

        selfExperienceDao.insert(expCoffee)
        selfExperienceDao.insert(expCode)

        // 索引自叙经历
        retriever.indexSelfExperiences(listOf(expCoffee, expCode))
        assertEquals(2, vectorStore.size(track = MemoryEmbeddingEntity.TRACK_SELF))

        // 通过语义相关词召回（例如用户说："拿铁和美式"）
        val recalled = memoryManager.recallSelfExperiences(limit = 1, query = "去喝杯冰咖啡")
        assertEquals(1, recalled.size)
        assertEquals("exp_coffee", recalled[0].id)
        assertTrue(recalled[0].content.contains("冰美式"))
    }

    /**
     * 核心测试：验证删除记忆时向量索引同步清理（防止孤儿向量）
     */
    @Test
    fun testVectorSyncOnDelete() = runBlocking {
        val now = System.currentTimeMillis()
        val userMem = UserMemoryEntity(
            id = "u_del_1",
            content = "喜欢吃日料",
            createdAt = now
        )
        userMemoryDao.insert(userMem)
        retriever.indexMemory(userMem)
        assertEquals(1, vectorStore.size(track = MemoryEmbeddingEntity.TRACK_USER))

        // 通过 memoryManager 删除
        memoryManager.deleteUserMemory("u_del_1")
        assertEquals(0, vectorStore.size(track = MemoryEmbeddingEntity.TRACK_USER))
        assertEquals(0, database.memoryEmbeddingDao().count())
    }

    /**
     * 核心测试：入梦时全量重算双轨向量
     */
    @Test
    fun testDualTrackRecomputeAllVectors() = runBlocking {
        val now = System.currentTimeMillis()
        val userMem = UserMemoryEntity(id = "u_1", content = "用户喜欢弹吉他", createdAt = now)
        val selfExp = SelfExperienceEntity(id = "s_1", title = "音乐", content = "今天听了贝多芬的月光奏鸣曲", createdAt = now)

        userMemoryDao.insert(userMem)
        selfExperienceDao.insert(selfExp)

        assertEquals(0, vectorStore.size())
        retriever.recomputeAllVectors()

        assertEquals(1, vectorStore.size(track = MemoryEmbeddingEntity.TRACK_USER))
        assertEquals(1, vectorStore.size(track = MemoryEmbeddingEntity.TRACK_SELF))
        assertEquals(2, vectorStore.size())
    }
}
