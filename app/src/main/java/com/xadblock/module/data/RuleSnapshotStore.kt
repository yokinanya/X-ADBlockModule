package com.xadblock.module.data

import android.content.Context
import java.security.MessageDigest
import com.xadblock.module.XAdApplication

/**
 * Publishes the merged local ruleset through LibXposed Remote Preferences so the injected X
 * process can read it without relying on world-readable app files. Invoked after every mutation.
 */
object RuleSnapshotStore {
    private const val MAX_RULES = 50_000

    fun rebuild(context: Context) {
        val db = AppDatabase.get(context)
        val rules = db.ruleDao().allEnabled()
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
        val settings = SettingsStore.load(context)
        val data = sb.toString()
        val version = fingerprint(data, settings)
        val prefs = XAdApplication.remotePreferences(Contract.PREF_SNAPSHOT)
        check(prefs.edit()
            .putString(Contract.KEY_SNAPSHOT_DATA, data)
            .putLong(Contract.KEY_SNAPSHOT_VERSION, version)
            .putInt(Contract.KEY_DISPLAY_MODE, settings.displayMode)
            .putBoolean(Contract.KEY_OPT_USERNAME, settings.optUsername)
            .putBoolean(Contract.KEY_OPT_EMOJI, settings.optEmoji)
            .putBoolean(Contract.KEY_OPT_SPECIAL_CHARS, settings.optSpecialChars)
            .putBoolean(Contract.KEY_OPT_GROK, settings.optGrok)
            .putBoolean(Contract.KEY_SKIP_VERIFIED, settings.skipVerified)
            .putStringSet(Contract.KEY_WHITELIST_USERS, settings.whitelistUsers)
            .putString(Contract.KEY_MARK_TEXT, settings.markText)
            .commit()) {
            "Remote Preferences 提交失败"
        }
    }

    private fun fingerprint(data: String, settings: SettingsStore.Settings): Long {
        val canonicalSettings = buildString {
            append(settings.displayMode).append('|')
            append(settings.optUsername).append('|')
            append(settings.optEmoji).append('|')
            append(settings.optSpecialChars).append('|')
            append(settings.optGrok).append('|')
            append(settings.skipVerified).append('|')
            append(settings.markText).append('|')
            settings.whitelistUsers.sorted().forEach { append(it).append('|') }
        }
        val digestInput = data + '\u0000' + canonicalSettings
        val digest = MessageDigest.getInstance("MD5")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
        var hash = 0L
        for (i in 0 until 8) {
            hash = (hash shl 8) or (digest[i].toLong() and 0xFF)
        }
        return if (hash == 0L) 1L else hash
    }
}
