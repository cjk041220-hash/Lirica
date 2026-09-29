package com.lirica.android.life

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 关心提示类型。
 *
 * [cooldownHours] = null 表示**永不冷却**（见 RETURNED 的说明）。
 */
enum class CareHintType(val cooldownHours: Long?) {
    /** 深夜还醒着（0:00–4:59）。同一由头 72 小时内不再提 */
    LATE_NIGHT(72),

    /** 深夜 + 次日/当日有考试类日程。比 LATE_NIGHT 更具体，命中时吞并后者。同一由头 24 小时内不再提 */
    EVENT_COMBO(24),

    /**
     * 久未出现后回来（间隔 ≥ 5 天）。
     * 冷却为 null：这个条件天然只会在"刚回来"那一刻命中一次，
     * 再叠一层时间冷却反而会漏掉真正的回归时刻。
     */
    RETURNED(null)
}

/** 一条关心提示。`text` 是给大模型看的**情境说明**，不是要它照读的台词。 */
data class CareHint(val type: CareHintType, val text: String)

/**
 * 可用于 care 检测的日程条目（只取检测必需的四个字段）。
 *
 * 不直接依赖数据库实体：这样检测器是纯函数，可脱离 Room 单测，
 * 也让未来把日程换存储时不必改检测逻辑。
 */
data class CareEvent(
    val eventType: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate
)

/**
 * 异常感知关心检测器 (Care Hint Detector)。
 *
 * 移植自 Python 参考实现 `backend/agent/care.py`。保留它的核心设计纪律：
 *
 * **"别每次都说"必须由确定性状态（冷却台账）保证，不能押在模型自觉上。**
 * 项目已有前科：靠提示词嘱咐模型"不要重复"在实盘上不可靠。所以本模块
 * 只负责"要不要给提示"，说不说、怎么说完全交给人格层自由发挥。
 *
 * 三条规则（全部只在用户主动来聊天时才有观测机会）：
 * 1. RETURNED   —— 距上一条用户消息 ≥ 5 天
 * 2. EVENT_COMBO —— 深夜（0:00–4:59）且今天/明天有考试类日程
 * 3. LATE_NIGHT —— 深夜还醒着（近 7 天熬夜天数一并报出，让模型知道该重还是该轻）
 *
 * 提示文本刻意**不带称呼**：称呼由人格层在回应里自然带出。若在这里写死
 * 一个名字，角色卡换了人设就会当场穿帮。
 */
class CareHintDetector(
    private val careLedger: CareLedger = NoOpCareLedger,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val examEventTypes: Set<String> = setOf("exam")
) {

    companion object {
        /** 消失多少天算"回来" */
        const val RETURNED_GAP_DAYS = 5

        /** 熬夜统计窗口 */
        const val NIGHT_COUNT_WINDOW_DAYS = 7

        /** 深夜区间：起始小时（含） */
        const val LATE_NIGHT_START_HOUR = 0

        /** 深夜区间：结束小时（不含）。5 点起天快亮了，再劝睡反而别扭 */
        const val LATE_NIGHT_END_HOUR = 5
    }

    /**
     * 一行接入：检测 → 冷却过滤 → 注入即记账 → 返回提示文本。
     *
     * 记账时机是**注入时**而非"模型真的说了"时——按 Python 参考实现的原意，
     * 提示已经进了上下文，再记一次账属于过度设计。
     * 任一环节失败都返回空列表：宁可漏一次关心，也不能让对话主链路挂掉。
     */
    fun collectHintTexts(
        now: Instant,
        recentUserMessageTimes: List<Instant>,
        activeEvents: List<CareEvent> = emptyList(),
        lastUserMessageTime: Instant? = null
    ): List<String> {
        val hints = runCatching {
            detect(now, recentUserMessageTimes, activeEvents, lastUserMessageTime)
        }.getOrElse { return emptyList() }
        if (hints.isEmpty()) return emptyList()

        val ledger = runCatching { careLedger.load() }.getOrDefault(emptyMap())
        val passed = filterCooled(hints, ledger, now)
        passed.forEach { hint ->
            if (hint.type.cooldownHours != null) {
                runCatching { careLedger.record(hint.type, now) }
            }
        }
        return passed.map { it.text }
    }

    /**
     * 纯检测（不含冷却）。单独暴露是为了能对三条规则写精确的单测。
     */
    fun detect(
        now: Instant,
        recentUserMessageTimes: List<Instant>,
        activeEvents: List<CareEvent> = emptyList(),
        lastUserMessageTime: Instant? = null
    ): List<CareHint> {
        val hints = mutableListOf<CareHint>()
        val nowZdt = now.atZone(zone)

        // ── RETURNED：距上一条用户消息 ≥ N 天 ────────────────────
        val last = lastUserMessageTime
        if (last != null && !last.isAfter(now)) {
            val gapDays = ChronoUnit.SECONDS.between(last, now) / 86_400.0
            if (gapDays >= RETURNED_GAP_DAYS) {
                hints.add(
                    CareHint(
                        CareHintType.RETURNED,
                        "距上一条消息已经 ${gapDays.toInt()} 天了，这次是回来了"
                    )
                )
            }
        }

        // 以下两条只在深夜成立——白天不该因为"有考试"就冒出一句熬夜提醒
        if (!isLateNight(nowZdt.hour)) return hints

        // ── EVENT_COMBO：今天/明天有考试类日程 ───────────────────
        val today = nowZdt.toLocalDate()
        val tomorrow = today.plusDays(1)
        val comboEvent = activeEvents.firstOrNull { event ->
            event.eventType in examEventTypes && !event.startDate.isAfter(tomorrow) && !event.endDate.isBefore(today)
        }

        if (comboEvent != null) {
            // 吞并规则：更具体的由头吞掉泛化的，避免一句话里塞两个唠叨点
            val dayWord = if (!comboEvent.startDate.isAfter(today)) "今天" else "明天"
            hints.add(
                CareHint(
                    CareHintType.EVENT_COMBO,
                    // ${} 必须写全：Kotlin 标识符可含 CJK，$dayWord有 会被解析成变量「dayWord有」
                    "${dayWord}有「${comboEvent.title}」，还没睡"
                )
            )
        } else {
            val nights = lateNightDays(recentUserMessageTimes, now) + 1 // +1 = 当前这一晚
            val text = buildString {
                append("现在还没睡。")
                if (nights >= 2) {
                    append("近 $NIGHT_COUNT_WINDOW_DAYS 天里这是第 $nights 个熬夜的夜晚。")
                }
            }
            hints.add(CareHint(CareHintType.LATE_NIGHT, text))
        }

        return hints
    }

    /**
     * 冷却过滤：同类提示在冷却期内的滤掉，各类型独立，RETURNED 永不滤。
     *
     * 失败语义（放行）：台账时间戳异常的条目直接放行——宁可多关心一次，
     * 也不因为一条脏数据把感知永久掐掉。
     */
    fun filterCooled(
        hints: List<CareHint>,
        ledger: Map<CareHintType, Instant>,
        now: Instant
    ): List<CareHint> {
        return hints.filter { hint ->
            val cooldownHours = hint.type.cooldownHours ?: return@filter true
            val lastAt = ledger[hint.type] ?: return@filter true
            // 台账时间戳晚于 now（时钟回拨 / 归档时钟与注入时钟不同源）→ 视为异常条目放行。
            // 若沿用 elapsed<0 的数值比较，会静默把提示滤掉直到 now 追上来，
            // 与本模块"宁可多关心，不因存储抖动丢感知"的失败语义相悖。
            if (lastAt.isAfter(now)) return@filter true
            val elapsedSeconds = ChronoUnit.SECONDS.between(lastAt, now)
            elapsedSeconds >= cooldownHours * 3600
        }
    }

    /** 深夜判定：落在 [LATE_NIGHT_START_HOUR, LATE_NIGHT_END_HOUR) 内 */
    fun isLateNight(hourOfDay: Int): Boolean =
        hourOfDay >= LATE_NIGHT_START_HOUR && hourOfDay < LATE_NIGHT_END_HOUR

    /**
     * 近 7 天内"有深夜消息"的天数（按天去重，不按条数）。
     *
     * 去重是刻意的：一晚连发 20 条和发 1 条是同一个熬夜之夜，
     * 按条数会把语气推得远比事实严重。
     * 同时排除晚于 now 的时间戳——归档时钟与注入时钟不同源时可能出现未来时间。
     */
    private fun lateNightDays(recentUserMessageTimes: List<Instant>, now: Instant): Int {
        val cutoff = now.minus(NIGHT_COUNT_WINDOW_DAYS.toLong(), ChronoUnit.DAYS)
        return recentUserMessageTimes
            .filter { !it.isBefore(cutoff) && !it.isAfter(now) }
            .filter { isLateNight(it.atZone(zone).hour) }
            .map { it.atZone(zone).toLocalDate() }
            .toSet()
            .size
    }
}
