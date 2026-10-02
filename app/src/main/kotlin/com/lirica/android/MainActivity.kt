package com.lirica.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.common.settings.ByokSettingsRepository
import com.lirica.android.database.memory.MemoryExtractor
import com.lirica.android.service.KeepAliveService
import com.lirica.android.ui.chat.ChatScreen
import com.lirica.android.ui.chat.ChatViewModel
import com.lirica.android.ui.onboarding.OnboardingScreen
import com.lirica.android.ui.settings.SettingsScreen

enum class AppNavScreen {
    ONBOARDING,
    CHAT,
    SETTINGS
}

/** 通知权限申请状态持久化：区分"从未问过"与"永久拒绝" */
private const val PREFS_PERMISSION_STATE = "lirica_permission_state"
private const val KEY_NOTIF_ASKED = "post_notifications_asked"

class MainActivity : ComponentActivity() {

    /**
     * 2026-09-27 审计 P0 修复：POST_NOTIFICATIONS 运行时申请。
     * Android 13+ 通知默认拒绝，仅 Manifest 声明不足以让主动性气泡/日程提醒可见。
     * 2026-10-02 补充：区分"从未问过 / 拒绝过但可再问 / 永久拒绝"三态——
     * 永久拒绝时引导跳系统通知设置，否则 L3 通知通道将无限期静默且用户无从得知。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                LiricaLog.w(
                    "MainActivity",
                    "通知权限被拒绝：主动性气泡与日程提醒将静默（可在系统设置中开启）"
                )
            }
        }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return

            val prefs = getSharedPreferences(PREFS_PERMISSION_STATE, Context.MODE_PRIVATE)
            val hasAskedBefore = prefs.getBoolean(KEY_NOTIF_ASKED, false)
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            when {
                // 首次：申请并记录，避免"永久拒绝"与"从未问过"无法区分
                !hasAskedBefore -> {
                    prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // 拒绝过但系统仍允许弹窗：再问一次
                canAskAgain -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // 永久拒绝：不再骚扰，直接给一条去系统设置的快捷通道
                else -> runCatching {
                    startActivity(
                        android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }.onFailure { LiricaLog.w("MainActivity", "跳转系统通知设置失败", it) }
            }
        }
    }

    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as LiricaApplication
                // 生产装配契约：ChatViewModel 的可空参数仅测试豁免，
                // 本装配点必须全量显式注入；关键依赖缺失立即失败而非静默关闭能力。
                requireNotNull(app.byokSettingsRepository) { "生产装配必须提供 byokSettingsRepository" }
                requireNotNull(app.nativeToolAgent) { "生产装配必须提供 nativeToolAgent" }
                requireNotNull(app.careHintDetector) { "生产装配必须提供 careHintDetector" }
                return ChatViewModel(
                    llmClient = app.llmClient,
                    memoryManager = app.memoryManager,
                    emotionEngine = app.emotionEngine,
                    phiMiddleware = app.phiMiddleware,
                    humannessFilter = app.humannessFilter,
                    chatDialogueDao = app.database.chatDialogueDao(),
                    // 全应用唯一实例：节流水位线与开环写入口径统一（见 LiricaApplication.memoryExtractor）
                    memoryExtractor = app.memoryExtractor,
                    toolAgent = app.nativeToolAgent,
                    careHintDetector = app.careHintDetector,
                    scheduledAlarmDao = app.database.scheduledAlarmDao(),
                    handoffManager = app.handoffManager,
                    byokRepository = app.byokSettingsRepository,
                    perceptionPipeline = app.perceptionPipeline,
                    proactiveCoordinator = app.proactiveCoordinator
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2026-09-27 审计 P1 修复：targetSdk 35 强制 edge-to-edge，内容会被压入
        // 状态栏/导航栏下方；显式启用并给根布局加 safeDrawing 内边距，
        // 顶栏与输入框不再被系统栏遮挡（真机表现待 API 35 设备截图验收）
        enableEdgeToEdge()

        val app = application as LiricaApplication
        ensureNotificationPermission()

        val prefs = getSharedPreferences(ByokSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val isOnboardingCompleted = prefs.getBoolean("byok_onboarding_completed", false)
        val hasKey = app.byokSettingsRepository.settings.value.hasValidKey

        val startScreen = if (!isOnboardingCompleted && !hasKey) {
            AppNavScreen.ONBOARDING
        } else {
            AppNavScreen.CHAT
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // edge-to-edge 下避开系统栏、刘海与输入法遮挡
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    var currentScreen by remember { mutableStateOf(startScreen) }

                    when (currentScreen) {
                        AppNavScreen.ONBOARDING -> {
                            OnboardingScreen(
                                byokRepository = app.byokSettingsRepository,
                                onFinish = {
                                    prefs.edit().putBoolean("byok_onboarding_completed", true).apply()
                                    // 首启引导完成即申请通知权限：此刻用户信任度最高
                                    ensureNotificationPermission()
                                    if (Settings.canDrawOverlays(this@MainActivity)) {
                                        KeepAliveService.start(this@MainActivity)
                                    }
                                    currentScreen = AppNavScreen.CHAT
                                }
                            )
                        }
                        AppNavScreen.CHAT -> {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onOpenSettings = {
                                    currentScreen = AppNavScreen.SETTINGS
                                }
                            )
                        }
                        AppNavScreen.SETTINGS -> {
                            BackHandler {
                                currentScreen = AppNavScreen.CHAT
                            }
                            SettingsScreen(
                                byokRepository = app.byokSettingsRepository,
                                onBack = {
                                    currentScreen = AppNavScreen.CHAT
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(ByokSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val isOnboardingCompleted = prefs.getBoolean("byok_onboarding_completed", false)
        val app = application as? LiricaApplication
        val hasKey = app?.byokSettingsRepository?.settings?.value?.hasValidKey ?: false
        if ((isOnboardingCompleted || hasKey) && Settings.canDrawOverlays(this)) {
            KeepAliveService.start(this)
        }
    }
}
