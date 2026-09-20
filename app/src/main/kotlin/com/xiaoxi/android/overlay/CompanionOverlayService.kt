package com.xiaoxi.android.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xiaoxi.android.MainActivity
import com.xiaoxi.android.XiaoxiApplication
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.overlay.ui.IcebreakerBubble
import com.xiaoxi.android.overlay.ui.OverlayBubble
import com.xiaoxi.android.overlay.ui.OverlayExpandPanel
import com.xiaoxi.android.overlay.viewmodel.OverlayExpandViewModel

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        expandViewModel?.refreshIcebreaker()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun initViewModel() {
        val app = application as? XiaoxiApplication ?: return
        expandViewModel = OverlayExpandViewModel(
            unspokenThoughtDao = app.database.unspokenThoughtDao(),
            emotionEngine = app.emotionEngine,
            llmClient = app.llmClient,
            memoryManager = app.memoryManager,
            phiMiddleware = app.phiMiddleware,
            humannessFilter = app.humannessFilter
        )
    }

    private fun setupOverlayView() {
        // 权限判定防御：未授权时安全跳过，防止 SecurityException
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted, skipping overlay mount.")
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

            // 【WindowManager 挂载 Compose 铁律】注入 Owners
            setViewTreeLifecycleOwner(this@CompanionOverlayService)

            val savedStateOwner = ServiceSavedStateRegistryOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)

            val vmStoreOwner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = serviceViewModelStore
            }
            setViewTreeViewModelStoreOwner(vmStoreOwner)

            val app = application as? XiaoxiApplication
            val emotionEngine = app?.emotionEngine
            val vm = expandViewModel

            setContent {
                val moodState by (emotionEngine?.moodFlow?.collectAsState()
                    ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(MoodState.CALM) })
                val currentMood = moodState

                val uiState by (vm?.uiState?.collectAsState()
                    ?: androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            com.xiaoxi.android.overlay.viewmodel.OverlayExpandUiState()
                        )
                    })

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // 1. 顶部未完成念头破冰小气泡 (当未展开且有念头时)
                    if (!uiState.isExpanded) {
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

                    // 2. 半展开轻量快速交互面板
                    OverlayExpandPanel(
                        isExpanded = uiState.isExpanded,
                        mood = currentMood,
                        inputText = uiState.quickInputText,
                        isStreaming = uiState.isStreaming,
                        streamingReply = uiState.streamingReply,
                        errorMessage = uiState.errorMessage,
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
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. 常驻悬浮伴生体小球
                    OverlayBubble(
                        mood = currentMood,
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
            Log.e(TAG, "Failed to attach overlay ComposeView to WindowManager", e)
        }
    }

    /**
     * 协调 WindowManager 是否捕获软键盘焦点
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        val targetFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        if (params.flags != targetFlags) {
            params.flags = targetFlags
            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update focusable flag", e)
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
        val effectiveWidth = if (params.width > 0) params.width else (72 * displayMetrics.density).toInt()
        val effectiveHeight = if (params.height > 0) params.height else (72 * displayMetrics.density).toInt()

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
            Log.e(TAG, "Failed to update overlay view layout", e)
        }
    }

    private fun snapToEdge() {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        val displayMetrics = resources.displayMetrics
        val effectiveWidth = if (params.width > 0) params.width else (72 * displayMetrics.density).toInt()

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
            Log.e(TAG, "Failed to snap overlay to edge", e)
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
                Log.e(TAG, "Error removing overlay view on destroy", e)
            }
        }
        overlayComposeView = null
        serviceViewModelStore.clear()
    }

    companion object {
        private const val TAG = "CompanionOverlayService"

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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
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
