package com.xiaoxi.android.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.SelfExperienceDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 1.1: Room 数据库与双轨记忆表结构单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class XiaoxiDatabaseTest {

    private lateinit var db: XiaoxiDatabase
    private lateinit var userMemoryDao: UserMemoryDao
    private lateinit var selfExperienceDao: SelfExperienceDao
    private lateinit var unspokenThoughtDao: UnspokenThoughtDao
    private lateinit var chatDialogueDao: ChatDialogueDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, XiaoxiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userMemoryDao = db.userMemoryDao()
        selfExperienceDao = db.selfExperienceDao()
        unspokenThoughtDao = db.unspokenThoughtDao()
        chatDialogueDao = db.chatDialogueDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testUserMemoryCrudAndSupersededFlow() = runBlocking {
        val now = 1726700000000L
        val mem1 = UserMemoryEntity(
            id = "mem_user_loc_1",
            content = "用户目前居住在北京市海淀区中关村",
            category = "用户画像",
            confidence = 0.95f,
            importance = 8,
            valence = 0.1f,
            arousal = 0.0f,
            createdAt = now,
            lastMentionTs = now,
            lastUsedAt = null,
            accessCount = 0,
            hitCount = 1,
            lastHitDate = "2026-09-19",
            status = "active",
            supersededBy = null,
            prevContent = null,
            source = "chat:session_init",
            validFrom = now,
            validTo = null
        )

        userMemoryDao.insert(mem1)

        val fetched = userMemoryDao.getById("mem_user_loc_1")
        assertNotNull(fetched)
        assertEquals("用户目前居住在北京市海淀区中关村", fetched?.content)
        assertEquals("active", fetched?.status)
        assertEquals(0.95f, fetched?.confidence ?: 0f, 0.001f)

        // 模拟用户说搬家到杭州，产生新记忆并覆盖旧记忆
        val moveTime = now + 86400000L
        val mem2 = UserMemoryEntity(
            id = "mem_user_loc_2",
            content = "用户搬家到了杭州市西湖区",
            category = "用户画像",
            confidence = 0.98f,
            importance = 8,
            valence = 0.3f,
            arousal = 0.2f,
            createdAt = moveTime,
            lastMentionTs = moveTime,
            status = "active",
            validFrom = moveTime
        )
        userMemoryDao.insert(mem2)

        // 矛盾记忆覆盖：将旧记忆标记为 superseded
        userMemoryDao.markSuperseded(
            oldId = "mem_user_loc_1",
            newId = "mem_user_loc_2",
            supersededTimestamp = moveTime,
            prevContentSnapshot = mem1.content
        )

        val updatedOld = userMemoryDao.getById("mem_user_loc_1")
        assertNotNull(updatedOld)
        assertEquals("superseded", updatedOld?.status)
        assertEquals("mem_user_loc_2", updatedOld?.supersededBy)
        assertEquals(moveTime, updatedOld?.validTo)
        assertEquals("用户目前居住在北京市海淀区中关村", updatedOld?.prevContent)

        // Active 列表查询：只返回 mem2，mem1 必须已被排除
        val activeList = userMemoryDao.getAllActive().first()
        assertEquals(1, activeList.size)
        assertEquals("mem_user_loc_2", activeList[0].id)

        // 日期 hitCount 去重测试
        // 同一日再次 hit，hitCount 不变
        userMemoryDao.updateHitCountIfNewDay("mem_user_loc_2", "2026-09-20", moveTime)
        var mem2Updated = userMemoryDao.getById("mem_user_loc_2")
        assertEquals(1, mem2Updated?.hitCount)
        assertEquals("2026-09-20", mem2Updated?.lastHitDate)

        userMemoryDao.updateHitCountIfNewDay("mem_user_loc_2", "2026-09-20", moveTime + 1000)
        mem2Updated = userMemoryDao.getById("mem_user_loc_2")
        assertEquals(1, mem2Updated?.hitCount) // 仍为 1

        // 换了一天再次 hit，hitCount + 1
        userMemoryDao.updateHitCountIfNewDay("mem_user_loc_2", "2026-09-21", moveTime + 86400000)
        mem2Updated = userMemoryDao.getById("mem_user_loc_2")
        assertEquals(2, mem2Updated?.hitCount)
        assertEquals("2026-09-21", mem2Updated?.lastHitDate)

        // access_count 与 last_used_at 增长测试
        userMemoryDao.incrementAccessCount("mem_user_loc_2", moveTime + 2000)
        mem2Updated = userMemoryDao.getById("mem_user_loc_2")
        assertEquals(1, mem2Updated?.accessCount)
        assertEquals(moveTime + 2000, mem2Updated?.lastUsedAt)
    }

    @Test
    fun testSelfExperienceCrudAndUnsharedFlow() = runBlocking {
        val now = 1726700000000L
        val exp1 = SelfExperienceEntity(
            id = "exp_01",
            title = "关于量子纠缠与意识形态的随想",
            content = "今天在网络上阅读了彭罗斯关于微管量子理论的论文，很有启发，也许意识真的不是纯计算。",
            sourceUrl = "https://arxiv.org/abs/example",
            domainTag = "科学探索",
            emotionalInsight = "感到一阵宇宙尺度的敬畏",
            valence = 0.6f,
            arousal = 0.4f,
            createdAt = now,
            status = "fresh"
        )

        selfExperienceDao.insert(exp1)

        val unshared = selfExperienceDao.getUnsharedExperiences()
        assertEquals(1, unshared.size)
        assertEquals("exp_01", unshared[0].id)

        // 标记为已和用户分享
        val shareTime = now + 3600000L
        selfExperienceDao.markAsShared("exp_01", shareTime)

        val expAfterShare = selfExperienceDao.getById("exp_01")
        assertNotNull(expAfterShare)
        assertEquals("shared", expAfterShare?.status)
        assertEquals(1, expAfterShare?.accessCount)
        assertEquals(shareTime, expAfterShare?.lastSharedAt)

        val unsharedAfter = selfExperienceDao.getUnsharedExperiences()
        assertEquals(0, unsharedAfter.size)

        // 领域搜索验证
        val searched = selfExperienceDao.searchByDomain("科学探索")
        assertEquals(1, searched.size)
        assertEquals("exp_01", searched[0].id)
    }

    @Test
    fun testUnspokenThoughtUrgencyAndDecayFlow() = runBlocking {
        val now = 1726700000000L
        val thought1 = UnspokenThoughtEntity(
            id = "thought_01",
            thought = "看到冷冷刚才叹了一口气，等会儿她停下来时问问要不要喝杯热饮",
            triggerContext = "app:com.tencent.mm, time:late_night",
            urgency = 0.85f,
            decayScore = 1.0f,
            targetScenario = "user_idle",
            createdAt = now,
            expiryTime = now + 1800000L, // 30分钟后过期
            status = "pending"
        )
        val thought2 = UnspokenThoughtEntity(
            id = "thought_02",
            thought = "下周好像有一场画展，她之前说过喜欢这个作者",
            triggerContext = "news:art_exhibition",
            urgency = 0.4f,
            decayScore = 1.0f,
            targetScenario = "weekend_chat",
            createdAt = now - 1000L,
            expiryTime = now + 86400000L * 3,
            status = "pending"
        )
        val thought3 = UnspokenThoughtEntity(
            id = "thought_03",
            thought = "手机电量低于15%还在刷视频，准备提醒充电",
            triggerContext = "battery:14",
            urgency = 0.95f,
            decayScore = 1.0f,
            targetScenario = "immediate",
            createdAt = now,
            expiryTime = now + 600000L,
            status = "pending"
        )

        unspokenThoughtDao.insertAll(listOf(thought1, thought2, thought3))

        // 获取高优先级待倾诉念头（按 urgency 降序）
        val urgentThoughts = unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 2)
        assertEquals(2, urgentThoughts.size)
        assertEquals("thought_03", urgentThoughts[0].id) // 0.95 最高
        assertEquals("thought_01", urgentThoughts[1].id) // 0.85 次之

        // 标记 thought_03 已消费发出
        unspokenThoughtDao.markConsumed("thought_03", now + 5000L)
        val consumed = unspokenThoughtDao.getById("thought_03")
        assertEquals("consumed", consumed?.status)
        assertEquals(now + 5000L, consumed?.consumedAt)

        // 模拟时间推进，超过 thought_01 的 expiryTime，执行过期丢弃
        val futureTime = now + 2000000L
        val discardedCount = unspokenThoughtDao.discardExpiredThoughts(futureTime)
        assertEquals(1, discardedCount)

        val expiredThought = unspokenThoughtDao.getById("thought_01")
        assertEquals("discarded", expiredThought?.status)

        // thought_02 仍处于 pending
        val remainingPending = unspokenThoughtDao.getPendingThoughts().first()
        assertEquals(1, remainingPending.size)
        assertEquals("thought_02", remainingPending[0].id)
    }

    @Test
    fun testChatDialogueSessionFlow() = runBlocking {
        val now = 1726700000000L
        val msg1 = ChatDialogueEntity(
            sessionId = "session_001",
            role = "user",
            content = "Lirica，我今天遇到一个有意思的Bug",
            msgType = "chat",
            timestamp = now,
            tokenCount = 15,
            model = null,
            metadataJson = null
        )
        val msg2 = ChatDialogueEntity(
            sessionId = "session_001",
            role = "assistant",
            content = "是什么Bug呀？快说给我听听~",
            msgType = "chat",
            timestamp = now + 1000L,
            tokenCount = 12,
            model = "deepseek-chat",
            metadataJson = "{\"valence\":0.5}"
        )
        val msg3 = ChatDialogueEntity(
            sessionId = "session_002",
            role = "user",
            content = "晚安 Lirica",
            msgType = "chat",
            timestamp = now + 5000L,
            tokenCount = 6,
            model = null,
            metadataJson = null
        )

        chatDialogueDao.insertAll(listOf(msg1, msg2, msg3))

        // 消息统计
        val countSess1 = chatDialogueDao.getSessionMessageCount("session_001")
        assertEquals(2, countSess1)
        val countSess2 = chatDialogueDao.getSessionMessageCount("session_002")
        assertEquals(1, countSess2)

        // 获取会话消息（按时间戳升序排序）
        val history1 = chatDialogueDao.getRecentBySession("session_001", limit = 10)
        assertEquals(2, history1.size)
        assertEquals("user", history1[0].role)
        assertEquals("assistant", history1[1].role)

        // 获取所有会话列表去重
        val sessions = chatDialogueDao.getAllSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.contains("session_001"))
        assertTrue(sessions.contains("session_002"))

        // 删除会话 1
        chatDialogueDao.deleteBySession("session_001")
        assertEquals(0, chatDialogueDao.getSessionMessageCount("session_001"))
        assertEquals(1, chatDialogueDao.getSessionMessageCount("session_002"))
    }

    @Test
    fun testDatabaseConcurrencyAndTransaction() = runBlocking {
        // 多协程并发向数据库不同表写入与读取
        val jobs = (1..20).map { i ->
            launch(Dispatchers.IO) {
                userMemoryDao.insert(
                    UserMemoryEntity(
                        id = "concurrent_mem_$i",
                        content = "并发写入测试记忆 $i",
                        category = "测试",
                        createdAt = System.currentTimeMillis()
                    )
                )
                selfExperienceDao.insert(
                    SelfExperienceEntity(
                        id = "concurrent_exp_$i",
                        title = "并发见闻 $i",
                        content = "并发见闻内容 $i",
                        domainTag = "测试",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
        jobs.joinAll()

        val activeMemories = userMemoryDao.getByCategory("测试")
        assertEquals(20, activeMemories.size)

        val expList = selfExperienceDao.searchByDomain("测试")
        assertEquals(20, expList.size)
    }
}
