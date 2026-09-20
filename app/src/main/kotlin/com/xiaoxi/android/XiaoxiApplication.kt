package com.xiaoxi.android

import android.app.Application
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.perception.PerceptionPipeline
import com.xiaoxi.android.perception.PrivacyBreaker
import com.xiaoxi.android.perception.ScreenStateWatcher
import com.xiaoxi.android.perception.UsageStatsTracker
import com.xiaoxi.android.perception.XiaoxiAccessibilityService
import com.xiaoxi.android.perception.XiaoxiNotificationListenerService
import com.xiaoxi.android.perception.model.ScreenState
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import com.xiaoxi.android.proactivity.AutonomousLifeWorker
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
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /**
     * 后台任务（记忆抽取等）共用的 LLM 配置
     */
    val backgroundLlmConfig: LlmConfig by lazy {
        LlmConfig(apiKey = BuildConfig.DEEPSEEK_API_KEY)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = XiaoxiDatabase.getInstance(this)
        memoryManager = DualTrackMemoryManager(database)
        emotionEngine = EmotionEngine()
        llmClient = XiaoxiLlmClient()
        phiMiddleware = PhiMiddleware()
        humannessFilter = HumannessFilter()

        startPerceptionPipeline()
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
     * 注册闲时自主生活周期任务（幂等，KEEP 策略）。
     * runCatching 兜底：Robolectric 单测环境可能未初始化 WorkManager，不应阻断 Application 启动。
     */
    private fun registerAutonomousLife() {
        runCatching { AutonomousLifeWorker.enqueue(this) }
    }

    companion object {
        lateinit var instance: XiaoxiApplication
            private set
    }
}
