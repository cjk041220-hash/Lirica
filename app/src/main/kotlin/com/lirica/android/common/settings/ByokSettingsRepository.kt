package com.lirica.android.common.settings

import android.content.SharedPreferences
import com.lirica.android.BuildConfig
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ByokSettings(
    val customApiKey: String? = null,
    val customBaseUrl: String? = null,
    val customModel: String? = null,
    val customProvider: LlmProvider? = null
) {
    val isCustomKeyConfigured: Boolean get() = !customApiKey.isNullOrBlank()

    /**
     * 判断当前系统是否拥有可用的 API Key（自备 Key 或 BuildConfig 内置 Key 任一有效）。
     */
    val hasValidKey: Boolean
        get() = isCustomKeyConfigured || BuildConfig.DEEPSEEK_API_KEY.isNotBlank()
}

/**
 * BYOK（用户自备 API Key）安全存储仓库。
 *
 * 安全规范（M3.1 T1.5）：
 * 1. API Key 不再明文落盘于 SharedPreferences，必须经由 SecureKeyStore (AES-GCM) 加密后存入；
 * 2. 具备自动明文迁移能力：若检测到旧版明文 Key，首启时无感升级为密文存储；
 * 3. 支持动态切换 Provider（DeepSeek、OpenAI 兼容服务、Claude 原生等）。
 */
interface ByokSettingsRepository {
    val settings: StateFlow<ByokSettings>

    fun getEffectiveLlmConfig(): LlmConfig
    fun saveCustomApiKey(apiKey: String)
    fun saveCustomBaseUrl(baseUrl: String)
    fun saveCustomModel(model: String)
    fun saveCustomProvider(provider: LlmProvider)
    fun clearCustomSettings()

    companion object {
        const val PREFS_NAME = "lirica_byok_prefs"
        const val KEY_API_KEY = "byok_api_key"
        const val KEY_BASE_URL = "byok_base_url"
        const val KEY_MODEL = "byok_model"
        const val KEY_PROVIDER = "byok_provider"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

class ByokSettingsRepositoryImpl(
    private val prefs: SharedPreferences,
    private val secureKeyStore: SecureKeyStore = DefaultSecureKeyStore()
) : ByokSettingsRepository {

    private val _settings = MutableStateFlow(loadSettingsAndMigrate())
    override val settings: StateFlow<ByokSettings> = _settings.asStateFlow()

    private fun loadSettingsAndMigrate(): ByokSettings {
        val rawStoredKey = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        val effectiveApiKey = if (rawStoredKey != null) {
            val decrypted = secureKeyStore.decrypt(rawStoredKey)
            if (decrypted != null) {
                decrypted
            } else {
                // 解密失败时，甄别是否为升级前真实的旧版明文 Key（以 sk- 开头或纯可读密钥字符串）
                val isLikelyPlaintextKey = rawStoredKey.startsWith("sk-", ignoreCase = true) ||
                    (rawStoredKey.length in 8..128 && rawStoredKey.all { it.isLetterOrDigit() || it == '-' || it == '_' })
                if (isLikelyPlaintextKey) {
                    LiricaLog.i("ByokSettings", "检测到旧版明文 API Key，执行 AES-GCM 安全迁移")
                    val encrypted = runCatching { secureKeyStore.encrypt(rawStoredKey) }.getOrNull()
                    if (!encrypted.isNullOrBlank()) {
                        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, encrypted).apply()
                    }
                    rawStoredKey
                } else {
                    // 无法解密且并非明文格式，说明是历史损坏或环境更替后的残留无效密文，主动清理防污染
                    LiricaLog.w("ByokSettings", "存储中的 API Key 密文无法解密且非合法明文，清理失效数据")
                    prefs.edit().remove(ByokSettingsRepository.KEY_API_KEY).apply()
                    null
                }
            }
        } else {
            null
        }

        val providerStr = prefs.getString(ByokSettingsRepository.KEY_PROVIDER, null)
        val provider = providerStr?.let {
            runCatching { LlmProvider.valueOf(it) }.getOrNull()
        }

        return ByokSettings(
            customApiKey = effectiveApiKey,
            customBaseUrl = prefs.getString(ByokSettingsRepository.KEY_BASE_URL, null)?.takeIf { it.isNotBlank() },
            customModel = prefs.getString(ByokSettingsRepository.KEY_MODEL, null)?.takeIf { it.isNotBlank() },
            customProvider = provider
        )
    }

    override fun getEffectiveLlmConfig(): LlmConfig {
        val current = _settings.value
        val effectiveKey = if (current.isCustomKeyConfigured) {
            current.customApiKey!!.trim()
        } else {
            BuildConfig.DEEPSEEK_API_KEY.trim()
        }

        val effectiveBaseUrl = if (!current.customBaseUrl.isNullOrBlank()) {
            current.customBaseUrl.trim()
        } else {
            ByokSettingsRepository.DEFAULT_BASE_URL
        }

        val effectiveModel = if (!current.customModel.isNullOrBlank()) {
            current.customModel.trim()
        } else {
            ByokSettingsRepository.DEFAULT_MODEL
        }

        val effectiveProvider = current.customProvider ?: when {
            effectiveBaseUrl.contains("anthropic", ignoreCase = true) -> LlmProvider.CUSTOM
            effectiveBaseUrl.contains("deepseek", ignoreCase = true) -> LlmProvider.DEEPSEEK
            else -> LlmProvider.OPENAI
        }

        return LlmConfig(
            apiKey = effectiveKey,
            provider = effectiveProvider,
            baseUrl = effectiveBaseUrl,
            model = effectiveModel
        )
    }

    override fun saveCustomApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(ByokSettingsRepository.KEY_API_KEY).apply()
            _settings.value = _settings.value.copy(customApiKey = null)
            return
        }
        val cipherText = runCatching { secureKeyStore.encrypt(trimmed) }
            .getOrElse { e ->
                LiricaLog.e("ByokSettings", "API Key 加密失败，保留原有密文不覆盖", e)
                return
            }
        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, cipherText).apply()
        _settings.value = _settings.value.copy(customApiKey = trimmed)
    }

    override fun saveCustomBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        prefs.edit().putString(ByokSettingsRepository.KEY_BASE_URL, trimmed).apply()
        _settings.value = _settings.value.copy(customBaseUrl = trimmed)
    }

    override fun saveCustomModel(model: String) {
        val trimmed = model.trim()
        prefs.edit().putString(ByokSettingsRepository.KEY_MODEL, trimmed).apply()
        _settings.value = _settings.value.copy(customModel = trimmed)
    }

    override fun saveCustomProvider(provider: LlmProvider) {
        prefs.edit().putString(ByokSettingsRepository.KEY_PROVIDER, provider.name).apply()
        _settings.value = _settings.value.copy(customProvider = provider)
    }

    override fun clearCustomSettings() {
        prefs.edit()
            .remove(ByokSettingsRepository.KEY_API_KEY)
            .remove(ByokSettingsRepository.KEY_BASE_URL)
            .remove(ByokSettingsRepository.KEY_MODEL)
            .remove(ByokSettingsRepository.KEY_PROVIDER)
            .apply()
        _settings.value = ByokSettings()
    }
}
