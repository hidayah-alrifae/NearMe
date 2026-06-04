package com.example.nearme.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.app.Activity

object AppPreferences {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }
    enum class Language  { ENGLISH, ARABIC }

    private const val PREFS = "nearme_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANG  = "language"

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme

    private val _language = MutableStateFlow(Language.ENGLISH)
    val language: StateFlow<Language> = _language

    /** Call once at app start (from NearMeApp) so the StateFlows reflect saved values. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _theme.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        _language.value = runCatching {
            Language.valueOf(prefs.getString(KEY_LANG, Language.ENGLISH.name)!!)
        }.getOrDefault(Language.ENGLISH)
    }

    fun setTheme(context: Context, mode: ThemeMode) {
        _theme.value = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setLanguage(context: Context, lang: Language) {
        _language.value = lang
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.name).apply()
        (context as? Activity)?.recreate()
    }
}
