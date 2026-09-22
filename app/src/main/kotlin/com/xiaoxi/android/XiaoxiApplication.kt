package com.xiaoxi.android

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.xiaoxi.android.automation.agent.AppPackageResolver
import com.xiaoxi.android.automation.agent.NativeToolAgent
import com.xiaoxi.android.automation.backend.AccessibilityExecutionBackend
import com.xiaoxi.android.automation.backend.HybridExecutionManager
import com.xiaoxi.android.automation.backend.ShizukuExecutionBackend
import com.xiaoxi.android.automation.capture.ScreenCaptureManager
import com.xiaoxi.android.automation.coordinator.HelixGuiCoordinator
import com.xiaoxi.android.automation.coordinator.HelixGuiCoordinatorImpl
import com.xiaoxi.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.xiaoxi.android.automation.gesture.GestureExecutorImpl
import com.xiaoxi.android.automation.gesture.TextInputExecutor
import com.xiaoxi.android.automation.grounding.ExecutionTreeExtractor
import com.xiaoxi.android.automation.grounding.HybridGroundingEngineImpl
import com.xiaoxi.android.automation.grounding.MlKitVisualDetector
import com.xiaoxi.android.automation.healing.SelfHealingWatchdogImpl
import com.xiaoxi.android.automation.intent.IntentActionDispatcherImpl
import com.xiaoxi.android.common.logging.XiaoxiCrashHandler
import com.xiaoxi.android.common.logging.XiaoxiLog
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.life.CareHintDetector
import com.xiaoxi.android.life.EventLifeTool
import com.xiaoxi.android.life.SharedPreferencesCareLedger
import com.xiaoxi.android.life.WeatherLifeTool
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.perception.AccessibilityServiceHolder
import com.xiaoxi.android.perception.PerceptionPipeline
import com.xiaoxi.android.perception.PrivacyBreaker
import com.xiaoxi.android.perception.ScreenStateWatcher
import com.xiaoxi.android.perception.UsageStatsTracker
import com.xiaoxi.android.perception.XiaoxiAccessibilityService
import com.xiaoxi.android.perception.XiaoxiNotificationListenerService
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.persona.HandoffManager
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import com.xiaoxi.android.proactivity.AutonomousLifeWorker
import com.xiaoxi.android.proactivity.ProactiveBubbleCoordinator
import com.xiaoxi.android.proactivity.ProactiveBubbleCoordinatorImpl
import com.xiaoxi.android.proactivity.SilenceDecisionEngine
import com.xiaoxi.android.proactivity.SilenceDecisionEngineImpl
import com.xiaoxi.android.proactivity.UnspokenThoughtManager
import com.xiaoxi.android.proactivity.UnspokenThoughtManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class XiaoxiApplication : Application() {

    /**
     * 应用级常驻作用域：感知管线等后台订阅随进程存活，
     * SupervisorJob 保证单个订阅失败不拖垮整个作用域。
     * Dispatchers.IO：订阅主体为 Room 写入与 LLM 流式 IO，IO 池弹性更大，避免阻塞 CPU 密集型 Default 池。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: XiaoxiDatabase
        private set

    lateinit var memoryManager: DualTrackMemoryManager
        private set

    lateinit var emotionEngine: EmotionEngine
        private set

    lateinit var llmClient: XiaoxiLlmClient
        private set

    lateinit var phiMiddleware: PhiMiddleware
        private set

    lateinit var humannessFilter: HumannessFilter
        private set

    lateinit var screenStateWatcher: ScreenStateWatcher
        private set

    lateinit var usageStatsTracker: UsageStatsTracker
        private set

    lateinit var perceptionPipeline: PerceptionPipeline
        private set

    lateinit var unspokenThoughtManager: UnspokenThoughtManager
        private set

    lateinit var silenceDecisionEngine: SilenceDecisionEngine
        private set

    lateinit var proactiveCoordinator: ProactiveBubbleCoordinator
        private set

    lateinit var scheduleWakeManager: com.xiaoxi.android.proactivity.ScheduleWakeManager
        private set

    /**
     * 会话交接快照单例：跨 Activity/ViewModel 重建保持快照不丢
     * （HandoffManager 为纯内存缓存，若由 ViewModel 各自 new 出，重建即丢失上下文交接快照）
     */
    val handoffManager: HandoffManager = HandoffManager()

    lateinit var helixCoordinator: HelixGuiCoordinator
        private set

    lateinit var nativeToolAgent: NativeToolAgent
        private set

    /**
     * 异常感知关心检测器（深夜 / 久未出现）。
     * 冷却台账用 SharedPreferences 而非 Room 表：只有三条记录、单值读取，
     * 不值得为它付一次 schema 迁移（详见 CareLedger 的说明）。
     */
    lateinit var careHintDetector: CareHintDetector
        private set

    /**
     * 后台任务（记忆抽取等）共用的 LLM 配置
     */
    val backgroundLlmConfig: LlmConfig by lazy {
        LlmConfig(apiKey = BuildConfig.DEEPSEEK_API_KEY)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        XiaoxiCrashHandler.install(this)
        XiaoxiLog.i("XiaoxiApplication", "小汐 2.0 系统已启动，本地崩溃守护与环形日志已就绪")

        database = XiaoxiDatabase.getInstance(this)
        memoryManager = DualTrackMemoryManager(database)
        emotionEngine = EmotionEngine()
        llmClient = XiaoxiLlmClient()
        phiMiddleware = PhiMiddleware()
        humannessFilter = HumannessFilter()

        startPerceptionPipeline()

        unspokenThoughtManager = UnspokenThoughtManagerImpl(database.unspokenThoughtDao())
        silenceDecisionEngine = SilenceDecisionEngineImpl()
        proactiveCoordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = unspokenThoughtManager,
            decisionEngine = silenceDecisionEngine,
            emotionEngine = emotionEngine
        )

        scheduleWakeManager = com.xiaoxi.android.proactivity.ScheduleWakeManager(
            scheduledAlarmDao = database.scheduledAlarmDao(),
            unspokenThoughtDao = database.unspokenThoughtDao(),
            alarmScheduler = com.xiaoxi.android.proactivity.AndroidSystemAlarmScheduler(this)
        )

        val intentDispatcher = IntentActionDispatcherImpl(this)
        val treeExtractor = ExecutionTreeExtractor()
        val gestureExecutor = GestureExecutorImpl(serviceProvider = { AccessibilityServiceHolder.get() })
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textInputExecutor = TextInputExecutor(clipboardManager, gestureExecutor)
        val watchdog = SelfHealingWatchdogImpl()
        val gatekeeper = FinancialGatekeeperImpl()
        val visualDetector = MlKitVisualDetector()
        val groundingEngine = HybridGroundingEngineImpl(visualDetector = visualDetector)
        val screenCaptureManager = ScreenCaptureManager(
            serviceProvider = { AccessibilityServiceHolder.get() },
            currentPackageProvider = { AccessibilityServiceHolder.get()?.rootInActiveWindow?.packageName?.toString() }
        )

        // 组装双通道自动化执行底座：无障碍拟人手势 + Shizuku 穿透通道
        val accessibilityBackend = AccessibilityExecutionBackend(
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            serviceProvider = { AccessibilityServiceHolder.get() }
        )
        val shizukuBackend = ShizukuExecutionBackend()
        val hybridExecutionManager = HybridExecutionManager(
            accessibilityBackend = accessibilityBackend,
            shizukuBackend = shizukuBackend
        )
        val packageResolver = AppPackageResolver(context = this)

        // 注入真实屏幕宽高：滑动比例与接地坐标换算依赖真实尺寸，
        // 默认 1080x2400 硬编码在其他分辨率设备上会产生错位手势；
        // 每次调用时实时读取，规避 Application 早期系统 displayMetrics 尚未就绪的问题。
        // 尺寸源用 DisplayManager 的物理显示（getRealMetrics，含系统装饰区），而非
        // Resources.getSystem()：后者跟随系统配置变化，分屏/自由窗口下可能拿到
        // 窗口尺寸而非整屏尺寸，而无障碍 bounds 与手势坐标始终是屏幕绝对坐标系，
        // 必须用整屏口径。getRealMetrics 在 API 31+ 标记 deprecated，但仍是 minSdk 26
        // 下语义最直接的全版本读取方式（WindowMetrics 需 API 30+ 且 Application
        // 非可视上下文会抛 UnsupportedOperationException）。
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        helixCoordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            screenWidthProvider = {
                runCatching {
                    val metrics = DisplayMetrics()
                    displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
                    metrics.widthPixels
                }.getOrDefault(0)
            },
            screenHeightProvider = {
                runCatching {
                    val metrics = DisplayMetrics()
                    displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
                    metrics.heightPixels
                }.getOrDefault(0)
            },
            screenCaptureManager = screenCaptureManager,
            executionBackend = hybridExecutionManager
        )

        nativeToolAgent = NativeToolAgent(
            coordinator = helixCoordinator,
            intentDispatcher = intentDispatcher,
            scheduleWakeManager = scheduleWakeManager,
            screenCaptureManager = screenCaptureManager,
            visualDetector = visualDetector,
            packageResolver = packageResolver,
            // 生活工具挂载：天气（Open-Meteo，免 Key）+ 日程（create_event/list_events，
            // 落 scheduled_alarms 表并注册系统闹钟）。后续搜索等按同一 LifeTool
            // 契约追加，不动 agent 主体
            lifeTools = listOf(
                WeatherLifeTool(),
                EventLifeTool(
                    scheduleWakeManager = scheduleWakeManager,
                    scheduledAlarmDao = database.scheduledAlarmDao()
                )
            )
        )

        careHintDetector = CareHintDetector(
            careLedger = SharedPreferencesCareLedger.create(this)
        )

        registerAutonomousLife()
    }

    /**
     * S1 感知接线：把 ScreenStateWatcher / UsageStatsTracker / NotificationListener /
     * AccessibilityService 四路信号源汇聚进 PerceptionPipeline，驱动情绪引擎与念头池。
     * 各组件内部对异常与权限缺失均有防御，无需额外守门。
     */
    private fun startPerceptionPipeline() {
        screenStateWatcher = ScreenStateWatcher().also { it.start(this) }

        // Watcher 输出快照流，这里映射为枚举状态流供 UsageStatsTracker 的息屏门控使用；
        // 初始值与快照默认状态 (OFF) 对齐，息屏语义下保守省电
        val screenStateFlow: StateFlow<ScreenState> = screenStateWatcher.screenState
            .map { it.state }
            .stateIn(appScope, SharingStarted.Eagerly, ScreenState.OFF)

        usageStatsTracker = UsageStatsTracker().also { tracker ->
            tracker.startTracking(
                scope = appScope,
                context = this,
                screenStateFlow = screenStateFlow
            )
        }

        perceptionPipeline = PerceptionPipeline(
            foregroundAppFlow = usageStatsTracker.foregroundApp,
            screenStateFlow = screenStateWatcher.screenState,
            notificationFlow = XiaoxiNotificationListenerService.notificationEvents,
            pageContentFlow = XiaoxiAccessibilityService.pageContentEvents,
            privacyBreaker = PrivacyBreaker(),
            emotionEngine = emotionEngine,
            unspokenThoughtDao = database.unspokenThoughtDao()
        ).also { it.start(appScope) }
    }

    /**
     * 注册闲时自主生活与自叙入梦周期任务（幂等，KEEP 策略）。
     * runCatching 兜底：Robolectric 单测环境可能未初始化 WorkManager，不应阻断 Application 启动。
     */
    fun registerAutonomousLife() {
        runCatching { AutonomousLifeWorker.enqueue(this) }
        runCatching { com.xiaoxi.android.memory.consolidation.SubconsciousDreamWorker.enqueue(this) }
    }

    companion object {
        lateinit var instance: XiaoxiApplication
            private set
    }
}
