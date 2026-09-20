package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.persona.model.CharacterCard

enum class MessageSender {
    USER,
    ASSISTANT
}

data class MemoryBadgeInfo(
    val userMemories: List<String> = emptyList(),
    val selfExperiences: List<String> = emptyList(),
    val thoughts: List<String> = emptyList()
) {
    val totalCount: Int get() = userMemories.size + selfExperiences.size + thoughts.size
    val isEmpty: Boolean get() = totalCount == 0
}

data class ChatMessageItem(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestamp: Long,
    val memoryBadge: MemoryBadgeInfo? = null,
    val mood: MoodState? = null
)

data class ChatUiState(
    val messages: List<ChatMessageItem> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val currentMood: MoodState = MoodState.CALM,
    val currentEmotionVector: EmotionVector = MoodState.CALM.defaultVector,
    val activeCharacterCard: CharacterCard? = null,
    val isCharacterCardDialogOpen: Boolean = false,
    val isMoodDebugPanelVisible: Boolean = false,
    val errorMessage: String? = null
)
