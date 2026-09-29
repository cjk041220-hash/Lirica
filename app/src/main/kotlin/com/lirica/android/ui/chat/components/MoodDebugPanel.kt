package com.lirica.android.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState

@Composable
fun MoodDebugPanel(
    isVisible: Boolean,
    moodState: MoodState,
    vector: EmotionVector,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧬 心智引擎 (Russell 2D 动力学)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${moodState.displayName} [${moodState.spriteTag}]",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Valence (-1.0 ~ 1.0 -> progress 0.0 ~ 1.0)
                DimensionRow(
                    label = "Valence (愉悦度)",
                    value = vector.valence,
                    color = if (vector.valence >= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Arousal (-1.0 ~ 1.0 -> progress 0.0 ~ 1.0)
                DimensionRow(
                    label = "Arousal (唤醒度)",
                    value = vector.arousal,
                    color = if (vector.arousal >= 0) Color(0xFFF59E0B) else Color(0xFF6B7280)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Dominance
                DimensionRow(
                    label = "Dominance (支配度)",
                    value = vector.dominance,
                    color = Color(0xFF8B5CF6)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "即时语气微调: \"${moodState.styleHint}\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun DimensionRow(
    label: String,
    value: Float,
    color: Color
) {
    val normalizedProgress = ((value + 1.0f) / 2.0f).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            modifier = Modifier.width(130.dp)
        )
        LinearProgressIndicator(
            progress = { normalizedProgress },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceDim
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = String.format("%+.2f", value),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
