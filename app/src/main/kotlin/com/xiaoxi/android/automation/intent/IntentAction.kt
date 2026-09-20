package com.xiaoxi.android.automation.intent

enum class SettingType {
    WIFI,
    BLUETOOTH,
    ACCESSIBILITY,
    APP_DETAILS,
    GENERAL
}

sealed interface IntentAction {
    data class Dial(val phoneNumber: String) : IntentAction
    data class SendSms(val phoneNumber: String, val message: String) : IntentAction
    data class SendEmail(
        val recipient: String,
        val subject: String? = null,
        val body: String? = null
    ) : IntentAction
    data class SetAlarm(
        val hour: Int,
        val minutes: Int,
        val label: String = "",
        val skipUi: Boolean = false
    ) : IntentAction
    data class OpenMap(
        val query: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    ) : IntentAction
    data class OpenSettings(
        val settingType: SettingType,
        val packageName: String? = null
    ) : IntentAction
    data class LaunchApp(
        val packageName: String,
        val fallbackWebUri: String? = null
    ) : IntentAction
    data class ShareText(
        val text: String,
        val targetPackage: String? = null
    ) : IntentAction
}
