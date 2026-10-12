package com.lirica.android.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lirica.android.automation.feedback.EmbodiedState

/**
 * 悬浮伴生体代行动作胶囊条 (Companion Status Capsule)
 *
 * 紧凑轻量，折叠态常驻小球旁，实时展现当前步数、目标与自愈状态，消灭切屏"黑盒感"。
 */
@Composable
fun CompanionStatusCapsule(
    isVisible: Boolean,
    statusText: String,
    embodiedState: EmbodiedState,
    isRecovering: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onCancelClick: (() -> Unit)? = null
) {
    val (borderColor, icon) = when {
        isRecovering || embodiedState == EmbodiedState.FAILED -> Color(0xFFFF9F1C) to "⚠️"
        embodiedState == EmbodiedState.USER_INTERRUPTED -> Color(0xFFFFAA00) to "⏸️"
        embodiedState == EmbodiedState.SUCCESS -> Color(0xFF06D6A0) to "✅"
        embodiedState == EmbodiedState.THINKING -> Color(0xFFB8C0FF) to "💭"
        else -> Color(0xFF7C4DFF) to "⚡"
    }

    AnimatedVisibility(
        visible = isVisible && statusText.isNotBlank(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.CenterVertically),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.CenterVertically),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF1E222D).copy(alpha = 0.95f),
                            Color(0xFF151821).copy(alpha = 0.95f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = borderColor.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.95f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )

                if (onCancelClick != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { onCancelClick() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "打断 ✕",
                            fontSize = 10.sp,
                            color = Color(0xFFFF9B9B),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 伴随自动话语弹泡 (Action Speech Bubble)
 *
 * 满足"任务完成或卡住阻碍时自动弹出话"的伴随感诉求。
 * 优雅磨砂质感，带提示文案与一键轻触关闭。
 */
@Composable
fun ActionSpeechBubble(
    speech: String?,
    modifier: Modifier = Modifier,
    onDismissClick: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = !speech.isNullOrBlank(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier
    ) {
        if (speech.isNullOrBlank()) return@AnimatedVisibility

        Box(
            modifier = Modifier
                .widthIn(min = 140.dp, max = 260.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF232838).copy(alpha = 0.96f),
                            Color(0xFF181C26).copy(alpha = 0.96f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFF00E5FF))
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onDismissClick() }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = speech,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.96f),
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { onDismissClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
