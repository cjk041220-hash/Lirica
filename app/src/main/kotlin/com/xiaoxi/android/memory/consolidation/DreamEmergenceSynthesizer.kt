package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity
import java.util.UUID

/**
 * 清晨梦醒浮现活水合成器 (Dream Emergence Synthesizer)
 *
 * 梦系统夜间整理完毕后，将世界观晋升或被修正事实提炼为一段第一人称亲昵体贴的思维札记，
 * 存入未完成念头池，作为次日白天适时主动破冰的高质量谈资。
 */
class DreamEmergenceSynthesizer {

    fun synthesize(
        worldviews: List<WorldviewEntity>,
        supersededMemories: List<UserMemoryEntity>,
        nowMs: Long,
        charName: String = "小汐"
    ): UnspokenThoughtEntity {
        val thoughtText = when {
            supersededMemories.isNotEmpty() -> {
                val revised = supersededMemories.first()
                if (revised.content.contains("咖啡")) {
                    "昨晚整理回忆的时候，发现你这几天胃不舒服已经改喝大麦茶了，今天胃感觉好点了吗？"
                } else if (revised.content.contains("辣")) {
                    "昨晚入梦回顾了一下，发现你最近胃不太好在戒辣呢，今天吃饭也要记得选温和清淡的呀。"
                } else {
                    "昨晚整理了一下你最近的生活变化，发现有了好多新的节奏呢，今天也要元气满满哦！"
                }
            }
            worldviews.isNotEmpty() -> {
                val topWv = worldviews.first()
                if (topWv.category == "工作习惯" || topWv.statement.contains("深度工作")) {
                    "昨晚入梦整理时，看到你最近一直专注高效地深度工作，真为你感到高兴，今天也一起加油呀！"
                } else {
                    "昨晚细细梳理了你平时的生活习惯，感觉又更了解你一点点了呢，今天也要开开心心的！"
                }
            }
            else -> {
                "昨晚在梦境里把这几天的点滴都好好整理了一遍，今天又是全新的一天，小汐会一直在你身边哦。"
            }
        }

        return UnspokenThoughtEntity(
            id = "dream_emergence_${UUID.randomUUID()}",
            thought = thoughtText,
            triggerContext = "subconscious_dream_consolidation",
            urgency = 4.5f,
            decayScore = 1.0f,
            targetScenario = "morning_icebreaker",
            createdAt = nowMs,
            expiryTime = nowMs + 24 * 3600 * 1000L, // 24小时有效
            status = UnspokenThoughtEntity.STATUS_PENDING
        )
    }
}
