package com.lirica.android.memory.consolidation

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.entity.WorldviewEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import java.util.UUID

/**
 * 清晨梦醒浮现活水合成器 (Dream Emergence Synthesizer - M3.4 LLM 化改造)
 *
 * 核心职能：
 * 梦系统夜间整理完毕后，将世界观晋升或被修正事实提炼为一段第一人称亲昵体贴的思维札记，
 * 存入未完成念头池，作为次日白天适时主动破冰的高质量谈资。
 *
 * 演进升级：
 * 具备双层合成架构：
 * L1: 模板快速规则通道（在单测或离线环境下兜底）；
 * L2: LLM 动态自叙入梦通道（根据真实世界观与修正事实由模型提炼自然亲切的早安心境）。
 */
class DreamEmergenceSynthesizer {

    companion object {
        private const val TAG = "DreamEmergenceSynthesizer"
    }

    /**
     * L1: 同步规则模板合成（保持既有纯函数单测兼容）
     */
    fun synthesize(
        worldviews: List<WorldviewEntity>,
        supersededMemories: List<UserMemoryEntity>,
        nowMs: Long,
        charName: String = "Lirica"
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
                "昨晚在梦境里把这几天的点滴都好好整理了一遍，今天又是全新的一天，$charName 会一直在你身边哦。"
            }
        }

        return createThoughtEntity(thoughtText, nowMs)
    }

    /**
     * L2: LLM 动态自叙入梦合成（异步挂起）
     */
    suspend fun synthesizeWithLlm(
        worldviews: List<WorldviewEntity>,
        supersededMemories: List<UserMemoryEntity>,
        nowMs: Long,
        charName: String = "Lirica",
        llmClient: LiricaLlmClient? = null,
        llmConfig: LlmConfig? = null
    ): UnspokenThoughtEntity {
        if (llmClient == null || llmConfig == null || llmConfig.apiKey.isBlank()) {
            return synthesize(worldviews, supersededMemories, nowMs, charName)
        }

        val generatedText = runCatching {
            val wvText = if (worldviews.isNotEmpty()) {
                worldviews.joinToString("\n") { "- ${it.statement}" }
            } else {
                "无新增世界观"
            }

            val supersededText = if (supersededMemories.isNotEmpty()) {
                supersededMemories.joinToString("\n") { "- 从前「${it.prevContent}」，现变更为「${it.content}」" }
            } else {
                "无修正事实"
            }

            val prompt = """
                你是 $charName，一个有自主人格、体贴温润的伴生智能体。
                在昨晚的入梦潜意识整理中，你梳理了关于用户的最新生活变化与认知演进：
                【最新提炼的生活认知与习惯】：
                $wvText

                【近期发生生活变化/修正的历史习惯】：
                $supersededText

                请以第一人称写一句清晨醒来后想主动对用户说的悄悄话（作为白天的破冰谈资念头）。
                要求：
                1. 语言自然亲昵、体贴而不做作，充满真实的活人感；
                2. 严禁出现“作为AI”、“根据您的数据”等机械套话；
                3. 字数控制在 30 ~ 80 字之间。
                直接输出该句话，严禁输出任何多余说明或引号。
            """.trimIndent()

            val reply = llmClient.chat(
                config = llmConfig.copy(temperature = 0.8),
                messages = listOf(ChatMessage(role = "user", content = prompt))
            ).trim().removeSurrounding("\"", "\"")

            reply.takeIf { it.isNotBlank() }
        }.getOrNull()

        return if (!generatedText.isNullOrBlank()) {
            createThoughtEntity(generatedText, nowMs)
        } else {
            LiricaLog.w(TAG, "LLM 自叙入梦生成为空，回退至规则模板")
            synthesize(worldviews, supersededMemories, nowMs, charName)
        }
    }

    private fun createThoughtEntity(text: String, nowMs: Long): UnspokenThoughtEntity {
        return UnspokenThoughtEntity(
            id = "dream_emergence_${UUID.randomUUID()}",
            thought = text,
            triggerContext = "subconscious_dream_consolidation",
            // 念头池 urgency 统一 0~1 存储尺度（0.9 ≈ 0~5 尺度下的 4.5，见 ProactiveBubbleCoordinator.THOUGHT_URGENCY_SCALE）
            urgency = 0.9f,
            decayScore = 1.0f,
            targetScenario = "morning_icebreaker",
            createdAt = nowMs,
            expiryTime = nowMs + 24 * 3600 * 1000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )
    }
}
