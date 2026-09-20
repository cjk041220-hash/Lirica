package com.xiaoxi.android.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.xiaoxi.android.XiaoxiApplication
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.overlay.ui.OverlayBubble

/**
 * 跨应用常驻伴生悬浮窗服务 (Companion Overlay Service)
 *
 * 继承 LifecycleService，遵循 WindowManager 挂载 Compose 的铁律：
 * 1. 显式配置 ViewCompositionStrategy.DisposeOnDetachedFromWindow
 * 2. 注入 ViewTreeLifecycleOwner
 * 3. 注入 ViewTreeSavedStateRegistryOwner 与 ViewTreeViewModelStoreOwner
 * 4. 健壮防范权限缺失与内存泄露
 */
class CompanionOverlayService : LifecycleService() {

    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val serviceViewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
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
            width = bubbleSizePx,
            height = bubbleSizePx
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

            setContent {
                val moodState = emotionEngine?.moodFlow?.collectAsState()
                val currentMood = moodState?.value ?: MoodState.CALM

                OverlayBubble(
                    mood = currentMood,
                    onClick = {
                        onOverlayClicked()
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

        try {
            wm.addView(composeView, params)
            overlayComposeView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay ComposeView to WindowManager", e)
        }
    }

    private fun updatePosition(deltaX: Float, deltaY: Float) {
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = layoutParams ?: return

        params.x = (params.x + deltaX.toInt())
        params.y = (params.y + deltaY.toInt())

        val displayMetrics = resources.displayMetrics
        val clamped = OverlayPositionCalculator.clampToBounds(
            x = params.x,
            y = params.y,
            bubbleWidth = params.width,
            bubbleHeight = params.height,
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
        val targetX = OverlayPositionCalculator.calculateSnapTargetX(
            currentX = params.x,
            bubbleWidth = params.width,
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

    private fun onOverlayClicked() {
        Log.d(TAG, "Overlay companion clicked")
        // Task 2.3 将在此唤起 OverlayExpandPanel
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
            width: Int,
            height: Int
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
