package com.xiaoxi.android.memory.consolidation

import com.xiaoxi.android.common.logging.XiaoxiLog
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RevisionResult(
    val supersededMemories: List<UserMemoryEntity>,
    val activeMemories: List<UserMemoryEntity>
)

/**
 * 矛盾消歧与信念修正引擎 (Belief Revision Engine - M3.4 LLM 化改造)
 *
 * 核心职能：
 * 发现新旧认知冲突时，将旧条目更新为 status = 'superseded'，
 * 记录 superseded_by、prev_content 与 valid_to，保留认识论审计轨迹。
 *
 * 演进升级：
 * 具备双层消歧架构：
 * L1: CONFLICT_PAIRS 快速规则通道（毫秒级命中高频反转，同步调用）；
 * L2: LLM 深度认知冲突推理通道（能够识别任意现实事实冲突，如“喜好逆转/居住地变更/状态演化”）。
 */
class BeliefRevisionEngine {

    companion object {
        private const val TAG = "BeliefRevisionEngine"

        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // L1 快速规则通道：矛盾对特征映射 (旧特征关键词组 to 新反转排斥关键词组)
        private val CONFLICT_PAIRS = listOf(
            // 咖啡
            setOf("咖啡", "美式", "黑咖啡", "拿铁") to setOf("戒掉咖啡", "不喝咖啡", "戒咖啡", "改喝大麦茶", "大麦茶"),
            // 吃辣
            setOf("吃辣", "麻辣", "无辣不欢", "喜欢辣") to setOf("不能吃辣", "严禁吃辣", "戒辣", "不吃辣", "清淡温热"),
            // 居住地
            setOf("住在北京", "在北京") to setOf("搬到了上海", "住在上海", "去上海生活"),
            // 感情状态
            setOf("单身") to setOf("恋爱中", "结婚了", "有对象")
        )
    }

    /**
     * L1: 同步规则消歧（保持既有纯函数单测兼容）
     */
    fun detectAndRevise(
        existingMemories: List<UserMemoryEntity>,
        newMemories: List<UserMemoryEntity>,
        nowMs: Long
    ): RevisionResult {
        val superseded = mutableListOf<UserMemoryEntity>()
        val active = newMemories.toMutableList()

        for (oldMem in existingMemories) {
            if (oldMem.status != UserMemoryEntity.STATUS_ACTIVE) continue

            var isContradicted = false
            var contradictor: UserMemoryEntity? = null

            for (newMem in newMemories) {
                if (hasContradiction(oldMem.content, newMem.content)) {
                    isContradicted = true
                    contradictor = newMem
                    break
                }
            }

            if (isContradicted && contradictor != null) {
                val updatedOld = oldMem.copy(
                    status = UserMemoryEntity.STATUS_SUPERSEDED,
                    supersededBy = contradictor.id,
                    prevContent = oldMem.content,
                    validTo = nowMs
                )
                superseded.add(updatedOld)
            } else {
                active.add(oldMem)
            }
        }

        return RevisionResult(
            supersededMemories = superseded,
            activeMemories = active
        )
    }

    /**
     * L2: LLM 增强型认知冲突检测（异步挂起）
     */
    suspend fun detectAndReviseWithLlm(
        existingMemories: List<UserMemoryEntity>,
        newMemories: List<UserMemoryEntity>,
        nowMs: Long,
        llmClient: XiaoxiLlmClient? = null,
        llmConfig: LlmConfig? = null
    ): RevisionResult {
        val activeExisting = existingMemories.filter { it.status == UserMemoryEntity.STATUS_ACTIVE }
        if (activeExisting.isEmpty() || newMemories.isEmpty()) {
            return RevisionResult(supersededMemories = emptyList(), activeMemories = existingMemories + newMemories)
        }

        // 若未提供 LLM 则直接走规则快速通道
        if (llmClient == null || llmConfig == null || llmConfig.apiKey.isBlank()) {
            return detectAndRevise(existingMemories, newMemories, nowMs)
        }

        val contradictionMap = mutableMapOf<String, UserMemoryEntity>()

        // 1. LLM 认知推理
        val llmContradictions = queryLlmForContradictions(
            existingMemories = activeExisting,
            newMemories = newMemories,
            llmClient = llmClient,
            llmConfig = llmConfig
        )
        contradictionMap.putAll(llmContradictions)

        // 2. 规则快速通道补漏
        for (oldMem in activeExisting) {
            if (contradictionMap.containsKey(oldMem.id)) continue
            for (newMem in newMemories) {
                if (hasContradiction(oldMem.content, newMem.content)) {
                    contradictionMap[oldMem.id] = newMem
                    break
                }
            }
        }

        // 3. 构建结果
        val superseded = mutableListOf<UserMemoryEntity>()
        val active = newMemories.toMutableList()

        for (oldMem in activeExisting) {
            val contradictor = contradictionMap[oldMem.id]
            if (contradictor != null) {
                val updatedOld = oldMem.copy(
                    status = UserMemoryEntity.STATUS_SUPERSEDED,
                    supersededBy = contradictor.id,
                    prevContent = oldMem.content,
                    validTo = nowMs
                )
                superseded.add(updatedOld)
            } else {
                active.add(oldMem)
            }
        }

        return RevisionResult(
            supersededMemories = superseded,
            activeMemories = active
        )
    }

    private suspend fun queryLlmForContradictions(
        existingMemories: List<UserMemoryEntity>,
        newMemories: List<UserMemoryEntity>,
        llmClient: XiaoxiLlmClient,
        llmConfig: LlmConfig
    ): Map<String, UserMemoryEntity> {
        return runCatching {
            val oldSummary = existingMemories.joinToString("\n") { "- [${it.id}] ${it.content}" }
            val newSummary = newMemories.joinToString("\n") { "- [${it.id}] ${it.content}" }

            val prompt = """
                你是一个专门负责长期记忆事实消歧与信念修正的认知系统。
                请仔细比对以下用户的【历史事实记忆】与【最新经历记忆】。
                分析新旧记忆之间是否存在事实矛盾或状态逆转（例如：喜好逆转、习惯改变、居住地变更、生活状态演进）。

                【历史事实记忆】：
                $oldSummary

                【最新经历记忆】：
                $newSummary

                输出规范：
                若存在矛盾，仅返回 JSON 列表：
                [{"oldId":"被推翻的历史记忆ID","newId":"引发推翻的最新记忆ID","reason":"简短原因"}]
                若不存在任何矛盾，返回 []。
                严禁输出任何解释、寒暄或 Markdown 标记。
            """.trimIndent()

            val response = llmClient.chat(
                config = llmConfig.copy(temperature = 0.0),
                messages = listOf(ChatMessage(role = "user", content = prompt))
            ).trim()

            parseContradictionJson(response, newMemories)
        }.getOrElse { e ->
            XiaoxiLog.w(TAG, "LLM 认知冲突推理失败，安全回退至规则通道: ${e.message}")
            emptyMap()
        }
    }

    private fun parseContradictionJson(
        rawJson: String,
        newMemories: List<UserMemoryEntity>
    ): Map<String, UserMemoryEntity> {
        val result = mutableMapOf<String, UserMemoryEntity>()
        val jsonStart = rawJson.indexOf('[')
        val jsonEnd = rawJson.lastIndexOf(']')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) return emptyMap()

        val cleanJson = rawJson.substring(jsonStart, jsonEnd + 1)
        val array = defaultJson.parseToJsonElement(cleanJson).jsonArray
        val newMemMap = newMemories.associateBy { it.id }

        for (elem in array) {
            val obj = elem.jsonObject
            val oldId = obj["oldId"]?.jsonPrimitive?.content ?: continue
            val newId = obj["newId"]?.jsonPrimitive?.content ?: continue
            val contradictor = newMemMap[newId]
            if (contradictor != null) {
                result[oldId] = contradictor
            }
        }
        return result
    }

    private fun hasContradiction(oldText: String, newText: String): Boolean {
        for ((oldTerms, newTerms) in CONFLICT_PAIRS) {
            val oldMatches = oldTerms.any { oldText.contains(it) }
            val newMatches = newTerms.any { newText.contains(it) }
            if (oldMatches && newMatches) {
                return true
            }
        }
        return false
    }
}
