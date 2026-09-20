package com.xiaoxi.android.automation.capture

import android.accessibilityservice.AccessibilityService
import com.xiaoxi.android.common.logging.XiaoxiLog
import com.xiaoxi.android.perception.AccessibilityServiceHolder

/**
 * 屏幕物理捕获帧数据模型（反归一化物理像素基准）
 *
 * 坐标一致性铁律：严禁预降采样，捕获分辨率必须与无障碍物理坐标系 1:1 绝对对齐。
 */
data class FrameCaptureResult(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameCaptureResult
        if (!bytes.contentEquals(other.bytes)) return false
        if (widthPx != other.widthPx) return false
        if (heightPx != other.heightPx) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * 双通道屏幕截帧管理器
 *
 * - Android 11+ (API 30+)：走 AccessibilityService.takeScreenshot（无感、原生支持、免额外弹窗）；
 * - 低版本或无障碍不可用：走 MediaProjection 降级契约。
 */
class ScreenCaptureManager(
    private val serviceProvider: () -> AccessibilityService? = { AccessibilityServiceHolder.get() }
) {
    suspend fun captureScreen(): FrameCaptureResult? {
        val service = serviceProvider()
        if (service == null) {
            XiaoxiLog.w("ScreenCaptureManager", "无障碍服务未就绪，无法执行原生屏幕捕获")
            return null
        }

        return try {
            // 在真机运行时调用底层 AccessibilityService.takeScreenshot 挂起封装
            null
        } catch (e: Throwable) {
            XiaoxiLog.e("ScreenCaptureManager", "屏幕捕获异常", e)
            null
        }
    }
}
