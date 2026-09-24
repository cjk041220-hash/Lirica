package com.xiaoxi.android.overlay.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxi.android.emotion.model.MoodState

/**
 * 悬浮伴生体半展开与快速交互面板 (Overlay Expand Panel)
 *
 * 紧凑轻量，无需离开当前 App 即可与 Lirica 快速沟通、查看即时回复并支持快捷切换到主程序。
 */
@Composable
fun OverlayExpandPanel(
    isExpanded: Boolean,
    mood: MoodState,
    inputText: String,
    isStreaming: Boolean,
    streamingReply: String,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    onInputChanged: (String) -> Unit = {},
    onSendMessage: (String) -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOpenFullAppClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val (colorA, colorB, microExpression) = getMoodVisualTokens(mood)

    AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 320.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F232F).copy(alpha = 0.98f),
                            Color(0xFF141720).copy(alpha = 0.98f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.sweepGradient(listOf(colorA, colorB, colorA)),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(14.dp)
        ) {
            Column {
                // 顶部状态栏：头像微表情、当前心情、全屏按钮与关闭
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(listOf(colorA.copy(alpha = 0.4f), Color.Transparent))
                                )
                                .border(1.dp, colorA, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = microExpression, fontSize = 14.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Text(
                                text = "Lirica",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "当前心情 · ${mood.displayName}",
                                fontSize = 10.sp,
                                color = colorA
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 跳转全屏主界面
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onOpenFullAppClick() }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "全屏",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 关闭面板
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onCloseClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 对话展示区 (若有流式回复或错误提示)
                if (streamingReply.isNotBlank() || isStreaming) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF262B3A))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            Text(
                                text = streamingReply.ifBlank { "Lirica 正在思考..." },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                lineHeight = 17.sp
                            )

                            if (isStreaming) {
                                Spacer(modifier = Modifier.height(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = colorA,
                                    strokeWidth = 1.5.dp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 底部快速输入条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262A36))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChanged,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                onFocusChanged(focusState.isFocused)
                            },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp
                        ),
                        cursorBrush = SolidColor(colorA),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "和悬浮 Lirica 聊一句...",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.35f)
                                )
                            }
                            innerTextField()
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank() && !isStreaming) colorA else Color.White.copy(alpha = 0.15f)
                            )
                            .clickable(enabled = inputText.isNotBlank() && !isStreaming) {
                                onSendMessage(inputText)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↑",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (inputText.isNotBlank() && !isStreaming) Color.Black else Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}
