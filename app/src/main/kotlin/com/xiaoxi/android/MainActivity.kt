package com.xiaoxi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaoxi.android.database.memory.MemoryExtractor
import com.xiaoxi.android.ui.chat.ChatScreen
import com.xiaoxi.android.ui.chat.ChatViewModel

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
                    )
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel = chatViewModel)
                }
            }
        }
    }
}
