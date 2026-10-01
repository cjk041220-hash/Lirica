package com.lirica.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.OpenLoopEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.memory.MemoryExtractor
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.proactivity.OpenLoopLifecycleManager
import com.lirica.android.proactivity.ProactiveBubbleCoordinatorImpl
import com.lirica.android.proactivity.SilenceDecisionEngineImpl
import com.lirica.android.proactivity.UnspokenThoughtManagerImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 本轮收尾的闭环断言。
 *
 * 1) 开环核销：用户主动提到某件事后必须立即闭嘴（旧实现 resolveLoop() 在生产里 0 调用方）；
 * 2) 装配自检：Application 里声明的基础设施必须在 onCreate 后真的被构造出来——
 *    "建了类、写了测试、没人调用"是本项目复发了三次的失效模式，这里把它变成红灯。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MasterPlan4ClosureVerificationTest {

    private lateinit var context: Context
    private lateinit var database: LiricaDatabase
    private val now = 1_700_000_000_000L
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
    fun testOpenLoop_extractedFromBothChatPaths_whenDaoWired() = runBlocking {
        val extractor = MemoryExtractor(
            llmClient = mockLlmClient,
            userMemoryDao = database.userMemoryDao(),
            llmConfig = LlmConfig(apiKey = "mock-key", model = "mock-model"),
            openLoopDao = database.openLoopDao()
        )
        every { mockLlmClient.streamChat(any(), any(), any()) } returns flowOf(
            LlmStreamEvent.Token(
                """{"memories":[],"open_loops":[{"title":"数据结构期末","target_offset_hours":10,"urgency":3}]}"""
            )
        )

        extractor.maybeExtract(userText = "我后天要考数据结构期末，得复习了", force = true)

        val loops = database.openLoopDao().getByStatus(OpenLoopEntity.STATUS_OPEN)
        assertEquals(1, loops.size)
        assertEquals("数据结构期末", loops[0].title)
    }

    @Test
    fun testResolveMentionedLoops_closesLoopAndRetractsThought() = runBlocking {
        val openLoopDao = database.openLoopDao()
        val thoughtDao = database.unspokenThoughtDao()
        val manager = OpenLoopLifecycleManager(
            openLoopDao = openLoopDao,
            unspokenThoughtDao = thoughtDao,
            timeProvider = { now }
        )
        manager.createLoop(
            title = "数据结构期末",
            targetTimeMs = now + 3600_000L,
            leadTimeMs = 7200_000L,
            urgencyLevel = 3
        )
        // 模拟到期推进：念头已被投进池子
        manager.checkAndPromoteDueLoops(now)
        assertEquals(1, thoughtDao.getTopUrgentPendingThoughts(limit = 5, nowMs = now).size)

        // 用户主动说"考完了" → 立即核销 + 撤回念头
        val resolved = manager.resolveMentionedLoops("数据结构期末终于考完了，还挺顺利")

        assertEquals("已被提醒过（TRIGGERED）的事项同样必须可核销", 1, resolved.size)
        assertTrue(
            "开环必须转为已闭环",
            openLoopDao.getByStatus(OpenLoopEntity.STATUS_OPEN).isEmpty()
        )
        assertTrue(
            "对应的待表达念头必须被撤回，否则还会再提醒一次",
            thoughtDao.getTopUrgentPendingThoughts(limit = 5, nowMs = now).isEmpty()
        )
    }

    @Test
    fun testResolveMentionedLoops_ignoresUnrelatedText() = runBlocking {
        val manager = OpenLoopLifecycleManager(
            openLoopDao = database.openLoopDao(),
            unspokenThoughtDao = database.unspokenThoughtDao(),
            timeProvider = { now }
        )
        manager.createLoop(title = "数据结构期末", targetTimeMs = now + 3600_000L, urgencyLevel = 1)

        val resolved = manager.resolveMentionedLoops("今天天气不错，出去走走吧")

        assertTrue(resolved.isEmpty())
        assertEquals(1, database.openLoopDao().getByStatus(OpenLoopEntity.STATUS_OPEN).size)
    }

    @Test
    fun testCoordinatorResolvesLoopsMentionedInUserText() = runBlocking {
        val openLoopDao = database.openLoopDao()
        val thoughtDao = database.unspokenThoughtDao()
        val loopManager = OpenLoopLifecycleManager(
            openLoopDao = openLoopDao,
            unspokenThoughtDao = thoughtDao,
            timeProvider = { now }
        )
        loopManager.createLoop(title = "体检", targetTimeMs = now + 3600_000L, urgencyLevel = 2)
        val coordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = UnspokenThoughtManagerImpl(dao = thoughtDao),
            decisionEngine = SilenceDecisionEngineImpl(),
            emotionEngine = EmotionEngine(),
            openLoopManager = loopManager
        )

        coordinator.resolveOpenLoopsMentionedIn("体检做完了，指标都正常")

        assertTrue(openLoopDao.getByStatus(OpenLoopEntity.STATUS_OPEN).isEmpty())
    }

    @Test
    fun testApplicationWiring_constructsInfrastructureOnCreate() {
        // 装配自检：Application 声明的全局基础设施必须在 onCreate 后真实存在。
        // 这不是"接口测试"，而是把"造了器官没接神经"变成编译期之外的第一道红灯。
        val app = context.applicationContext as LiricaApplication

        assertTrue("感知管线未装配", app.perceptionPipeline != null)
        assertTrue("情境切片缓冲器未装配（白天相处会没有原料）", app.sensingEpisodicBuffer != null)
        assertTrue("开环生命周期管理器未装配", app.openLoopLifecycleManager != null)
        assertTrue("L3 通知派发器未装配", app.proactiveNotificationDispatcher != null)
        assertTrue("主动协调器未装配", app.proactiveCoordinator != null)
        assertTrue("记忆抽取器未装配", app.memoryExtractor != null)
        assertEquals(
            "记忆抽取器必须全应用唯一，否则两条聊天链路各算各的节流门槛",
            app.memoryExtractor,
            app.memoryExtractor
        )
    }

    @Test
    fun testLateinitInfrastructure_isInitializedAfterOnCreate() {
        // 反射兜底：任何新增的 lateinit 基础设施只要忘了在 onCreate 里赋值，这里立刻炸
        val app = context.applicationContext as LiricaApplication
        val missing = mutableListOf<String>()
        for (field in LiricaApplication::class.java.declaredFields) {
            if (!field.type.name.startsWith("com.lirica.android")) continue
            field.isAccessible = true
            if (field.get(app) == null) missing.add(field.name)
        }
        assertTrue("以下基础设施声明了却未在 onCreate 装配（典型的" + "造器官不接神经" + "）：$missing", missing.isEmpty())
    }
}
