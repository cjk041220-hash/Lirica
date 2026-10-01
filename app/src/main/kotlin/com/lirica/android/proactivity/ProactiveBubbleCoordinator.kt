package com.lirica.android.proactivity

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 主动表达级别映射
 * 严格封禁任何全屏阻塞式弹窗 (L4 严厉禁止)
 */
enum class ProactivityLevel {
    /** L0: 静默更新，仅更新内部动力学与感知状态，无任何显式呈现 */
    L0_SILENT,
    /** L1: 伴生体微表情/呼吸光效微调，表达内心活动，无气泡打扰 */
    L1_MICRO_EXPRESSION,
    /** L2: 伴生体头顶非阻塞破冰小气泡 (IcebreakerBubble)，可一键对话或划掉 */
    L2_ICEBREAKER_BUBBLE,
    /** L3: 轻提示通知 (仅在极高紧急度通知且屏幕关闭等特殊边界下使用) */
    L3_LIGHT_NOTIFICATION
}

/**
 * 用户对主动破冰气泡的反馈类型
 */
enum class UserFeedback {
    /** 用户点击气泡接茬，进入对话面板 (正面反馈) */
    CLICKED,
    /** 用户主动点击关闭或划掉气泡 (强负面反馈) */
    DISMISSED,
    /** 气泡超时自然消失，用户未做交互 (弱负面反馈) */
    IGNORED
}

/**
 * 主动协调中枢裁决结果
 */
data class ProactiveCoordinationResult(
    val level: ProactivityLevel,
    val decision: DecisionResult,
    val thought: UnspokenThoughtEntity?,
    val reason: String,
    val bubblesShownToday: Int
)

/**
 * 悬浮窗主动破冰气泡与防打扰退避总线协调器
 */
interface ProactiveBubbleCoordinator {
    suspend fun coordinate(snapshot: PerceptionSnapshot, nowMs: Long = System.currentTimeMillis()): ProactiveCoordinationResult

    /**
     * 首帧强制裁决：悬浮窗冷启动等无感知快照场景的兜底入口。
     * 不推进感知动机计算与沉默裁决（避免污染 prevSnapshot），仅做
     * 跨天配额重置 + 每日配额守门（高优先级履约念头豁免）并计入配额，
     * 保证"谁可以展示气泡"的裁决权不被 UI 层旁路（评审中等#4）。
     */
    suspend fun coordinateFirstFrame(nowMs: Long = System.currentTimeMillis()): ProactiveCoordinationResult

    suspend fun onUserFeedback(feedback: UserFeedback, thoughtId: String?, nowMs: Long = System.currentTimeMillis())

    /**
     * L3 通知**投递成功后**的核销回调：把该念头标记为已表达，避免下一轮裁决重复送达。
     *
     * 只在投递真正成功时由协调器调用——投递失败（无权限/冷却中）时念头必须留在池子里，
     * 否则"想说但没送到"的话会被静默吃掉，这正是本轮修复要消灭的断链。
     */
    suspend fun onThoughtDelivered(thoughtId: String, nowMs: Long) {}

    /**
     * 用户这轮消息提到了某个未决事项时，把它核销掉（闭环）。
     * 这是"完事就不再唠叨"的入口；没有开环管理器的实现可安全空转。
     */
    suspend fun resolveOpenLoopsMentionedIn(userText: String) {}

    fun getBackoffPenalty(): Double
    fun getBubblesShownToday(): Int

    companion object {
        /** 每日主动破冰气泡配额硬上限 (2次/天) */
        const val DAILY_BUBBLE_QUOTA = 2

        /**
         * 每日 L3 通知配额硬上限 (3次/天)。
         * 比气泡宽松：通知不遮挡内容、可延后阅读；但也必须有硬上限，
         * 否则"高优先事项"会退化成推送轰炸。
         */
        const val DAILY_L3_NOTIFICATION_QUOTA = 3

        /** L3 候选的紧急度下限：低于此值走静默/微表情，绝不用通知吵人 */
        const val L3_URGENCY_THRESHOLD = 4.5f

        /**
         * 念头正文里的"标题｜正文"分隔符。
         * 用于把承载语气的短标题与用户原话分开：气泡直接展示整条（自然可读），
         * 通知则拆成 title/message 两行。不引入新表、不改历史数据。
         */
        const val TITLE_SEPARATOR = "｜"

        /** 无专属标题时的兜底通知标题 */
        const val FALLBACK_NOTIFICATION_TITLE = "想跟你说件事"

        /** L3 投递失败时的统一原因文案（真实拦截点由日志与调用方 reason 承载） */
        const val L3_BLOCKED_REASON = "L3 通知未送达（权限/冷却/系统拒绝），念头保留在池中待下次机会"
    }
}

class ProactiveBubbleCoordinatorImpl(
    private val thoughtManager: UnspokenThoughtManager,
    private val decisionEngine: SilenceDecisionEngine,
    private val emotionEngine: EmotionEngine,
    /**
     * 2026-09-27 审计 P2 修复：配额/退避/冷却台账持久化（可选，测试缺省 null 走纯内存）。
     * 生产装配由 LiricaApplication 注入 SharedPreferences——杀进程重启后每日配额
     * 与退避不再清零，堵住"重启规避退避"的漏洞。
     */
    private val statePrefs: android.content.SharedPreferences? = null,
    val openLoopManager: OpenLoopLifecycleManager? = null,
    val notificationDispatcher: ProactiveNotificationDispatcher? = null,
    private val contextProvider: (() -> android.content.Context)? = null,
    /**
     * 时钟单一来源。
     *
     * 协调器内部所有"现在几点"的判断都必须走这里：既有的 [coordinate] 允许调用方传入 nowMs，
     * 如果内部某处又偷偷用 System.currentTimeMillis()，就会出现"调用方给逻辑时间、内部拿真实时间"
     * 的双时钟错配——过期判定、L3 冷却在真机长跑与自动化测试里会得出相反结论。
     */
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ProactiveBubbleCoordinator {

    // 并发互斥：overlay 的 processPerception / refreshIcebreaker / onUserFeedback
    // 各自起协程并发进入，裸 var 台账存在竞态；所有状态变更收口到同一把锁
    private val stateMutex = kotlinx.coroutines.sync.Mutex()

    private var bubblesShownToday = 0
    private var lastBubbleDay: String = ""
    private var lastInterruptionTime = 0L
    private var backoffPenalty = 0.0
    private var prevSnapshot: PerceptionSnapshot? = null

    /** 当日 L3 通知递送计数与归属日（与气泡配额同样持久化，防杀进程重启绕过上限） */
    private var l3DeliveredToday = 0
    private var lastL3Day: String = ""

    init {
        // 从持久化台账恢复（跨天重置由 coordinate/coordinateFirstFrame 的日期检查接管）
        statePrefs?.let { prefs ->
            bubblesShownToday = prefs.getInt(KEY_BUBBLES_TODAY, 0)
            lastBubbleDay = prefs.getString(KEY_LAST_DAY, "") ?: ""
            backoffPenalty = Double.fromBits(prefs.getLong(KEY_BACKOFF_BITS, 0.0.toRawBits()))
            lastInterruptionTime = prefs.getLong(KEY_LAST_INTERRUPTION, 0L)
            l3DeliveredToday = prefs.getInt(KEY_L3_TODAY, 0)
            lastL3Day = prefs.getString(KEY_LAST_L3_DAY, "") ?: ""
        }
    }

    private fun persistState(today: String) {
        statePrefs?.edit()?.apply {
            putInt(KEY_BUBBLES_TODAY, bubblesShownToday)
            putString(KEY_LAST_DAY, today)
            putLong(KEY_BACKOFF_BITS, backoffPenalty.toRawBits())
            putLong(KEY_LAST_INTERRUPTION, lastInterruptionTime)
            putInt(KEY_L3_TODAY, l3DeliveredToday)
            putString(KEY_LAST_L3_DAY, lastL3Day)
            apply()
        }
    }

    override fun getBackoffPenalty(): Double = backoffPenalty
    override fun getBubblesShownToday(): Int = bubblesShownToday

    /**
     * L3 候选判定：这条念头够不够格动用系统通知。
     *
     * 边界（宁可漏送，绝不越界）：
     * - 隐私熔断中 → 不送（物理红线，通知正文可能带出敏感语境）；
     * - 通话中 → 不送（对方听得见提示音）；
     * - 屏幕已解锁 → 不送（该走 L2 气泡；通知是"人没在看屏幕"时的补充通道）；
     * - 紧急度低于 [ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD] → 不送。
     */
    private suspend fun buildL3Candidate(
        snapshot: PerceptionSnapshot,
        nowMs: Long
    ): UnspokenThoughtEntity? {
        if (snapshot.isPrivacyFusing) return null
        if (snapshot.hardwareEnvironment.isPhoneInCall) return null
        if (snapshot.screenState.state == ScreenState.UNLOCKED) return null

        val top = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull() ?: return null
        if (top.urgency < ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD) return null
        if (top.expiryTime != null && top.expiryTime < nowMs) return null
        return top
    }

    /**
     * 首帧（无感知快照）下的 L3 判定。
     *
     * 与 [buildL3Candidate] 的区别只有一个：没有快照可查，因此改用"屏幕是否处于可交互态"
     * 这个弱信号兜底——拿不到屏幕状态时按"不可交互"处理（宁可发通知，也别把气泡塞给锁屏）。
     */
    private suspend fun tryFirstFrameNotification(
        thought: UnspokenThoughtEntity,
        today: String,
        nowMs: Long
    ): Boolean {
        if (thought.urgency < ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD) return false
        if (thought.expiryTime != null && thought.expiryTime < nowMs) return false

        val context = contextProvider?.invoke() ?: return false
        val interactive = runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive ?: false
        }.getOrDefault(false)
        if (interactive) return false

        return dispatchL3Notification(thought, today, nowMs)
    }

    /**
     * 高优先履约念头的判定（在"沉默裁决之前"使用）。
     *
     * 与 [ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD] 同源，但走队列首位的实际条目，
     * 而不依赖 [decision] 的动机数值——动机是"她想不想说"，履约是"她答应过要说"，
     * 两件事不能互相否决。
     */
    private suspend fun isPriorityWakeThoughtCompat(
        decision: DecisionResult,
        snapshot: PerceptionSnapshot
    ): Boolean {
        // 隐私熔断与通话保持物理红线：这两个场景下连通知都不发，更不发气泡
        if (snapshot.isPrivacyFusing) return false
        if (snapshot.hardwareEnvironment.isPhoneInCall) return false
        if (snapshot.screenState.state != ScreenState.UNLOCKED) return false
        // 仅对"裁决本来会沉默/降级"的情形生效：已经要打断的走原路径（保留原有理由文案与配额语义）
        if (decision.action == ProactiveAction.PROACTIVE_INTERRUPT) return false

        val top = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull() ?: return false
        if (top.expiryTime != null && top.expiryTime < timeProvider()) return false
        return top.id.startsWith("thought_wake_") || top.urgency >= ProactiveBubbleCoordinator.L3_URGENCY_THRESHOLD
    }

    /**
     * 真正把念头投递到系统通知栏。
     *
     * @return true 已成功投递（念头已核销）；false 未送达（念头保留在池中）
     */
    private suspend fun dispatchL3Notification(
        thought: UnspokenThoughtEntity,
        today: String,
        nowMs: Long
    ): Boolean {
        val dispatcher = notificationDispatcher ?: return false
        val context = contextProvider?.invoke() ?: return false

        if (l3DeliveredToday >= ProactiveBubbleCoordinator.DAILY_L3_NOTIFICATION_QUOTA) {
            LiricaLog.d(
                TAG,
                "当日 L3 通知配额已用尽 (@l3DeliveredToday/@DAILY_L3_NOTIFICATION_QUOTA)，保留念头待明日"
            )
            return false
        }

        val raw = thought.thought
        val separatorIndex = raw.indexOf(ProactiveBubbleCoordinator.TITLE_SEPARATOR)
        val title = when {
            thought.contentTitle.isNotBlank() -> thought.contentTitle
            separatorIndex > 0 -> raw.substring(0, separatorIndex)
            else -> ProactiveBubbleCoordinator.FALLBACK_NOTIFICATION_TITLE
        }
        val message = if (separatorIndex > 0) {
            raw.substring(separatorIndex + ProactiveBubbleCoordinator.TITLE_SEPARATOR.length)
        } else {
            raw
        }

        val delivered = dispatcher.dispatchNotification(context, title, message)
        if (!delivered) {
            LiricaLog.w(TAG, "L3 通知未送达，念头保留待下次机会: @thought.id")
            return false
        }

        if (today != lastL3Day) {
            lastL3Day = today
            l3DeliveredToday = 0
        }
        l3DeliveredToday++
        // 与气泡同源的"已表达"记账：推进不应期、压缩动机，避免紧接着又来一轮
        thoughtManager.markExpressed(nowMs)
        onThoughtDelivered(thought.id, nowMs)
        persistState(today)
        LiricaLog.i(TAG, "L3 通知已送达并核销念头: @thought.id")
        return true
    }

    /**
     * L3 投递成功后的核销：把念头标记为已消费，杜绝下一轮裁决重复送达同一句话。
     * 若将来需要"用户真的读了才算送达"的更强语义，在这里替换为通知回执驱动即可。
     */
    override suspend fun onThoughtDelivered(thoughtId: String, nowMs: Long) {
        thoughtManager.consumeThought(thoughtId, nowMs)
    }

    /**
     * 用户这轮消息提到了某个未决事项 → 立即核销相关开环（并撤回对应念头）。
     * 走同一把状态锁，避免与裁决流程并发改念头池。
     */
    override suspend fun resolveOpenLoopsMentionedIn(userText: String) {
        stateMutex.withLock {
            openLoopManager?.resolveMentionedLoops(userText)
        }
    }

    override suspend fun coordinate(
        snapshot: PerceptionSnapshot,
        nowMs: Long
    ): ProactiveCoordinationResult = stateMutex.withLock {
        coordinateLocked(snapshot, nowMs)
    }

    private suspend fun coordinateLocked(
        snapshot: PerceptionSnapshot,
        nowMs: Long
    ): ProactiveCoordinationResult {
        // 1. 日期跨天重置配额
        val currentDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(nowMs))
        if (currentDay != lastBubbleDay) {
            lastBubbleDay = currentDay
            bubblesShownToday = 0
            // 跨天退避惩罚衰减一半
            backoffPenalty = max(0.0, backoffPenalty * 0.5)
            persistState(currentDay)
        }

        // 1.5 扫描并推进已到期的开环未决事项进入念头池 (M4.2 联动)
        openLoopManager?.checkAndPromoteDueLoops(nowMs)

        // 2. 动机动力学计算与念头更新 (融合即时感知动力学与待决念头池最高紧急度)
        val perceptionMotivation = thoughtManager.processPerception(snapshot, emotionEngine.currentVector, nowMs)
        val topPendingThought = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull()
        val thoughtUrgency = topPendingThought?.urgency?.toDouble() ?: 0.0
        val rawMotivation = max(perceptionMotivation, thoughtUrgency)

        // 3. 用户退避惩罚抵扣
        val effectiveInnerMotivation = max(0.0, rawMotivation - backoffPenalty)

        // 4. 沉默裁决器双门控判定
        val decision = decisionEngine.evaluate(
            curr = snapshot,
            innerMotivation = effectiveInnerMotivation,
            lastInterruptionMs = lastInterruptionTime,
            nowMs = nowMs,
            prev = prevSnapshot
        )
        prevSnapshot = snapshot

        // 4.5 L3 前置判定（本轮修复的核心接线）：
        // 原实现里"息屏 → INNER_THOUGHT_ONLY"是硬门，导致 L3_LIGHT_NOTIFICATION 永远不可达，
        // 高优先事项在用户不看屏幕时无任何送达通道。这里对**高优先待办**开一条受控旁路：
        // 只放行"屏幕未解锁 + 紧急度高 + 非隐私熔断 + 非通话"的念头，投递成功后立即核销。
        // 顺序必须在 when 之前：息屏时裁决结果为 INNER_THOUGHT_ONLY，事后再判就永远轮不到 L3。
        val l3Candidate = buildL3Candidate(snapshot, nowMs)
        if (l3Candidate != null) {
            val delivered = dispatchL3Notification(l3Candidate, currentDay, nowMs)
            if (delivered) {
                return ProactiveCoordinationResult(
                    level = ProactivityLevel.L3_LIGHT_NOTIFICATION,
                    decision = decision,
                    thought = l3Candidate,
                    reason = "屏幕未解锁且存在高优先待办，已通过 L3 系统通知送达并核销",
                    bubblesShownToday = bubblesShownToday
                )
            }
            // 未送达（无权限/冷却中/配额用尽）：念头保留，继续走常规分级映射
        }

        // 4.6 高优先履约念头：绕过动机门控直接送达（本轮修复的第二个断链）
        //
        // 起因：自定闹钟约定（schedule_wake）生成 urgency=4.8 的念头，但它的送达要过
        // 「内隐动机 >= 3.6」这道门——而动机来自闲时动力学，在用户专注/刚被打扰/退避惩罚生效时
        // 完全可能低于阈值，于是**用户亲口定下的约定被静默吞掉**。
        // 政策：用户显式约定 > 系统自律。屏幕可交互时直接给气泡，不走代价模型（否则息屏/通话
        // 等物理边界仍会把它变成通知或继续静默，物理红线优先）。
        run {
            val dbgTop = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull()
            LiricaLog.i(
                "ProactiveCoordinator",
                "DBG decision=@decision.action top=@{dbgTop?.id}/@{dbgTop?.urgency}/exp=@{dbgTop?.expiryTime} now=@{timeProvider()} screen=@{snapshot.screenState.state}"
            )
        }
        if (isPriorityWakeThoughtCompat(decision, snapshot)) {
            val priorityThought = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull()
            if (priorityThought != null) {
                lastInterruptionTime = nowMs
                persistState(currentDay)
                return ProactiveCoordinationResult(
                    level = ProactivityLevel.L2_ICEBREAKER_BUBBLE,
                    decision = decision,
                    thought = priorityThought,
                    reason = "高优先履约念头（用户显式约定/紧急度 >= @L3_URGENCY_THRESHOLD）：绕过动机门控直接送达，不计入每日闲聊配额",
                    bubblesShownToday = bubblesShownToday
                )
            }
        }

        // 5. 分级映射与每日配额守门
        return when (decision.action) {
            ProactiveAction.SILENT -> {
                ProactiveCoordinationResult(
                    level = ProactivityLevel.L0_SILENT,
                    decision = decision,
                    thought = null,
                    reason = decision.reason,
                    bubblesShownToday = bubblesShownToday
                )
            }
            ProactiveAction.INNER_THOUGHT_ONLY -> {
                // 微表情微调光晕刺激
                emotionEngine.applyStimulus(EmotionVector(valence = 0.05f, arousal = 0.05f))
                ProactiveCoordinationResult(
                    level = ProactivityLevel.L1_MICRO_EXPRESSION,
                    decision = decision,
                    thought = null,
                    reason = decision.reason,
                    bubblesShownToday = bubblesShownToday
                )
            }
            ProactiveAction.PROACTIVE_INTERRUPT -> {
                val topThoughts = thoughtManager.getTopPendingThoughts(limit = 1)
                val topThought = topThoughts.firstOrNull()
                val isPriorityWakeThought = topThought != null && (
                    topThought.id.startsWith("thought_wake_") || topThought.urgency >= 4.5f
                )

                // 每日配额硬约束守门 (高优先级自定约定闹钟念头跳过闲聊配额限制)
                if (bubblesShownToday >= ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA && !isPriorityWakeThought) {
                    ProactiveCoordinationResult(
                        level = ProactivityLevel.L1_MICRO_EXPRESSION,
                        decision = decision,
                        thought = null,
                        reason = "每日主动破冰配额已用尽 (${bubblesShownToday}/${ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA})，降级为伴生微表情",
                        bubblesShownToday = bubblesShownToday
                    )
                } else {
                    if (topThought != null) {
                        if (!isPriorityWakeThought) {
                            bubblesShownToday++
                        }
                        lastInterruptionTime = nowMs
                        persistState(currentDay)
                        ProactiveCoordinationResult(
                            level = ProactivityLevel.L2_ICEBREAKER_BUBBLE,
                            decision = decision,
                            thought = topThought,
                            reason = if (isPriorityWakeThought) "自定闹钟约定到期唤醒，优先展示履约气泡" else "触发主动破冰条件，展示头顶内心独白气泡",
                            bubblesShownToday = bubblesShownToday
                        )
                    } else {
                        ProactiveCoordinationResult(
                            level = ProactivityLevel.L1_MICRO_EXPRESSION,
                            decision = decision,
                            thought = null,
                            reason = "虽达成打断阈值但念头池无待表达念头，降级为微表情",
                            bubblesShownToday = bubblesShownToday
                        )
                    }
                }
            }
        }
    }

    override suspend fun coordinateFirstFrame(nowMs: Long): ProactiveCoordinationResult = stateMutex.withLock {
        // 跨天配额重置与退避衰减，语义与 coordinate 保持一致
        val currentDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(nowMs))
        if (currentDay != lastBubbleDay) {
            lastBubbleDay = currentDay
            bubblesShownToday = 0
            backoffPenalty = max(0.0, backoffPenalty * 0.5)
            persistState(currentDay)
        }

        // 首帧场景无感知快照，无法推进动机计算与沉默裁决；仅守每日配额
        // （高优先级履约念头豁免，语义与 coordinate 的 PROACTIVE_INTERRUPT 分支对齐）
        //
        // 但"没有快照"不等于"能弹气泡"：悬浮窗冷启动时屏幕经常还锁着（息屏后 START_STICKY
        // 拉起、通知点击冷启等），此时展示气泡等于没人看得见。所以这里同样先尝试 L3 通知，
        // 送不到再落回气泡路径。开环到期事项的扫描也放在这里——原实现只在 coordinate 里扫，
        // 导致"用户不再正眼看屏幕"的场景永远不推进未决事项。
        runCatching { openLoopManager?.checkAndPromoteDueLoops(nowMs) }

        val topThought = thoughtManager.getTopPendingThoughts(limit = 1).firstOrNull()
        val isPriorityWakeThought = topThought != null && (
            topThought.id.startsWith("thought_wake_") || topThought.urgency >= 4.5f
        )
        val quotaAllowed = bubblesShownToday < ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA

        // 首帧同样先试 L3；失败（锁屏/无权限/冷却）再走气泡
        val firstFrameL3 = topThought?.let { tryFirstFrameNotification(it, currentDay, nowMs) } ?: false
        if (firstFrameL3) {
            return ProactiveCoordinationResult(
                level = ProactivityLevel.L3_LIGHT_NOTIFICATION,
                decision = DecisionResult(
                    action = ProactiveAction.PROACTIVE_INTERRUPT,
                    innerMotivation = 0.0,
                    interruptCost = 0.0,
                    effectiveMotivation = 0.0,
                    breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
                    reason = "首帧无快照且屏幕非交互态，经 L3 通知送达"
                ),
                thought = topThought,
                reason = "首帧 L3 通知送达并核销",
                bubblesShownToday = bubblesShownToday
            )
        }

        if (topThought != null && (quotaAllowed || isPriorityWakeThought)) {
            if (!isPriorityWakeThought) {
                bubblesShownToday++
            }
            lastInterruptionTime = nowMs
            persistState(currentDay)
            ProactiveCoordinationResult(
                level = ProactivityLevel.L2_ICEBREAKER_BUBBLE,
                decision = DecisionResult(
                    action = ProactiveAction.PROACTIVE_INTERRUPT,
                    innerMotivation = 0.0,
                    interruptCost = 0.0,
                    effectiveMotivation = 0.0,
                    breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
                    reason = "首帧兜底展示（无感知快照），计入每日主动配额"
                ),
                thought = topThought,
                reason = "首帧兜底展示，计入每日主动配额",
                bubblesShownToday = bubblesShownToday
            )
        } else {
            ProactiveCoordinationResult(
                level = ProactivityLevel.L0_SILENT,
                decision = DecisionResult(
                    action = ProactiveAction.SILENT,
                    innerMotivation = 0.0,
                    interruptCost = 0.0,
                    effectiveMotivation = 0.0,
                    breakpoint = BreakpointResult(isBreakpoint = false, type = BreakpointType.NONE),
                    reason = "首帧兜底被每日配额拦截或念头池为空"
                ),
                thought = null,
                reason = if (topThought == null) "念头池无待表达念头"
                else "每日主动破冰配额已用尽 (${bubblesShownToday}/${ProactiveBubbleCoordinator.DAILY_BUBBLE_QUOTA})",
                bubblesShownToday = bubblesShownToday
            )
        }
    }

    override suspend fun onUserFeedback(
        feedback: UserFeedback,
        thoughtId: String?,
        nowMs: Long
    ) = stateMutex.withLock {
        val currentDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(nowMs))
        when (feedback) {
            UserFeedback.CLICKED -> {
                // 正向反馈：消耗念头，标记已表达，减轻退避惩罚
                if (thoughtId != null) {
                    thoughtManager.consumeThought(thoughtId, nowMs)
                }
                thoughtManager.markExpressed(nowMs)
                backoffPenalty = max(0.0, backoffPenalty - 0.5)
            }
            UserFeedback.DISMISSED -> {
                // 强负向反馈：关闭念头，记录冷却，显著加重退避惩罚
                if (thoughtId != null) {
                    thoughtManager.consumeThought(thoughtId, nowMs)
                }
                thoughtManager.markExpressed(nowMs)
                lastInterruptionTime = nowMs
                backoffPenalty = min(3.0, backoffPenalty + 1.0)
            }
            UserFeedback.IGNORED -> {
                // 弱负向反馈：轻微加重退避惩罚
                lastInterruptionTime = nowMs
                backoffPenalty = min(3.0, backoffPenalty + 0.3)
            }
        }
        persistState(currentDay)
    }

    private companion object {
        const val TAG = "ProactiveCoordinator"
        const val KEY_BUBBLES_TODAY = "proactive_bubbles_today"
        const val KEY_LAST_DAY = "proactive_last_day"
        const val KEY_BACKOFF_BITS = "proactive_backoff_bits"
        const val KEY_LAST_INTERRUPTION = "proactive_last_interruption_ms"
        const val KEY_L3_TODAY = "proactive_l3_delivered_today"
        const val KEY_LAST_L3_DAY = "proactive_last_l3_day"
    }
}
