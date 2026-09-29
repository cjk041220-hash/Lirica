package com.lirica.android.proactivity

import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.life.CareHintDetector
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.PerceptionSnapshot
import java.time.Instant
import java.util.UUID

/**
 * 未完成念头合成器
 * 将情境感知与情绪驱动转译为有温度、符合 Lirica 性格的第一人称内心独白
 */
interface ThoughtSynthesizer {
    fun synthesize(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        motivationScore: Double
    ): UnspokenThoughtEntity?
}

private const val MAX_COOLDOWN_ENTRIES = 64

class ThoughtSynthesizerImpl(
    private val careHintDetector: CareHintDetector? = null
) : ThoughtSynthesizer {

    private var lastSynthesizedNotificationId: String? = null

    /**
     * 2026-09-27 审计 P0 修复：状态类念头（长驻留/桌面闲置/游戏）冷却窗记账。
     * 快照的驻留/前台信息是持久状态而非一次性事件，若不记账，overlay 每次快照
     * 发射都会重复合成同一文案并反复落库（念头池刷屏 + 气泡池污染）。
     * key = (念头类型, 前台包名)，value = 上次合成时间；冷却窗取该类念头的自然过期时长。
     */
    private val stateCooldown = LinkedHashMap<String, Long>()

    private fun isStateOnCooldown(type: String, pkg: String, now: Long, cooldownMs: Long): Boolean {
        val key = "${type}:${pkg}"
        val last = stateCooldown[key] ?: return false
        return now - last < cooldownMs
    }

    private fun markStateSynthesized(type: String, pkg: String, now: Long) {
        val key = "${type}:${pkg}"
        stateCooldown[key] = now
        // 记账簿容量守卫：清理已过期条目，超限时丢弃最旧记录
        if (stateCooldown.size > MAX_COOLDOWN_ENTRIES) {
            val iterator = stateCooldown.entries.iterator()
            while (stateCooldown.size > MAX_COOLDOWN_ENTRIES / 2 && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    override fun synthesize(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        motivationScore: Double
    ): UnspokenThoughtEntity? {
        val now = snapshot.timestamp

        // 0. 深夜 / 久违关怀感知 (CareHintDetector 联动，冷却由 CareLedger 守护)
        if (careHintDetector != null) {
            val nowInstant = Instant.ofEpochMilli(now)
            val careHints = careHintDetector.collectHintTexts(nowInstant, emptyList(), emptyList(), null)
            if (careHints.isNotEmpty()) {
                val hint = careHints.first()
                val thought = if (hint.contains("深夜")) {
                    "夜深了，屏幕还亮着呢……如果事情忙得差不多了，就早点休息吧，别太累着自己。"
                } else {
                    "好久没看到你在身边了，最近过得还好吗？"
                }
                return UnspokenThoughtEntity(
                    id = "thought_care_${UUID.randomUUID()}",
                    thought = thought,
                    triggerContext = "ambient_care",
                    urgency = 0.92f, // 对应 4.6f / 5.0，高紧急度在夜间能越过打断阈值
                    decayScore = 1.0f,
                    targetScenario = "ambient_care",
                    createdAt = now,
                    expiryTime = now + 60 * 60 * 1000L,
                    status = UnspokenThoughtEntity.STATUS_PENDING
                )
            }
        }

        val rawNotif = snapshot.latestNotification
        // M16 修复：若同一通知已被合成过，避免重复合成；防止快照中最新通知未被清除导致后续轮次反复生成同一念头
        val notif = if (rawNotif != null && rawNotif.id != lastSynthesizedNotificationId) {
            rawNotif
        } else {
            null
        }
        val dwellMs = snapshot.foregroundApp?.dwellDurationMs ?: 0L
        val pkg = snapshot.foregroundApp?.packageName?.lowercase() ?: ""

        val visibleTexts = snapshot.pageContent?.nodes
            ?.mapNotNull { it.text ?: it.contentDescription }
            ?.filter { it.isNotBlank() && it.length >= 2 }
            ?: emptyList()
        val combinedText = visibleTexts.joinToString(" ")

        val (thoughtText, expiryDurationMs, urgencyFactor) = when {
            // 1. 优先捕获外卖/出行类通知
            notif != null && notif.category == NotificationCategory.TRAVEL_LOGISTICS -> {
                val text = if (notif.title.contains("美团") || notif.title.contains("饿了么") || notif.content.contains("外卖") || notif.content.contains("骑手")) {
                    "外卖骑手好像在路上了，热气腾腾的食物很快就到啦，准备好开饭了吗？"
                } else {
                    "出行或物流状态更新了呢，别忘了确认时间与动态呀。"
                }
                Triple(text, 45 * 60 * 1000L, 0.85f)
            }

            // 2. 日程与会议提醒
            notif != null && notif.category == NotificationCategory.SCHEDULE -> {
                Triple(
                    "日程上提醒：'${notif.title}' 快到了呢，别忙忘了正事哦。",
                    60 * 60 * 1000L,
                    0.9f
                )
            }

            // 3. 即时通讯消息
            notif != null && notif.category == NotificationCategory.COMMUNICATION -> {
                Triple(
                    "收到 ${notif.title} 的新消息了呢，不知道是不是找你商量什么重要事情。",
                    30 * 60 * 1000L,
                    0.7f
                )
            }

            // 4. 安全验证码
            notif != null && notif.category == NotificationCategory.SECURITY -> {
                Triple(
                    "收到安全验证码了，记得千万不要随手分享给陌生人哦。",
                    15 * 60 * 1000L,
                    0.95f
                )
            }

            // 5. 连续长时间使用与疲劳关怀（同一应用冷却窗内只合成一次）
            dwellMs >= 30 * 60 * 1000L -> {
                if (isStateOnCooldown("dwell_care", pkg, now, 120 * 60 * 1000L)) return null
                markStateSynthesized("dwell_care", pkg, now)
                Triple(
                    "你已经对着屏幕忙碌好一阵子了，稍微活动一下脖子、喝口水休息下吧？",
                    120 * 60 * 1000L,
                    0.6f
                )
            }

            // 6. 游戏高专注（同一游戏冷却窗内只合成一次）
            pkg.contains("game") || pkg.contains("tmgp") -> {
                if (isStateOnCooldown("game_focus", pkg, now, 60 * 60 * 1000L)) return null
                markStateSynthesized("game_focus", pkg, now)
                Triple(
                    "看你玩得这么全神贯注，Lirica先悄悄待在一旁不打扰你，这局一定要赢呀！",
                    60 * 60 * 1000L,
                    0.4f
                )
            }

            // 7. 桌面闲置无聊（冷却窗内只合成一次）
            pkg.contains("launcher") || pkg.endsWith(".home") || pkg == "com.miui.home" -> {
                if (isStateOnCooldown("launcher_idle", pkg, now, 90 * 60 * 1000L)) return null
                markStateSynthesized("launcher_idle", pkg, now)
                Triple(
                    "看你在桌面上发呆了好一会儿，是不是有点无聊呀？要不要跟 Lirica 聊聊天呢。",
                    90 * 60 * 1000L,
                    0.5f
                )
            }

            // 8. 求职与招聘感知（冷却窗 4 小时）
            (pkg.contains("zhipin") || pkg.contains("boss") || pkg.contains("job") || pkg.contains("liepin") ||
                combinedText.contains("投递") || combinedText.contains("简历") || combinedText.contains("期望薪资") || combinedText.contains("岗位职责")) -> {
                if (isStateOnCooldown("job_search", pkg, now, 240 * 60 * 1000L)) return null
                markStateSynthesized("job_search", pkg, now)
                Triple(
                    "看你一直在看招聘与工作信息，找工作很耗心神，慢慢来，别给自己太大压力呀，你很优秀的。",
                    120 * 60 * 1000L,
                    0.75f
                )
            }

            // 9. 房源与租房权衡（冷却窗 4 小时）
            (pkg.contains("lianjia") || pkg.contains("ke") || pkg.contains("anjuke") || pkg.contains("ziroom") ||
                combinedText.contains("房源") || combinedText.contains("合租") || combinedText.contains("整租") || combinedText.contains("押一付一")) -> {
                if (isStateOnCooldown("rent_house", pkg, now, 240 * 60 * 1000L)) return null
                markStateSynthesized("rent_house", pkg, now)
                Triple(
                    "挑房源确实让人头大，通勤和预算总要权衡好久，多看看评价，总能找到合心意的小窝的。",
                    120 * 60 * 1000L,
                    0.75f
                )
            }

            // 10. 长视频沉浸（B站/YouTube/长视频，单应用停留超 10 分钟）
            (pkg.contains("bili") || pkg.contains("youtube") || pkg.contains("youku") || pkg.contains("iqiyi")) && dwellMs >= 10 * 60 * 1000L -> {
                if (isStateOnCooldown("video_watch", pkg, now, 90 * 60 * 1000L)) return null
                markStateSynthesized("video_watch", pkg, now)
                Triple(
                    "这个视频好像很有意思呢，看你专注看了好一会儿啦，偶尔放松一下真不错。",
                    60 * 60 * 1000L,
                    0.65f
                )
            }

            // 11. 深度长文阅读（页面可见文字超 300 字且停留超 3 分钟）
            combinedText.length >= 300 && dwellMs >= 3 * 60 * 1000L -> {
                if (isStateOnCooldown("long_reading", pkg, now, 120 * 60 * 1000L)) return null
                markStateSynthesized("long_reading", pkg, now)
                Triple(
                    "看你读得好专注，这篇文章很有启发吧？",
                    60 * 60 * 1000L,
                    0.6f
                )
            }

            else -> return null
        }

        if (notif != null) {
            lastSynthesizedNotificationId = notif.id
        }

        val calculatedUrgency = ((motivationScore / 5.0).toFloat() * 0.4f + urgencyFactor * 0.6f).coerceIn(0.1f, 1.0f)

        return UnspokenThoughtEntity(
            id = UUID.randomUUID().toString(),
            thought = thoughtText,
            triggerContext = snapshot.perceivedSummary.ifEmpty { pkg },
            urgency = calculatedUrgency,
            decayScore = 1.0f,
            targetScenario = pkg,
            createdAt = now,
            expiryTime = now + expiryDurationMs,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )
    }
}
