package com.lirica.android.ui.chat

import androidx.compose.runtime.Immutable
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.persona.model.CharacterCard
import kotlinx.serialization.Serializable

enum class MessageSender {
    USER,
    ASSISTANT
}

@Immutable
@Serializable
data class MemoryTraceDetail(
    val id: String = "",
    val content: String,
    val category: String = "其他",
    val createdAt: Long = System.currentTimeMillis()
)

@Immutable
@Serializable
data class MemoryBadgeInfo(
    val userMemories: List<String> = emptyList(),
    val selfExperiences: List<String> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val memoryDetails: List<MemoryTraceDetail> = emptyList()
) {
    val totalCount: Int get() = userMemories.size + selfExperiences.size + thoughts.size
    val isEmpty: Boolean get() = totalCount == 0
}

@Immutable
data class ChatMessageItem(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestamp: Long,
    val memoryBadge: MemoryBadgeInfo? = null,
    val mood: MoodState? = null
)

@Immutable
data class ChatUiState(
    val messages: List<ChatMessageItem> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val currentMood: MoodState = MoodState.CALM,
    val currentEmotionVector: EmotionVector = MoodState.CALM.defaultVector,
    val activeCharacterCard: CharacterCard? = null,
    val isCharacterCardDialogOpen: Boolean = false,
    val isMoodDebugPanelVisible: Boolean = false,
    val isApiKeyConfigured: Boolean = true,
    val isKeyPromptBannerVisible: Boolean = false,
    val errorMessage: String? = null
)
