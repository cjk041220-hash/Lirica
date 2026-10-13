package com.lirica.android.automation.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.perception.AccessibilityServiceHolder
import com.lirica.android.perception.PrivacyBreaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

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
 * 核心设计指标：
 * 1. Android 11+ (API 30+)：走 AccessibilityService.takeScreenshot（无感、原生支持、免系统弹窗）；
 * 2. 硬件缓冲安全防泄露：screenshotResult.hardwareBuffer 必须在 finally 块中显式 close() 释放显存；
 * 3. 250ms 频率节流：250ms 窗口内重复调用返回缓存帧，杜绝 GPU / CPU 瞬时风暴；
 * 4. 金融隐私物理熔断：前台为银行/支付类应用时直接断流拒绝截屏（返回 null）；
 * 5. 图像压缩下沉：JPEG 压缩在后台协程安全调度。
 */
class ScreenCaptureManager(
    private val serviceProvider: () -> AccessibilityService? = { AccessibilityServiceHolder.get() },
    private val currentPackageProvider: () -> String? = { AccessibilityServiceHolder.get()?.rootInActiveWindow?.packageName?.toString() },
    private val minIntervalMs: Long = 250L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val captureExecutor: (suspend () -> FrameCaptureResult?)? = null
) {
    @Volatile
    private var lastCapturedFrame: FrameCaptureResult? = null

    @Volatile
    private var lastCaptureTime: Long = 0L

    suspend fun captureScreen(quality: Int = 85): FrameCaptureResult? {
        // 1. 金融与敏感凭据隐私熔断第一闸门
        val pkg = currentPackageProvider()
        if (!pkg.isNullOrBlank() && PrivacyBreaker.isSensitiveApp(pkg)) {
            LiricaLog.w("ScreenCaptureManager", "前台处于金融/敏感应用 ($pkg)，物理断流拦截截屏")
            return null
        }

        // 2. 节流保护：250ms 窗口内复用最近捕获帧
        val now = timeProvider()
        val cached = lastCapturedFrame
        if (cached != null && (now - lastCaptureTime) < minIntervalMs) {
            return cached
        }

        // 3. 测试或外部自定义截帧执行器支持
        if (captureExecutor != null) {
            val result = captureExecutor.invoke()
            if (result != null) {
                lastCapturedFrame = result
                lastCaptureTime = now
            }
            return result
        }

        val service = serviceProvider()
        if (service == null) {
            LiricaLog.w("ScreenCaptureManager", "无障碍服务未就绪，无法执行原生屏幕捕获")
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 架构决策：放弃高侵扰性 MediaProjection 弹窗，采用纯静默 AccessibilityService.takeScreenshot (API 30+)；
            // Android 11 以下设备安全降级为 null，上层接地引擎自动纯走无障碍语义树。
            LiricaLog.w("ScreenCaptureManager", "系统版本低于 Android 11 (API 30)，静默截屏不可用，降级无障碍树模式")
            return null
        }

        return try {
            val frame = captureFromService(service, quality, now)
            if (frame != null) {
                lastCapturedFrame = frame
                lastCaptureTime = now
            }
            frame
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M6.0 P1-7：协程取消原样上抛，不粉饰成"捕获失败返回 null"
            throw e
        } catch (e: Throwable) {
            LiricaLog.e("ScreenCaptureManager", "屏幕捕获异常", e)
            null
        }
    }

    private suspend fun captureFromService(
        service: AccessibilityService,
        quality: Int,
        now: Long
    ): FrameCaptureResult? = suspendCancellableCoroutine { continuation ->
        // M6.0 P1-8：所有 resume 点前必须守 isActive——回调运行在主线程
        // （service.mainExecutor），用户在截屏在飞的 100-300ms 窗口内取消协程时，
        // 迟到的 onSuccess/onFailure 会在已取消的 continuation 上 resume 并抛
        // IllegalStateException 崩溃应用。正确范式见 GestureExecutorImpl。
        val executor = service.mainExecutor
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (bitmap == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return
                        }

                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), stream)
                        val bytes = stream.toByteArray()

                        if (continuation.isActive) {
                            continuation.resume(
                                FrameCaptureResult(
                                    bytes = bytes,
                                    widthPx = bitmap.width,
                                    heightPx = bitmap.height,
                                    timestamp = now
                                )
                            )
                        }
                    } catch (e: Throwable) {
                        LiricaLog.e("ScreenCaptureManager", "位图压缩处理异常", e)
                        if (continuation.isActive) continuation.resume(null)
                    } finally {
                        // 铁律：显式释放底层硬件缓冲，绝不泄露 GPU 显存
                        hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    LiricaLog.w("ScreenCaptureManager", "无障碍截屏失败，错误码: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        )
    }
}
