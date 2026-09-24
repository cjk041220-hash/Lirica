package com.xiaoxi.android.database.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DualTrackMemoryManagerTest {

    private lateinit var db: XiaoxiDatabase
    private lateinit var memoryManager: DualTrackMemoryManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, XiaoxiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryManager = DualTrackMemoryManager(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testBigramOverlapChineseAndEnglish() {
        // 完全相同短语
        val fullMatch = MemoryScorer.bigramOverlap("喜欢喝乌龙茶", "用户很喜欢喝乌龙茶")
        assertEquals(1.0f, fullMatch, 0.001f)

        // 部分命中
        val partial = MemoryScorer.bigramOverlap("喝乌龙茶", "红茶与乌龙茶")
        assertTrue("应包含乌龙茶重合", partial > 0.4f)

        // 完全无关
        val noMatch = MemoryScorer.bigramOverlap("天气很好", "用户住在北京海淀")
        assertEquals(0.0f, noMatch, 0.001f)

        // 单字或空串
        assertEquals(0.0f, MemoryScorer.bigramOverlap("", "内容"), 0.001f)
        assertEquals(1.0f, MemoryScorer.bigramOverlap("茶", "茶"), 0.001f)
    }

    @Test
    fun testMoodAffinityNeutralImmunityAndProximity() {
        // 中性免疫：记忆效价为 0.0 时，无论当前心情如何，亲和度恒为 0.5
        assertEquals(0.5f, MemoryScorer.moodAffinity(0.0f, 0.8f), 0.001f)
        assertEquals(0.5f, MemoryScorer.moodAffinity(0.0f, -0.8f), 0.001f)

        // 无心情输入时，亲和度为 0.0 (因子关闭)
        assertEquals(0.0f, MemoryScorer.moodAffinity(0.6f, null), 0.001f)

        // 效价同向接近：高分
        val highAffinity = MemoryScorer.moodAffinity(0.8f, 0.9f)
        assertTrue("同为极度开效应接近 1.0", highAffinity > 0.9f)

        // 效价异向相反：低分
        val lowAffinity = MemoryScorer.moodAffinity(0.8f, -0.8f)
        assertTrue("开心与闹别扭效价差为 1.6，亲和度应很低", lowAffinity < 0.3f)
    }

    @Test
    fun test6FactorRelevanceScoreRecencyAndImportance() {
        val now = 1726700000000L
        val sevenDaysAgo = now - 7 * 86_400_000L

        val freshMem = UserMemoryEntity(
            id = "mem_fresh",
            content = "刚记录的普通事实",
            category = "日常",
            importance = 5,
            createdAt = now,
            lastMentionTs = now
        )

        val oldMem = UserMemoryEntity(
            id = "mem_old",
            content = "一周前的普通事实",
            category = "日常",
            importance = 5,
            createdAt = sevenDaysAgo,
            lastMentionTs = sevenDaysAgo
        )

        val freshScore = MemoryScorer.calculateScore(freshMem, nowTs = now)
        val oldScore = MemoryScorer.calculateScore(oldMem, nowTs = now)

        assertTrue("新近记忆得分应当高于一周前的记忆", freshScore > oldScore)
        // 7天后衰减项 exp(-0.1 * 7) ≈ exp(-0.7) ≈ 0.496, 差值 ≈ 0.5
        assertTrue("新老得分差距应显著", (freshScore - oldScore) > 0.4f)
    }

    @Test
    fun testTwoTierRecallPrioritizesTopicMatchesOverRecentBackground() = runBlocking {
        val now = 1726700000000L
        val sevenDaysAgo = now - 7 * 86_400_000L

        // 记忆 A：7天前记录的关于“乌龙茶”的偏好（虽老，但命中当前话题）
        val memA = UserMemoryEntity(
            id = "mem_oolong",
            content = "夏天最喜欢喝冷泡冻顶乌龙茶",
            category = "饮食偏好",
            importance = 6,
            createdAt = sevenDaysAgo,
            lastMentionTs = sevenDaysAgo
        )

        // 记忆 B：刚才记录的无关事实（新近度极高，但与话题完全不符）
        val memB = UserMemoryEntity(
            id = "mem_walk",
            content = "下午去公园湖边散步看了野鸭",
            category = "日常活动",
            importance = 5,
            createdAt = now,
            lastMentionTs = now
        )

        db.userMemoryDao().insertAll(listOf(memA, memB))

        // 当用户提到“乌龙茶”时发起召回
        val recalled = memoryManager.recallUserMemories(
            query = "我想喝点冰乌龙茶，有什么推荐吗？",
            limit = 2,
            nowTs = now
        )

        assertEquals(2, recalled.size)
        // 二段排序核心保证：命中话题的 mem_oolong 必须排在第 1 位！
        assertEquals("mem_oolong", recalled[0].id)
        assertEquals("mem_walk", recalled[1].id)
    }

    @Test
    fun testDualTrackDynamicContextAssembly() = runBlocking {
        val now = 1726700000000L

        // 1. 注入用户轨记忆
        val userMem = UserMemoryEntity(
            id = "user_01",
            content = "正在开发原生 Android 伴生智能体项目",
            category = "工作项目",
            importance = 9,
            createdAt = now,
            lastMentionTs = now
        )
        db.userMemoryDao().insert(userMem)

        // 2. 注入自叙轨新鲜经历 (Track B)
        val selfExp = SelfExperienceEntity(
            id = "self_01",
            title = "量子纠缠感悟",
            content = "今天在网上看了量子意识假说的科普视频",
            domainTag = "科技奇想",
            emotionalInsight = "宇宙真的好神奇",
            createdAt = now,
            status = "fresh"
        )
        db.selfExperienceDao().insert(selfExp)

        // 3. 注入未完成念头 (Unspoken Thought)
        val thought = UnspokenThoughtEntity(
            id = "thought_01",
            thought = "看搭档写代码忙了一下午，待会儿问问要不要揉揉眼睛",
            urgency = 0.9f,
            createdAt = now,
            expiryTime = now + 3600000L,
            status = "pending"
        )
        db.unspokenThoughtDao().insert(thought)

        // 装配动态上下文
        val dynamicContext = memoryManager.assembleDynamicContext(
            query = "今天代码写得怎么样？",
            charName = "Lirica",
            nowTs = now
        )

        assertNotNull(dynamicContext)
        assertTrue(dynamicContext!!.contains("## 关于用户的长期记忆与事实"))
        assertTrue(dynamicContext.contains("正在开发原生 Android 伴生智能体项目"))

        assertTrue(dynamicContext.contains("## Lirica 最近的个人经历与见闻"))
        assertTrue(dynamicContext.contains("今天在网上看了量子意识假说的科普视频"))
        assertTrue(dynamicContext.contains("宇宙真的好神奇"))

        assertTrue(dynamicContext.contains("## Lirica 心里惦记的未完成念头"))
        assertTrue(dynamicContext.contains("看搭档写代码忙了一下午"))
    }

    @Test
    fun testEmptyDatabaseProducesNullDynamicContext() = runBlocking {
        val context = memoryManager.assembleDynamicContext(query = "随便聊聊")
        assertNull("无记忆与念头时应返回 null，避免 Prompt 产生空标题", context)
    }

    @Test
    fun testAssembleDynamicContext_usesPreRecalledMemoriesDirectly() = runBlocking {
        val preRecalledUser = listOf(
            UserMemoryEntity(
                id = "pre_user_1",
                category = "PRE_USER",
                content = "预召回用户记忆：喜欢喝黑咖啡",
                importance = 9,
                createdAt = System.currentTimeMillis()
            )
        )
        val preRecalledSelf = listOf(
            SelfExperienceEntity(
                id = "pre_self_1",
                title = "阅读感悟",
                domainTag = "PRE_SELF",
                content = "预召回自叙经历：今天看了一本好书",
                emotionalInsight = "平静且充实",
                createdAt = System.currentTimeMillis()
            )
        )
        val preRecalledThought = listOf(
            UnspokenThoughtEntity(
                id = "pre_thought_1",
                thought = "预召回念头：提醒用户早点休息",
                urgency = 0.8f,
                createdAt = System.currentTimeMillis(),
                expiryTime = System.currentTimeMillis() + 60000
            )
        )

        val result = memoryManager.assembleDynamicContext(
            query = "完全无关的查询关键词",
            charName = "Lirica",
            preRecalledUserMemories = preRecalledUser,
            preRecalledSelfExperiences = preRecalledSelf,
            preRecalledUnspokenThoughts = preRecalledThought
        )

        assertNotNull(result)
        assertTrue(result!!.contains("预召回用户记忆：喜欢喝黑咖啡"))
        assertTrue(result.contains("预召回自叙经历：今天看了一本好书"))
        assertTrue(result.contains("平静且充实"))
        assertTrue(result.contains("预召回念头：提醒用户早点休息"))
    }
}
