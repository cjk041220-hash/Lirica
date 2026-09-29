package com.lirica.android.overlay

/**
 * 悬浮窗位置计算与贴边阻尼数学算法类
 *
 * 100% 纯 Kotlin 实现，无 Android View 视图或 JNI 强依赖。
 * 负责处理悬浮伴生体的屏幕吸附计算、物理边界安全钳制及拖拽越界弹性阻尼模拟。
 */
object OverlayPositionCalculator {

    /**
     * 计算用户松手时悬浮窗吸附到最近屏幕边缘（左侧或右侧）的目标水平坐标 X
     *
     * @param currentX 当前左上角 X 坐标
     * @param bubbleWidth 伴生体宽度
     * @param screenWidth 屏幕物理或工作区总宽度
     * @param edgeMargin 停靠时距离屏幕边缘的边距像素
     * @return 目标吸附 X 坐标
     */
    fun calculateSnapTargetX(
        currentX: Int,
        bubbleWidth: Int,
        screenWidth: Int,
        edgeMargin: Int = 0
    ): Int {
        val centerX = currentX + bubbleWidth / 2
        val screenCenterX = screenWidth / 2

        return if (centerX < screenCenterX) {
            edgeMargin
        } else {
            (screenWidth - bubbleWidth - edgeMargin).coerceAtLeast(edgeMargin)
        }
    }

    /**
     * 将悬浮窗左上角坐标安全钳制在合法可见屏幕区域内
     *
     * @param x 期望 X 坐标
     * @param y 期望 Y 坐标
     * @param bubbleWidth 伴生体宽度
     * @param bubbleHeight 伴生体高度
     * @param screenWidth 屏幕总宽度
     * @param screenHeight 屏幕总高度
     * @param topInset 顶部状态栏或防遮挡安全区高度
     * @param bottomInset 底部手势横条或导航栏高度
     * @return 钳制后的安全 (x, y) 坐标对
     */
    fun clampToBounds(
        x: Int,
        y: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        topInset: Int = 0,
        bottomInset: Int = 0
    ): Pair<Int, Int> {
        val minX = 0
        val maxX = (screenWidth - bubbleWidth).coerceAtLeast(0)

        val minY = topInset
        val maxY = (screenHeight - bubbleHeight - bottomInset).coerceAtLeast(topInset)

        val clampedX = x.coerceIn(minX, maxX)
        val clampedY = y.coerceIn(minY, maxY)

        return Pair(clampedX, clampedY)
    }

    /**
     * 计算拖拽越界时的减速阻尼位置（橡皮筋弹性算法）
     *
     * @param draggedOffset 拖拽手指期望到达的绝对偏移
     * @param minBound 最小允许合法边界
     * @param maxBound 最大允许合法边界
     * @param dampingRatio 越界阻尼衰减系数（0.0 ~ 1.0，推荐 0.35f）
     * @return 施加阻尼衰减后的物理平滑坐标
     */
    fun calculateDampedPosition(
        draggedOffset: Float,
        minBound: Float,
        maxBound: Float,
        dampingRatio: Float = 0.35f
    ): Float {
        return when {
            draggedOffset < minBound -> {
                minBound + (draggedOffset - minBound) * dampingRatio
            }
            draggedOffset > maxBound -> {
                maxBound + (draggedOffset - maxBound) * dampingRatio
            }
            else -> draggedOffset
        }
    }
}
