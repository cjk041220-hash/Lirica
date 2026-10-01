package com.lirica.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.database.entity.OpenLoopEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.memory.MemoryExtractor
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.memory.consolidation.DreamEpisodeConsolidator
import com.lirica.android.memory.consolidation.SubconsciousDreamCoordinator
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.perception.SensingEpisodicBuffer
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.proactivity.OpenLoopLifecycleManager
import com.lirica.android.proactivity.ProactiveBubbleCoordinatorImpl
import com.lirica.android.proactivity.ProactiveNotificationDispatcher
import com.lirica.android.proactivity.SilenceDecisionEngineImpl
import com.lirica.android.proactivity.UnspokenThoughtManagerImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MasterPlan4WiringIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: LiricaDatabase
    private val mockLlmClient = mockk<LiricaLlmClient>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LiricaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testWiring1_sensingEpisodicBuffer_persistsAndConsolidatesInDreamCoordinator() = runBlocking {
        val sliceDao = database.episodicSensingSliceDao()
        val buffer = SensingEpisodicBuffer(
            sliceDao = sliceDao,
            minDwellDurationMs = 1000L
        )

        // 模拟白天应用驻留
        buffer.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("com.study.app", "Main")),
            1000L
        )
        buffer.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("com.other.app", "Main")),
            5000L
        )

        assertEquals(1, sliceDao.getTotalCount())
        assertEquals(1, sliceDao.getUnconsolidatedCount())

        // 模拟夜间入梦流水线零消化
        val consolidator = DreamEpisodeConsolidator(
            sliceDao = sliceDao,
            selfExperienceDao = database.selfExperienceDao()
        )
        val coordinator = SubconsciousDreamCoordinator(
            userMemoryDao = database.userMemoryDao(),
            worldviewDao = database.worldviewDao(),
            selfExperienceDao = database.selfExperienceDao(),
            unspokenThoughtDao = database.unspokenThoughtDao(),
            episodeConsolidator = consolidator
        )

        val report = coordinator.runDreamCycle(10000L)
        assertEquals(1, report.consolidatedEpisodesCount)
        assertEquals(0, sliceDao.getUnconsolidatedCount())
        val experiences = database.selfExperienceDao().getActiveExperiences()
        assertTrue(experiences.any { it.domainTag == "情境感知" })
    }

    @Test
    fun testWiring2_memoryExtractor_extractsOpenLoopsDirectlyIntoDao() = runBlocking {
        val openLoopDao = database.openLoopDao()
        val extractor = MemoryExtractor(
            llmClient = mockLlmClient,
            userMemoryDao = database.userMemoryDao(),
            llmConfig = LlmConfig(apiKey = "mock-key", model = "mock-model"),
            openLoopDao = openLoopDao
        )

        val mockLlmJson = """
            {
                "memories": [{"content": "用户准备参加考试", "category": "作息", "importance": 7}],
                "open_loops": [{"title": "高数期末考试", "target_offset_hours": 12, "urgency": 2}]
            }
        """.trimIndent()

        every { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token(mockLlmJson)
        )

        val count = extractor.maybeExtract(
            userText = "我明天上午有一场高数期末考试，得好好复习了",
            force = true
        )

        assertEquals(2, count)
        val loops = openLoopDao.getByStatus(OpenLoopEntity.STATUS_OPEN)
        assertEquals(1, loops.size)
        assertEquals("高数期末考试", loops[0].title)
        assertEquals(2, loops[0].urgencyLevel)
    }

    @Test
    fun testWiring3_proactiveCoordinator_scansOpenLoopsAndInjectsThoughts() = runBlocking {
        val openLoopDao = database.openLoopDao()
        val unspokenDao = database.unspokenThoughtDao()
        val now = 2000000L
        val loopManager = OpenLoopLifecycleManager(
            openLoopDao = openLoopDao,
            unspokenThoughtDao = unspokenDao,
            timeProvider = { now }
        )
        loopManager.createLoop(
            title = "毕业设计终审答辩",
            targetTimeMs = now + 3600 * 1000L, // 1小时后
            leadTimeMs = 2 * 3600 * 1000L      // 提前2小时触发
        )

        val thoughtManager = UnspokenThoughtManagerImpl(dao = unspokenDao)
        val coordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            decisionEngine = SilenceDecisionEngineImpl(),
            emotionEngine = EmotionEngine(),
            openLoopManager = loopManager,
            notificationDispatcher = ProactiveNotificationDispatcher()
        )

        // 触发协调，应当自动扫描并推进开环事项
        coordinator.coordinate(PerceptionSnapshot(), now)

        val thoughts = unspokenDao.getTopUrgentPendingThoughts()
        assertTrue("开环事项应已推入念头池", thoughts.any { it.thought.contains("毕业设计终审答辩") })
    }
}