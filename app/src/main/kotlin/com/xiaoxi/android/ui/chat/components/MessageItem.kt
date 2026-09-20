package com.xiaoxi.android.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.ui.chat.ChatMessageItem
import com.xiaoxi.android.ui.chat.MessageSender

@Composable
fun MessageItem(
    message: ChatMessageItem,
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            MoodAvatar(mood = message.mood)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Surface(
                shape = if (isUser) {
                    RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
                } else {
                    RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
                },
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    FormattedMessageText(
                        text = message.text,
                        isUser = isUser
                    )
                }
            }

            if (!isUser && message.memoryBadge != null && !message.memoryBadge.isEmpty) {
                Spacer(modifier = Modifier.height(4.dp))
                MemoryBadge(badgeInfo = message.memoryBadge)
            }
        }
    }
}

@Composable
private fun MoodAvatar(
    mood: MoodState?,
    modifier: Modifier = Modifier
) {
    val iconChar = when (mood) {
        MoodState.HAPPY -> "😊"
        MoodState.EXCITED -> "🤩"
        MoodState.LIVELY -> "✨"
        MoodState.CURIOUS -> "🧐"
        MoodState.EXPECTANT -> "🌟"
        MoodState.CONTENT -> "🥰"
        MoodState.DAZE -> "😶"
        MoodState.LAZY -> "🦥"
        MoodState.SLEEPY -> "🥱"
        MoodState.CONCERNED -> "🥺"
        MoodState.SULKING -> "😤"
        MoodState.CONFUSED -> "❓"
        MoodState.CALM -> "🌿"
        MoodState.RELIEVED -> "😌"
        MoodState.RELAXED -> "☕"
        MoodState.MISSING -> "💭"
        null -> "🐬"
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = iconChar,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun FormattedMessageText(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    if (text.isEmpty()) {
        Text(
            text = "正在思考中...",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isUser) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
        )
        return
    }

    val annotated = buildAnnotatedString {
        var cursor = 0
        // Simple Markdown parser for **bold** and `code`
        val regex = Regex("(\\*\\*(.*?)\\*\\*)|(`(.*?)`)")
        val matches = regex.findAll(text)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            val boldContent = match.groups[2]?.value
            val codeContent = match.groups[4]?.value

            if (boldContent != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldContent)
                }
            } else if (codeContent != null) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = if (isUser) Color.Black.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.15f)
                    )
                ) {
                    append(" $codeContent ")
                }
            }
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        lineHeight = 20.sp
    )
}
