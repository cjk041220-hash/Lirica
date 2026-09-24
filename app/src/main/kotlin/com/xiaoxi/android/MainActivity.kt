package com.xiaoxi.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaoxi.android.common.settings.ByokSettingsRepository
import com.xiaoxi.android.database.memory.MemoryExtractor
import com.xiaoxi.android.ui.chat.ChatScreen
import com.xiaoxi.android.ui.chat.ChatViewModel
import com.xiaoxi.android.ui.onboarding.OnboardingScreen
import com.xiaoxi.android.ui.settings.SettingsScreen

enum class AppNavScreen {
    ONBOARDING,
    CHAT,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as XiaoxiApplication
                return ChatViewModel(
                    llmClient = app.llmClient,
                    memoryManager = app.memoryManager,
                    emotionEngine = app.emotionEngine,
                    phiMiddleware = app.phiMiddleware,
                    humannessFilter = app.humannessFilter,
                    chatDialogueDao = app.database.chatDialogueDao(),
                    memoryExtractor = MemoryExtractor(
                        llmClient = app.llmClient,
                        userMemoryDao = app.database.userMemoryDao(),
                        llmConfig = app.backgroundLlmConfig
                    ),
                    toolAgent = app.nativeToolAgent,
                    careHintDetector = app.careHintDetector,
                    scheduledAlarmDao = app.database.scheduledAlarmDao(),
                    handoffManager = app.handoffManager,
                    byokRepository = app.byokSettingsRepository
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as XiaoxiApplication
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    var currentScreen by remember { mutableStateOf(startScreen) }

                    when (currentScreen) {
                        AppNavScreen.ONBOARDING -> {
                            OnboardingScreen(
                                byokRepository = app.byokSettingsRepository,
                                onFinish = {
                                    prefs.edit().putBoolean("byok_onboarding_completed", true).apply()
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
}
