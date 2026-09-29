package com.lirica.android.perception

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.NotificationEvent
import com.lirica.android.perception.model.PageContentSnapshot
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 统一端侧感知流水线 (PerceptionPipeline)
 *
 * 核心职能：
 * 1. 汇聚 Task 3.1 (前台/屏幕)、Task 3.2 (通知) 与 Task 3.3 (无障碍节点)
 * 2. 经由 Task 3.4 (PrivacyBreaker) 守门熔断与脱敏，输出统一情境感知快照 PerceptionSnapshot
 * 3. 驱动 Phase 1 情绪动力学中枢 (EmotionEngine) 与未完成念头池 (UnspokenThoughtDao)
 */
class PerceptionPipeline(
    private val foregroundAppFlow: StateFlow<ForegroundAppInfo?>,
    private val screenStateFlow: StateFlow<ScreenStateSnapshot>,
    private val notificationFlow: SharedFlow<NotificationEvent>,
    private val pageContentFlow: SharedFlow<PageContentSnapshot>,
    private val privacyBreaker: PrivacyBreaker = PrivacyBreaker(),
    private val emotionEngine: EmotionEngine? = null,
    private val unspokenThoughtDao: UnspokenThoughtDao? = null,
    private val hardwareStateFlow: StateFlow<HardwareEnvironmentState>? = null
) {

    private val _snapshotFlow = MutableStateFlow(PerceptionSnapshot())
    val snapshotFlow: StateFlow<PerceptionSnapshot> = _snapshotFlow.asStateFlow()

    private var pipelineJob: Job? = null

    val isRunning: Boolean
        get() = pipelineJob?.isActive == true

    /**
     * 启动感知流水线监听与汇聚
     */
    fun start(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        stop()

        val parentJob = scope.coroutineContext[Job]
        val pipelineSupervisor = SupervisorJob(parentJob)
        pipelineJob = pipelineSupervisor

        val pipelineScope = CoroutineScope(dispatcher + pipelineSupervisor)

        // 1. 监听前台应用变动
        pipelineScope.launch {
            foregroundAppFlow.collect { app ->
                synchronized(this@PerceptionPipeline) {
                    val current = _snapshotFlow.value
                    // 感知层用整包熔断口径（含微信）——自动化门控另有 isSensitiveApp 精细口径
                    val isSensitive = app != null && privacyBreaker.isPerceptionFusedApp(app.packageName)
                    val newPage = if (isSensitive && current.pageContent != null) {
                        current.pageContent.copy(nodes = emptyList(), focusedText = null)
                    } else {
                        current.pageContent
                    }

                    _snapshotFlow.value = current.copy(
                        timestamp = System.currentTimeMillis(),
                        foregroundApp = app,
                        pageContent = newPage,
                        isPrivacyFusing = isSensitive,
                        perceivedSummary = buildPerceivedSummary(app, current.screenState, isSensitive)
                    )
                }
            }
        }

        // 2. 监听屏幕状态变动
        pipelineScope.launch {
            screenStateFlow.collect { screen ->
                synchronized(this@PerceptionPipeline) {
                    val current = _snapshotFlow.value
                    val prevState = current.screenState.state
                    _snapshotFlow.value = current.copy(
                        timestamp = System.currentTimeMillis(),
                        screenState = screen,
                        perceivedSummary = buildPerceivedSummary(current.foregroundApp, screen, current.isPrivacyFusing)
                    )

                    // 屏幕由息屏/锁屏变为解锁就绪，注入轻微唤醒与关注情绪冲激 (依据昼夜自适应)
                    if (prevState != ScreenState.UNLOCKED && screen.state == ScreenState.UNLOCKED) {
                        val cal = java.util.Calendar.getInstance()
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        val stimulus = if (hour in 0..4 || hour >= 23) {
                            EmotionVector(valence = 0.05f, arousal = -0.10f) // 深夜安宁轻困
                        } else {
                            EmotionVector(valence = 0.05f, arousal = 0.05f)
                        }
                        emotionEngine?.applyStimulus(stimulus)
                    }
                }
            }
        }

        // 3. 监听全局有效通知
        pipelineScope.launch {
            notificationFlow.collect { notif ->
                // 2026-09-27 审计 P1 修复：通知文本进入快照/念头池前统一过 PII 脱敏。
                // 此为通知链路的唯一收口点：下游（快照 latestNotification、念头池、
                // LLM 上下文）全部消费脱敏后的事件，手机号/银行卡/身份证不再绕过防线。
                val sanitized = notif.copy(
                    title = privacyBreaker.maskPiiText(notif.title),
                    content = privacyBreaker.maskPiiText(notif.content),
                    subText = notif.subText?.let { privacyBreaker.maskPiiText(it) }
                )
                synchronized(this@PerceptionPipeline) {
                    val current = _snapshotFlow.value
                    _snapshotFlow.value = current.copy(
                        timestamp = System.currentTimeMillis(),
                        latestNotification = sanitized
                    )
                }

                // 高价值通讯或日程通知，自动投递未完成念头池
                if (sanitized.category == NotificationCategory.COMMUNICATION ||
                    sanitized.category == NotificationCategory.SCHEDULE ||
                    sanitized.category == NotificationCategory.TRAVEL_LOGISTICS
                ) {
                    emitPerceivedThought(sanitized)
                }
            }
        }

        // 4. 监听页面无障碍节点快照
        pipelineScope.launch {
            pageContentFlow.collect { page ->
                synchronized(this@PerceptionPipeline) {
                    val sanitized = privacyBreaker.sanitizePageSnapshot(page)
                    // 感知层用整包熔断口径（含微信）——自动化门控另有 isSensitiveApp 精细口径
                    val isSensitive = privacyBreaker.isPerceptionFusedApp(page.packageName)
                    val current = _snapshotFlow.value

                    _snapshotFlow.value = current.copy(
                        timestamp = System.currentTimeMillis(),
                        pageContent = sanitized,
                        isPrivacyFusing = isSensitive,
                        perceivedSummary = buildPerceivedSummary(current.foregroundApp, current.screenState, isSensitive)
                    )
                }
            }
        }

        // 5. 监听硬件环境态变动 (M3.4 硬件环境态总线)
        hardwareStateFlow?.let { flow ->
            pipelineScope.launch {
                flow.collect { hwState ->
                    synchronized(this@PerceptionPipeline) {
                        val current = _snapshotFlow.value
                        _snapshotFlow.value = current.copy(
                            timestamp = System.currentTimeMillis(),
                            hardwareEnvironment = hwState
                        )
                    }
                }
            }
        }
    }

    /**
     * 停止感知流水线
     */
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
    }

    private suspend fun emitPerceivedThought(notif: NotificationEvent) {
        val thoughtText = when (notif.category) {
            NotificationCategory.COMMUNICATION -> "收到一条来自${notif.title}的新消息「${notif.content.take(30)}」"
            NotificationCategory.SCHEDULE -> "你有待办日程提醒「${notif.title}: ${notif.content.take(30)}」"
            NotificationCategory.TRAVEL_LOGISTICS -> "外卖/出行状态更新「${notif.title}: ${notif.content.take(30)}」"
            else -> "收到来自${notif.packageName}的通知「${notif.title}」"
        }

        val entity = UnspokenThoughtEntity(
            id = UUID.randomUUID().toString(),
            thought = thoughtText,
            triggerContext = notif.packageName,
            urgency = if (notif.category == NotificationCategory.COMMUNICATION) 0.8f else 0.6f,
            targetScenario = "notification_perception",
            createdAt = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + 30 * 60 * 1000L // 30 分钟自然过期
        )

        try {
            unspokenThoughtDao?.insert(entity)
        } catch (e: Exception) {
            LiricaLog.e("PerceptionPipeline", "插入感知念头异常: $e", e)
        }
    }

    private fun buildPerceivedSummary(
        app: ForegroundAppInfo?,
        screen: ScreenStateSnapshot,
        isPrivacy: Boolean
    ): String {
        val appPart = if (app != null) {
            val dwellSec = app.dwellDurationMs / 1000
            if (isPrivacy) "在前台应用[隐私保护态]" else "在前台${app.packageName}(驻留${dwellSec}s)"
        } else {
            "前台桌面或待机"
        }
        val screenPart = when (screen.state) {
            ScreenState.OFF -> "息屏"
            ScreenState.ON -> "亮屏锁屏"
            ScreenState.UNLOCKED -> "已解锁活跃"
        }
        return "$appPart, 屏幕$screenPart"
    }
}
