package com.lirica.android.memory.consolidation

import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.dao.WorldviewDao
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.memory.retrieval.HybridMemoryRetriever
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.proactivity.DiaryEngine

data class DreamCycleReport(
    val originalCount: Int,
    val survivingCount: Int,
    val archivedCount: Int,
    val timeDisambiguatedCount: Int,
    val supersededCount: Int,
    val worldviewPromotedCount: Int,
    val emergenceThoughtId: String?,
    val consolidatedEpisodesCount: Int = 0
)

/**
 * 自叙梦系统总控中枢 (Subconscious Dream Coordinator)
 *
 * 统筹夜间入梦的核心整理流水线：
 * 0. 白天情境感知切片提炼自叙生活经历（Track B - M4.1 核心）
 * 1. 相对时间绝对化
 * 2. 语义去重合并（反囤积铁律）
 * 3. 矛盾消歧与信念修正
 * 4. 高层世界观认知晋升
 * 5. 闲时日记沉淀与占位日记刷新
 * 6. 梦醒浮现活水注入念头池
 * 7. 向量索引全量刷新
 */
class SubconsciousDreamCoordinator(
    private val userMemoryDao: UserMemoryDao,
    private val worldviewDao: WorldviewDao,
    private val selfExperienceDao: SelfExperienceDao,
    private val unspokenThoughtDao: UnspokenThoughtDao,
    private val diaryEngine: DiaryEngine? = null,
    private val deduplicator: SemanticDeduplicator = SemanticDeduplicator(),
    private val timeDisambiguator: TimeDisambiguator = TimeDisambiguator(),
    private val beliefRevisionEngine: BeliefRevisionEngine = BeliefRevisionEngine(),
    private val worldviewPromoter: WorldviewPromoter = WorldviewPromoter(),
    private val emergenceSynthesizer: DreamEmergenceSynthesizer = DreamEmergenceSynthesizer(),
    private val llmClient: LiricaLlmClient? = null,
    private val llmConfig: LlmConfig? = null,
    private val charName: String = "Lirica",
    private val hybridRetriever: HybridMemoryRetriever? = null,
    private val episodeConsolidator: DreamEpisodeConsolidator? = null
) {

    suspend fun runDreamCycle(nowMs: Long = System.currentTimeMillis()): DreamCycleReport {
        // 0. 流水线零：白天积累的情境切片提炼自叙生活经历（Track B）
        val consolidatedCount = episodeConsolidator?.consolidateDailyEpisodes(
            nowMs = nowMs,
            charName = charName,
            llmClient = llmClient,
            llmConfig = llmConfig
        ) ?: 0

        // 1. 获取所有活跃记忆
        val allActive = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 500)
        val originalCount = allActive.size
        if (allActive.isEmpty()) {
            diaryEngine?.generateDailyDiary(nowMs, charName, llmClient, llmConfig)
            return DreamCycleReport(0, 0, 0, 0, 0, 0, null, consolidatedEpisodesCount = consolidatedCount)
        }

        // 2. 流水线一：相对时间绝对化
        var timeDisambiguatedCount = 0
        val timeDisambiguatedMemories = allActive.map { mem ->
            val updatedContent = timeDisambiguator.disambiguate(mem.content, mem.createdAt)
            if (updatedContent != mem.content) {
                timeDisambiguatedCount++
                val disambiguated = mem.copy(content = updatedContent)
                userMemoryDao.update(disambiguated)
                disambiguated
            } else {
                mem
            }
        }

        // 3. 流水线二：语义去重合并（反囤积）
        val dedupResult = deduplicator.deduplicate(timeDisambiguatedMemories)
        for (archived in dedupResult.archivedMemories) {
            userMemoryDao.update(archived)
        }
        for (surviving in dedupResult.survivingMemories) {
            userMemoryDao.update(surviving)
        }

        // 4. 流水线三：矛盾消歧与信念修正 (LLM 增强)
        val sortedByDate = dedupResult.survivingMemories.sortedBy { it.createdAt }
        val olderMemories = sortedByDate.take(sortedByDate.size / 2 + 1)
        val newerMemories = sortedByDate.drop(sortedByDate.size / 2 + 1)

        val revisionResult = beliefRevisionEngine.detectAndReviseWithLlm(
            existingMemories = olderMemories,
            newMemories = newerMemories,
            nowMs = nowMs,
            llmClient = llmClient,
            llmConfig = llmConfig
        )

        for (superseded in revisionResult.supersededMemories) {
            userMemoryDao.markSuperseded(
                oldId = superseded.id,
                newId = superseded.supersededBy ?: "",
                supersededTimestamp = nowMs,
                prevContentSnapshot = superseded.prevContent
            )
        }

        // 5. 流水线四：高层世界观提炼晋升
        val currentSurviving = userMemoryDao.getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 500)
        val promotedWorldviews = worldviewPromoter.evaluatePromotions(currentSurviving, nowMs)
        if (promotedWorldviews.isNotEmpty()) {
            worldviewDao.insertAll(promotedWorldviews)
        }

        // 6. 流水线五：夜间日记沉淀生成（LLM 动态第一人称自叙日记）
        diaryEngine?.generateDailyDiary(nowMs, charName, llmClient, llmConfig)

        // 7. 流水线六：次日清晨梦醒浮现活水注入念头池 (LLM 增强)
        val emergenceThought = emergenceSynthesizer.synthesizeWithLlm(
            worldviews = promotedWorldviews,
            supersededMemories = revisionResult.supersededMemories,
            nowMs = nowMs,
            charName = charName,
            llmClient = llmClient,
            llmConfig = llmConfig
        )
        unspokenThoughtDao.insert(emergenceThought)

        // 8. 流水线七：入梦流水线末端全量向量重算与索引刷新 (D21)
        hybridRetriever?.recomputeAllVectors()

        return DreamCycleReport(
            originalCount = originalCount,
            survivingCount = currentSurviving.size,
            archivedCount = dedupResult.archivedMemories.size,
            timeDisambiguatedCount = timeDisambiguatedCount,
            supersededCount = revisionResult.supersededMemories.size,
            worldviewPromotedCount = promotedWorldviews.size,
            emergenceThoughtId = emergenceThought.id,
            consolidatedEpisodesCount = consolidatedCount
        )
    }
}