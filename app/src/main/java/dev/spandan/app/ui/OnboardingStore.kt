package dev.spandan.app.ui

import android.content.Context

/** Tracks whether onboarding has been completed (finished or skipped) at least once. */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(KEY, false)

    fun markDone() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    companion object {
        private const val KEY = "done"
    }
}
