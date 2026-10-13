package com.lirica.android.automation.backend

import android.content.pm.PackageManager
import com.lirica.android.common.logging.LiricaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 基于 Shizuku (ADB 提权) 的高速穿透执行底座 (ShizukuExecutionBackend)
 *
 * 核心优势：
 * 1. 穿透自绘与 Webview/Canvas 界面，直接下发底层 Linux input 事件；
 * 2. 调度时延显著降低，点击与滑动执行极为迅捷；
 * 3. 具备完整的物理按键模拟能力（keyevent）。
 */
class ShizukuExecutionBackend(
    private val isBinderAliveProvider: () -> Boolean = {
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    },
    private val permissionChecker: () -> Boolean = {
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    },
    private val shellExecutor: (suspend (String) -> Boolean)? = null
) : ExecutionBackend {

    override suspend fun isAvailable(): Boolean {
        return runCatching {
            isBinderAliveProvider() && permissionChecker()
        }.getOrDefault(false)
    }

    override suspend fun click(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        executeCommand("input tap ${x.toInt()} ${y.toInt()}")
    }

    override suspend fun longClick(x: Float, y: Float, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        val px = x.toInt()
        val py = y.toInt()
        executeCommand("input swipe $px $py $px $py $durationMs")
    }

    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        executeCommand("input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $durationMs")
    }

    override suspend fun sendKeycode(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        executeCommand("input keyevent $keyCode")
    }

    override suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        if (text.isEmpty()) return@withContext true
        // ADB input text 需要对空格转义为 %s，以及转义特殊符号
        val escaped = text.replace(" ", "%s")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace(";", "\\;")
        executeCommand("input text \"$escaped\"")
    }

    private suspend fun executeCommand(cmd: String): Boolean {
        if (shellExecutor != null) {
            // M6.0 P1-7：外部 shell 执行器的取消必须透传
            return try {
                shellExecutor.invoke(cmd)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LiricaLog.e("ShizukuExecutionBackend", "Shizuku shell 执行异常: $cmd", e)
                false
            }
        }

        return runCatching {
            // 通过反射调用 Shizuku.newProcess 调度特权 shell 进程
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val exitCode = process.waitFor()
            exitCode == 0
        }.getOrElse {
            LiricaLog.e("ShizukuExecutionBackend", "Shizuku 命令执行异常: $cmd", it)
            false
        }
    }
}
