package com.xiaoxi.android.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.BuildConfig
import org.junit.Assert.assertEquals
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
class BackupAndByokTest {

    private lateinit var context: Context
    private lateinit var repository: ByokSettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_byok_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = ByokSettingsRepositoryImpl(prefs)
    }

    @Test
    fun testBackupRulesXmlExistsAndExcludesDatabaseAndPrefs() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val dataRulesFile = File(projectDir, "src/main/res/xml/data_extraction_rules.xml").let {
            if (it.exists()) it else File(projectDir, "app/src/main/res/xml/data_extraction_rules.xml")
        }
        val backupRulesFile = File(projectDir, "src/main/res/xml/backup_rules.xml").let {
            if (it.exists()) it else File(projectDir, "app/src/main/res/xml/backup_rules.xml")
        }

        assertTrue("data_extraction_rules.xml 必须存在", dataRulesFile.exists())
        assertTrue("backup_rules.xml 必须存在", backupRulesFile.exists())

        val dataRulesContent = dataRulesFile.readText()
        assertTrue(dataRulesContent.contains("xiaoxi_database"))
        assertTrue(dataRulesContent.contains("xiaoxi_byok_prefs"))

        val backupRulesContent = backupRulesFile.readText()
        assertTrue(backupRulesContent.contains("xiaoxi_database"))
        assertTrue(backupRulesContent.contains("xiaoxi_byok_prefs"))
    }

    @Test
    fun testManifestDeclaresDataExtractionRulesAndFullBackupContent() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val manifestFile = File(projectDir, "src/main/AndroidManifest.xml").let {
            if (it.exists()) it else File(projectDir, "app/src/main/AndroidManifest.xml")
        }
        assertTrue("AndroidManifest.xml 必须存在", manifestFile.exists())
        val manifestContent = manifestFile.readText()

        assertTrue(
            "Manifest 必须声明 dataExtractionRules",
            manifestContent.contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        )
        assertTrue(
            "Manifest 必须声明 fullBackupContent",
            manifestContent.contains("""android:fullBackupContent="@xml/backup_rules"""")
        )
    }

    @Test
    fun testByokSettingsRepository_defaultFallbackToBuildConfig() {
        assertFalse(repository.settings.value.isCustomKeyConfigured)
        val config = repository.getEffectiveLlmConfig()

        assertEquals(BuildConfig.DEEPSEEK_API_KEY, config.apiKey)
        assertNotNull(config.baseUrl)
    }

    @Test
    fun testByokSettingsRepository_customKeyTakesPrecedence() {
        repository.saveCustomApiKey("sk-custom-secret-key")
        repository.saveCustomBaseUrl("https://api.custom-ai.com/v1")
        repository.saveCustomModel("custom-fast-model")

        assertTrue(repository.settings.value.isCustomKeyConfigured)
        val config = repository.getEffectiveLlmConfig()

        assertEquals("sk-custom-secret-key", config.apiKey)
        assertEquals("https://api.custom-ai.com/v1", config.baseUrl)
        assertEquals("custom-fast-model", config.model)
    }

    @Test
    fun testByokSettingsRepository_clearSettings_revertsToDefault() {
        repository.saveCustomApiKey("sk-custom-secret-key")
        assertTrue(repository.settings.value.isCustomKeyConfigured)

        repository.clearCustomSettings()
        assertFalse(repository.settings.value.isCustomKeyConfigured)

        val config = repository.getEffectiveLlmConfig()
        assertEquals(BuildConfig.DEEPSEEK_API_KEY, config.apiKey)
    }
}
