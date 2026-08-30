package dev.spandan.app.ui

import android.content.Context

data class AppSettings(
    val hapticsEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val lowPowerMode: Boolean = false,
    val responderMode: Boolean = false,
)

/** Persists the small set of user-facing toggles Settings exposes. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
        audioEnabled = prefs.getBoolean(KEY_AUDIO, true),
        lowPowerMode = prefs.getBoolean(KEY_LOW_POWER, false),
        responderMode = prefs.getBoolean(KEY_RESPONDER, false),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_HAPTICS, settings.hapticsEnabled)
            .putBoolean(KEY_AUDIO, settings.audioEnabled)
            .putBoolean(KEY_LOW_POWER, settings.lowPowerMode)
            .putBoolean(KEY_RESPONDER, settings.responderMode)
            .apply()
    }

    companion object {
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AUDIO = "audio"
        private const val KEY_LOW_POWER = "low_power"
        private const val KEY_RESPONDER = "responder"
    }
}
