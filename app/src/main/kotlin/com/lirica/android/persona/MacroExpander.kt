package com.lirica.android.persona

object MacroExpander {

    fun expand(
        template: String,
        charName: String = "",
        userName: String = "",
        currentTime: String = "",
        currentApp: String = "",
        batteryLevel: String = "",
        idleDuration: String = ""
    ): String {
        return template
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("<BOT>", charName, ignoreCase = true)
            .replace("<CHAR>", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
            .replace("<USER>", userName, ignoreCase = true)
            .replace("{{current_time}}", currentTime, ignoreCase = true)
            .replace("{{time}}", currentTime, ignoreCase = true)
            .replace("{{date}}", currentTime, ignoreCase = true)
            .replace("{{current_app}}", currentApp, ignoreCase = true)
            .replace("{{battery_level}}", batteryLevel, ignoreCase = true)
            .replace("{{idle_duration}}", idleDuration, ignoreCase = true)
    }
}
