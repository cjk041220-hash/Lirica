package com.lirica.android.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.lirica.android.common.logging.LiricaLog
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lirica.android.MainActivity
import com.lirica.android.LiricaApplication
import com.lirica.android.automation.coordinator.ExecutionStepItem
import com.lirica.android.automation.coordinator.StepExecutionStatus
import com.lirica.android.automation.coordinator.TaskExecutionState
import com.lirica.android.database.memory.MemoryExtractor
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.overlay.ui.ActionSpeechBubble
import com.lirica.android.overlay.ui.CompanionStatusCapsule
import com.lirica.android.overlay.ui.DraftConfirmationCard
import com.lirica.android.overlay.ui.IcebreakerBubble
import com.lirica.android.overlay.ui.OverlayBubble
import com.lirica.android.overlay.ui.OverlayExpandPanel
import com.lirica.android.overlay.ui.TaskTraceCard
import com.lirica.android.overlay.viewmodel.OverlayExpandViewModel
import kotlinx.coroutines.launch

/**
 * 跨应用常驻伴生悬浮窗服务 (Companion Overlay Service)
 *
 * 继承 LifecycleService，遵循 WindowManager 挂载 Compose 的铁律：
 * 1. 显式配置 ViewCompositionStrategy.DisposeOnDetachedFromWindow
 * 2. 注入 ViewTreeLifecycleOwner
 * 3. 注入 ViewTreeSavedStateRegistryOwner 与 ViewTreeViewModelStoreOwner
 * 4. 动态协调破冰气泡、伴生体气泡、半展开快速交互面板与输入法焦点
 */
class CompanionOverlayService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val serviceViewModelStore = ViewModelStore()
    private var expandViewModel: OverlayExpandViewModel? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        initViewModel()
        setupOverlayView()
        observePerception()
        observeCoordinatorTaskState()
        observeOverlayUiState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as? LiricaApplication
        val snapshot = app?.perceptionPipeline?.snapshotFlow?.value
        if (snapshot != null) {
            expandViewModel?.processPerception(snapshot)
        } else {
            expandViewModel?.refreshIcebreaker()
        }
        return START_STICKY
    }

    private fun observePerception() {
        val app = application as? LiricaApplication ?: return
        lifecycleScope.launch {
            app.perceptionPipeline.snapshotFlow.collect { snapshot ->
                expandViewModel?.processPerception(snapshot)
            }
        }
    }

    private fun observeCoordinatorTaskState() {
        val app = application as? LiricaApplication ?: return
        // 注册具身视觉反馈监听器（打通气泡状态机、思考、自愈与单步进度）
        app.helixCoordinator.registerFeedbackListener { event ->
            expandViewModel?.onActionFeedback(event)
        }
        lifecycleScope.launch {
            app.helixCoordinator.state.collect { state ->
                expandViewModel?.onTaskExecutionStateChanged(state)
                when (state) {
                    is TaskExecutionState.ExecutingStep -> {
                        promoteToForeground(state.actionDescription)
                    }
                    is TaskExecutionState.AwaitingUserConfirmation -> {
                        setWindowFocusable(true)
                    }
                    is TaskExecutionState.Completed, is TaskExecutionState.Failed, TaskExecutionState.Idle -> {
                        demoteFromForeground()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 监听悬浮窗面板展开状态：
     * 1. 展开时：切换为可聚焦（允许软键盘输入法弹出）、并自动修正 X 坐标防止卡片向右侧溢出屏幕；
     * 2. 折叠时：释放软键盘焦点并将小球自动贴边停靠。
     */
    private fun observeOverlayUiState() {
        val vm = expandViewModel ?: return
        lifecycleScope.launch {
            var wasExpanded = false
            vm.uiState.collect { state ->
                if (state.isExpanded != wasExpanded) {
                    wasExpanded = state.isExpanded
                    if (state.isExpanded) {
                        setWindowFocusable(true)
                        adjustPositionForExpandedPanel()
                    } else {
                        setWindowFocusable(false)
                        snapToEdge()
                    }
                }
            }
        }
    }

    private fun adjustPositionForExpandedPanel() {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val panelWidth = (320 * displayMetrics.density).toInt()
        if (params.x + panelWidth > screenWidth - 16) {
            params.x = (screenWidth - panelWidth - 16).coerceAtLeast(16)
        }
        val panelHeight = (420 * displayMetrics.density).toInt()
        if (params.y + panelHeight > screenHeight - 64) {
            params.y = (screenHeight - panelHeight - 64).coerceAtLeast(64)
        }
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            LiricaLog.e(TAG, "Failed to adjust position for expanded panel", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * 任务代行执行期前台服务等级提升
     *
     * 锁定进程优先级，杜绝切屏至目标第三方 App 期间被系统低内存杀手回收。
     */
    fun promoteToForeground(taskDescription: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASK_EXECUTION,
                "Lirica 任务执行通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lirica 在前台代行操作时的任务执行状态提示"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_TASK_EXECUTION)
            .setContentTitle("Lirica 正在代行操作")
            .setContentText(taskDescription)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID_TASK_EXECUTION, notification)
    }

    /**
     * 任务代行完毕后降级为普通后台悬浮服务
     */
    fun demoteFromForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun initViewModel() {
        val app = application as? LiricaApplication ?: return
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OverlayExpandViewModel(
                    unspokenThoughtDao = app.database.unspokenThoughtDao(),
                    emotionEngine = app.emotionEngine,
                    llmClient = app.llmClient,
                    memoryManager = app.memoryManager,
                    phiMiddleware = app.phiMiddleware,
                    humannessFilter = app.humannessFilter,
                    proactiveCoordinator = app.proactiveCoordinator,
                    // 2026-09-27 审计 P0 修复：悬浮窗链路必须注入 BYOK 仓库。
                    byokRepository = app.byokSettingsRepository,
                    chatDialogueDao = app.database.chatDialogueDao(),
                    toolAgent = app.nativeToolAgent,
                    // 与主聊天共用同一实例（原实现漏传 openLoopDao，导致悬浮窗会话抽不出开环事项）
                    memoryExtractor = app.memoryExtractor
                ) as T
            }
        }
        expandViewModel = ViewModelProvider(serviceViewModelStore, factory)[OverlayExpandViewModel::class.java]
        // 生产装配契约：OverlayExpandViewModel 的可空参数仅测试豁免，
        // 本装配点关键依赖缺失即失败启动，杜绝"装配遗漏静默关功能"。
        requireNotNull(app.byokSettingsRepository) { "生产装配必须提供 byokSettingsRepository" }
        requireNotNull(expandViewModel?.proactiveCoordinator) { "生产装配必须提供 proactiveCoordinator" }
        requireNotNull(app.nativeToolAgent) { "生产装配必须提供 nativeToolAgent" }
    }

    private fun setupOverlayView() {
        // 权限判定防御：未授权时安全跳过，防止 SecurityException
        if (!Settings.canDrawOverlays(this)) {
            LiricaLog.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted, skipping overlay mount.")
            return
        }

        val wm = windowManager ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val bubbleSizePx = (72 * displayMetrics.density).toInt()
        val initialX = screenWidth - bubbleSizePx - 20
        val initialY = screenHeight / 3

        val params = createDefaultLayoutParams(
            initialX = initialX,
            initialY = initialY,
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT
        )
        layoutParams = params

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true

            // 拦截硬件或系统返回键：当面板处于展开状态时，按下返回键优雅收起面板
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (expandViewModel?.uiState?.value?.isExpanded == true) {
                        expandViewModel?.setExpanded(false)
                        setWindowFocusable(false)
                        return@setOnKeyListener true
                    }
                }
                false
            }

            // 动态边界自适应：当 Compose 展开/折叠重测尺寸时，确保右下边界永不溢出物理屏幕
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                val currentParams = this@CompanionOverlayService.layoutParams ?: return@addOnLayoutChangeListener
                val displayMetrics = resources.displayMetrics
                if (currentParams.x + w > displayMetrics.widthPixels - 16) {
                    currentParams.x = (displayMetrics.widthPixels - w - 16).coerceAtLeast(16)
                    try {
                        windowManager?.updateViewLayout(this, currentParams)
                    } catch (e: Exception) {
                        // 忽略瞬态布局更新
                    }
                }
            }

            // 外部触摸监听：当用户轻触悬浮窗卡片外部的屏幕空白区域时，自动收起展开面板并释放输入法焦点
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (expandViewModel?.uiState?.value?.isExpanded == true) {
                        expandViewModel?.setExpanded(false)
                        setWindowFocusable(false)
                    }
                    expandViewModel?.dismissCurrentIcebreaker()
                    false
                } else {
                    false
                }
            }

            // 【WindowManager 挂载 Compose 铁律】注入 Owners
            setViewTreeLifecycleOwner(this@CompanionOverlayService)

            val savedStateOwner = ServiceSavedStateRegistryOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)

            val vmStoreOwner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = serviceViewModelStore
            }
            setViewTreeViewModelStoreOwner(vmStoreOwner)

            val app = application as? LiricaApplication
            val emotionEngine = app?.emotionEngine
            val vm = expandViewModel

            setContent {
                val moodState by (emotionEngine?.moodFlow?.collectAsState()
                    ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(MoodState.CALM) })
                val currentMood = moodState

                val uiState by (vm?.uiState?.collectAsState()
                    ?: androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            com.lirica.android.overlay.viewmodel.OverlayExpandUiState()
                        )
                    })

                val taskState by (app?.helixCoordinator?.state?.collectAsState()
                    ?: androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(TaskExecutionState.Idle)
                    })

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // 0. 草稿二次确认门卡片 (形态三)
                    if (taskState is TaskExecutionState.AwaitingUserConfirmation) {
                        val confirmState = taskState as TaskExecutionState.AwaitingUserConfirmation
                        DraftConfirmationCard(
                            isVisible = true,
                            title = confirmState.draftTitle ?: "草稿确认",
                            riskDetails = confirmState.riskDetails,
                            initialDraftContent = confirmState.draftContent ?: "",
                            onConfirm = { edited ->
                                if (confirmState.onConfirmedWithDraft != null) {
                                    confirmState.onConfirmedWithDraft?.invoke(edited)
                                } else {
                                    confirmState.onConfirmed()
                                }
                                setWindowFocusable(false)
                            },
                            onCancel = {
                                confirmState.onCancelled()
                                setWindowFocusable(false)
                            },
                            onDragDelta = { dx, dy -> updatePosition(dx, dy) },
                            onDragEnd = { snapToEdge() }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 0.1 任务代行实时步骤流水卡 (形态二)
                    if (taskState is TaskExecutionState.ExecutingStep && uiState.isExpanded) {
                        val execState = taskState as TaskExecutionState.ExecutingStep
                        TaskTraceCard(
                            isVisible = true,
                            goal = execState.actionDescription,
                            currentStepIndex = execState.stepIndex,
                            totalSteps = execState.totalStepsEstimate,
                            steps = listOf(
                                ExecutionStepItem(
                                    index = execState.stepIndex,
                                    description = execState.actionDescription,
                                    status = StepExecutionStatus.RUNNING
                                )
                            ),
                            onCancelClick = {
                                app?.helixCoordinator?.cancelCurrentTask()
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 0.2 折叠态伴随自动话语弹泡 (完成时自动弹出话或卡住自愈话，满足用户"自动弹出话"伴随感)
                    if (!uiState.isExpanded && !uiState.actionSpeechText.isNullOrBlank()) {
                        ActionSpeechBubble(
                            speech = uiState.actionSpeechText,
                            onDismissClick = {
                                vm?.dismissActionSpeech()
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 0.3 伴随执行胶囊 (第N步/自愈卡住状态/思考状态)
                    if (!uiState.isExpanded && uiState.isActionRunning && !uiState.actionStatusText.isNullOrBlank()) {
                        CompanionStatusCapsule(
                            isVisible = true,
                            statusText = uiState.actionStatusText.orEmpty(),
                            embodiedState = uiState.embodiedState,
                            isRecovering = uiState.isActionRecovering,
                            onClick = {
                                vm?.toggleExpanded()
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // 1. 顶部未完成念头破冰小气泡 (当未展开且无确认门卡片且当前非行动代行时)
                    if (!uiState.isExpanded && !uiState.isActionRunning && uiState.actionSpeechText.isNullOrBlank() && taskState !is TaskExecutionState.AwaitingUserConfirmation) {
                        IcebreakerBubble(
                            thought = uiState.icebreakerThought,
                            mood = currentMood,
                            onThoughtClick = { thought ->
                                vm?.onIcebreakerClicked(thought)
                            },
                            onDismissClick = { thought ->
                                vm?.dismissIcebreaker(thought)
                            }
                        )
                    }

                    // 2. 半展开轻量快速交互面板 (同步展示最近两三句对话)
                    OverlayExpandPanel(
                        isExpanded = uiState.isExpanded,
                        mood = currentMood,
                        inputText = uiState.quickInputText,
                        isStreaming = uiState.isStreaming,
                        streamingReply = uiState.streamingReply,
                        errorMessage = uiState.errorMessage,
                        recentMessages = uiState.recentMessages,
                        onInputChanged = { text ->
                            vm?.onQuickInputChanged(text)
                        },
                        onSendMessage = { text ->
                            vm?.sendQuickMessage(text)
                        },
                        onCloseClick = {
                            vm?.setExpanded(false)
                            setWindowFocusable(false)
                        },
                        onOpenFullAppClick = {
                            val intent = Intent(this@CompanionOverlayService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                            vm?.setExpanded(false)
                            setWindowFocusable(false)
                        },
                        onFocusChanged = { isFocused ->
                            setWindowFocusable(isFocused)
                        },
                        onDragDelta = { dx, dy -> updatePosition(dx, dy) },
                        onDragEnd = { snapToEdge() }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. 常驻悬浮伴生体小球 (视觉映射：支持具身行动态与 Russell 情绪融合)
                    OverlayBubble(
                        mood = currentMood,
                        isExecuting = uiState.isActionRunning || (taskState is TaskExecutionState.ExecutingStep),
                        embodiedState = uiState.embodiedState,
                        onClick = {
                            vm?.toggleExpanded()
                        },
                        onDragDelta = { dx, dy ->
                            updatePosition(dx, dy)
                        },
                        onDragEnd = {
                            snapToEdge()
                        }
                    )
                }
            }
        }

        try {
            wm.addView(composeView, params)
            overlayComposeView = composeView
            expandViewModel?.refreshIcebreaker()
        } catch (e: Exception) {
            LiricaLog.e(TAG, "Failed to attach overlay ComposeView to WindowManager", e)
        }
    }

    /**
     * 协调 WindowManager 是否捕获软键盘焦点
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        // 核心防御：无论是否聚焦，始终保留 FLAG_NOT_TOUCH_MODAL 与 FLAG_WATCH_OUTSIDE_TOUCH，
        // 绝不将悬浮窗提升为全屏模态窗口（Modal Window），保证窗口外所有点击正常穿透到下层应用
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val targetFlags = if (focusable) {
            (params.flags or baseFlags) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        if (params.flags != targetFlags) {
            params.flags = targetFlags
            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                LiricaLog.e(TAG, "Failed to update focusable flag", e)
            }
        }
    }

    private fun updatePosition(deltaX: Float, deltaY: Float) {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        params.x = (params.x + deltaX.toInt())
        params.y = (params.y + deltaY.toInt())

        val displayMetrics = resources.displayMetrics
        // 优先使用当前 View 的真实测量尺寸（精准感知展开面板宽度），避免被写死为小球 72dp 导致越界剪裁
        val fallbackSize = (72 * displayMetrics.density).toInt()
        val effectiveWidth = if (view.width > 0) view.width else fallbackSize
        val effectiveHeight = if (view.height > 0) view.height else fallbackSize

        val clamped = OverlayPositionCalculator.clampToBounds(
            x = params.x,
            y = params.y,
            bubbleWidth = effectiveWidth,
            bubbleHeight = effectiveHeight,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            topInset = 48,
            bottomInset = 48
        )
        params.x = clamped.first
        params.y = clamped.second

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            LiricaLog.e(TAG, "Failed to update overlay view layout", e)
        }
    }

    private fun snapToEdge() {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        val displayMetrics = resources.displayMetrics
        val fallbackSize = (72 * displayMetrics.density).toInt()
        val effectiveWidth = if (view.width > 0) view.width else fallbackSize

        val targetX = OverlayPositionCalculator.calculateSnapTargetX(
            currentX = params.x,
            bubbleWidth = effectiveWidth,
            screenWidth = displayMetrics.widthPixels,
            edgeMargin = 16
        )
        params.x = targetX

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            LiricaLog.e(TAG, "Failed to snap overlay to edge", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayComposeView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                LiricaLog.e(TAG, "Error removing overlay view on destroy", e)
            }
        }
        overlayComposeView = null
        serviceViewModelStore.clear()
    }

    companion object {
        private const val TAG = "CompanionOverlayService"
        const val NOTIFICATION_CHANNEL_TASK_EXECUTION = "lirica_task_execution"
        const val NOTIFICATION_ID_TASK_EXECUTION = 1002

        /**
         * 基础非模态悬浮窗 Flags：
         * 1. FLAG_NOT_TOUCH_MODAL：必须声明！允许悬浮窗外的触摸事件自由穿透到下层其它应用；
         * 2. FLAG_WATCH_OUTSIDE_TOUCH：允许接收 ACTION_OUTSIDE 外部触摸事件，实现点击外部自动收起；
         * 3. 严格禁用 FLAG_LAYOUT_NO_LIMITS，避免华为鸿蒙等 ROM 错误计算 TouchRegion 导致全屏触摸锁死。
         */
        const val BASE_OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        fun createDefaultLayoutParams(
            initialX: Int,
            initialY: Int,
            width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
            height: Int = WindowManager.LayoutParams.WRAP_CONTENT
        ): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or BASE_OVERLAY_FLAGS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }
    }

    /**
     * 针对 Service 环境构造轻量级 SavedStateRegistryOwner 适配器
     */
    private class ServiceSavedStateRegistryOwner(
        private val serviceLifecycle: Lifecycle
    ) : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)

        init {
            controller.performRestore(null)
        }

        override val lifecycle: Lifecycle get() = serviceLifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }
}
