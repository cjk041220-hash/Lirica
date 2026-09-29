package com.lirica.android.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可编辑起草-确认门卡片 (Draft-Confirmation Card - 形态三)
 *
 * 针对发送消息、创建闹钟、高危敏感操作，提供屏幕中央轻量可就地微调的草稿卡，
 * 杜绝繁琐口头反复问答与误操作风险。
 */
@Composable
fun DraftConfirmationCard(
    isVisible: Boolean,
    title: String,
    riskDetails: String,
    initialDraftContent: String,
    modifier: Modifier = Modifier,
    confirmButtonText: String = "立即发送",
    onConfirm: (editedDraft: String) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    var draftText by remember(initialDraftContent) { mutableStateOf(initialDraftContent) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = modifier
                .width(300.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.5.dp, Color(0xFF7C4DFF), RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF51A1829),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                // 顶部标题
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📝",
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title.ifBlank { "草稿确认" },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (riskDetails.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = riskDetails,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 可就地编辑草稿内容的输入框
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = Color.White
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C4DFF),
                        unfocusedBorderColor = Color(0x667C4DFF),
                        focusedContainerColor = Color(0x33000000),
                        unfocusedContainerColor = Color(0x33000000)
                    ),
                    placeholder = {
                        Text(
                            text = "草稿内容为空...",
                            color = Color(0xFF78909C),
                            fontSize = 12.sp
                        )
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 操作按钮：取消 vs 确认
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFB0BEC5)
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0x66B0BEC5))
                        )
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = { onConfirm(draftText) },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C4DFF),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = confirmButtonText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
