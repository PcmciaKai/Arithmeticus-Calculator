package com.vortessence.calculator.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import com.vortessence.calculator.core.math.i18n.Language
import java.util.Locale

/** What the user chose for the language of the app: the language of the device, or one of the available ones. */
enum class LanguageChoice(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    GERMAN("de"),
    FRENCH("fr"),
    SPANISH("es"),
    RUSSIAN("ru"),
    CHINESE("zh-CN")
}

/**
 * The language of the app. From Android 13 the system keeps it (the "App languages" setting of the device shows the same list); before,
 * the choice is kept in the preferences and applied to the activity when it is made ([wrap]).
 */
object AppLanguage {
    private const val PREFERENCES = "calculator_settings"
    private const val KEY = "language"

    /** The choice made, the system's language if none. */
    fun choice(context: Context): LanguageChoice {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            val language = if (locales.isEmpty) null else locales[0].language
            return LanguageChoice.entries.firstOrNull { it.tag != null && it.tag.substringBefore('-') == language } ?: LanguageChoice.SYSTEM
        }
        val saved = try { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null) } catch (e: Exception) { null }
        return LanguageChoice.entries.firstOrNull { it.name == saved } ?: LanguageChoice.SYSTEM
    }

    /** Applies [choice]: the activity is made again in the new language. */
    fun set(activity: Activity, choice: LanguageChoice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system keeps it and makes the activity again
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (choice.tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(choice.tag)
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY, choice.name) }
            activity.recreate()
        }
    }

    /** For attachBaseContext of an activity before Android 13: [base] in the language chosen. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = choice(base).tag?.let { Locale.forLanguageTag(it) } ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }

    /** The language the app shows its texts in now (English where the language of the device is not available). */
    fun current(context: Context): Language = try {
        val tag = choice(context).tag ?: Resources.getSystem().configuration.locales[0].toLanguageTag()
        Language.fromTag(tag)
    } catch (e: Exception) {
        // (no system to ask, as in unit tests)
        Language.ENGLISH
    }
}
