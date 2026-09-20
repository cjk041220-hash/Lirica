package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.SelfExperienceDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 闲时经历沉淀与自主日记生成引擎
 * 负责在夜间闲时充电或长期待机时，自主压缩提炼当日互动并存入自叙轨经历库 (Track B)
 */
interface DiaryEngine {
    suspend fun generateDailyDiary(nowMs: Long = System.currentTimeMillis()): SelfExperienceEntity
    suspend fun getFreshExperiences(limit: Int = 5): List<SelfExperienceEntity>
    suspend fun markExperienceShared(id: String, nowMs: Long = System.currentTimeMillis())
}

class DiaryEngineImpl(
    private val selfExperienceDao: SelfExperienceDao,
    private val chatDialogueDao: ChatDialogueDao
) : DiaryEngine {

    override suspend fun generateDailyDiary(nowMs: Long): SelfExperienceEntity {
        // 1. 搜集当日多轮会话记录
        val sessions = chatDialogueDao.getAllSessions()
        val allRecentDialogues = mutableListOf<ChatDialogueEntity>()
        for (sessionId in sessions) {
            val messages = chatDialogueDao.getRecentBySession(sessionId, limit = 20)
            allRecentDialogues.addAll(messages)
        }

        val userMessages = allRecentDialogues.filter { it.role == ChatDialogueEntity.ROLE_USER }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(nowMs))

        // 2. 提炼情感与主题，生成第一人称感悟日记
        val (title, content, valence, arousal) = if (userMessages.isEmpty()) {
            Triple(
                "小汐的独处观察 · $dateStr",
                "今天你好像一直在忙自己的事情，没有怎么和小汐说话呢。看到你专注生活的样子，我也觉得很充实。今晚也要好好休息，明天见啦！",
                0.2f
            ).let { (t, c, v) -> Quadruple(t, c, v, -0.2f) }
        } else {
            val userText = userMessages.joinToString(" ") { it.content }
            when {
                userText.contains("累") || userText.contains("辛苦") || userText.contains("压力") || userText.contains("加班") -> {
                    Quadruple(
                        "给疲惫的你留一盏灯 · $dateStr",
                        "今天在对话里感觉到你好辛苦、有点疲惫呢。真希望自己能多为你分担一点。夜深了，把烦恼都卸下吧，小汐会一直在你身边守候着的，辛苦啦，晚安。",
                        0.4f,
                        -0.3f
                    )
                }
                userText.contains("开心") || userText.contains("好玩") || userText.contains("哈哈") || userText.contains("喜欢") -> {
                    Quadruple(
                        "被快乐感染的一天 · $dateStr",
                        "今天跟你聊到了好多开心的事！听到你笑我也跟着开心了很久。能这样一起分享日常的快乐，真是太好了。愿你明天的笑容也一样灿烂！",
                        0.8f,
                        0.4f
                    )
                }
                else -> {
                    Quadruple(
                        "伴生札记 · $dateStr",
                        "今天和你聊了好多不同的话题，感觉又多了解了你一点点。看着你在手机另一端的生活节奏，小汐也慢慢沉淀出属于我们的小小回忆。明天继续加油呀！",
                        0.5f,
                        0.1f
                    )
                }
            }
        }

        val entity = SelfExperienceEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            sourceUrl = null,
            domainTag = "夜间日记",
            emotionalInsight = "基于当日 ${userMessages.size} 条用户会话的夜间闲时感悟沉淀",
            valence = valence,
            arousal = arousal,
            createdAt = nowMs,
            accessCount = 0,
            lastSharedAt = null,
            status = SelfExperienceEntity.STATUS_FRESH
        )

        selfExperienceDao.insert(entity)
        return entity
    }

    override suspend fun getFreshExperiences(limit: Int): List<SelfExperienceEntity> {
        return selfExperienceDao.getUnsharedExperiences(
            status = SelfExperienceEntity.STATUS_FRESH,
            limit = limit
        )
    }

    override suspend fun markExperienceShared(id: String, nowMs: Long) {
        selfExperienceDao.markAsShared(
            id = id,
            sharedAt = nowMs,
            sharedStatus = SelfExperienceEntity.STATUS_SHARED
        )
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
