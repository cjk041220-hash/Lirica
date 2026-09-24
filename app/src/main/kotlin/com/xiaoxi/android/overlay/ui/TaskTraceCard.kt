package com.xiaoxi.android.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxi.android.automation.coordinator.ExecutionStepItem
import com.xiaoxi.android.automation.coordinator.StepExecutionStatus

/**
 * 任务代行实时步骤流水卡 (Step-by-step Trace Card - 形态二)
 *
 * 展示当前长程任务代行的实时步骤状态机流水，支持一键中止任务。
 */
@Composable
fun TaskTraceCard(
    isVisible: Boolean,
    goal: String,
    currentStepIndex: Int,
    totalSteps: Int,
    steps: List<ExecutionStepItem>,
    modifier: Modifier = Modifier,
    onCancelClick: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x337C4DFF), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF01E1B2E),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // 顶部标题与目标
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF7C4DFF))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lirica 正在代行操作",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "$currentStepIndex/$totalSteps",
                        color = Color(0xFFB388FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (goal.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = goal,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 步骤列表
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    steps.forEach { step ->
                        StepItemRow(step = step)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 底部中止按钮
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF5252)
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0x66FF5252))
                    )
                ) {
                    Text(
                        text = "✕ 中止代行",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StepItemRow(step: ExecutionStepItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (step.status) {
            StepExecutionStatus.COMPLETED -> {
                Text(
                    text = "✓",
                    color = Color(0xFF00E676),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            StepExecutionStatus.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = Color(0xFF7C4DFF)
                )
            }
            StepExecutionStatus.PENDING -> {
                Text(
                    text = "⏳",
                    color = Color(0xFF9E9E9E),
                    fontSize = 10.sp
                )
            }
            StepExecutionStatus.FAILED -> {
                Text(
                    text = "✕",
                    color = Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        val statusText = when (step.status) {
            StepExecutionStatus.COMPLETED -> "[完成]"
            StepExecutionStatus.RUNNING -> "[进行]"
            StepExecutionStatus.PENDING -> "[等待]"
            StepExecutionStatus.FAILED -> "[失败]"
        }

        val textColor = when (step.status) {
            StepExecutionStatus.COMPLETED -> Color(0xFFB0BEC5)
            StepExecutionStatus.RUNNING -> Color.White
            StepExecutionStatus.PENDING -> Color(0xFF78909C)
            StepExecutionStatus.FAILED -> Color(0xFFFF8A80)
        }

        Text(
            text = "${step.index}. $statusText ${step.description}",
            color = textColor,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
