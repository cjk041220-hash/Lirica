package com.lirica.android.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.common.settings.ByokSettings
import com.lirica.android.common.settings.ByokSettingsRepository
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiagnosticExporterTest {

    private lateinit var context: Context
    private val mockByokRepo = mockk<ByokSettingsRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        every { mockByokRepo.settings } returns MutableStateFlow(ByokSettings(customApiKey = "sk-secret-real-key-12345"))
        every { mockByokRepo.getEffectiveLlmConfig() } returns LlmConfig(
            apiKey = "sk-secret-real-key-12345",
            provider = LlmProvider.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat"
        )
    }

    @Test
    fun testExportDiagnosticMarkdown_masksApiKeyAndContainsSections() {
        val markdown = DiagnosticExporter.exportDiagnosticMarkdown(context, mockByokRepo)

        assertNotNull(markdown)
        assertTrue("必须包含设备环境信息", markdown.contains("## 1. 设备与系统环境"))
        assertTrue("必须包含权限体检状态", markdown.contains("## 2. 核心权限健康度"))
        assertTrue("必须包含模型配置摘要", markdown.contains("## 3. 大模型与网关配置"))
        assertTrue("必须包含崩溃守护记录", markdown.contains("## 4. 本地崩溃守护记录"))
        assertTrue("必须包含最近日志", markdown.contains("环形日志现场记录"))

        // 安全红线：绝不能输出明文 API Key
        assertFalse("严禁在诊断包中输出明文 API Key", markdown.contains("sk-secret-real-key-12345"))
        assertTrue("必须包含脱敏掩码 Key 标识", markdown.contains("sk-****") || markdown.contains("已配置 (已脱敏)"))
    }

    @Test
    fun testExportDiagnosticFile_createsPhysicalFileInCache() {
        val file = DiagnosticExporter.exportDiagnosticFile(context, mockByokRepo)
        assertNotNull(file)
        assertTrue("导出的物理文件必须存在", file.exists())
        assertTrue("文件内容不能为空", file.length() > 0)
        assertTrue("文件扩展名应为 .md 或 .txt", file.name.endsWith(".md") || file.name.endsWith(".txt"))
    }
}
