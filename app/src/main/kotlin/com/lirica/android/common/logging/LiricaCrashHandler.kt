package com.lirica.android.common.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lirica 本地私密崩溃守护器
 *
 * 坚守 100% 本地安全沙盒原则，绝不上报云端，将未捕获异常与环形日志格式化落盘并轮转清理。
 */
class LiricaCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
) : Thread.UncaughtExceptionHandler {

    companion object {
        const val MAX_CRASH_FILES = 5
        const val CRASH_DIR_NAME = "crashes"

        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is LiricaCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(LiricaCrashHandler(context, current))
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(thread, throwable)
        } catch (t: Throwable) {
            LiricaLog.e("LiricaCrashHandler", "保存崩溃报告异常", t)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    internal fun saveCrashReport(thread: Thread, throwable: Throwable): File? {
        val crashDir = File(context.filesDir, CRASH_DIR_NAME).apply { mkdirs() }

        // 轮转清理超额旧文件，保持最多 MAX_CRASH_FILES - 1 份，以便放入新报告
        val existing = crashDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (existing.size >= MAX_CRASH_FILES) {
            val toDeleteCount = existing.size - MAX_CRASH_FILES + 1
            existing.take(toDeleteCount).forEach { it.delete() }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val file = File(crashDir, "crash_$timestamp.txt")

        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val stackTrace = stringWriter.toString()

        val report = buildString {
            append("=== Lirica 崩溃诊断报告 ===\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}\n")
            append("崩溃线程: ${thread.name} (id=${thread.id})\n")
            append("设备信息: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
            append("异常类型: ${throwable.javaClass.name}\n")
            append("异常信息: ${throwable.message ?: "无详细描述"}\n\n")
            append("=== 异常堆栈 ===\n")
            append(stackTrace)
            append("\n=== 崩溃前最近环形日志 (${LiricaLog.getRecentLogs().size}条) ===\n")
            for (log in LiricaLog.getRecentLogs()) {
                append(log).append("\n")
            }
            append("=== 报告结束 ===\n")
        }

        file.writeText(report)
        return file
    }
}
