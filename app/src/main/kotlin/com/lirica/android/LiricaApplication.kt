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
import com.lirica.android.automation.verify.toViewSignatures
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
     * 硬件环境态总线（M3.4 起存在，M5.2 起被物理资源熔断器消费）。
     * 提升为字段的原因：PhysicalResourceGuard 需要在任务派发前读取温度/电量快照。
     */
    lateinit var hardwareEnvironmentWatcher: HardwareEnvironmentWatcher
        private set

    /** 无障碍保活健康监视器（§7.1 国产 ROM 墓碑探测） */
    lateinit var accessibilityHealthMonitor: com.lirica.android.perception.AccessibilityHealthMonitor
        private set

    /** 用户抢占熔断器（§6.2） */
    lateinit var userInterruptionDetector: com.lirica.android.automation.verify.UserInterruptionDetector
        private set

    /** 执行轨迹记录器（§九 可观测性，三项核心指标的数据源） */
    lateinit var executionTraceRecorder: com.lirica.android.automation.trace.ExecutionTraceRecorder
        private set

    /** 经验轨迹库（M5.3 §5.2 单机自学） */
    lateinit var trajectoryMemory: com.lirica.android.automation.macro.TrajectoryMemory
        private set

    /** 统一安全裁决引擎（M5.1 P0-1 收口） */
    lateinit var safetyPolicyEngine: com.lirica.android.automation.policy.SafetyPolicyEngine
        private set

    /** 双态行动模式持久化（M5.1 §八） */
    lateinit var actionModeSettings: com.lirica.android.common.settings.ActionModeSettings
        private set

    /**
     * 剪贴板前台焦点读取器（M5.1 §4.1 P2-6）。
     *
     * 暴露原因：Android 10+ 只允许"前台有焦点"或"用户主动分享"两种合法读取场景，
     * 触发面在 UI 层（用户点按/分享），因此这里提供**已过安全闸门**的实例，
     * 由 UI 侧在合法时机调用——而不是让 UI 直接摸 ClipboardManager 绕过策略。
     */
    lateinit var clipboardFocusReader: com.lirica.android.automation.policy.ClipboardFocusReader
        private set

    /**
     * 用户抢占后的显式"继续"入口（§6.2）。
     *
     * 默认不自动恢复（复审 R4 裁决：用户停手 != 用户同意继续），
     * 因此必须有一个显式入口：对话层/悬浮胶囊在用户表达"继续"时调用本方法。
     * @return true 表示确实处于抢占态并被解除
     */
    /**
     * 漫游模式下的"破例"入口（§八 自主风险交互）。
     *
     * 当漫游拦截把"会留下痕迹"的互动转成征询后，用户在对话里点头，
     * 对话层/悬浮层调用本方法提升**本次任务**的作用域；任务结束或切回共享模式即失效。
     * 硬熔断（支付/密码/通信代发）不受影响——作用域提升不是安全豁免。
     *
     * @return 作用域令牌；调用方在任务结束时归还（clearElevation）
     */
    fun elevateRoamingScopeForTask(taskId: String): String =
        helixCoordinator.elevateRoamingScope(taskId)

    fun resumeFromUserInterruption(): Boolean {
        if (!userInterruptionDetector.isUserActive()) return false
        userInterruptionDetector.requestResume()
        LiricaLog.i("LiricaApplication", "用户显式确认继续，解除抢占熔断")
        return true
    }

    /**
     * 切换双态行动模式：先落盘再生效，保证进程被杀后重启不会回到旧模式。
     */
    fun setActionMode(mode: com.lirica.android.automation.policy.ActionExecutionMode) {
        actionModeSettings.save(mode)
        safetyPolicyEngine.setMode(mode)
        LiricaLog.i("LiricaApplication", "行动模式已切换为 " + mode)
    }

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

        // M5.1 安全收口（P0-1 / 复审 R7）：构造唯一裁决服务并注入 Layer 1 通道入口。
        // 裁决服务在 Layer 2 编排层与 Layer 1 各通道之间共享，绝不允许某条通道自建旁路。
        val gatekeeper = FinancialGatekeeperImpl()
        safetyPolicyEngine = com.lirica.android.automation.policy.SafetyPolicyEngineImpl(
            gatekeeper = gatekeeper,
            permissionProvider = com.lirica.android.common.permissions.PermissionManager
                .automationPermissionProvider(this)
        )
        val policyEngine = safetyPolicyEngine
        // M5.1 §八：把用户上次选择的双态模式在启动时恢复（默认共享）。
        // 恢复动作必须发生在任何自动化派发之前，否则会出现"UI 显示漫游、
        // 底层却按共享模式全权限执行"的危险窗口。
        actionModeSettings = com.lirica.android.common.settings.ActionModeSettings.create(this)
        policyEngine.setMode(actionModeSettings.load())
        val intentDispatcher = IntentActionDispatcherImpl(
            context = this,
            safetyPolicy = policyEngine
        )
        clipboardFocusReader = com.lirica.android.automation.policy.ClipboardFocusReaderImpl(
            context = this,
            safetyPolicy = policyEngine
        )
        val treeExtractor = ExecutionTreeExtractor()
        val gestureExecutor = GestureExecutorImpl(serviceProvider = { AccessibilityServiceHolder.get() })
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textInputExecutor = TextInputExecutor(clipboardManager, gestureExecutor)
        val watchdog = SelfHealingWatchdogImpl()
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
        val screenWidthProvider: () -> Int = {
            runCatching {
                val metrics = DisplayMetrics()
                displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
                metrics.widthPixels
            }.getOrDefault(0)
        }
        val screenHeightProvider: () -> Int = {
            runCatching {
                val metrics = DisplayMetrics()
                displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
                metrics.heightPixels
            }.getOrDefault(0)
        }
        val currentPackageProvider: () -> String? = {
            AccessibilityServiceHolder.get()?.rootInActiveWindow?.packageName?.toString()
        }
        val rootNodeProvider: () -> android.view.accessibility.AccessibilityNodeInfo? = {
            AccessibilityServiceHolder.get()?.rootInActiveWindow
        }

        // =================================================================
        // M5.1 / M5.2 / M5.3 行动引擎装配
        // =================================================================

        // 轨迹记录器：环形缓存 + 日志落点（诊断包导出时与 LiricaLog 时间轴互相对照）
        executionTraceRecorder = com.lirica.android.automation.trace.ExecutionTraceRecorder(
            sink = { trace ->
                LiricaLog.i(
                    "ExecutionTrace",
                    "task=" + trace.taskId + " state=" + trace.finalState +
                        " steps=" + trace.stepCount + " dur=" + trace.durationMs + "ms" +
                        " vlm=" + trace.vlmCallCount + " heal=" + trace.selfHealAttempts +
                        " ineffective=" + trace.ineffectiveStepCount + " mode=" + trace.mode
                )
            }
        )

        // 经验轨迹库（M5.3）：单机自学，绝不上云
        trajectoryMemory = com.lirica.android.automation.macro.RoomTrajectoryMemory(
            dao = database.actionMacroDao()
        )

        // 用户抢占熔断器（§6.2）：默认不自动恢复（R4 裁决：用户停手 != 用户同意继续）
        userInterruptionDetector = com.lirica.android.automation.verify.UserInterruptionDetector()

        // 无障碍保活健康监视器（§7.1）
        accessibilityHealthMonitor = com.lirica.android.perception.AccessibilityHealthMonitor()

        // 经验轨迹库维护（M5.3 §5.2 淘汰纪律）：
        // 版本失效已在查询层硬保证，这里补上"已弃用行清理 + 容量治理"，
        // 否则弃用记录只增不减，宏库会随使用时长无声膨胀。
        appScope.launch {
            runCatching {
                val purged = trajectoryMemory.purgeDeprecated()
                val trimmed = trajectoryMemory.enforceCapacity(
                    com.lirica.android.automation.macro.RoomTrajectoryMemory.DEFAULT_MAX_ACTIVE_MACROS
                )
                if (purged > 0 || trimmed > 0) {
                    LiricaLog.i("LiricaApplication", "经验轨迹库维护完成：清理弃用 " + purged + " 条、容量淘汰 " + trimmed + " 条")
                }
            }.onFailure { LiricaLog.w("LiricaApplication", "经验轨迹库维护失败", it) }
        }

        // 抢占主通道接线：无障碍物理触控事件。可达性待真机验证（R4），
        // 因此这里只是"尽力而为"，前台突变与手势异常两条降级通道由闭环内部兜底。
        appScope.launch {
            com.lirica.android.perception.LiricaAccessibilityService.physicalTouchEvents.collect { atMs ->
                if (userInterruptionDetector.onPhysicalTouch(atMs)) {
                    LiricaLog.i("UserInterruption", "检测到人工触控，自动化暂停派发")
                }
            }
        }
        appScope.launch {
            com.lirica.android.perception.LiricaAccessibilityService.serviceDisconnectEvents.collect { reason ->
                LiricaLog.w("AccessibilityHealth", "无障碍服务断开（" + reason + "），需引导用户重开")
                runCatching { accessibilityHealthMonitor.evaluate(this@LiricaApplication) }
            }
        }

        // 双采样抗噪指纹服务（§5.1.4）：采样器直接复用执行态高保真树
        val fingerprintService = com.lirica.android.automation.verify.StateFingerprintService(
            sampler = sampler@{
                val service = AccessibilityServiceHolder.get() ?: return@sampler null
                val root = service.rootInActiveWindow ?: return@sampler null
                val pkg = root.packageName?.toString().orEmpty()
                val dump = treeExtractor.dumpTree(
                    rootNode = root,
                    screenWidth = screenWidthProvider(),
                    screenHeight = screenHeightProvider(),
                    packageName = pkg
                )
                if (dump is com.lirica.android.automation.grounding.TreeDumpResult.Ok) {
                    com.lirica.android.automation.verify.PageSample(
                        packageName = pkg,
                        activityName = dump.activityName.orEmpty(),
                        nodes = dump.nodes.toViewSignatures()
                    )
                } else {
                    null
                }
            }
        )

        // 物理资源闸门（§7.3）：温度/电量熔断视觉重通道；同时供观测层与执行层共用
        val resourceGuard = com.lirica.android.automation.policy.PhysicalResourceGuard(
            stateProvider = { hardwareEnvironmentWatcher.hardwareState.value }
        )

        // 多级观测构建器（§5.1.1）：
        // - L1 本地 OCR 接既有 ML Kit 检测器（端侧、无网络、无云成本）；
        // - L2 云端 VLM 暂不接线（需要独立的多模态模型配置，属后续迭代）；
        // - 两级都被视觉闸门管辖：熔断期间只走 L0 语义树。
        val observationBuilder = com.lirica.android.automation.observe.ObservationBuilder(
            treeExtractor = treeExtractor,
            rootNodeProvider = rootNodeProvider,
            screenWidthProvider = screenWidthProvider,
            screenHeightProvider = screenHeightProvider,
            packageProvider = currentPackageProvider,
            localOcrProvider = ocr@{
                val frame = screenCaptureManager.captureScreen() ?: return@ocr emptyList()
                runCatching {
                    visualDetector.detectAll(frame.bytes).mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
                }.getOrDefault(emptyList())
            },
            visionAllowedProvider = { resourceGuard.isVisionAllowed() }
        )

        // 单步决策器（§5.1.2）：LLM 只输出一个 JSON 动作，且只能用 refId 指代目标
        val stepPlanner = com.lirica.android.automation.coordinator.LlmDynamicStepPlanner(
            chatInvoker = { prompt ->
                llmClient.chat(
                    config = backgroundLlmConfig,
                    messages = listOf(
                        com.lirica.android.network.model.ChatMessage(role = "user", content = prompt)
                    )
                )
            },
            usageSink = { usage ->
                LiricaLog.d(
                    "DynamicPlanner",
                    "token 估算 prompt=" + usage.promptTokens + " completion=" + usage.completionTokens
                )
            }
        )

        // 执行器延迟绑定：解开"闭环需要协调器、协调器需要闭环"的构造环
        val executorBridge = com.lirica.android.automation.coordinator.AtomicActionExecutorBridge()

        // 共享取消信号：用户喊停时必须让**闭环立刻停止派发**，
        // 而不是只把状态显示改成"已取消"却继续把手势打完
        val taskCancelSignal = java.util.concurrent.atomic.AtomicBoolean(false)

        val autonomousLoop = com.lirica.android.automation.coordinator.AutonomousActionLoop(
            observer = observationBuilder,
            planner = stepPlanner,
            executor = executorBridge,
            fingerprintService = fingerprintService,
            interruptionDetector = userInterruptionDetector,
            watchdog = watchdog,
            traceRecorder = executionTraceRecorder,
            trajectoryMemory = trajectoryMemory,
            policyEngine = policyEngine,
            resourceGuard = resourceGuard,
            currentPackageProvider = currentPackageProvider,
            cancellationCheck = { taskCancelSignal.get() }
        )

        helixCoordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            screenWidthProvider = screenWidthProvider,
            screenHeightProvider = screenHeightProvider,
            screenCaptureManager = screenCaptureManager,
            executionBackend = hybridExecutionManager,
            // M5.1：统一安全裁决引擎注入执行层（未注入 = 双态模式与漫游只读整体失效）
            safetyPolicy = policyEngine,
            // M5.2：单步动态闭环（execute_macro 只给目标时自动升级为 ReAct 闭环）
            autonomousLoop = autonomousLoop,
            // 与闭环共用同一个取消信号（用户喊停 → 两条路径同时停）
            cancelSignal = taskCancelSignal,
            // §7.1 第 2 条：任务分发前探测无障碍可用性，失效时给出引导话术而非静默失败
            accessibilityReadyProvider = {
                accessibilityHealthMonitor.evaluate(this)
                accessibilityHealthMonitor.canDispatchAutomation()
            },
            // §7.3：视觉重通道闸门（温度/电量熔断期间禁止截屏接地）
            visionAllowedProvider = { resourceGuard.isVisionAllowed() }
        )
        // 闭环内的每一步原子动作都回流到协调器：静态序列与动态闭环共用同一落地通道，
        // 因此安全闸门/自愈/具身反馈三者对两条路径完全同源
        executorBridge.bind(helixCoordinator as com.lirica.android.automation.coordinator.AtomicActionExecutor)

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
        hardwareEnvironmentWatcher = hardwareWatcher

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
