package com.lirica.android.common.logging

import android.content.Context
import android.os.Build
import com.lirica.android.common.permissions.PermissionManager
import com.lirica.android.common.settings.ByokSettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包一键导出器（M3.1 T1.10 核心交付物）。
 *
 * 核心职能：
 * 1. 聚合系统环境、六项授权体检结果、大模型网关连通状态、本地崩溃栈与最近 100 条环形日志；
 * 2. 严格安全脱敏：对用户的 API Key 进行物理级掩码屏蔽，严禁泄露隐私；
 * 3. 产出结构化 Markdown 文件，便于真机联调或装机遇到故障时一键分享定位。
 */
object DiagnosticExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // 2026-09-27 审计 P3 修复：诊断包经 ACTION_SEND 分享给任意应用，属回归敏感面。
    // 环形日志行在导出前做 PII 正则二次脱敏（与 PrivacyBreaker.maskPiiText 同规则，
    // 不直接引用以避免 common → perception 依赖反向；两处正则需保持同步）。
    private val EXPORT_PHONE_REGEX = Regex("1[3-9]\\d{9}")
    private val EXPORT_ID_CARD_REGEX = Regex("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]")
    private val EXPORT_BANK_CARD_REGEX = Regex("\\d{16,19}")

    private fun sanitizeLogLine(line: String): String {
        var result = line
        result = EXPORT_ID_CARD_REGEX.replace(result) { m -> m.value.take(6) + "********" + m.value.takeLast(4) }
        result = EXPORT_BANK_CARD_REGEX.replace(result) { m ->
            val len = m.value.length
            m.value.take(4) + "*".repeat((len - 8).coerceAtLeast(4)) + m.value.takeLast(4)
        }
        result = EXPORT_PHONE_REGEX.replace(result) { m -> m.value.take(3) + "****" + m.value.takeLast(4) }
        return result
    }

    fun exportDiagnosticMarkdown(
        context: Context,
        byokRepo: ByokSettingsRepository
    ): String {
        val now = dateFormat.format(Date())
        val config = byokRepo.getEffectiveLlmConfig()
        val permissions = PermissionManager.checkAll(context)

        val maskedKey = if (config.apiKey.isNotBlank()) {
            val key = config.apiKey.trim()
            if (key.length >= 8) {
                "${key.take(3)}****${key.takeLast(4)} (已配置 (已脱敏))"
            } else {
                "**** (已配置 (已脱敏))"
            }
        } else {
            "未配置"
        }

        val crashDir = File(context.filesDir, LiricaCrashHandler.CRASH_DIR_NAME)
        val crashFiles = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        val recentLogs = LiricaLog.getRecentLogs().takeLast(100)

        return buildString {
            appendLine("# Lirica 智能体运行诊断报告")
            appendLine("> 生成时间: $now\n")

            appendLine("## 1. 设备与系统环境")
            appendLine("- **设备型号**: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- **系统版本**: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- **指纹信息**: ${Build.FINGERPRINT}")
            appendLine("- **应用包名**: ${context.packageName}\n")

            appendLine("## 2. 核心权限健康度")
            for (p in permissions) {
                val statusEmoji = if (p.isGranted) "✅" else if (p.type.isCrucial) "❌" else "⚠️"
                appendLine("- $statusEmoji **${p.type.title}** (${p.type.description}): ${p.details}")
            }
            appendLine()

            appendLine("## 3. 大模型与网关配置")
            appendLine("- **协议供应商**: ${config.provider}")
            appendLine("- **Base URL**: ${config.baseUrl}")
            appendLine("- **默认模型**: ${config.model}")
            appendLine("- **API Key 状态**: $maskedKey\n")

            appendLine("## 4. 本地崩溃守护记录")
            if (crashFiles.isEmpty()) {
                appendLine("- 无未捕获异常崩溃记录（系统健康）\n")
            } else {
                appendLine("- 发现 ${crashFiles.size} 份本地崩溃日志:")
                for (crash in crashFiles.take(3)) {
                    appendLine("### ${crash.name}")
                    appendLine("```text")
                    appendLine(crash.readText().take(1500))
                    appendLine("```")
                }
                appendLine()
            }

            appendLine("## 5. 环形日志现场记录 (最近 ${recentLogs.size} 条，已过 PII 脱敏)")
            if (recentLogs.isEmpty()) {
                appendLine("- 暂无近期内存日志记录\n")
            } else {
                appendLine("```text")
                for (log in recentLogs) {
                    appendLine(sanitizeLogLine(log))
                }
                appendLine("```\n")
            }
        }
    }

    fun exportDiagnosticFile(
        context: Context,
        byokRepo: ByokSettingsRepository
    ): File {
        val markdown = exportDiagnosticMarkdown(context, byokRepo)
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val filename = "lirica_diagnostic_${fileTimestampFormat.format(Date())}.md"
        val targetFile = File(dir, filename)
        targetFile.writeText(markdown, Charsets.UTF_8)
        return targetFile
    }
}
