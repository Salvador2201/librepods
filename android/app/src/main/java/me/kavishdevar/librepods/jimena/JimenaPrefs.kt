package me.kavishdevar.librepods.jimena

import android.content.Context

class JimenaPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("jimena_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var translateModeEnabled: Boolean
        get() = prefs.getBoolean("translate_mode", false)
        set(value) = prefs.edit().putBoolean("translate_mode", value).apply()

    var translateSourceLang: String
        get() = prefs.getString("translate_source", "Español") ?: "Español"
        set(value) = prefs.edit().putString("translate_source", value).apply()

    var translateTargetLang: String
        get() = prefs.getString("translate_target", "Inglés") ?: "Inglés"
        set(value) = prefs.edit().putString("translate_target", value).apply()
}
