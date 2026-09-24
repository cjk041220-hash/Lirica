package com.xiaoxi.android.persona

import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity
import com.xiaoxi.android.perception.model.ForegroundAppInfo
import com.xiaoxi.android.perception.model.NotificationCategory
import com.xiaoxi.android.perception.model.NotificationEvent
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.perception.model.ScreenStateSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBasedPromptAssemblerTest {

    private val assembler = MapBasedPromptAssembler()

    @Test
    fun testAssemble_emptyInput_returnsNull() {
        val result = assembler.assemble(MapContextInput())
        assertNull(result)
    }

    @Test
    fun testAssemble_full5LayerContextAssembly() {
        val worldviews = listOf(
            WorldviewEntity(
                id = "wv_1",
                category = "工作习惯",
                statement = "用户倾向于高专注深潜工作，工作期间偏好静音所有通知",
                supportingEvidenceCount = 5,
                lastConfirmedAt = 1000L,
                confidence = 0.95f
            )
        )
        val memories = listOf(
            UserMemoryEntity(
                id = "mem_1",
                content = "用户喜欢清淡无糖茶饮",
                category = "偏好",
                importance = 8,
                createdAt = 1000L
            ),
            UserMemoryEntity(
                id = "mem_2",
                content = "用户计划下周一提交项目总结",
                category = "日程",
                importance = 6,
                createdAt = 2000L
            )
        )
        val dreamEmergence = UnspokenThoughtEntity(
            id = "thought_1",
            thought = "昨晚入梦整理时，看到你最近一直专注高效，今天也一起加油呀！",
            urgency = 4.5f,
            createdAt = 3000L
        )
        val selfExperiences = listOf(
            SelfExperienceEntity(
                id = "exp_1",
                title = "阅读感悟",
                content = "今天读到关于心流状态的描述，感觉和 Lirica 陪伴你的感觉很像",
                domainTag = "见闻",
                createdAt = 4000L
            )
        )
        val perception = PerceptionSnapshot(
            timestamp = 1726890000000L,
            foregroundApp = ForegroundAppInfo(
                packageName = "com.tencent.mm",
                className = "com.tencent.mm.ui.LauncherUI",
                dwellDurationMs = 15 * 60 * 1000L // 15分钟
            ),
            screenState = ScreenStateSnapshot(state = ScreenState.UNLOCKED),
            latestNotification = NotificationEvent(
                id = "notif_1",
                packageName = "com.sf.express",
                title = "顺丰速运",
                content = "您的快递已到达驿站，请凭取件码取件",
                postTime = 1726889900000L,
                category = NotificationCategory.TRAVEL_LOGISTICS
            ),
            perceivedSummary = "用户已在微信停留15分钟，刚收到一条顺丰快递取件通知"
        )

        val input = MapContextInput(
            worldviews = worldviews,
            activeMemories = memories,
            archivedMemoryCount = 12,
            roster = listOf("导师：张教授", "同事：李工"),
            dreamEmergence = dreamEmergence,
            selfExperiences = selfExperiences,
            perceptionSnapshot = perception,
            charName = "Lirica"
        )

        val assembled = assembler.assemble(input)
        assertTrue(assembled != null)
        val text = assembled!!

        // 验证 Level 0 记忆地图
        assertTrue(text.contains("Level 0"))
        assertTrue(text.contains("偏好(1条)"))
        assertTrue(text.contains("日程(1条)"))
        assertTrue(text.contains("历史归档: 12项"))

        // 验证 Level 1 核心认知与花名册
        assertTrue(text.contains("Level 1"))
        assertTrue(text.contains("用户倾向于高专注深潜工作"))
        assertTrue(text.contains("导师：张教授"))

        // 验证 Level 2 工作记忆三级分层 (Resident / Active)
        assertTrue(text.contains("Level 2"))
        assertTrue(text.contains("用户喜欢清淡无糖茶饮"))
        assertTrue(text.contains("用户计划下周一提交项目总结"))

        // 验证 Level 3 梦境浮现与经历
        assertTrue(text.contains("Level 3"))
        assertTrue(text.contains("昨晚入梦整理时"))
        assertTrue(text.contains("今天读到关于心流状态的描述"))

        // 验证 Level 4 即时情境感知 (P11)
        assertTrue(text.contains("Level 4"))
        assertTrue(text.contains("微信"))
        assertTrue(text.contains("15 分钟"))
        assertTrue(text.contains("顺丰速运"))
        assertTrue(text.contains("屏幕使用中"))
    }

    @Test
    fun testAssemble_partialInput_omitsEmptyLayersGracefully() {
        val input = MapContextInput(
            worldviews = listOf(
                WorldviewEntity(
                    id = "wv_solo",
                    category = "偏好",
                    statement = "用户喜欢自然光工作环境",
                    lastConfirmedAt = 1000L
                )
            )
        )
        val text = assembler.assemble(input)!!
        assertTrue(text.contains("Level 1"))
        assertTrue(text.contains("用户喜欢自然光工作环境"))
        // 未提供的层不输出
        assertFalse(text.contains("Level 3"))
        assertFalse(text.contains("Level 4"))
    }
}
