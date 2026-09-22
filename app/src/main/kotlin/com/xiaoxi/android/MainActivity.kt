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
                    ),
                    // 接通系统工具闭环：toolAgent 缺位时 LLM 拿不到 tools 声明，代行/闹钟/输入等能力全部死路
                    toolAgent = app.nativeToolAgent,
                    // 异常感知关心检测（深夜/久未出现）；缺位时 ChatViewModel 内部自动跳过
                    careHintDetector = app.careHintDetector,
                    // 共享全局交接快照单例，VM 重建后长程会话上下文不丢
                    handoffManager = app.handoffManager
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
