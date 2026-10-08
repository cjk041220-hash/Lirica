package com.lirica.android.common.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.network.model.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], manifest = Config.NONE)
class ByokSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var secureKeyStore: SecureKeyStore
    private lateinit var repository: ByokSettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(ByokSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        secureKeyStore = DefaultSecureKeyStore()
        repository = ByokSettingsRepositoryImpl(prefs, secureKeyStore)
    }

    @Test
    fun testSaveCustomApiKey_encryptsAndPersists() {
        val rawKey = "sk-deepseek-test-999"
        repository.saveCustomApiKey(rawKey)

        assertEquals(rawKey, repository.settings.value.customApiKey)
        assertTrue(repository.settings.value.isCustomKeyConfigured)

        // 验证 prefs 存储的是密文，而非明文
        val stored = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)
        assertTrue(!stored.isNullOrBlank())
        assertTrue("存储的必须是密文而非明文", stored != rawKey)

        // 重新构建 repository，验证能正确解密恢复
        val reloaded = ByokSettingsRepositoryImpl(prefs, secureKeyStore)
        assertEquals(rawKey, reloaded.settings.value.customApiKey)
    }

    @Test
    fun testSaveCustomApiKey_emptyString_clearsKey() {
        repository.saveCustomApiKey("sk-initial-key")
        assertTrue(repository.settings.value.isCustomKeyConfigured)

        // 保存空串应主动清空
        repository.saveCustomApiKey("   ")
        assertNull(repository.settings.value.customApiKey)
        assertFalse(repository.settings.value.isCustomKeyConfigured)
        assertNull(prefs.getString(ByokSettingsRepository.KEY_API_KEY, null))
    }

    @Test
    fun testLoadSettings_corruptedCiphertext_isNotTreatedAsPlaintextAndCleared() {
        // 模拟损坏的密文（例如旧版异常留下的非合法明文数据）
        val corruptedPayload = "dGhpcy1pcy1ub3QtYS12YWxpZC1jaXBoZXJ0ZXh0LWRhdGE="
        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, corruptedPayload).commit()

        val reloaded = ByokSettingsRepositoryImpl(prefs, secureKeyStore)
        assertNull("损坏的密文不应被当作明文透传给上游", reloaded.settings.value.customApiKey)
        assertNull("已失效的残留数据应被清理", prefs.getString(ByokSettingsRepository.KEY_API_KEY, null))
    }

    @Test
    fun testLoadSettings_legacyPlaintextKey_migratesToCiphertext() {
        // 模拟升级前的旧版明文 Key
        val legacyKey = "sk-legacy-valid-api-key"
        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, legacyKey).commit()

        val reloaded = ByokSettingsRepositoryImpl(prefs, secureKeyStore)
        assertEquals(legacyKey, reloaded.settings.value.customApiKey)

        // 验证落盘数据已经被自动加密升级
        val updated = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)
        assertTrue(!updated.isNullOrBlank())
        assertTrue("应已自动加密迁移", updated != legacyKey)
    }

    @Test
    fun testEffectiveLlmConfig_withCustomSettings() {
        repository.saveCustomApiKey("sk-my-key")
        repository.saveCustomBaseUrl("https://discovery-api.intern-ai.org.cn/v1")
        repository.saveCustomModel("deepseek-v4-flash-vision")
        repository.saveCustomProvider(LlmProvider.OPENAI)

        val config = repository.getEffectiveLlmConfig()
        assertEquals("sk-my-key", config.apiKey)
        assertEquals("https://discovery-api.intern-ai.org.cn/v1", config.baseUrl)
        assertEquals("deepseek-v4-flash-vision", config.model)
        assertEquals(LlmProvider.OPENAI, config.provider)
    }
}
