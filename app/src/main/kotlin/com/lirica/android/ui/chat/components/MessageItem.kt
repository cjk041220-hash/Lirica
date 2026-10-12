package com.lirica.android.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lirica.android.ui.chat.ChatMessageItem
import com.lirica.android.ui.chat.MessageSender
import com.lirica.android.ui.theme.LiricaColors

/**
 * 暮夜星紫对话气泡体系 (Atmospheric Soft Capsule)
 *
 * 抛弃生硬方框与老旧 emoji 头像：
 * 她的消息采用融边微光气泡 + 20dp 柔和圆角 + 左侧呼吸边框；
 * 用户的消息采用深紫灰收拢胶囊；
 * 记忆召回以温润时光回响印记呈现。
 */
@Composable
fun MessageItem(
    message: ChatMessageItem,
    modifier: Modifier = Modifier,
    onMemoryBadgeClick: () -> Unit = {}
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            if (!isUser && message.text.isEmpty()) {
                // 正在打字指示器 (非流式真人感：三点呼吸弹跳)
                TypingIndicatorCapsule()
            } else {
                Surface(
                    shape = if (isUser) {
                        RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
                    } else {
                        RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
                    },
                    color = if (isUser) {
                        LiricaColors.BubbleUser
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier
                        .then(
                            if (!isUser) {
                                Modifier
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                LiricaColors.BubbleHerGlow,
                                                Color(0xFF1B1828).copy(alpha = 0.55f)
                                            )
                                        ),
                                        shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = LiricaColors.BubbleHerBorder,
                                        shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
                                    )
                            } else {
                                Modifier.border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
                                )
                            }
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)
                    ) {
                        FormattedMessageText(
                            text = message.text,
                            isUser = isUser
                        )
                    }
                }
            }

            // 被记得的证据 (时光回响印记)
            if (!isUser && message.memoryBadge != null && !message.memoryBadge.isEmpty) {
                Spacer(modifier = Modifier.height(6.dp))
                MemoryBadge(
                    badgeInfo = message.memoryBadge,
                    onClick = onMemoryBadgeClick
                )
            }
        }
    }
}

/**
 * 真人打字呼吸指示器 (三点微波弹动)
 */
@Composable
fun TypingIndicatorCapsule(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "TypingBounce")

    val dot1Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot1"
    )

    val dot2Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot2"
    )

    val dot3Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, delayMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Dot3"
    )

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = Color(0xFF1B1828).copy(alpha = 0.85f),
        modifier = modifier
            .border(1.dp, LiricaColors.BubbleHerBorder, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .offset(y = dot1Offset.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(LiricaColors.VioletLight)
            )
            Box(
                modifier = Modifier
                    .offset(y = dot2Offset.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(LiricaColors.VioletCore)
            )
            Box(
                modifier = Modifier
                    .offset(y = dot3Offset.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(LiricaColors.VioletDeep)
            )
        }
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
            text = "正在打字...",
            style = MaterialTheme.typography.bodyMedium,
            color = LiricaColors.TextDim
        )
        return
    }

    val annotated = buildAnnotatedString {
        var cursor = 0
        val regex = Regex("(\\*\\*(.*?)\\*\\*)|(`(.*?)`)")
        val matches = regex.findAll(text)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            val boldContent = match.groups[2]?.value
            val codeContent = match.groups[4]?.value

            if (boldContent != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (isUser) Color.White else LiricaColors.VioletLight)) {
                    append(boldContent)
                }
            } else if (codeContent != null) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Black.copy(alpha = 0.25f),
                        color = LiricaColors.VioletLight
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
        style = MaterialTheme.typography.bodyMedium.copy(
            color = if (isUser) LiricaColors.TextMain else LiricaColors.TextMain,
            lineHeight = 24.sp,
            fontSize = 15.sp
        ),
        modifier = modifier
    )
}
