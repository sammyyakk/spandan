package dev.spandan.app.ui

import android.content.Context
import android.util.Base64
import dev.spandan.mesh.PersonalCard

/**
 * Local-only storage for the optional personal card -- never synced anywhere,
 * never required. Uses the card's own length-prefixed wire encoding rather
 * than a JSON dependency, base64'd into SharedPreferences.
 */
class PersonalCardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("personal_card", Context.MODE_PRIVATE)

    fun load(): PersonalCard {
        val encoded = prefs.getString(KEY, null) ?: return PersonalCard.empty()
        return runCatching { PersonalCard.decode(Base64.decode(encoded, Base64.DEFAULT)) }.getOrDefault(PersonalCard.empty())
    }

    fun save(card: PersonalCard) {
        prefs.edit().putString(KEY, Base64.encodeToString(card.encode(), Base64.DEFAULT)).apply()
    }

    companion object {
        private const val KEY = "card"
    }
}
