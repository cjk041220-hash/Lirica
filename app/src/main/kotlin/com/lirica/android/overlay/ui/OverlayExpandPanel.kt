package com.lirica.android.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.emotion.model.MoodState

/**
 * 悬浮伴生体半展开与快速交互面板 (Overlay Expand Panel)
 *
 * 紧凑轻量，无需离开当前 App 即可与 Lirica 快速沟通、实时同步 App 对话（展示最近两三句）并支持快捷切换到主程序。
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
    recentMessages: List<ChatDialogueEntity> = emptyList(),
    onInputChanged: (String) -> Unit = {},
    onSendMessage: (String) -> Unit = {},
    onCloseClick: () -> Unit = {},
    onOpenFullAppClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    onDragDelta: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
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
                // 顶部柔和拖拽手柄条（支持按住拖移整个浮窗面板）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragDelta(dragAmount.x, dragAmount.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }

                // 顶部状态栏：头像微表情、当前心情、全屏按钮与关闭（整行亦支持拖拽）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragDelta(dragAmount.x, dragAmount.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() }
                            )
                        }
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

                        // 关闭面板 (增大点击区域至 32dp，更易触控退出)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { onCloseClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.95f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 紧凑迷你同步对话流 (只展示最近两三句，面积可控极低遮挡)
                val displayHistory = recentMessages.takeLast(3)
                if (displayHistory.isNotEmpty() || streamingReply.isNotBlank() || isStreaming) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp, max = 150.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1B1E29))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            displayHistory.forEach { msg ->
                                val isUser = msg.role == ChatDialogueEntity.ROLE_USER
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 240.dp)
                                            .clip(
                                                RoundedCornerShape(
                                                    topStart = 10.dp,
                                                    topEnd = 10.dp,
                                                    bottomStart = if (isUser) 10.dp else 2.dp,
                                                    bottomEnd = if (isUser) 2.dp else 10.dp
                                                )
                                            )
                                            .background(
                                                if (isUser) Color(0xFF313F5B) else Color(0xFF252A37)
                                            )
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = msg.content,
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.95f),
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            // 正在流式打字机生成的当前回复
                            if (isStreaming || (streamingReply.isNotBlank() && displayHistory.none { it.content == streamingReply })) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 240.dp)
                                            .clip(RoundedCornerShape(10.dp, 10.dp, 10.dp, 2.dp))
                                            .background(Color(0xFF252A37))
                                            .padding(horizontal = 9.dp, vertical = 5.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = streamingReply.ifBlank { "Lirica 正在思考..." },
                                                fontSize = 11.sp,
                                                color = Color.White.copy(alpha = 0.95f),
                                                lineHeight = 15.sp
                                            )
                                            if (isStreaming) {
                                                Spacer(modifier = Modifier.height(3.dp))
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(10.dp),
                                                    color = colorA,
                                                    strokeWidth = 1.2.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
