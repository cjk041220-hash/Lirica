package com.xiaoxi.android.memory.consolidation

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 相对时间绝对化引擎 (Time Disambiguator)
 *
 * 根据记忆发生的时间戳，将对话中"明天/后天/昨晚/下周一"等模糊口语
 * 统一替换为严格的绝对 ISO 日期 (YYYY-MM-DD)，消除跨时间断层。
 */
class TimeDisambiguator(
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun disambiguate(content: String, baseTimestamp: Long): String {
        val baseDate = Instant.ofEpochMilli(baseTimestamp)
            .atZone(zoneId)
            .toLocalDate()

        var result = content

        // 1. 相对天数映射（较长词组在前，避免"后天"贪心吞噬"大后天"）
        val dayOffsets = listOf(
            "大后天" to 3L,
            "大前天" to -3L,
            "后天" to 2L,
            "前天" to -2L,
            "昨天" to -1L,
            "今天" to 0L,
            "明天" to 1L
        )

        for ((term, offset) in dayOffsets) {
            if (result.contains(term)) {
                val targetDate = baseDate.plusDays(offset).format(dateFormatter)
                result = result.replace(Regex("(?:于|在)?$term\\s*"), "在 $targetDate ")
            }
        }

        // 2. 下周几映射
        val nextWeekDays = listOf(
            "下周一" to DayOfWeek.MONDAY,
            "下周二" to DayOfWeek.TUESDAY,
            "下周三" to DayOfWeek.WEDNESDAY,
            "下周四" to DayOfWeek.THURSDAY,
            "下周五" to DayOfWeek.FRIDAY,
            "下周六" to DayOfWeek.SATURDAY,
            "下周日" to DayOfWeek.SUNDAY,
            "下周天" to DayOfWeek.SUNDAY
        )

        for ((term, dayOfWeek) in nextWeekDays) {
            if (result.contains(term)) {
                val nextMonday = baseDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                val targetDate = nextMonday.with(TemporalAdjusters.nextOrSame(dayOfWeek)).format(dateFormatter)
                result = result.replace(Regex("(?:于|在)?$term\\s*"), "在 $targetDate ")
            }
        }

        return result
    }
}
