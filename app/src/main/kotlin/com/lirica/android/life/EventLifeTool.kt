package com.lirica.android.life

import com.lirica.android.automation.agent.LifeTool
import com.lirica.android.automation.agent.ToolExecutionResult
import com.lirica.android.database.dao.ScheduledAlarmDao
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.network.model.ToolDefinition
import com.lirica.android.network.model.ToolFunction
import com.lirica.android.proactivity.ScheduleWakeManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日程生活工具：`create_event` / `list_events`（评审批次 2 · T2.2 后半）。
 *
 * 存储复用既有 `scheduled_alarms` 表与 [ScheduleWakeManager]：
 * create_event 落库即注册系统闹钟，到点后经 ScheduleWakeReceiver 转成
 * UnspokenThought 的"自定约定"提醒——这正是"她记着你的安排"的完整闭环。
 * **不引入 Room schema 迁移**：现有四列（trigger_at / bot_note / block_delivery /
 * status）足以承载"未来某时刻要做某事"的语义。
 *
 * 与 backend 参考实现的差异（刻意为之）：backend 的 events 是"日期区间事件表"，
 * Android 侧没有对应存储，本工具按"单点提醒"语义落闹钟表；列表查询只看
 * 未来窗口，历史触发的记录不进 list_events（那是日记与记忆的事）。
 *
 * 失败语义与 [WeatherLifeTool] 一致：全部失败路径转成给大模型看的中文说明，
 * 绝不抛异常击穿对话主链路（CancellationException 原样上抛）。
 */
class EventLifeTool(
    private val scheduleWakeManager: ScheduleWakeManager,
    private val scheduledAlarmDao: ScheduledAlarmDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) : LifeTool {

    companion object {
        const val TOOL_CREATE = "create_event"
        const val TOOL_LIST = "list_events"

        /** list_events 单次最多列出的条数：日程是给人看的摘要，不是数据库导出 */
        const val MAX_LIST_COUNT = 20

        /** 默认列出未来几天的安排 */
        const val DEFAULT_LIST_DAYS = 7

        /** 允许的过去时间偏移：容忍模型与端侧时钟的秒级偏差 */
        private const val PAST_TIME_SKEW_MS = 60_000L

        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINESE)

        private const val TIME_FORMAT_HINT =
            "时间格式：YYYY-MM-DDTHH:mm（本地时间）、带时区的 ISO 8601（如 2026-09-23T15:00:00+08:00）、" +
                "或仅日期 YYYY-MM-DD（默认按上午 9 点）。"
    }

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            type = "function",
            function = ToolFunction(
                name = TOOL_CREATE,
                description = "帮用户记一件未来要做的事，到点会主动提醒（写入日程并设置系统闹钟）。" +
                    "用户说『提醒我…』『记得…』『X 月 X 日要…』『别忘…』时调用。" +
                    TIME_FORMAT_HINT,
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "简短的事件名，如「期中考试」「下午三点取快递」。")
                        }
                        putJsonObject("time") {
                            put("type", "string")
                            put("description", "事件触发时间。" + TIME_FORMAT_HINT)
                        }
                        putJsonObject("notes") {
                            put("type", "string")
                            put("description", "补充说明（可选），提醒时一并带出。")
                        }
                        putJsonObject("block_delivery") {
                            put("type", "boolean")
                            put("description", "触发前屏蔽主动性气泡（可选，默认 false）。用于用户明确要求『到点前别找我』的场景。")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("title"))
                        add(JsonPrimitive("time"))
                    }
                }
            )
        ),
        ToolDefinition(
            type = "function",
            function = ToolFunction(
                name = TOOL_LIST,
                description = "列出未来几天已记录的日程安排。用户问『最近有什么安排』『接下来要做什么』时调用。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("days") {
                            put("type", "integer")
                            put("description", "往后看几天（可选，默认 7，最大 30）。")
                        }
                    }
                    putJsonArray("required") {}
                }
            )
        )
    )

    override suspend fun execute(name: String, args: JsonObject, toolCallId: String): ToolExecutionResult? {
        return when (name) {
            TOOL_CREATE -> try {
                createEvent(args, toolCallId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(toolCallId, "记日程的时候出了点问题（${e.javaClass.simpleName}），稍后再试。")
            }

            TOOL_LIST -> try {
                listEvents(args, toolCallId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(toolCallId, "查日程的时候出了点问题（${e.javaClass.simpleName}），稍后再试。")
            }

            // 契约约定：不是我的工具返回 null，由 NativeToolAgent 继续向下派发
            else -> null
        }
    }

    // ── create_event ─────────────────────────────────────────────

    private suspend fun createEvent(args: JsonObject, toolCallId: String): ToolExecutionResult {
        val title = args.string("title")?.trim().orEmpty()
        if (title.isEmpty()) {
            return failure(toolCallId, "要记什么事呀？把事情的名字（title）告诉我就行。")
        }

        val rawTime = args.string("time")?.trim().orEmpty()
        val triggerAtMs = parseTriggerAtMs(rawTime)
        if (rawTime.isEmpty() || triggerAtMs == null) {
            return failure(toolCallId, "这个时间我没看懂。$TIME_FORMAT_HINT")
        }

        val nowMs = clock()
        if (triggerAtMs < nowMs - PAST_TIME_SKEW_MS) {
            return failure(toolCallId, "这个时间已经过去了，要记未来的安排哦。")
        }

        val notes = args.string("notes")?.trim().orEmpty()
        val botNote = if (notes.isEmpty()) title else "$title\n备注：$notes"
        val blockDelivery = args.boolean("block_delivery") ?: false

        val entity = scheduleWakeManager.scheduleWake(
            triggerAtMs = triggerAtMs,
            botNote = botNote,
            blockDelivery = blockDelivery
        )

        return ToolExecutionResult(
            toolCallId = toolCallId,
            isSuccess = true,
            output = "记好了：${formatInstant(entity.triggerAt)} 我会提醒你「${title}」。" +
                if (notes.isEmpty()) "" else "（备注：$notes）"
        )
    }

    // ── list_events ──────────────────────────────────────────────

    private suspend fun listEvents(args: JsonObject, toolCallId: String): ToolExecutionResult {
        val days = (args.int("days") ?: DEFAULT_LIST_DAYS).coerceIn(1, 30)
        val nowMs = clock()
        val upcoming = scheduledAlarmDao.getUpcomingAlarms(
            nowMs = nowMs,
            untilMs = nowMs + days * 86_400_000L,
            limit = MAX_LIST_COUNT
        )

        if (upcoming.isEmpty()) {
            return ToolExecutionResult(
                toolCallId = toolCallId,
                isSuccess = true,
                output = "接下来 $days 天没有安排，日程空空的。"
            )
        }

        val lines = upcoming.joinToString("\n") { entity ->
            // botNote 首行是事件名（备注在第二行起），列表里只展示事件名
            "· ${formatInstant(entity.triggerAt)}「${entity.botNote.lineSequence().firstOrNull().orEmpty()}」"
        }
        return ToolExecutionResult(
            toolCallId = toolCallId,
            isSuccess = true,
            output = "接下来 $days 天的安排：\n$lines"
        )
    }

    // ── 时间解析与格式化（全部"看不懂就返回 null"，绝不抛） ──────

    /**
     * 三级解析：带时区 ISO → 本地时间 ISO → 纯日期（默认上午 9 点）。
     * 纯日期默认 9 点是刻意的：只说"9 月 23 日要交报告"的场景，提醒落在
     * 上午 9 点比落在凌晨 0 点更接近用户的真实意图。
     */
    internal fun parseTriggerAtMs(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(trimmed).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun formatInstant(epochMs: Long): String {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val weekday = zdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINESE)
        return "${DISPLAY_FORMAT.format(zdt)}（$weekday）"
    }

    // ── 参数取值小工具 ───────────────────────────────────────────

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun failure(toolCallId: String, text: String) = ToolExecutionResult(
        toolCallId = toolCallId,
        isSuccess = false,
        output = text
    )
}
