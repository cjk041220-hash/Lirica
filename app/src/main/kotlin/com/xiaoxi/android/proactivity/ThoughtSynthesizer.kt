package com.xiaoxi.android.proactivity

import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.NotificationCategory
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import java.util.UUID

/**
 * 未完成念头合成器
 * 将情境感知与情绪驱动转译为有温度、符合小汐性格的第一人称内心独白
 */
interface ThoughtSynthesizer {
    fun synthesize(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        motivationScore: Double
    ): UnspokenThoughtEntity?
}

class ThoughtSynthesizerImpl : ThoughtSynthesizer {

    override fun synthesize(
        snapshot: PerceptionSnapshot,
        emotion: EmotionVector,
        motivationScore: Double
    ): UnspokenThoughtEntity? {
        val now = snapshot.timestamp
        val notif = snapshot.latestNotification
        val dwellMs = snapshot.foregroundApp?.dwellDurationMs ?: 0L
        val pkg = snapshot.foregroundApp?.packageName?.lowercase() ?: ""

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

            // 5. 连续长时间使用与疲劳关怀
            dwellMs >= 30 * 60 * 1000L -> {
                Triple(
                    "你已经对着屏幕忙碌好一阵子了，稍微活动一下脖子、喝口水休息下吧？",
                    120 * 60 * 1000L,
                    0.6f
                )
            }

            // 6. 游戏高专注
            pkg.contains("game") || pkg.contains("tmgp") -> {
                Triple(
                    "看你玩得这么全神贯注，小汐先悄悄待在一旁不打扰你，这局一定要赢呀！",
                    60 * 60 * 1000L,
                    0.4f
                )
            }

            // 7. 桌面闲置无聊
            pkg.contains("launcher") || pkg.contains("home") -> {
                Triple(
                    "看你在桌面上发呆了好一会儿，是不是有点无聊呀？要不要跟小汐聊聊天呢。",
                    90 * 60 * 1000L,
                    0.5f
                )
            }

            else -> return null
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
