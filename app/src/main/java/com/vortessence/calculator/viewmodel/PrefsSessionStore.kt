package com.vortessence.calculator.viewmodel

import android.content.SharedPreferences
import androidx.core.content.edit

/** Keeps the session in the app's preferences; without preferences (as in unit tests) nothing is kept. */
class PrefsSessionStore(private val prefs: SharedPreferences?) : SessionStore {
    override fun load(): String? = prefs?.getString(KEY, null)

    override fun save(text: String) {
        prefs?.edit { putString(KEY, text) }
    }

    private companion object {
        const val KEY = "session"
    }
}
