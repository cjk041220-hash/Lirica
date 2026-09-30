package com.lirica.android.memory.consolidation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeDisambiguatorTest {

    private val testZoneId = ZoneId.of("Asia/Shanghai")
    private val disambiguator = TimeDisambiguator(testZoneId)

    // 设定基准时间为 2026-09-21 10:00:00 UTC+8
    private val baseTimestamp = LocalDate.of(2026, 9, 21)
        .atStartOfDay(testZoneId)
        .toInstant()
        .toEpochMilli()

    @Test
    fun testDisambiguate_relativeDays() {
        assertEquals(
            "用户在 2026-09-22 要去北京出差",
            disambiguator.disambiguate("用户明天要去北京出差", baseTimestamp)
        )
        assertEquals(
            "用户在 2026-09-23 有重要答辩",
            disambiguator.disambiguate("用户后天有重要答辩", baseTimestamp)
        )
        assertEquals(
            "用户在 2026-09-24 参加技术峰会",
            disambiguator.disambiguate("用户大后天参加技术峰会", baseTimestamp)
        )
        assertEquals(
            "用户在 2026-09-20 加班到深夜",
            disambiguator.disambiguate("用户昨天加班到深夜", baseTimestamp)
        )
        assertEquals(
            "用户在 2026-09-19 买了新显卡",
            disambiguator.disambiguate("用户前天买了新显卡", baseTimestamp)
        )
        assertEquals(
            "用户在 2026-09-21 感到身体不适",
            disambiguator.disambiguate("用户今天感到身体不适", baseTimestamp)
        )
    }

    @Test
    fun testDisambiguate_nextWeekDays() {
        // 2026-09-21 是周一。下周一为 2026-09-28，下周五为 2026-10-02
        assertEquals(
            "用户计划在 2026-09-28 开启新项目",
            disambiguator.disambiguate("用户计划下周一开启新项目", baseTimestamp)
        )
        assertEquals(
            "用户计划在 2026-10-02 提交报告",
            disambiguator.disambiguate("用户计划下周五提交报告", baseTimestamp)
        )
    }

    @Test
    fun testDisambiguate_noRelativeTime_keepsOriginal() {
        val original = "用户喜欢吃清淡的粤菜"
        assertEquals(original, disambiguator.disambiguate(original, baseTimestamp))
    }
}
