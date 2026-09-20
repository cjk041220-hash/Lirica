package com.xiaoxi.android.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.emotion.model.MoodState

/**
 * 未完成念头破冰小气泡 (Icebreaker Bubble)
 *
 * 浮现在伴生体头顶/侧翼，展示小汐内心的碎片念头，点击直接代入对话交流，右上角支持轻触忽略。
 */
@Composable
fun IcebreakerBubble(
    thought: UnspokenThoughtEntity?,
    mood: MoodState,
    modifier: Modifier = Modifier,
    onThoughtClick: (UnspokenThoughtEntity) -> Unit = {},
    onDismissClick: (UnspokenThoughtEntity) -> Unit = {}
) {
    val (colorA, colorB, _) = getMoodVisualTokens(mood)

    AnimatedVisibility(
        visible = thought != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier
    ) {
        if (thought != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(min = 140.dp, max = 220.dp)
                    .padding(bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .shadow(elevation = 6.dp, shape = RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF232734).copy(alpha = 0.95f),
                                    Color(0xFF1B1E28).copy(alpha = 0.95f)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(listOf(colorA, colorB)),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onThoughtClick(thought) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💭 心里惦记着...",
                                fontSize = 10.sp,
                                color = colorA,
                                fontWeight = FontWeight.SemiBold
                            )

                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { onDismissClick(thought) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 9.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = thought.thought,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.92f),
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
