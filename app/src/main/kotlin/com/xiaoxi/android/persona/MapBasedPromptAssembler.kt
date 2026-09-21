package com.xiaoxi.android.persona

import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MapContextInput(
    val worldviews: List<WorldviewEntity> = emptyList(),
    val activeMemories: List<UserMemoryEntity> = emptyList(),
    val archivedMemoryCount: Int = 0,
    val roster: List<String> = emptyList(),
    val dreamEmergence: UnspokenThoughtEntity? = null,
    val selfExperiences: List<SelfExperienceEntity> = emptyList(),
    val perceptionSnapshot: PerceptionSnapshot? = null,
    val charName: String = "小汐"
)

/**
 * 5 层地图式上下文组装器 (Map-Based Prompt Assembler)
 *
 * 摒弃暴力 Top-5 全文堆叠，采用 Cortico 验证的 5 层分层组装拓扑：
 * Level 0: 记忆地图 (Memory Map: 领域目录与数量统计，极低 Token 占用)
 * Level 1: 核心认知与花名册 (Worldview & Roster: 长期稳定世界观与人物关系)
 * Level 2: 备忘与工作记忆 (Memo 3-Tier: Resident 常驻核心 / Active 观察)
 * Level 3: 梦境浮现与自叙经历 (Dream Emergence & Reflection: 梦境反思与见闻活水)
 * Level 4: 即时情境感知 (Immediate Environment Context - 彻底解决 P11 采而不议断层)
 */
class MapBasedPromptAssembler(
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
) {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun assemble(input: MapContextInput): String? {
        val blocks = mutableListOf<String>()

        // --- Level 0: 记忆地图 (Memory Map) ---
        if (input.activeMemories.isNotEmpty() || input.archivedMemoryCount > 0) {
            val categoryCounts = input.activeMemories.groupBy { it.category }
                .map { (cat, list) -> "$cat(${list.size}条)" }
                .joinToString(" · ")
            val archiveNote = if (input.archivedMemoryCount > 0) " | 历史归档: ${input.archivedMemoryCount}项" else ""
            blocks.add(
                """
                ### [Level 0] 记忆地图纲要
                已归纳领域：$categoryCounts$archiveNote
                """.trimIndent()
            )
        }

        // --- Level 1: 核心认知与花名册 (Worldview & Roster) ---
        if (input.worldviews.isNotEmpty() || input.roster.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("### [Level 1] 核心认知与花名册\n")
            if (input.worldviews.isNotEmpty()) {
                sb.append("【高层世界观与认知】\n")
                for (wv in input.worldviews) {
                    sb.append("- [${wv.category}] ${wv.statement}\n")
                }
            }
            if (input.roster.isNotEmpty()) {
                sb.append("【花名册与人际关系】\n")
                for (person in input.roster) {
                    sb.append("- $person\n")
                }
            }
            blocks.add(sb.toString().trim())
        }

        // --- Level 2: 备忘与工作记忆 (Memo 3-Tier) ---
        if (input.activeMemories.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("### [Level 2] 工作记忆与关注事项\n")
            val resident = input.activeMemories.filter { it.importance >= 8 }
            val active = input.activeMemories.filter { it.importance < 8 }

            if (resident.isNotEmpty()) {
                sb.append("【常驻核心偏好与重点】\n")
                for (mem in resident) {
                    sb.append("- [${mem.category}] ${mem.content}\n")
                }
            }
            if (active.isNotEmpty()) {
                sb.append("【近期活跃关注事项】\n")
                for (mem in active) {
                    sb.append("- [${mem.category}] ${mem.content}\n")
                }
            }
            blocks.add(sb.toString().trim())
        }

        // --- Level 3: 梦境浮现与自叙经历 (Dream Emergence & Reflection) ---
        if (input.dreamEmergence != null || input.selfExperiences.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("### [Level 3] 梦境浮现与自叙见闻\n")
            if (input.dreamEmergence != null) {
                sb.append("【昨晚入梦整理与心底浮现】\n")
                sb.append("- ${input.dreamEmergence.thought}\n")
            }
            if (input.selfExperiences.isNotEmpty()) {
                sb.append("【${input.charName} 最近的见闻经历】\n")
                for (exp in input.selfExperiences) {
                    sb.append("- [${exp.domainTag}] ${exp.content}\n")
                }
            }
            blocks.add(sb.toString().trim())
        }

        // --- Level 4: 即时情境感知 (P11) ---
        val perception = input.perceptionSnapshot
        if (perception != null && (perception.foregroundApp != null || perception.latestNotification != null)) {
            val sb = StringBuilder()
            sb.append("### [Level 4] 即时情境感知\n")

            val appInfo = perception.foregroundApp
            val appDesc = if (appInfo != null) {
                val appLabel = resolveAppLabel(appInfo.packageName)
                val dwellMinutes = (appInfo.dwellDurationMs / 60000).coerceAtLeast(1)
                "前台应用: $appLabel (已停留 $dwellMinutes 分钟)"
            } else "无前台应用"

            val screenDesc = when (perception.screenState.state) {
                ScreenState.OFF -> "屏幕息屏待机"
                ScreenState.ON -> "屏幕已亮起待解锁"
                ScreenState.UNLOCKED -> "屏幕使用中"
            }

            val notifDesc = if (perception.latestNotification != null) {
                val notif = perception.latestNotification
                "通知: ${notif.title} · ${notif.content}"
            } else null

            sb.append("- $appDesc | $screenDesc")
            if (notifDesc != null) {
                sb.append(" | $notifDesc")
            }
            blocks.add(sb.toString().trim())
        }

        if (blocks.isEmpty()) return null
        return blocks.joinToString("\n\n")
    }

    private fun resolveAppLabel(packageName: String): String {
        return when {
            packageName.contains("tencent.mm") -> "微信"
            packageName.contains("tencent.mobileqq") -> "QQ"
            packageName.contains("sina.weibo") -> "微博"
            packageName.contains("autonavi") -> "高德地图"
            packageName.contains("taobao") -> "淘宝"
            packageName.contains("meituan") -> "美团"
            packageName.contains("bilibili") -> "哔哩哔哩"
            packageName.contains("sf.express") -> "顺丰速运"
            packageName.contains("launcher") -> "桌面"
            else -> packageName.substringAfterLast('.')
        }
    }
}
