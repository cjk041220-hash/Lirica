package com.lirica.android

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.lirica.android.automation.agent.AppPackageResolver
import com.lirica.android.automation.agent.NativeToolAgent
import com.lirica.android.automation.backend.AccessibilityExecutionBackend
import com.lirica.android.automation.backend.HybridExecutionManager
import com.lirica.android.automation.backend.ShizukuExecutionBackend
import com.lirica.android.automation.capture.ScreenCaptureManager
import com.lirica.android.automation.coordinator.HelixGuiCoordinator
import com.lirica.android.automation.coordinator.HelixGuiCoordinatorImpl
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gesture.GestureExecutorImpl
import com.lirica.android.automation.gesture.TextInputExecutor
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.HybridGroundingEngineImpl
import com.lirica.android.automation.grounding.MlKitVisualDetector
import com.lirica.android.automation.healing.SelfHealingWatchdogImpl
import com.lirica.android.automation.intent.IntentActionDispatcherImpl
import com.lirica.android.common.logging.LiricaCrashHandler
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.common.settings.ByokSettingsRepository
import com.lirica.android.common.settings.ByokSettingsRepositoryImpl
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.database.memory.MemoryVectorStore
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.life.CareHintDetector
import com.lirica.android.life.EventLifeTool
import com.lirica.android.life.SharedPreferencesCareLedger
import com.lirica.android.life.WeatherLifeTool
import com.lirica.android.memory.retrieval.HybridMemoryRetriever
import com.lirica.android.memory.retrieval.LiricaEmbeddingModel
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.perception.AccessibilityServiceHolder
import com.lirica.android.perception.HardwareEnvironmentWatcher
import com.lirica.android.perception.PerceptionPipeline
import com.lirica.android.perception.PrivacyBreaker
import com.lirica.android.perception.ScreenStateWatcher
import com.lirica.android.perception.UsageStatsTracker
import com.lirica.android.perception.LiricaAccessibilityService
import com.lirica.android.perception.LiricaNotificationListenerService
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.persona.HandoffManager
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.proactivity.AutonomousLifeWorker
import com.lirica.android.proactivity.ProactiveBubbleCoordinator
import com.lirica.android.proactivity.ProactiveBubbleCoordinatorImpl
import com.lirica.android.proactivity.SilenceDecisionEngine
import com.lirica.android.proactivity.SilenceDecisionEngineImpl
import com.lirica.android.proactivity.UnspokenThoughtManager
import com.lirica.android.proactivity.UnspokenThoughtManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LiricaApplication : Application() {

    /**
     * 应用级常驻作用域：感知管线等后台订阅随进程存活，
     * SupervisorJob 保证单个订阅失败不拖垮整个作用域。
     * Dispatchers.IO：订阅主体为 Room 写入与 LLM 流式 IO，IO 池弹性更大，避免阻塞 CPU 密集型 Default 池。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: LiricaDatabase
        private set

    lateinit var memoryManager: DualTrackMemoryManager
        private set

    lateinit var emotionEngine: EmotionEngine
        private set

    lateinit var llmClient: LiricaLlmClient
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

    lateinit var scheduleWakeManager: com.lirica.android.proactivity.ScheduleWakeManager
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

    lateinit var sensingEpisodicBuffer: com.lirica.android.perception.SensingEpisodicBuffer
        private set

    lateinit var openLoopLifecycleManager: com.lirica.android.proactivity.OpenLoopLifecycleManager
        private set

    lateinit var proactiveNotificationDispatcher: com.lirica.android.proactivity.ProactiveNotificationDispatcher
        private set

    lateinit var byokSettingsRepository: ByokSettingsRepository
        private set

    /**
     * BYOK 密钥存储实例。
     *
     * 2026-10-02 P0 安全修复：降级路径不再使用源码硬编码常量，改为设备绑定的随机密钥。
     * 暴露此字段是为了让设置页能通过 [com.lirica.android.common.settings.SecureKeyStore.isHardwareBacked]
     * 向用户诚实披露加密强度，而非默认宣称"已加密"。
     */
    lateinit var secureKeyStore: com.lirica.android.common.settings.SecureKeyStore
        private set

    /**
     * 记忆抽取器单例。
     *
     * 必须全应用唯一：抽取节流（pendingTurns / lastExtractionTimestamp / 失败退避）是**实例级状态**，
     * 之前 MainActivity 与 CompanionOverlayService 各 new 一个，等于两条聊天链路各算各的 3 分钟/3 轮门槛，
     * 同一批对话会被抽两次、记忆重复入库、开环事项开出两条。
     * 同时它也是主聊天与悬浮窗唯一的构造入口——避免某一侧漏传 openLoopDao 导致"开环只在主界面生效"。
     */
    lateinit var memoryExtractor: com.lirica.android.database.memory.MemoryExtractor
        private set

    /**
     * 后台任务（记忆抽取等）共用的 LLM 配置（动态消费用户有效设置）
     */
    val backgroundLlmConfig: LlmConfig
        get() = byokSettingsRepository.getEffectiveLlmConfig()

    override fun onCreate() {
        super.onCreate()
        instance = this

        LiricaCrashHandler.install(this)
        LiricaLog.i("LiricaApplication", "Lirica 系统已启动，本地崩溃守护与环形日志已就绪")

        val byokPrefs = getSharedPreferences(ByokSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        // 2026-10-02 P0 安全修复：把降级密钥的持久化容器注入 SecureKeyStore。
        // 旧实现内置源码常量作为兜底根密钥，可被反编译直接提取；现改为设备绑定的随机密钥。
        val softwareKeyPrefs = getSharedPreferences(
            com.lirica.android.common.settings.DefaultSecureKeyStore.SOFTWARE_KEY_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        secureKeyStore = com.lirica.android.common.settings.DefaultSecureKeyStore(
            fallbackPrefs = softwareKeyPrefs
        )
        byokSettingsRepository = ByokSettingsRepositoryImpl(
            prefs = byokPrefs,
            secureKeyStore = secureKeyStore
        )

        database = LiricaDatabase.getInstance(this)
        memoryManager = DualTrackMemoryManager(database)
        initVectorMemoryAsync()
        emotionEngine = EmotionEngine()
        llmClient = LiricaLlmClient()
        phiMiddleware = PhiMiddleware()
        humannessFilter = HumannessFilter()

        startPerceptionPipeline()

        openLoopLifecycleManager = com.lirica.android.proactivity.OpenLoopLifecycleManager(
            openLoopDao = database.openLoopDao(),
            unspokenThoughtDao = database.unspokenThoughtDao()
        )
        proactiveNotificationDispatcher = com.lirica.android.proactivity.ProactiveNotificationDispatcher()

        careHintDetector = CareHintDetector(
            careLedger = SharedPreferencesCareLedger.create(this)
        )

        unspokenThoughtManager = UnspokenThoughtManagerImpl(
            dao = database.unspokenThoughtDao(),
            synthesizer = com.lirica.android.proactivity.ThoughtSynthesizerImpl(careHintDetector = careHintDetector)
        )
        silenceDecisionEngine = SilenceDecisionEngineImpl()
        proactiveCoordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = unspokenThoughtManager,
            decisionEngine = silenceDecisionEngine,
            emotionEngine = emotionEngine,
            // 2026-09-27 审计 P2 修复：配额/退避/冷却台账持久化，防杀进程重启规避
            statePrefs = getSharedPreferences("lirica_proactivity_prefs", Context.MODE_PRIVATE),
            openLoopManager = openLoopLifecycleManager,
            notificationDispatcher = proactiveNotificationDispatcher,
            contextProvider = { this }
        )

        scheduleWakeManager = com.lirica.android.proactivity.ScheduleWakeManager(
            scheduledAlarmDao = database.scheduledAlarmDao(),
            unspokenThoughtDao = database.unspokenThoughtDao(),
            alarmScheduler = com.lirica.android.proactivity.AndroidSystemAlarmScheduler(this)
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

        memoryExtractor = com.lirica.android.database.memory.MemoryExtractor(
            llmClient = llmClient,
            userMemoryDao = database.userMemoryDao(),
            llmConfigProvider = { backgroundLlmConfig },
            hybridRetrieverProvider = { memoryManager.getHybridRetriever() },
            openLoopDao = database.openLoopDao()
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

        val hardwareWatcher = HardwareEnvironmentWatcher().also { it.start(this) }

        // M3.4 零轮询前台感知（无障碍秒级事件驱动）+ UsageStats 降级兜底通道
        val zeroPollingForegroundFlow: StateFlow<ForegroundAppInfo?> = merge(
            LiricaAccessibilityService.windowForegroundAppEvents,
            usageStatsTracker.foregroundApp.filterNotNull()
        ).stateIn(appScope, SharingStarted.Eagerly, null)

        perceptionPipeline = PerceptionPipeline(
            foregroundAppFlow = zeroPollingForegroundFlow,
            screenStateFlow = screenStateWatcher.screenState,
            notificationFlow = LiricaNotificationListenerService.notificationEvents,
            pageContentFlow = LiricaAccessibilityService.pageContentEvents,
            privacyBreaker = PrivacyBreaker(),
            emotionEngine = emotionEngine,
            unspokenThoughtDao = database.unspokenThoughtDao(),
            hardwareStateFlow = hardwareWatcher.hardwareState
        ).also { it.start(appScope) }

        // M4.1 接线：挂载情境感知切片聚合缓冲器，实时将前台驻留与硬件跃迁沉淀入库
        sensingEpisodicBuffer = com.lirica.android.perception.SensingEpisodicBuffer(
            sliceDao = database.episodicSensingSliceDao()
        ).also {
            it.start(appScope, perceptionPipeline.snapshotFlow)
        }
    }

    /**
     * 异步装配端侧 BGE 向量模型与混合记忆检索器 (P0 接线)。
     * 在 IO 协程中加载 Assets ONNX 模型与初始化内存向量库，规避阻塞主线程 onCreate。
     * 若在 Robolectric 测试环境或设备不支持 native ONNX，安全捕获并降级维持 2-gram。
     */
    private fun initVectorMemoryAsync() {
        appScope.launch {
            try {
                val vectorStore = MemoryVectorStore(database.memoryEmbeddingDao())
                vectorStore.initFromDatabase()

                val isRobolectric = android.os.Build.FINGERPRINT == "robolectric"
                val embeddingModel = if (!isRobolectric) {
                    runCatching {
                        LiricaEmbeddingModel.createFromAssets(this@LiricaApplication)
                    }.getOrNull()
                } else null

                if (embeddingModel != null) {
                    val retriever = HybridMemoryRetriever(
                        embeddingModel = embeddingModel,
                        vectorStore = vectorStore,
                        userMemoryDao = database.userMemoryDao(),
                        selfExperienceDao = database.selfExperienceDao()
                    )
                    memoryManager.attachHybridRetriever(retriever)
                    LiricaLog.i("LiricaApplication", "端侧 BGE 向量模型与混合记忆检索器异步装配成功")

                    // 若向量库为空但数据库已有记忆/经历，后台补齐向量索引
                    val activeMemories = database.userMemoryDao().getActiveList(UserMemoryEntity.STATUS_ACTIVE, limit = 500)
                    val unindexedUser = activeMemories.filter { mem ->
                        database.memoryEmbeddingDao().getByMemoryId(mem.id) == null
                    }
                    if (unindexedUser.isNotEmpty()) {
                        retriever.indexMemories(unindexedUser)
                        LiricaLog.i("LiricaApplication", "后台回填未索引用户记忆向量: ${unindexedUser.size} 条")
                    }

                    val activeExperiences = database.selfExperienceDao().getActiveExperiences(limit = 200)
                    val unindexedSelf = activeExperiences.filter { exp ->
                        database.memoryEmbeddingDao().getByMemoryId(exp.id) == null
                    }
                    if (unindexedSelf.isNotEmpty()) {
                        retriever.indexSelfExperiences(unindexedSelf)
                        LiricaLog.i("LiricaApplication", "后台回填未索引自叙经历向量: ${unindexedSelf.size} 条")
                    }
                } else {
                    LiricaLog.w("LiricaApplication", "端侧 BGE 向量模型加载未就绪或运行环境不支持，降级维持 2-gram 话题检索")
                }
            } catch (e: Throwable) {
                LiricaLog.e("LiricaApplication", "端侧向量记忆库初始化异常", e)
            }
        }
    }

    /**
     * 注册闲时自主生活与自叙入梦周期任务（幂等，KEEP 策略）。
     * runCatching 兜底：Robolectric 单测环境可能未初始化 WorkManager，不应阻断 Application 启动。
     */
    fun registerAutonomousLife() {
        runCatching { AutonomousLifeWorker.enqueue(this) }
        runCatching { com.lirica.android.memory.consolidation.SubconsciousDreamWorker.enqueue(this) }
    }

    companion object {
        lateinit var instance: LiricaApplication
            private set
    }
}
