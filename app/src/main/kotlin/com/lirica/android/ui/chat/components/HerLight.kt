package com.lirica.android.ui.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lirica.android.ui.theme.LiricaColors

/**
 * HerLight —— 活体流光核与公转星尘 (Dusk Velvet Violet)
 *
 * 不做立绘、不做 3D，而是代码算法绘制的生命感光斑。
 * 具备 3.6s 慢板呼吸律动、微光外晕扩散与环绕公转星尘粒子。
 */
@Composable
fun HerLight(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    isActing: Boolean = false,
    onClick: () -> Unit = {}
) {
    val transition = rememberInfiniteTransition(label = "HerLightPulse")

    // 核心慢板呼吸脉动 (3.6s 德彪西节奏)
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActing) 1200 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CoreScale"
    )

    // 外层弥散光晕扩展
    val haloScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActing) 1200 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HaloScale"
    )

    // 星尘公转角度 (6s 一周)
    val stardustAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "StardustOrbit"
    )

    val coreSize = size * 0.44f
    val haloSize = size * 0.95f
    val orbitRadius = (size.value * 0.42f)

    Box(
        modifier = modifier
            .size(size)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // 1. 外层柔雾弥散光晕 (Halo)
        Box(
            modifier = Modifier
                .size(haloSize)
                .scale(haloScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            LiricaColors.HaloLight,
                            Color.Transparent
                        )
                    )
                )
        )

        // 2. 核心流光体 (Core)
        Box(
            modifier = Modifier
                .size(coreSize)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            LiricaColors.VioletLight,
                            LiricaColors.VioletCore,
                            LiricaColors.VioletDeep
                        )
                    )
                )
        )

        // 3. 绕行公转微光星尘粒子 (Orbiting Stardust)
        val rad = Math.toRadians(stardustAngle.toDouble())
        val offsetX = (orbitRadius * Math.cos(rad)).dp
        val offsetY = (orbitRadius * Math.sin(rad)).dp

        Box(
            modifier = Modifier
                .offset(x = offsetX, y = offsetY)
                .size(size * 0.1f)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
