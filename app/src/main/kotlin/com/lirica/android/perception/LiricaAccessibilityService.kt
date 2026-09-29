package com.lirica.android.perception

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.LightweightAccessibilityEvent
import com.lirica.android.perception.model.PageContentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍前台节点轻量抽取与焦点感知服务
 *
 * 架构规范与感知-执行双模契约严守：
 * 1. Binder 线程阻断延迟 <= 0.1ms：浅拷贝入队即走，绝不在 Binder 线程遍历节点树
 * 2. L0 SPSC 环形队列无锁解耦 (AccessibilityRingBuffer)
 * 3. 树深度严格限高 (depth <= 4)、字符限长截断 (NodeExtractor)
 * 4. Fastbot 状态指纹计算与防抖动过滤 (FastbotStateHasher)
 * 5. 感知-执行双模物理隔离契约：
 *    - 感知主通道：在无障碍事件生命周期内保持 100% 纯只读提取，绝不调用任何 performAction()，彻底避免读写竞争；
 *    - 执行通道：手势与穿透代行由独立的 [AccessibilityServiceHolder] 单例托管实例，在协程调度器下异步触发贝塞尔曲线手势；
 *    - 双重安全守门：所有自动化执行动作前，必须无条件通过 [FinancialGatekeeper] 与 [PrivacyBreaker] 金融隐私双闸门审查。
 */
class LiricaAccessibilityService : AccessibilityService() {

    companion object {
        const val NOTIFICATION_TIMEOUT_MS: Long = 150L
        const val IS_ACCESSIBILITY_TOOL: Boolean = false
        const val CAN_PERFORM_GESTURES: Boolean = true
        const val CAN_RETRIEVE_WINDOW_CONTENT: Boolean = true
        const val CAN_TAKE_SCREENSHOT: Boolean = true

        @Volatile
        var isServiceConnected: Boolean = false
            internal set

        private val _latestPageContent = MutableStateFlow<PageContentSnapshot?>(null)
        val latestPageContent: StateFlow<PageContentSnapshot?> = _latestPageContent.asStateFlow()

        private val _pageContentEvents = MutableSharedFlow<PageContentSnapshot>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val pageContentEvents: SharedFlow<PageContentSnapshot> = _pageContentEvents.asSharedFlow()

        private val _windowForegroundAppEvents = MutableSharedFlow<ForegroundAppInfo>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val windowForegroundAppEvents: SharedFlow<ForegroundAppInfo> = _windowForegroundAppEvents.asSharedFlow()

        /**
         * 检查用户是否已在系统设置中开启本无障碍服务
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent = ComponentName(context, LiricaAccessibilityService::class.java).flattenToString()
            val expectedShortComponent = ComponentName(context, LiricaAccessibilityService::class.java).flattenToShortString()
            return enabledServices.contains(expectedComponent) || enabledServices.contains(expectedShortComponent)
        }

        /**
         * 生成跳转至系统无障碍设置页的 Intent
         */
        fun createSettingsIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    val ringBuffer = AccessibilityRingBuffer<LightweightAccessibilityEvent>(capacity = 128)
    private val eventSignal = Channel<Unit>(Channel.CONFLATED)
    private val nodeExtractor = NodeExtractor()
    private val privacyBreaker = PrivacyBreaker()

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var lastFingerprint = 0L
    private var lastContentChangeTime = 0L
    private var lastContentChangePkg = ""
    private var lastContentChangeClass = ""

    public override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        AccessibilityServiceHolder.register(this)
        startWorker()
    }

    internal fun onServiceConnectedForTest() {
        isServiceConnected = true
        AccessibilityServiceHolder.register(this)
        startWorker()
    }

    override fun onDestroy() {
        isServiceConnected = false
        AccessibilityServiceHolder.unregister()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        AccessibilityServiceHolder.unregister()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // 无障碍服务中断响应
    }

    /**
     * AOSP Binder 核心回调：耗时严控在 <= 0.05ms，入队即走
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 零轮询前台应用秒级感知 (M3.4)：无需跨进程读树，直接在 Binder 回调中极速分发
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isIgnoredPackage(pkg)) {
            _windowForegroundAppEvents.tryEmit(
                ForegroundAppInfo(
                    packageName = pkg,
                    timestamp = System.currentTimeMillis(),
                    dwellDurationMs = 0L
                )
            )
        }

        // 仅抓取核心轻量元组，绝不在 Binder 线程取 root 节点或遍历树
        val lightEvent = LightweightAccessibilityEvent(
            eventType = event.eventType,
            packageName = pkg,
            className = event.className?.toString(),
            eventTime = event.eventTime
        )

        ringBuffer.offer(lightEvent)
        eventSignal.trySend(Unit)
    }

    private fun startWorker() {
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
        }

        serviceScope.launch {
            // L2 优化：改用 Channel 挂起唤醒机制，队列为空时彻底挂起，
            // 避免每秒 20 次 delay(50ms) 轮询唤醒消耗 CPU 与电量
            for (signal in eventSignal) {
                while (isActive) {
                    val event = ringBuffer.poll() ?: break
                    processBackgroundEvent(event)
                }
            }
        }
        eventSignal.trySend(Unit)
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        return pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.sohu.inputmethod") ||
                pkg.startsWith("com.baidu.input")
    }

    private fun processBackgroundEvent(event: LightweightAccessibilityEvent) {
        // L0 快速拦截：输入法与状态栏微小变更过滤
        val pkg = event.packageName
        if (isIgnoredPackage(pkg)) {
            return
        }

        val interestingEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (event.eventType and interestingEvents == 0) {
            return
        }

        // M10 前置事件级轻量去重：对于高频内容变动事件（打字、滚动等），
        // 若同页面在 300ms 内已处理过，直接跳过昂贵的跨进程 root 节点树抽取
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            val className = event.className.orEmpty()
            if (pkg == lastContentChangePkg && className == lastContentChangeClass && (now - lastContentChangeTime) < 300L) {
                return
            }
            lastContentChangePkg = pkg
            lastContentChangeClass = className
            lastContentChangeTime = now
        }

        try {
            val root = rootInActiveWindow ?: return
            val nodes = nodeExtractor.extract(root)
            val activityName = event.className ?: "UnknownWindow"

            // L1 Fastbot 状态指纹防抖动比对
            val fingerprint = FastbotStateHasher.computeFingerprint(activityName, nodes)
            if (fingerprint == lastFingerprint) {
                return // 界面结构与交互项未发生实质突变，直接丢弃
            }
            lastFingerprint = fingerprint

            val focusedNodeText = nodes.firstOrNull { it.isFocused }?.text

            val snapshot = PageContentSnapshot(
                packageName = pkg,
                className = event.className,
                timestamp = System.currentTimeMillis(),
                fingerprint = fingerprint,
                nodes = nodes,
                focusedText = focusedNodeText
            )

            // 出口统一脱敏：敏感 App 整屏清空、密码框覆写、PII 正则脱敏后再发布
            val sanitized = privacyBreaker.sanitizePageSnapshot(snapshot)

            _latestPageContent.value = sanitized
            _pageContentEvents.tryEmit(sanitized)
        } catch (_: Exception) {
            // 防御性保护，避免极端情况底层 IPC 异常影响常驻服务
        }
    }
}
