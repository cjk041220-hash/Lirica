package com.xiaoxi.android.common.settings

import android.content.Context
import android.content.SharedPreferences
import com.xiaoxi.android.BuildConfig
import com.xiaoxi.android.network.model.LlmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ByokSettings(
    val customApiKey: String? = null,
    val customBaseUrl: String? = null,
    val customModel: String? = null
) {
    val isCustomKeyConfigured: Boolean get() = !customApiKey.isNullOrBlank()
}

interface ByokSettingsRepository {
    val settings: StateFlow<ByokSettings>

    fun getEffectiveLlmConfig(): LlmConfig
    fun saveCustomApiKey(apiKey: String)
    fun saveCustomBaseUrl(baseUrl: String)
    fun saveCustomModel(model: String)
    fun clearCustomSettings()

    companion object {
        const val PREFS_NAME = "xiaoxi_byok_prefs"
        const val KEY_API_KEY = "byok_api_key"
        const val KEY_BASE_URL = "byok_base_url"
        const val KEY_MODEL = "byok_model"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

class ByokSettingsRepositoryImpl(
    private val prefs: SharedPreferences
) : ByokSettingsRepository {

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<ByokSettings> = _settings.asStateFlow()

    private fun loadSettings(): ByokSettings {
        return ByokSettings(
            customApiKey = prefs.getString(ByokSettingsRepository.KEY_API_KEY, null)?.takeIf { it.isNotBlank() },
            customBaseUrl = prefs.getString(ByokSettingsRepository.KEY_BASE_URL, null)?.takeIf { it.isNotBlank() },
            customModel = prefs.getString(ByokSettingsRepository.KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        )
    }

    override fun getEffectiveLlmConfig(): LlmConfig {
        val current = _settings.value
        val effectiveKey = if (current.isCustomKeyConfigured) {
            current.customApiKey!!.trim()
        } else {
            BuildConfig.DEEPSEEK_API_KEY
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

        return LlmConfig(
            apiKey = effectiveKey,
            baseUrl = effectiveBaseUrl,
            model = effectiveModel
        )
    }

    override fun saveCustomApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(ByokSettingsRepository.KEY_API_KEY, trimmed).apply()
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

    override fun clearCustomSettings() {
        prefs.edit()
            .remove(ByokSettingsRepository.KEY_API_KEY)
            .remove(ByokSettingsRepository.KEY_BASE_URL)
            .remove(ByokSettingsRepository.KEY_MODEL)
            .apply()
        _settings.value = ByokSettings()
    }
}
