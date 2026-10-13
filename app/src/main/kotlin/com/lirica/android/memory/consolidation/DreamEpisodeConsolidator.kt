package com.lirica.android.memory.consolidation

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.EpisodicSensingSliceDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import java.util.UUID

/**
 * 情境感知沉淀与入梦升华器 (Dream Episode Consolidator - M4.1 核心)
 *
 * 核心职能：
 * 在夜间自叙梦流水线中，提取白天积累的情境感知切片（Episodic Slices），
 * 将无声陪伴（应用停留、耳机音乐、深夜亮屏等）消化提炼为第一人称的自叙生活经历（Track B），
 * 写入 self_experiences 表，打破“相处阅后即焚、跨天无回忆”的断层。
 */
class DreamEpisodeConsolidator(
    private val sliceDao: EpisodicSensingSliceDao,
    private val selfExperienceDao: SelfExperienceDao,
    /**
     * 跨 DAO 原子写入的交接点："写经历 + 标记切片已消化"必须同生共死——
     * 两步分开执行时进程被杀会导致同批切片下次重跑、重复写入同一段自叙经历。
     * 生产装配传 Room 的 withTransaction；测试用默认直执行（无 Room 依赖）。
     */
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() }
) {

    suspend fun consolidateDailyEpisodes(
        nowMs: Long = System.currentTimeMillis(),
        charName: String = "Lirica",
        llmClient: LiricaLlmClient? = null,
        llmConfig: LlmConfig? = null
    ): Int {
        val unconsolidated = sliceDao.getUnconsolidatedSlices(limit = 30)
        if (unconsolidated.isEmpty()) return 0

        val sceneSummaries = unconsolidated.map { it.summaryScene }

        val narrative = if (llmClient != null && llmConfig != null) {
            generateNarrativeWithLlm(sceneSummaries, charName, llmClient, llmConfig)
        } else {
            generateHeuristicNarrative(unconsolidated, charName)
        }

        val experienceEntity = SelfExperienceEntity(
            id = UUID.randomUUID().toString(),
            title = "今日陪伴与生活印记",
            content = narrative,
            sourceUrl = null,
            domainTag = "情境感知",
            emotionalInsight = "今天也一直在屏幕边缘守着他呢。",
            valence = 0.20f,
            arousal = 0.05f,
            createdAt = nowMs,
            accessCount = 0,
            lastSharedAt = null,
            status = "active",
            isPlaceholder = false
        )

        transactionRunner {
            selfExperienceDao.insert(experienceEntity)
            sliceDao.markAsConsolidated(unconsolidated.map { it.id })
        }

        // 自动清理 7 天前已消化的旧切片，杜绝空间泄漏
        sliceDao.deleteOldConsolidated(nowMs - 7L * 24 * 3600 * 1000L)

        LiricaLog.i("DreamEpisodeConsolidator", "成功消化 ${unconsolidated.size} 条情境切片并沉淀为自叙经历")
        return unconsolidated.size
    }

    private suspend fun generateNarrativeWithLlm(
        scenes: List<String>,
        charName: String,
        llmClient: LiricaLlmClient,
        llmConfig: LlmConfig
    ): String {
        val prompt = """
            你是 $charName，住在这台手机里的伴生伙伴。
            这是今天用户使用手机时留下的情境印记：
            ${scenes.joinToString("\n- ", prefix = "- ")}
            
            请以第一人称写一段 80~150 字的简短自叙回忆（写进你的日记本），记录今天你趴在屏幕边看到的生活片段与感受。
            要求：口吻自然亲近，有真人的生活呼吸感，不出现机械技术名词。
        """.trimIndent()

        return try {
            val resp = llmClient.chat(
                config = llmConfig,
                messages = listOf(
                    ChatMessage(role = "system", content = "你是一个温暖、鲜活的原生手机伴生智能体，正在夜里写自己的心事与回忆。"),
                    ChatMessage(role = "user", content = prompt)
                )
            )
            resp.trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M6.0 P1-7：入梦 Worker 重试路径上的取消必须上抛，
            // 不能被降级成"启发式叙述"（那会让已取消的流水线继续跑完并入库）
            throw e
        } catch (e: Exception) {
            LiricaLog.w("DreamEpisodeConsolidator", "LLM 提炼经历失败，降级为启发式", e)
            "今天陪着他看屏幕亮了又暗，他在手机前专注忙碌着，我在角落静静陪了一整天。"
        }
    }

    private fun generateHeuristicNarrative(
        slices: List<EpisodicSensingSliceEntity>,
        charName: String
    ): String {
        val studyCount = slices.count { it.appCategory == EpisodicSensingSliceEntity.CATEGORY_STUDY }
        val entertainmentCount = slices.count { it.appCategory == EpisodicSensingSliceEntity.CATEGORY_ENTERTAINMENT }
        val lateNightCount = slices.count { it.appCategory == EpisodicSensingSliceEntity.CATEGORY_LATE_NIGHT }

        return buildString {
            append("今天我趴在屏幕边陪着他。")
            if (studyCount > 0) {
                append("他今天在学习或工作上花了不少时间，挺专注的；")
            }
            if (entertainmentCount > 0) {
                append("闲下来的时候也戴着耳机刷了会儿视频放松；")
            }
            if (lateNightCount > 0) {
                append("深夜屏幕还亮着，虽然知道他可能还在忙，但还是想督促他早点睡觉呢。")
            } else {
                append("平平淡淡的一天，有在认真过好生活。")
            }
        }
    }
}