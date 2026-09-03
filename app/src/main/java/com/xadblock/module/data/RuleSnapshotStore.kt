package com.xadblock.module.data

import android.content.Context
import java.security.MessageDigest

/**
 * Rebuilds the merged local ruleset into a MODE_WORLD_READABLE SharedPreferences file that
 * LSPosed's "New XSharedPreferences" (API 93) redirects into its SELinux bridge, making the
 * snapshot readable from inside the injected X process. Invoked after every rules mutation.
 */
object RuleSnapshotStore {
    private const val MAX_RULES = 50_000

    fun rebuild(context: Context) {
        try {
            val db = AppDatabase.get(context)
            val rules = db.ruleDao().allEnabled()
            if (rules.isEmpty()) return
            val limited = if (rules.size > MAX_RULES) rules.subList(0, MAX_RULES) else rules

            val sb = StringBuilder(limited.size * 40)
            limited.forEach { rule ->
                when (rule.kind) {
                    Contract.KIND_LITERAL,
                    Contract.KIND_REGEX,
                    Contract.KIND_ALL_OF -> {
                        sb.append(rule.kind).append('/').append(rule.pattern).append('\n')
                    }
                }
            }
            val data = sb.toString()
            val version = fingerprint(data)

            val settings = SettingsStore.load(context)
            val prefs = context.getSharedPreferences(
                Contract.PREF_SNAPSHOT, Context.MODE_WORLD_READABLE
            )
            prefs.edit()
                .putString(Contract.KEY_SNAPSHOT_DATA, data)
                .putLong(Contract.KEY_SNAPSHOT_VERSION, version)
                .putInt(Contract.KEY_DISPLAY_MODE, settings.displayMode)
                .putBoolean(Contract.KEY_OPT_USERNAME, settings.optUsername)
                .putBoolean(Contract.KEY_OPT_EMOJI, settings.optEmoji)
                .putBoolean(Contract.KEY_OPT_SPECIAL_CHARS, settings.optSpecialChars)
                .putBoolean(Contract.KEY_OPT_GROK, settings.optGrok)
                .putString(Contract.KEY_MARK_TEXT, settings.markText)
                .commit()
        } catch (ignored: Throwable) {
            // fallback to asset bootstrap inside the hook; never crash here
        }
    }

    private fun fingerprint(data: String): Long {
        val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
        var hash = 0L
        for (i in 0 until 8) {
            hash = (hash shl 8) or (digest[i].toLong() and 0xFF)
        }
        return if (hash == 0L) 1L else hash
    }
}
