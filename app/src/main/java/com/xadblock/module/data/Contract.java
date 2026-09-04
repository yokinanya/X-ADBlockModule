package com.xadblock.module.data;

/**
 * Shared configuration contract between the injected hook (target process, X app)
 * and the module app.
 *
 * Cross-process channel notes (X 12.22.0, LibXposed API 102):
 *  - The module app writes snapshots through LibXposed Remote Preferences.
 *  - The injected target process reads the same remote preference group through
 *    XposedInterface.getRemotePreferences(String).
 *  - Blocks/heartbeats go back through an exported broadcast (Binder-safe batching).
 */
public final class Contract {
    public static final String TARGET_PACKAGE = "com.twitter.android";

    /** Module package (scope of the Remote Preferences and the broadcast target). */
    public static final String MODULE_PACKAGE = "com.xadblock.module";

    /** Remote Preferences snapshot group (module writes, hook reads). */
    public static final String PREF_SNAPSHOT = "rules_snapshot";
    public static final String KEY_SNAPSHOT_VERSION = "snapshot_version";
    public static final String KEY_SNAPSHOT_DATA = "snapshot_data";

    /** Display mode: 0 = remove the post entirely, 1 = keep a "[已拦截]" placeholder. */
    public static final String KEY_DISPLAY_MODE = "display_mode";
    public static final int DISPLAY_MODE_REMOVE = 0;
    public static final int DISPLAY_MODE_MARK = 1;

    /** Match options mirrored into the snapshot prefs (x-comment-blocker parity). */
    public static final String KEY_OPT_USERNAME = "opt_username";
    public static final String KEY_OPT_EMOJI = "opt_emoji";
    public static final String KEY_OPT_SPECIAL_CHARS = "opt_special_chars";
    public static final String KEY_OPT_GROK = "opt_grok";
    public static final String KEY_SKIP_VERIFIED = "skip_verified";
    public static final String KEY_WHITELIST_USERS = "whitelist_users";

    /** Item placeholders carried by marked posts (preview text for the mark mode). */
    public static final String KEY_MARK_TEXT = "mark_text";

    /** Line format in the snapshot: kind + '/' + pattern, one rule per line. */
    public static final String KIND_LITERAL = "LITERAL";
    public static final String KIND_REGEX = "REGEX";
    public static final String KIND_ALL_OF = "ALL_OF";
    public static final char ALL_OF_SEPARATOR = '\u001F';

    public static final String ACTION_BLOCK_EVENTS = "com.xadblock.module.ACTION_BLOCK_EVENTS";
    public static final String ACTION_HEARTBEAT = "com.xadblock.module.ACTION_HEARTBEAT";

    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PROCESS = "process";
    public static final String EXTRA_TARGET_VERSION = "target_version";
    public static final String EXTRA_SNAPSHOT_VERSION = "snapshot_version";
    public static final String EXTRA_LOG = "log_lines";

    private Contract() {}
}
