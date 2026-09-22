package com.xiaoxi.android.life

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 异常感知关心检测器的规则单测。
 *
 * 判定基准与 Python 参考实现 `backend/tests/test_care.py` 对齐：
 * 规则语义移植时保持一致，冷却台账的时间存储从字符串改为 epochMillis。
 */
class CareHintDetectorTest {

    companion object {
        private val ZONE = ZoneId.of("Asia/Shanghai")

        private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
            LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant()

        /** 检测时点固定在 2026-09-22 01:30（深夜区间内），保证用例确定性 */
        private val NOW: Instant = at(2026, 9, 22, 1, 30)
    }

    private fun detector(ledger: CareLedger = NoOpCareLedger) =
        CareHintDetector(careLedger = ledger, zone = ZONE)

    // ── RETURNED：久未出现后回来 ───────────────────────────────

    @Test
    fun returned_gapBeyond5Days_producesReturnedHint() {
        // 距 now 5.56 天（5 天 13.5 小时），int() 向下取整为 5 —— 与 Python int(gap_days) 语义一致
        val last = at(2026, 9, 16, 12, 0)
        val hints = detector().detect(NOW, emptyList(), lastUserMessageTime = last)

        val returned = hints.filter { it.type == CareHintType.RETURNED }
        assertEquals(1, returned.size)
        assertTrue(returned[0].text.contains("5 天"))
    }

    @Test
    fun returned_gapWithin5Days_noReturnedHint() {
        val last = at(2026, 9, 20, 12, 0) // 距 now 2.6 天
        val hints = detector().detect(NOW, emptyList(), lastUserMessageTime = last)
        assertTrue(hints.none { it.type == CareHintType.RETURNED })
    }

    @Test
    fun returned_gapExactlyAtThreshold_triggers() {
        // now - 5 天整 → 5.0 天，>= RETURNED_GAP_DAYS 应命中（边界语义与 Python 一致）
        val last = NOW.minus(5, java.time.temporal.ChronoUnit.DAYS)
        val hints = detector().detect(NOW, emptyList(), lastUserMessageTime = last)
        assertTrue(hints.any { it.type == CareHintType.RETURNED })
    }

    // ── 深夜判定与 LATE_NIGHT ─────────────────────────────────

    @Test
    fun lateNight_daytimeRequest_doesNotEmitLateNightOrCombo() {
        val noon = at(2026, 9, 22, 14, 0)
        val exam = CareEvent("exam", "高数期中", java.time.LocalDate.of(2026, 9, 22), java.time.LocalDate.of(2026, 9, 22))
        val hints = detector().detect(noon, emptyList(), listOf(exam))
        // 白天不该因为"有考试"冒出熬夜提醒；也没有久未出现
        assertTrue(hints.isEmpty())
    }

    @Test
    fun lateNight_lateNightBoundaryStartHour_counts() {
        val midnight = at(2026, 9, 22, 0, 0)
        val hints = detector().detect(midnight, emptyList())
        assertTrue(hints.any { it.type == CareHintType.LATE_NIGHT })
    }

    @Test
    fun lateNight_afterEndHour_doesNotCount() {
        val fiveAm = at(2026, 9, 22, 5, 0)
        val hints = detector().detect(fiveAm, emptyList())
        // 5 点起天快亮了，不再视为深夜（区间 [0,5)）
        assertTrue(hints.none { it.type == CareHintType.LATE_NIGHT })
    }

    @Test
    fun lateNight_firstNight_textHasNoNightCount() {
        val hints = detector().detect(NOW, emptyList())
        val late = hints.first { it.type == CareHintType.LATE_NIGHT }
        assertTrue(late.text.contains("现在还没睡"))
        assertFalse(late.text.contains("第"))
    }

    @Test
    fun lateNight_threePreviousNights_reportsFourthNight() {
        // 近 7 天内有 3 个不同的熬夜之夜（按天去重），+当前这一晚 = 第 4 个
        val recent = listOf(
            at(2026, 9, 19, 1, 0),
            at(2026, 9, 20, 2, 0),
            at(2026, 9, 21, 3, 0),
            // 白天消息不该被算进熬夜之夜
            at(2026, 9, 20, 14, 0),
            // 窗口外（now - 7 天之前）不该计入
            at(2026, 9, 15, 1, 0)
        )
        val hints = detector().detect(NOW, recent)
        val late = hints.first { it.type == CareHintType.LATE_NIGHT }
        assertTrue(late.text.contains("第 4 个"))
    }

    @Test
    fun lateNight_multipleMessagesSameNight_countAsOneNight() {
        // 一晚连发多条与发一条是同一个熬夜之夜，按条数会夸大严重度
        val recent = listOf(
            at(2026, 9, 21, 1, 0),
            at(2026, 9, 21, 2, 0),
            at(2026, 9, 21, 3, 0)
        )
        val hints = detector().detect(NOW, recent)
        val late = hints.first { it.type == CareHintType.LATE_NIGHT }
        assertTrue(late.text.contains("第 2 个"))
    }

    @Test
    fun lateNight_futureTimestamps_excludedFromWindow() {
        // 归档时钟与注入时钟不同源时可能出现未来时间，必须排除
        val future = NOW.plus(2, java.time.temporal.ChronoUnit.DAYS)
        val hints = detector().detect(NOW, listOf(future))
        val late = hints.first { it.type == CareHintType.LATE_NIGHT }
        assertFalse(late.text.contains("第"))
    }

    // ── EVENT_COMBO：具体由头吞并泛化的 ────────────────────────

    @Test
    fun combo_examToday_suppressesGenericLateNight() {
        val exam = CareEvent("exam", "高数期中", java.time.LocalDate.of(2026, 9, 22), java.time.LocalDate.of(2026, 9, 22))
        val hints = detector().detect(NOW, emptyList(), listOf(exam))

        assertEquals(listOf(CareHintType.EVENT_COMBO), hints.map { it.type })
        val combo = hints.first()
        assertTrue(combo.text.contains("今天"))
        assertTrue(combo.text.contains("高数期中"))
    }

    @Test
    fun combo_examTomorrow_reportsTomorrow() {
        val exam = CareEvent("exam", "英语四级", java.time.LocalDate.of(2026, 9, 23), java.time.LocalDate.of(2026, 9, 23))
        val hints = detector().detect(NOW, emptyList(), listOf(exam))
        assertTrue(hints.first { it.type == CareHintType.EVENT_COMBO }.text.contains("明天"))
    }

    @Test
    fun combo_examSpanningTodayAndTomorrow_reportsToday() {
        // start <= tomorrow && end >= today 即命中；start <= today 时用"今天"
        val exam = CareEvent("exam", "结课考", java.time.LocalDate.of(2026, 9, 21), java.time.LocalDate.of(2026, 9, 23))
        val hints = detector().detect(NOW, emptyList(), listOf(exam))
        assertTrue(hints.first { it.type == CareHintType.EVENT_COMBO }.text.contains("今天"))
    }

    @Test
    fun combo_nonExamEventType_ignored() {
        val interview = CareEvent("interview", "面试", java.time.LocalDate.of(2026, 9, 22), java.time.LocalDate.of(2026, 9, 22))
        val hints = detector().detect(NOW, emptyList(), listOf(interview))
        // 参考实现注记：interview 不在事件类型白名单，第一期 combo 只认 exam
        assertTrue(hints.none { it.type == CareHintType.EVENT_COMBO })
        assertTrue(hints.any { it.type == CareHintType.LATE_NIGHT })
    }

    @Test
    fun combo_examNextWeek_notOverlapping() {
        val exam = CareEvent("exam", "下周的考试", java.time.LocalDate.of(2026, 9, 29), java.time.LocalDate.of(2026, 9, 29))
        val hints = detector().detect(NOW, emptyList(), listOf(exam))
        assertTrue(hints.none { it.type == CareHintType.EVENT_COMBO })
    }

    // ── 冷却台账 ─────────────────────────────────────────────

    @Test
    fun cooldown_lateNightWithin72h_filtered() {
        val hints = listOf(CareHint(CareHintType.LATE_NIGHT, "现在还没睡。"))
        val ledger = mapOf(CareHintType.LATE_NIGHT to NOW.minus(71, java.time.temporal.ChronoUnit.HOURS))
        assertTrue(detector().filterCooled(hints, ledger, NOW).isEmpty())
    }

    @Test
    fun cooldown_lateNightBeyond72h_passes() {
        val hints = listOf(CareHint(CareHintType.LATE_NIGHT, "现在还没睡。"))
        val ledger = mapOf(CareHintType.LATE_NIGHT to NOW.minus(73, java.time.temporal.ChronoUnit.HOURS))
        assertEquals(1, detector().filterCooled(hints, ledger, NOW).size)
    }

    @Test
    fun cooldown_eventComboIndependentOfLateNight() {
        val hints = listOf(
            CareHint(CareHintType.LATE_NIGHT, "现在还没睡。"),
            CareHint(CareHintType.EVENT_COMBO, "今天有考试，还没睡")
        )
        // LATE_NIGHT 在冷却期内，EVENT_COMBO 没有 → 只放行后者
        val ledger = mapOf(CareHintType.LATE_NIGHT to NOW.minus(1, java.time.temporal.ChronoUnit.HOURS))
        val passed = detector().filterCooled(hints, ledger, NOW)
        assertEquals(listOf(CareHintType.EVENT_COMBO), passed.map { it.type })
    }

    @Test
    fun cooldown_returnedNeverFiltered() {
        val hints = listOf(CareHint(CareHintType.RETURNED, "回来了"))
        val ledger = mapOf(CareHintType.RETURNED to NOW.minus(1, java.time.temporal.ChronoUnit.HOURS))
        // RETURNED 冷却为 null：间隔条件天然单次触发，再叠冷却反而会漏掉回归时刻
        assertEquals(1, detector().filterCooled(hints, ledger, NOW).size)
    }

    @Test
    fun cooldown_ledgerAheadOfNow_failsOpen() {
        // 台账时间戳异常（晚于 now，时钟回拨等）→ 放行，宁可多关心
        val hints = listOf(CareHint(CareHintType.LATE_NIGHT, "现在还没睡。"))
        val ledger = mapOf(CareHintType.LATE_NIGHT to NOW.plus(1, java.time.temporal.ChronoUnit.HOURS))
        assertEquals(1, detector().filterCooled(hints, ledger, NOW).size)
    }

    // ── 一行接入（collectHintTexts）：检测 + 冷却 + 注入即记账 ──

    private class MemoryCareLedger : CareLedger {
        val store = mutableMapOf<CareHintType, Instant>()
        override fun load(): Map<CareHintType, Instant> = store.toMap()
        override fun record(type: CareHintType, at: Instant) {
            store[type] = at
        }
    }

    @Test
    fun collect_recordsOnInject_andFiltersSecondTimeWithinCooldown() = runTest {
        val ledger = MemoryCareLedger()
        val d = detector(ledger)

        val first = d.collectHintTexts(NOW, emptyList())
        assertTrue(first.any { it.contains("现在还没睡") })
        assertTrue(ledger.store.containsKey(CareHintType.LATE_NIGHT))

        // 3 小时后再来一次：仍在 72h 冷却期内 → 不再注入
        val threeHoursLater = NOW.plus(3, java.time.temporal.ChronoUnit.HOURS)
        val second = d.collectHintTexts(threeHoursLater, emptyList())
        assertTrue(second.isEmpty())
    }

    @Test
    fun collect_returnsEmptyWhenNoSignal() = runTest {
        // 白天 + 无久未出现 + 无考试 → 无提示
        val noon = at(2026, 9, 22, 14, 0)
        assertTrue(detector().collectHintTexts(noon, emptyList()).isEmpty())
    }
}
