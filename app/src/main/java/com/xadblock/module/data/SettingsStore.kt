package com.xadblock.module.data

import android.content.Context
import android.content.SharedPreferences

/** User-facing options stored by the module app; mirrored into the hook snapshot. */
object SettingsStore {

    private const val NAME = "xadblock_settings"
    private const val KEY_DISPLAY_MODE = "display_mode"
    private const val KEY_OPT_USERNAME = "opt_username"
    private const val KEY_OPT_EMOJI = "opt_emoji"
    private const val KEY_OPT_SPECIAL_CHARS = "opt_special_chars"
    private const val KEY_OPT_GROK = "opt_grok"
    private const val KEY_MARK_TEXT = "mark_text"

    data class Settings(
        val displayMode: Int = Contract.DISPLAY_MODE_MARK,
        val optUsername: Boolean = true,
        val optEmoji: Boolean = true,
        val optSpecialChars: Boolean = true,
        val optGrok: Boolean = true,
        val markText: String = "已屏蔽"
    ) {
        val isMarkMode: Boolean get() = displayMode == Contract.DISPLAY_MODE_MARK
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val prefs = prefs(context)
        return Settings(
            displayMode = prefs.getInt(KEY_DISPLAY_MODE, Contract.DISPLAY_MODE_MARK),
            optUsername = prefs.getBoolean(KEY_OPT_USERNAME, true),
            optEmoji = prefs.getBoolean(KEY_OPT_EMOJI, true),
            optSpecialChars = prefs.getBoolean(KEY_OPT_SPECIAL_CHARS, true),
            optGrok = prefs.getBoolean(KEY_OPT_GROK, true),
            markText = prefs.getString(KEY_MARK_TEXT, "已屏蔽") ?: "已屏蔽"
        )
    }

    fun save(context: Context, settings: Settings) {
        prefs(context).edit()
            .putInt(KEY_DISPLAY_MODE, settings.displayMode)
            .putBoolean(KEY_OPT_USERNAME, settings.optUsername)
            .putBoolean(KEY_OPT_EMOJI, settings.optEmoji)
            .putBoolean(KEY_OPT_SPECIAL_CHARS, settings.optSpecialChars)
            .putBoolean(KEY_OPT_GROK, settings.optGrok)
            .putString(KEY_MARK_TEXT, settings.markText)
            .apply()
        RuleSnapshotStore.rebuild(context)
    }
}
