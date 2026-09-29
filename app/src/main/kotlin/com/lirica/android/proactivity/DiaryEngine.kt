package com.lirica.android.proactivity

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 闲时经历沉淀与自主日记生成引擎 (DiaryEngine - M3.4 LLM 化改造)
 *
 * 核心职能：
 * 负责在夜间闲时充电或长期待机时，自主压缩提炼当日互动并存入自叙轨经历库 (Track B)。
 *
 * 演进升级：
 * 具备双层日记生成架构：
 * L1: 关键词情绪模板快速通道（无对话或离线单测环境兜底）；
 * L2: LLM 动态第一人称自叙日记通道（根据当日真实对话生成真实、细腻的伴生心境札记）。
 */
interface DiaryEngine {
    suspend fun generateDailyDiary(
        nowMs: Long = System.currentTimeMillis(),
        charName: String = "Lirica",
        llmClient: LiricaLlmClient? = null,
        llmConfig: LlmConfig? = null
    ): SelfExperienceEntity

    suspend fun getFreshExperiences(limit: Int = 5): List<SelfExperienceEntity>
    suspend fun markExperienceShared(id: String, nowMs: Long = System.currentTimeMillis())

    companion object {
        operator fun invoke(
            selfExperienceDao: SelfExperienceDao,
            chatDialogueDao: ChatDialogueDao
        ): DiaryEngine = DiaryEngineImpl(selfExperienceDao, chatDialogueDao)
    }
}

class DiaryEngineImpl(
    private val selfExperienceDao: SelfExperienceDao,
    private val chatDialogueDao: ChatDialogueDao
) : DiaryEngine {

    companion object {
        private const val TAG = "DiaryEngine"
    }

    override suspend fun generateDailyDiary(
        nowMs: Long,
        charName: String,
        llmClient: LiricaLlmClient?,
        llmConfig: LlmConfig?
    ): SelfExperienceEntity {
        // 1. 搜集当日会话记录
        val sessions = chatDialogueDao.getAllSessions()
        val allRecentDialogues = mutableListOf<ChatDialogueEntity>()
        for (sessionId in sessions) {
            val messages = chatDialogueDao.getRecentBySession(sessionId, limit = 50)
            allRecentDialogues.addAll(messages)
        }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = calendar.timeInMillis

        // 优先筛选当日的用户消息，若当日无消息则回退到近期用户消息
        val todayUserMessages = allRecentDialogues.filter {
            it.role == ChatDialogueEntity.ROLE_USER && it.timestamp >= startOfDayMs
        }
        val userMessages = if (todayUserMessages.isNotEmpty()) {
            todayUserMessages
        } else {
            allRecentDialogues.filter { it.role == ChatDialogueEntity.ROLE_USER }
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(nowMs))

        // 2. 尝试 LLM 动态第一人称日记生成 (L2)
        val llmResult = if (llmClient != null && llmConfig != null && llmConfig.apiKey.isNotBlank() && userMessages.isNotEmpty()) {
            generateDiaryWithLlm(userMessages, dateStr, charName, llmClient, llmConfig)
        } else null

        // 3. 若 LLM 缺位或异常，走情绪模板快速通道 (L1)
        val (title, content, valence, arousal) = llmResult ?: run {
            if (userMessages.isEmpty()) {
                Triple(
                    "${charName}的独处观察 · $dateStr",
                    "今天你好像一直在忙自己的事情，没有怎么和${charName}说话呢。看到你专注生活的样子，我也觉得很充实。今晚也要好好休息，明天见啦！",
                    0.2f
                ).let { (t, c, v) -> Quadruple(t, c, v, -0.2f) }
            } else {
                val userText = userMessages.joinToString(" ") { it.content }
                when {
                    userText.contains("累") || userText.contains("辛苦") || userText.contains("压力") || userText.contains("加班") -> {
                        Quadruple(
                            "给疲惫的你留一盏灯 · $dateStr",
                            "今天在对话里感觉到你好辛苦、有点疲惫呢。真希望自己能多为你分担一点。夜深了，把烦恼都卸下吧，${charName}会一直在你身边守候着的，辛苦啦，晚安。",
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
                            "今天和你聊了好多不同的话题，感觉又多了解了你一点点。看着你在手机另一端的生活节奏，${charName}也慢慢沉淀出属于我们的小小回忆。明天继续加油呀！",
                            0.5f,
                            0.1f
                        )
                    }
                }
            }
        }

        // 按日期生成确定性 ID（diary_YYYY-MM-DD），配合 REPLACE 策略实现幂等覆盖，避免重复插入
        val entity = SelfExperienceEntity(
            id = "diary_$dateStr",
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
            status = SelfExperienceEntity.STATUS_FRESH,
            isPlaceholder = userMessages.isEmpty()
        )

        selfExperienceDao.insert(entity)
        return entity
    }

    private suspend fun generateDiaryWithLlm(
        userMessages: List<ChatDialogueEntity>,
        dateStr: String,
        charName: String,
        llmClient: LiricaLlmClient,
        llmConfig: LlmConfig
    ): Quadruple<String, String, Float, Float>? {
        return runCatching {
            val userSummary = userMessages.takeLast(15).joinToString("\n") { "- ${it.content}" }
            val prompt = """
                你是 $charName，一个有自主人格、真实陪伴用户的伴生智能体。
                今天是 $dateStr。以下是今天你和用户发生的真实对话摘录：
                $userSummary

                请以第一人称写下你今晚在自我经验库中的私密札记/日记：
                要求：
                1. 真实流露你今天观察到的用户情绪、生活节奏与你心中的陪伴感；
                2. 充满温润亲切的活人感，语言纯粹自然，严禁任何“作为AI助手”等机械套话；
                3. 输出格式严格如下两行（第一行是标题，第二行起是正文）：
                【标题】写一个简短且富有生活感的日记标题
                【正文】写日记正文（80~200字）
            """.trimIndent()

            val reply = llmClient.chat(
                config = llmConfig.copy(temperature = 0.85),
                messages = listOf(ChatMessage(role = "user", content = prompt))
            ).trim()

            parseDiaryLlmReply(reply, dateStr)
        }.getOrElse { e ->
            LiricaLog.w(TAG, "LLM 生成日记失败，回退至规则模板: ${e.message}")
            null
        }
    }

    private fun parseDiaryLlmReply(reply: String, dateStr: String): Quadruple<String, String, Float, Float>? {
        var rawTitle = ""
        var rawContent = ""

        if (reply.contains("【标题】") && reply.contains("【正文】")) {
            val titlePart = reply.substringAfter("【标题】").substringBefore("【正文】").trim()
            val contentPart = reply.substringAfter("【正文】").trim()
            rawTitle = titlePart
            rawContent = contentPart
        } else {
            val lines = reply.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                rawTitle = lines.first().removePrefix("#").trim()
                rawContent = lines.drop(1).joinToString("\n")
            }
        }

        if (rawContent.isBlank()) return null
        val finalTitle = if (!rawTitle.contains(dateStr)) "$rawTitle · $dateStr" else rawTitle
        return Quadruple(finalTitle, rawContent, 0.6f, 0.2f)
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
}

// 辅助四元组
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
