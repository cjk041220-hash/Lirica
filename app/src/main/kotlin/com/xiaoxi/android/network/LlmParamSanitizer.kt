package com.xiaoxi.android.network

import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmProvider

object LlmParamSanitizer {

    fun sanitize(config: LlmConfig): LlmConfig {
        val cleanKey = config.apiKey.trim()
        val cleanBaseUrl = config.baseUrl.trim().removeSuffix("/")

        val isDeepSeek = config.provider == LlmProvider.DEEPSEEK ||
            config.model.contains("deepseek", ignoreCase = true) ||
            cleanBaseUrl.contains("deepseek.com", ignoreCase = true)

        return if (isDeepSeek) {
            // DeepSeek 专属清洗策略
            val sanitizedTopP = if (config.topP != null && config.topP <= 0.0) {
                null // 物理清除 top_p=0 报错陷阱
            } else {
                config.topP
            }

            val sanitizedTemperature = config.temperature ?: 0.95
            val sanitizedPresencePenalty = config.presencePenalty ?: 0.25

            config.copy(
                apiKey = cleanKey,
                baseUrl = cleanBaseUrl,
                topP = sanitizedTopP,
                temperature = sanitizedTemperature,
                presencePenalty = sanitizedPresencePenalty
            )
        } else {
            config.copy(
                apiKey = cleanKey,
                baseUrl = cleanBaseUrl
            )
        }
    }
}
