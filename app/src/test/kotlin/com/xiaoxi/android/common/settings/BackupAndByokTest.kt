package com.xiaoxi.android.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.BuildConfig
import com.xiaoxi.android.database.XiaoxiDatabase
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
        assertTrue(dataRulesContent.contains(XiaoxiDatabase.DATABASE_NAME))
        assertTrue(dataRulesContent.contains("xiaoxi_byok_prefs"))
        // 库文件三件套（主库 + shm + wal）必须全部排除，防止亲密记忆随备份外流
        assertTrue(dataRulesContent.contains("${XiaoxiDatabase.DATABASE_NAME}-shm"))
        assertTrue(dataRulesContent.contains("${XiaoxiDatabase.DATABASE_NAME}-wal"))
        assertFalse("不得残留错误的旧库名 xiaoxi_database", dataRulesContent.contains("xiaoxi_database"))

        val backupRulesContent = backupRulesFile.readText()
        assertTrue(backupRulesContent.contains(XiaoxiDatabase.DATABASE_NAME))
        assertTrue(backupRulesContent.contains("xiaoxi_byok_prefs"))
        assertTrue(backupRulesContent.contains("${XiaoxiDatabase.DATABASE_NAME}-shm"))
        assertTrue(backupRulesContent.contains("${XiaoxiDatabase.DATABASE_NAME}-wal"))
        assertFalse("不得残留错误的旧库名 xiaoxi_database", backupRulesContent.contains("xiaoxi_database"))
    }

    /**
     * 常量-XML 一致性断言：备份排除规则引用的库名必须与
     * XiaoxiDatabase.DATABASE_NAME 完全一致，防止重命名数据库后备份规则静默失效。
     */
    @Test
    fun testBackupRulesDatabaseNameMatchesConstant() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val dataRulesFile = File(projectDir, "src/main/res/xml/data_extraction_rules.xml").let {
            if (it.exists()) it else File(projectDir, "app/src/main/res/xml/data_extraction_rules.xml")
        }
        val backupRulesFile = File(projectDir, "src/main/res/xml/backup_rules.xml").let {
            if (it.exists()) it else File(projectDir, "app/src/main/res/xml/backup_rules.xml")
        }

        assertEquals(
            "data_extraction_rules.xml 必须引用 XiaoxiDatabase.DATABASE_NAME 声明的库名",
            XiaoxiDatabase.DATABASE_NAME,
            dataRulesFile.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("<exclude") }?.let { line ->
                    Regex("""path="([^"]+)"""").find(line)?.groupValues?.get(1)
                }
        )
        assertEquals(
            "backup_rules.xml 必须引用 XiaoxiDatabase.DATABASE_NAME 声明的库名",
            XiaoxiDatabase.DATABASE_NAME,
            backupRulesFile.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("<exclude") }?.let { line ->
                    Regex("""path="([^"]+)"""").find(line)?.groupValues?.get(1)
                }
        )
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
    fun testByokSettingsRepository_apiKeyEncryptedInPreferences() {
        val rawKey = "sk-custom-secret-key-to-encrypt"
        repository.saveCustomApiKey(rawKey)

        val prefs = context.getSharedPreferences("test_byok_prefs", Context.MODE_PRIVATE)
        val storedRaw = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)
        assertNotNull("存储的密文不能为空", storedRaw)
        assertFalse("存储的必须是密文，不得为明文", storedRaw == rawKey)

        assertEquals("读取有效配置时自动无感解密", rawKey, repository.getEffectiveLlmConfig().apiKey)
    }

    @Test
    fun testByokSettingsRepository_legacyPlaintextKeyAutoMigrated() {
        val legacyPlaintextKey = "sk-legacy-plaintext-key-999"
        val prefs = context.getSharedPreferences("test_byok_migration_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, legacyPlaintextKey).commit()

        val repoWithMigration = ByokSettingsRepositoryImpl(prefs)
        assertEquals("旧明文自动正确解析", legacyPlaintextKey, repoWithMigration.getEffectiveLlmConfig().apiKey)

        val storedAfterMigration = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)
        assertNotNull(storedAfterMigration)
        assertFalse("旧明文已被迁移为密文", storedAfterMigration == legacyPlaintextKey)
    }

    @Test
    fun testByokSettingsRepository_providerSelection() {
        repository.saveCustomProvider(com.xiaoxi.android.network.model.LlmProvider.OPENAI)
        assertEquals(com.xiaoxi.android.network.model.LlmProvider.OPENAI, repository.settings.value.customProvider)
        assertEquals(com.xiaoxi.android.network.model.LlmProvider.OPENAI, repository.getEffectiveLlmConfig().provider)
    }
}
