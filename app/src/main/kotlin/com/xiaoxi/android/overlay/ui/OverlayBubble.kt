package com.xiaoxi.android.overlay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxi.android.emotion.model.MoodState

/**
 * 悬浮小汐伴生体 (Overlay Bubble)
 *
 * 跨应用常驻悬浮伴生体核心视图，承载 16 种 Russell 情绪微表情、光晕呼吸律动与拖拽贴边手势响应。
 */
@Composable
fun OverlayBubble(
    mood: MoodState,
    modifier: Modifier = Modifier,
    isExecuting: Boolean = false,
    onClick: () -> Unit = {},
    onDragDelta: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
) {
    // 16 种情绪的动态光晕主色调映射；若正在代行操作，切换为蓝紫色执行脉冲 (形态一)
    val (rawPrimaryColor, rawSecondaryColor, rawExpression) = getMoodVisualTokens(mood)
    val primaryHaloColor = if (isExecuting) Color(0xFF7C4DFF) else rawPrimaryColor
    val secondaryHaloColor = if (isExecuting) Color(0xFF00E5FF) else rawSecondaryColor
    val microExpression = if (isExecuting) "⚡" else rawExpression

    // 呼吸律动动效：执行中采用 700ms 快速流光律动
    val infiniteTransition = rememberInfiniteTransition(label = "BubbleBreathing")
    val pulseDuration = if (isExecuting) 700 else when (mood) {
        MoodState.EXCITED, MoodState.LIVELY -> 900
        MoodState.HAPPY, MoodState.CURIOUS, MoodState.EXPECTANT -> 1400
        MoodState.SLEEPY, MoodState.LAZY, MoodState.DAZE -> 2600
        else -> 1800
    }

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val animatedColorA by animateColorAsState(targetValue = primaryHaloColor, label = "ColorA")
    val animatedColorB by animateColorAsState(targetValue = secondaryHaloColor, label = "ColorB")

    Box(
        modifier = modifier
            .size(72.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = {
                        onDragEnd()
                    },
                    onDragCancel = {
                        onDragEnd()
                    }
                )
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // 外层情绪光晕 (Breathing Halo)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .shadow(elevation = 8.dp, shape = CircleShape, spotColor = animatedColorA)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedColorA.copy(alpha = 0.55f),
                            animatedColorB.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 伴生体核心圆球
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF222831),
                            Color(0xFF1E1F29)
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(animatedColorA, animatedColorB, animatedColorA)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = microExpression,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mood.displayName,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 16 种 Russell 心情状态对应的光晕色彩与微表情文本标识
 */
internal fun getMoodVisualTokens(mood: MoodState): Triple<Color, Color, String> {
    return when (mood) {
        MoodState.HAPPY -> Triple(Color(0xFFFFD166), Color(0xFFFF9F1C), "✨")
        MoodState.EXCITED -> Triple(Color(0xFFFF6B6B), Color(0xFFFFE66D), "⚡")
        MoodState.LIVELY -> Triple(Color(0xFF06D6A0), Color(0xFF118AB2), "♪")
        MoodState.CURIOUS -> Triple(Color(0xFF4CC9F0), Color(0xFF7209B7), "👀")
        MoodState.EXPECTANT -> Triple(Color(0xFFF72585), Color(0xFFB5179E), "🌟")
        MoodState.CONTENT -> Triple(Color(0xFF83C5BE), Color(0xFF006D77), "☕")
        MoodState.DAZE -> Triple(Color(0xFFB8C0FF), Color(0xFFE7C6FF), "💭")
        MoodState.LAZY -> Triple(Color(0xFFD8BBFF), Color(0xFFA370F7), "🛋️")
        MoodState.SLEEPY -> Triple(Color(0xFF8E9AAF), Color(0xFFCBC0D3), "💤")
        MoodState.CONCERNED -> Triple(Color(0xFFFFAA00), Color(0xFFFF5400), "🥺")
        MoodState.SULKING -> Triple(Color(0xFF7B2CBF), Color(0xFF3C096C), "哼")
        MoodState.CONFUSED -> Triple(Color(0xFF48CAE4), Color(0xFF0096C7), "❓")
        MoodState.CALM -> Triple(Color(0xFF90E0EF), Color(0xFF00B4D8), "🍃")
        MoodState.RELIEVED -> Triple(Color(0xFFA7C957), Color(0xFF6A994E), "🌱")
        MoodState.RELAXED -> Triple(Color(0xFFE9D8A6), Color(0xFFEE9B00), "🌊")
        MoodState.MISSING -> Triple(Color(0xFFFFB4A2), Color(0xFFE5989B), "🌧️")
    }
}
