package com.xiaoxi.android.proactivity

import androidx.work.NetworkType
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.SelfExperienceDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.XiaoxiApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = XiaoxiApplication::class, sdk = [34])
class DiaryEngineTest {

    private lateinit var selfExperienceDao: SelfExperienceDao
    private lateinit var chatDialogueDao: ChatDialogueDao
    private lateinit var diaryEngine: DiaryEngine

    @Before
    fun setUp() {
        selfExperienceDao = mockk(relaxed = true)
        chatDialogueDao = mockk(relaxed = true)
        diaryEngine = DiaryEngineImpl(selfExperienceDao, chatDialogueDao)
    }

    // --- WorkManager Constraints Contract Test ---

    @Test
    fun testAutonomousLifeWorker_constraintsContract() {
        val workRequest = AutonomousLifeWorker.createPeriodicWorkRequest()
        val constraints = workRequest.workSpec.constraints

        // Verify strict battery and idle constraints (charging + idle + connected)
        assertTrue("Worker must require device to be charging", constraints.requiresCharging())
        assertTrue("Worker must require device to be idle", constraints.requiresDeviceIdle())
        assertEquals(
            "Worker must require connected network",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
    }

    // --- Diary Generation Tests ---

    @Test
    fun testDiaryEngine_generateDailyDiary_emptyDayCreatesGentleReflection() = runTest {
        coEvery { chatDialogueDao.getAllSessions() } returns emptyList()
        val slot = slot<SelfExperienceEntity>()
        coEvery { selfExperienceDao.insert(capture(slot)) } returns 1L

        val now = 100_000_000L
        val entry = diaryEngine.generateDailyDiary(now)

        assertNotNull(entry)
        assertEquals(SelfExperienceEntity.STATUS_FRESH, entry.status)
        assertEquals("夜间日记", entry.domainTag)
        assertTrue("Content should be in first person: ${entry.content}", entry.content.contains("我") || entry.content.contains("小汐"))
        coVerify(exactly = 1) { selfExperienceDao.insert(any()) }
        assertEquals(entry.id, slot.captured.id)
    }

    @Test
    fun testDiaryEngine_generateDailyDiary_withDialoguesExtractsKeyThemes() = runTest {
        val dialogues = listOf(
            ChatDialogueEntity(
                id = 1,
                sessionId = "default",
                role = ChatDialogueEntity.ROLE_USER,
                content = "今天工作好多，好累啊",
                timestamp = 100_000L
            ),
            ChatDialogueEntity(
                id = 2,
                sessionId = "default",
                role = ChatDialogueEntity.ROLE_ASSISTANT,
                content = "抱抱你，辛苦啦，要多喝水早点休息",
                timestamp = 101_000L
            )
        )
        coEvery { chatDialogueDao.getAllSessions() } returns listOf("default")
        coEvery { chatDialogueDao.getRecentBySession("default", any()) } returns dialogues

        val slot = slot<SelfExperienceEntity>()
        coEvery { selfExperienceDao.insert(capture(slot)) } returns 1L

        val now = 200_000_000L
        val entry = diaryEngine.generateDailyDiary(now)

        assertNotNull(entry)
        assertTrue("Content should reflect user fatigue or empathy: ${entry.content}", entry.content.contains("辛苦") || entry.content.contains("累") || entry.content.contains("休息"))
        assertEquals(SelfExperienceEntity.STATUS_FRESH, entry.status)
        coVerify(exactly = 1) { selfExperienceDao.insert(any()) }
    }

    // --- Dynamic Living Water (活水反哺) Tests ---

    @Test
    fun testDiaryEngine_statusTransition_freshToShared() = runTest {
        val now = 300_000_000L
        diaryEngine.markExperienceShared("exp-1", now)

        coVerify(exactly = 1) {
            selfExperienceDao.markAsShared(
                id = "exp-1",
                sharedAt = now,
                sharedStatus = SelfExperienceEntity.STATUS_SHARED
            )
        }
    }

    @Test
    fun testDiaryEngine_getFreshExperiences_returnsOnlyFresh() = runTest {
        val freshList = listOf(
            SelfExperienceEntity(
                id = "exp-fresh-1",
                title = "今日日记",
                content = "今天过得好充实...",
                createdAt = 100_000L,
                status = SelfExperienceEntity.STATUS_FRESH
            )
        )
        coEvery { selfExperienceDao.getUnsharedExperiences(SelfExperienceEntity.STATUS_FRESH, 5) } returns freshList

        val result = diaryEngine.getFreshExperiences(limit = 5)
        assertEquals(1, result.size)
        assertEquals("exp-fresh-1", result[0].id)
        assertEquals(SelfExperienceEntity.STATUS_FRESH, result[0].status)
    }
}
