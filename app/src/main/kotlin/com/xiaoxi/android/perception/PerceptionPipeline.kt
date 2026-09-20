package com.xiaoxi.android.perception

import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.perception.model.ForegroundAppInfo
import com.xiaoxi.android.perception.model.NotificationCategory
import com.xiaoxi.android.perception.model.NotificationEvent
import com.xiaoxi.android.perception.model.PageContentSnapshot
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.perception.model.ScreenStateSnapshot
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
    private val unspokenThoughtDao: UnspokenThoughtDao? = null
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
                    val isSensitive = app != null && privacyBreaker.isSensitiveApp(app.packageName)
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

                    // 屏幕由息屏/锁屏变为解锁就绪，注入轻微唤醒与关注情绪冲激
                    if (prevState != ScreenState.UNLOCKED && screen.state == ScreenState.UNLOCKED) {
                        emotionEngine?.applyStimulus(EmotionVector(valence = 0.05f, arousal = 0.05f))
                    }
                }
            }
        }

        // 3. 监听全局有效通知
        pipelineScope.launch {
            notificationFlow.collect { notif ->
                synchronized(this@PerceptionPipeline) {
                    val current = _snapshotFlow.value
                    _snapshotFlow.value = current.copy(
                        timestamp = System.currentTimeMillis(),
                        latestNotification = notif
                    )
                }

                // 高价值通讯或日程通知，自动投递未完成念头池
                if (notif.category == NotificationCategory.COMMUNICATION ||
                    notif.category == NotificationCategory.SCHEDULE ||
                    notif.category == NotificationCategory.TRAVEL_LOGISTICS
                ) {
                    emitPerceivedThought(notif)
                }
            }
        }

        // 4. 监听页面无障碍节点快照
        pipelineScope.launch {
            pageContentFlow.collect { page ->
                synchronized(this@PerceptionPipeline) {
                    val sanitized = privacyBreaker.sanitizePageSnapshot(page)
                    val isSensitive = privacyBreaker.isSensitiveApp(page.packageName)
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
        } catch (_: Exception) {
            // 防御性异常隔离
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
