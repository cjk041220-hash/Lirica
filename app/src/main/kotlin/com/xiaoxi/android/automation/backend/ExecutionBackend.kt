package com.xiaoxi.android.automation.backend

/**
 * 跨通道自动化执行底座契约 (ExecutionBackend)
 *
 * 统一抽象无障碍拟人手势通道与 Shizuku/ADB 提权穿透通道。
 */
interface ExecutionBackend {
    /**
     * 当前执行通道是否就绪可用
     */
    suspend fun isAvailable(): Boolean

    /**
     * 屏幕物理像素绝对坐标点击
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     */
    suspend fun click(x: Float, y: Float): Boolean

    /**
     * 屏幕坐标长按
     *
     * @param x 物理像素 X 坐标
     * @param y 物理像素 Y 坐标
     * @param durationMs 长按持续毫秒数
     */
    suspend fun longClick(x: Float, y: Float, durationMs: Long = 1000L): Boolean

    /**
     * 屏幕滑动操作
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean

    /**
     * 发送系统硬件按键码
     *
     * @param keyCode Android 系统 KeyEvent 按键码
     */
    suspend fun sendKeycode(keyCode: Int): Boolean

    /**
     * 文本内容注入
     */
    suspend fun inputText(text: String): Boolean
}
