package com.xiaoxi.android.perception

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.xiaoxi.android.perception.model.LightweightAccessibilityEvent
import com.xiaoxi.android.perception.model.PageContentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
 * 架构规范严守：
 * 1. Binder 线程阻断延迟 <= 0.1ms：浅拷贝入队即走，绝不在 Binder 线程遍历节点树
 * 2. L0 SPSC 环形队列无锁解耦 (AccessibilityRingBuffer)
 * 3. 树深度严格限高 (depth <= 4)、字符限长截断 (NodeExtractor)
 * 4. Fastbot 状态指纹计算与防抖动过滤 (FastbotStateHasher)
 * 5. 严格只读隔离：绝不调用任何 performAction()
 */
class XiaoxiAccessibilityService : AccessibilityService() {

    companion object {
        const val NOTIFICATION_TIMEOUT_MS: Long = 150L
        const val IS_ACCESSIBILITY_TOOL: Boolean = false
        const val CAN_PERFORM_GESTURES: Boolean = true
        const val CAN_RETRIEVE_WINDOW_CONTENT: Boolean = true

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

        /**
         * 检查用户是否已在系统设置中开启本无障碍服务
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent = ComponentName(context, XiaoxiAccessibilityService::class.java).flattenToString()
            val expectedShortComponent = ComponentName(context, XiaoxiAccessibilityService::class.java).flattenToShortString()
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
    private val nodeExtractor = NodeExtractor()

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var lastFingerprint = 0L

    public override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        startWorker()
    }

    internal fun onServiceConnectedForTest() {
        isServiceConnected = true
        startWorker()
    }

    override fun onDestroy() {
        isServiceConnected = false
        serviceJob.cancel()
        super.onDestroy()
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

        // 仅抓取核心轻量元组，绝不在 Binder 线程取 root 节点或遍历树
        val lightEvent = LightweightAccessibilityEvent(
            eventType = event.eventType,
            packageName = pkg,
            className = event.className?.toString(),
            eventTime = event.eventTime
        )

        ringBuffer.offer(lightEvent)
    }

    private fun startWorker() {
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
        }

        serviceScope.launch {
            while (isActive) {
                val event = ringBuffer.poll()
                if (event == null) {
                    delay(50L) // 队列为空时让出 CPU，避免死循环空转
                    continue
                }

                processBackgroundEvent(event)
            }
        }
    }

    private fun processBackgroundEvent(event: LightweightAccessibilityEvent) {
        // L0 快速拦截：输入法与状态栏微小变更过滤
        val pkg = event.packageName
        if (pkg.startsWith("com.google.android.inputmethod") ||
            pkg.startsWith("com.sohu.inputmethod") ||
            pkg.startsWith("com.baidu.input")
        ) {
            return
        }

        val interestingEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (event.eventType and interestingEvents == 0) {
            return
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

            _latestPageContent.value = snapshot
            _pageContentEvents.tryEmit(snapshot)
        } catch (_: Exception) {
            // 防御性保护，避免极端情况底层 IPC 异常影响常驻服务
        }
    }
}
