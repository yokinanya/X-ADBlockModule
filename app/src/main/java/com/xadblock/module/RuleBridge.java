package com.xadblock.module;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.xadblock.module.data.Contract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs inside the X process. Loads rule snapshots written by the module app
 * through LibXposed Remote Preferences, matches post text/url against them and
 * reports blocks back via exported broadcasts. The packaged asset bootstrap
 * stays as the explicit no-configuration startup path.
 */
public final class RuleBridge {
    private static final String TAG = "[X-ADBlock]";
    private static final String BUILTIN_ASSET = "builtin_keywords.txt";
    private static final long HEARTBEAT_INTERVAL_MS = 120_000L;
    private static final long EVENT_FLUSH_MS = 4_000L;
    private static final int MAX_EVENT_BATCH = 20;
    private static final int MAX_PENDING_EVENTS = 20;

    private static final AtomicReference<Snapshot> ACTIVE = new AtomicReference<>();
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private static volatile Context targetContext;
    private static volatile SharedPreferences snapshotPrefs;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener snapshotListener;
    private static volatile long lastSnapshotVersion = Long.MIN_VALUE;
    private static volatile String lastHeartbeatStatus = "STARTING";

    private static volatile int displayMode = Contract.DISPLAY_MODE_MARK;
    private static volatile boolean optUsername = true;
    private static volatile boolean optEmoji = true;
    private static volatile boolean optSpecialChars = true;
    private static volatile boolean optGrok = true;
    private static volatile String markText = "已屏蔽";

    // x-comment-blocker parity: emoji/symbol detection patterns.
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\uD83C-\\uD83E]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]|"
                    + "[\\u2600-\\u27BF]|[\\u2B00-\\u2BFF]|\\uFE0F|\\u3030|\\u303D");

    static int getDisplayMode() {
        return displayMode;
    }

    static String getMarkText() {
        return markText;
    }

    private static final AtomicLong STAT_TOTAL = new AtomicLong();
    private static final AtomicInteger PENDING_EVENTS = new AtomicInteger();

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xadblock-bridge");
        thread.setDaemon(true);
        return thread;
    });

    /** entryId -> Match (or NULL_MARKER) for the active snapshot; cleared on reload. */
    private static final java.util.Map<String, Object> EVAL_CACHE =
            java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Object>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Object> eldest) {
                    return size() > 8192;
                }
            });
    private static final Object NULL_MARKER = new Object();

    private static final List<PendingBlock> EVENT_QUEUE = Collections.synchronizedList(new ArrayList<>());
    private static volatile long lastEventFlushAt;

    private RuleBridge() {}

    static void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("null base context during Application.attach");
        }
        targetContext = context;
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            snapshotPrefs = HookEntry.remotePreferences(Contract.PREF_SNAPSHOT);
            snapshotListener = (preferences, key) -> {
                if (key == null || Contract.KEY_SNAPSHOT_VERSION.equals(key)
                        || Contract.KEY_SNAPSHOT_DATA.equals(key)) {
                    IO.execute(() -> reloadIfChanged(true));
                }
            };
            snapshotPrefs.registerOnSharedPreferenceChangeListener(snapshotListener);
        } catch (Throwable throwable) {
            snapshotPrefs = null;
            HookEntry.log("Remote Preferences unavailable; using packaged bootstrap: " + throwable);
        }
        reloadIfChanged(true);
        lastHeartbeatStatus = "ACTIVE";
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.postDelayed(RuleBridge::sendPeriodicHeartbeat, HEARTBEAT_INTERVAL_MS);
        mainHandler.postDelayed(RuleBridge::flushEvents, EVENT_FLUSH_MS);
        sendHeartbeat("ACTIVE");
        HookEntry.log("bridge initialized: snapshotVersion=" + lastSnapshotVersion);
    }

    static void clearState() {
        SharedPreferences prefs = snapshotPrefs;
        SharedPreferences.OnSharedPreferenceChangeListener listener = snapshotListener;
        if (prefs != null && listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        }
        snapshotPrefs = null;
        snapshotListener = null;
        targetContext = null;
        ACTIVE.set(null);
        EVAL_CACHE.clear();
        EVENT_QUEUE.clear();
        PENDING_EVENTS.set(0);
        STAT_TOTAL.set(0);
        BOOTSTRAPPED.set(false);
        INITIALIZED.set(false);
        lastSnapshotVersion = Long.MIN_VALUE;
        lastHeartbeatStatus = "STARTING";
    }

    /** Rebuilds the active snapshot from the module prefs when untouched; falls back to assets. */
    private static void reloadIfChanged(boolean force) {
        ensureBootstrap();
        SharedPreferences prefs = snapshotPrefs;
        if (prefs == null) {
            return;
        }
        try {
            long version = prefs.getLong(Contract.KEY_SNAPSHOT_VERSION, 0L);
            if (!force && version == lastSnapshotVersion) {
                return;
            }
            String data = prefs.getString(Contract.KEY_SNAPSHOT_DATA, "");
            if (data == null || data.isEmpty() || version <= 0) {
                return;
            }
            loadOptions(prefs);
            List<Rule> rules = compileSnapshot(data);
            if (rules.isEmpty()) {
                return;
            }
            ACTIVE.set(new Snapshot(version, Collections.unmodifiableList(rules),
                    "module-snapshot"));
            EVAL_CACHE.clear();
            lastSnapshotVersion = version;
            HookEntry.log("activated module snapshot=" + version + " rules=" + rules.size()
                    + " mode=" + displayMode + " opt(U/E/S/G)=" + optUsername + "/" + optEmoji
                    + "/" + optSpecialChars + "/" + optGrok);
        } catch (Throwable throwable) {
            HookEntry.log("module snapshot reload failed; keeping active: " + throwable);
        }
    }

    private static void loadOptions(SharedPreferences prefs) {
        try {
            displayMode = prefs.getInt(Contract.KEY_DISPLAY_MODE, Contract.DISPLAY_MODE_MARK);
            optUsername = prefs.getBoolean(Contract.KEY_OPT_USERNAME, true);
            optEmoji = prefs.getBoolean(Contract.KEY_OPT_EMOJI, true);
            optSpecialChars = prefs.getBoolean(Contract.KEY_OPT_SPECIAL_CHARS, true);
            optGrok = prefs.getBoolean(Contract.KEY_OPT_GROK, true);
            String text = prefs.getString(Contract.KEY_MARK_TEXT, "已屏蔽");
            markText = (text == null || text.isEmpty()) ? "已屏蔽" : text;
        } catch (Throwable ignored) {
            // keep previous values on parse hiccup
        }
    }

    private static List<Rule> compileSnapshot(String data) {
        List<Rule> rules = new ArrayList<>();
        long id = 0;
        for (String line : data.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int slash = trimmed.indexOf('/');
            if (slash <= 0) continue;
            String kind = trimmed.substring(0, slash);
            String pattern = trimmed.substring(slash + 1);
            switch (kind) {
                case Contract.KIND_LITERAL:
                case Contract.KIND_REGEX:
                case Contract.KIND_ALL_OF:
                    try {
                        rules.add(Rule.compile(id++, kind.equals(Contract.KIND_REGEX) ? "regex"
                                : kind.toLowerCase(Locale.ROOT), kind, pattern, 80));
                    } catch (Throwable ignored) {
                        // skip unparsable rule
                    }
                    break;
                default:
                    break;
            }
        }
        return rules;
    }

    private static void ensureBootstrap() {
        if (BOOTSTRAPPED.get()) {
            return;
        }
        BOOTSTRAPPED.set(true);
        try {
            Context context = targetContext;
            if (context == null) {
                throw new IllegalStateException("target context is not initialized");
            }
            AssetManager assets = context.createPackageContext(
                    Contract.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY).getAssets();
            List<Rule> rules = new ArrayList<>();
            try (InputStream in = assets.open(BUILTIN_ASSET);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                int builtInId = -1;
                String line;
                while ((line = reader.readLine()) != null) {
                    List<String[]> parsed = RuleParserLine.parse(line);
                    if (parsed == null) continue;
                    for (String[] kindPattern : parsed) {
                        rules.add(Rule.compile(builtInId--, "builtin",
                                kindPattern[0], kindPattern[1], 50));
                    }
                }
            }
            Snapshot snapshot = new Snapshot(
                    rules.size(), Collections.unmodifiableList(rules), "packaged-bootstrap");
            if (ACTIVE.compareAndSet(null, snapshot) || ACTIVE.get().origin.startsWith("packaged")) {
                ACTIVE.set(snapshot);
            }
            HookEntry.log("packaged bootstrap ready rules=" + rules.size());
        } catch (Throwable throwable) {
            HookEntry.log("packaged bootstrap failed: " + throwable);
        }
    }

    /** Render-time text-level check: matches a single text against the active ruleset. */
    static String matchText(String text) {
        ensureBootstrap();
        if (text == null || text.isEmpty()) {
            return null;
        }
        Snapshot snapshot = ACTIVE.get();
        if (snapshot == null || snapshot.rules.isEmpty()) {
            return null;
        }
        String normalized = normalizeForMatch(text);
        for (Rule rule : snapshot.rules) {
            String matched = rule.match(text, normalized);
            if (matched != null) {
                return markText;
            }
        }
        return null;
    }

    static Match firstMatch(String postText, String postUrl, String authorText, String entryId,
                            boolean isGrok) {
        ensureBootstrap();
        if (entryId != null && !entryId.isEmpty()) {
            Object cached = EVAL_CACHE.get(entryId);
            if (cached == NULL_MARKER) {
                return null;
            }
            if (cached instanceof Match) {
                return (Match) cached;
            }
        }
        Snapshot snapshot = ACTIVE.get();
        if (snapshot == null) {
            return null;
        }
        Match result = null;
        StringBuilder sb = new StringBuilder();
        if (postText != null) sb.append(postText);
        sb.append('\n');
        if (postUrl != null) sb.append(postUrl);
        if (optUsername && authorText != null) sb.append('\n').append(authorText);
        String combined = sb.toString();
        String normalized = normalizeForMatch(combined);

        // x-comment-blocker parity options (checked before keyword rules).
        if (!combined.isEmpty()) {
            if (optGrok && isGrok) {
                result = new Match(null, "grok", snapshot.version, entryId);
            } else if (optEmoji && EMOJI_PATTERN.matcher(combined).find()) {
                result = new Match(null, "emoji", snapshot.version, entryId);
            } else if (optSpecialChars && hasSuspiciousSpecialChars(combined)) {
                result = new Match(null, "special-chars", snapshot.version, entryId);
            }
        }
        if (result == null) {
            Rule ahoRule = snapshot.aho.scan(normalized);
            if (ahoRule != null) {
                result = new Match(ahoRule, ahoRule.pattern, snapshot.version, entryId);
            }
        }
        if (result == null) {
            for (Rule rule : snapshot.allOfRules) {
                String matched = rule.match(combined, normalized);
                if (matched != null) {
                    result = new Match(rule, matched, snapshot.version, entryId);
                    break;
                }
            }
        }
        if (result == null) {
            for (Rule rule : snapshot.regexRules) {
                String matched = rule.match(combined, normalized);
                if (matched != null) {
                    result = new Match(rule, matched, snapshot.version, entryId);
                    break;
                }
            }
        }
        if (entryId != null && !entryId.isEmpty()) {
            EVAL_CACHE.put(entryId, result == null ? NULL_MARKER : result);
        }
        return result;
    }

    /** Heuristic for "充满特殊符号/乱码" posts: symbols, control chars and private-use
     *  codepoints make up >= 25% of the visible text (excluding whitespace). */
    private static boolean hasSuspiciousSpecialChars(String text) {
        int total = 0;
        int suspicious = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            total++;
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL) {
                suspicious++;
            }
            if (codePoint >= 0xE000 && codePoint <= 0xF8FF) { // private use
                suspicious++;
            }
        }
        return total >= 3 && suspicious * 100 >= total * 25;
    }

    static long getTotalBlocks() {
        return STAT_TOTAL.get();
    }

    static void recordBlock(Match match, String postText) {
        if (match == null) return;
        STAT_TOTAL.incrementAndGet();
        String matchedRule = match.rule == null
                ? match.matchedText
                : match.rule.pattern;
        EVENT_QUEUE.add(new PendingBlock(
                match.rule == null ? "?" : match.rule.sourceId,
                "[匹配:" + matchedRule + "] " + preview(postText, 160),
                match.entryId));
        if (EVENT_QUEUE.size() >= MAX_EVENT_BATCH) {
            flushEvents();
        }
    }

    private static void flushEvents() {
        if (EVENT_QUEUE.isEmpty()) {
            return;
        }
        List<PendingBlock> batch;
        synchronized (EVENT_QUEUE) {
            if (EVENT_QUEUE.isEmpty()) return;
            batch = new ArrayList<>(EVENT_QUEUE.subList(0, Math.min(EVENT_QUEUE.size(), MAX_EVENT_BATCH)));
            EVENT_QUEUE.subList(0, batch.size()).clear();
        }
        if (batch.isEmpty() || PENDING_EVENTS.incrementAndGet() > MAX_PENDING_EVENTS) {
            if (!batch.isEmpty()) PENDING_EVENTS.decrementAndGet();
            return;
        }
        IO.execute(() -> {
            try {
                Context context = targetContext;
                if (context == null) return;
                Bundle extras = new Bundle();
                final StringBuilder payload = new StringBuilder();
                for (PendingBlock block : batch) {
                    if (payload.length() > 0) payload.append('\n');
                    payload.append(block.sourceId).append('\t')
                            .append(block.preview).append('\t')
                            .append(block.postId == null ? "" : block.postId);
                }
                Intent intent = new Intent(Contract.ACTION_BLOCK_EVENTS)
                        .setPackage(Contract.MODULE_PACKAGE)
                        .putExtra(Contract.EXTRA_COUNT, batch.size())
                        .putExtra(Contract.EXTRA_ITEMS, payload.toString());
                context.sendBroadcast(intent);
            } catch (Throwable throwable) {
                HookEntry.log("failed to send block broadcast: " + throwable);
            } finally {
                PENDING_EVENTS.decrementAndGet();
            }
        });
    }

    private static void sendHeartbeat(String status) {
        lastHeartbeatStatus = status;
        Context context = targetContext;
        if (context == null) return;
        Intent intent = new Intent(Contract.ACTION_HEARTBEAT)
                .setPackage(Contract.MODULE_PACKAGE)
                .putExtra(Contract.EXTRA_STATUS, status)
                .putExtra(Contract.EXTRA_PROCESS, context.getApplicationInfo().processName)
                .putExtra(Contract.EXTRA_SNAPSHOT_VERSION, lastSnapshotVersion)
                .putExtra(Contract.EXTRA_TARGET_VERSION, safeVersionName(context));
        String fallbackLog = HookLogSink.drainFallback();
        if (fallbackLog != null) {
            intent.putExtra(Contract.EXTRA_LOG, fallbackLog);
        }
        context.sendBroadcast(intent);
    }

    private static void sendPeriodicHeartbeat() {
        reloadIfChanged(false);
        sendHeartbeat(lastHeartbeatStatus);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(RuleBridge::sendPeriodicHeartbeat, HEARTBEAT_INTERVAL_MS);
    }

    private static String safeVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable throwable) {
            return "?";
        }
    }

    /** Keeps zero-width/whitespace-insensitive text for literal matching. */
    static String normalizeForMatch(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int offset = 0; offset < lower.length(); ) {
            int codePoint = lower.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }

    private static String preview(String text, int limit) {
        if (text == null || text.isEmpty()) return text;
        int count = text.codePointCount(0, text.length());
        int end = text.offsetByCodePoints(0, Math.min(count, limit));
        return text.substring(0, end).replace('\n', ' ').replace('\r', ' ');
    }

    static final class Match {
        final Rule rule;
        final String matchedText;
        final long snapshotVersion;
        final String entryId;

        Match(Rule rule, String matchedText, long snapshotVersion, String entryId) {
            this.rule = rule;
            this.matchedText = matchedText;
            this.snapshotVersion = snapshotVersion;
            this.entryId = entryId;
        }
    }

    private static final class PendingBlock {
        final String sourceId;
        final String preview;
        final String postId;

        PendingBlock(String sourceId, String preview, String postId) {
            this.sourceId = sourceId;
            this.preview = preview;
            this.postId = postId;
        }
    }

    /** Aho-Corasick automaton over all normalized literal keywords. */
    private static final class AhoNode {
        final java.util.HashMap<Character, AhoNode> next = new java.util.HashMap<>();
        AhoNode fail;
        Rule rule;
    }

    private static final class AhoMatcher {
        final AhoNode root = new AhoNode();

        AhoMatcher(List<Rule> literalRules) {
            for (Rule rule : literalRules) {
                AhoNode cur = root;
                String pattern = rule.pattern;
                for (int i = 0; i < pattern.length(); i++) {
                    char c = pattern.charAt(i);
                    AhoNode next = cur.next.get(c);
                    if (next == null) {
                        next = new AhoNode();
                        cur.next.put(c, next);
                    }
                    cur = next;
                }
                cur.rule = rule;
            }
            java.util.ArrayDeque<AhoNode> queue = new java.util.ArrayDeque<>();
            for (AhoNode child : root.next.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                AhoNode cur = queue.poll();
                for (java.util.Map.Entry<Character, AhoNode> entry : cur.next.entrySet()) {
                    char c = entry.getKey();
                    AhoNode child = entry.getValue();
                    AhoNode f = cur.fail;
                    while (f != null && !f.next.containsKey(c)) {
                        f = f.fail;
                    }
                    child.fail = (f == null) ? root : f.next.get(c);
                    if (child.rule == null && child.fail.rule != null) {
                        child.rule = child.fail.rule;
                    }
                    queue.add(child);
                }
            }
        }

        /** Returns the first rule matched anywhere in normalized text, or null. */
        Rule scan(String text) {
            AhoNode cur = root;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                while (cur != root && !cur.next.containsKey(c)) {
                    cur = cur.fail;
                }
                AhoNode next = cur.next.get(c);
                cur = (next == null) ? root : next;
                if (cur.rule != null) {
                    return cur.rule;
                }
            }
        return null;
        }
    }

    private static final class Snapshot {
        final long version;
        final List<Rule> rules;
        final List<Rule> literalRules;
        final List<Rule> allOfRules;
        final List<Rule> regexRules;
        final AhoMatcher aho;
        final String origin;

        Snapshot(long version, List<Rule> rules, String origin) {
            this.version = version;
            this.rules = rules;
            this.origin = origin;
            List<Rule> literals = new ArrayList<>();
            List<Rule> allOfs = new ArrayList<>();
            List<Rule> regexes = new ArrayList<>();
            for (Rule rule : rules) {
                switch (rule.kind) {
                    case Contract.KIND_LITERAL: literals.add(rule); break;
                    case Contract.KIND_ALL_OF: allOfs.add(rule); break;
                    default: regexes.add(rule); break;
                }
            }
            this.literalRules = Collections.unmodifiableList(literals);
            this.allOfRules = Collections.unmodifiableList(allOfs);
            this.regexRules = Collections.unmodifiableList(regexes);
            this.aho = new AhoMatcher(literals);
        }
    }
    private static final class Rule {
        final long id;
        final String sourceId;
        final String kind;
        final String pattern;
        final int priority;
        final Pattern regex;
        final List<String> allOf;

        private Rule(long id, String sourceId, String kind, String pattern, int priority,
                     Pattern regex, List<String> allOf) {
            this.id = id;
            this.sourceId = sourceId;
            this.kind = kind;
            this.pattern = pattern;
            this.priority = priority;
            this.regex = regex;
            this.allOf = allOf;
        }

        static Rule compile(long id, String sourceId, String kind, String pattern, int priority) {
            if (kind == null || pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("rule kind/pattern missing: " + id);
            }
            switch (kind) {
                case Contract.KIND_LITERAL:
                    String normalized = normalizeForMatch(pattern);
                    if (normalized.isEmpty()) {
                        throw new IllegalArgumentException("empty normalized literal: " + id);
                    }
                    return new Rule(id, sourceId, kind, normalized, priority, null, null);
                case Contract.KIND_REGEX:
                    try {
                        return new Rule(id, sourceId, kind, pattern, priority,
                                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                                null);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("invalid regex rule " + id, e);
                    }
                case Contract.KIND_ALL_OF:
                    List<String> parts = new ArrayList<>();
                    for (String part : pattern.split(String.valueOf(Contract.ALL_OF_SEPARATOR), -1)) {
                        String normalizedPart = normalizeForMatch(part);
                        if (!normalizedPart.isEmpty()) parts.add(normalizedPart);
                    }
                    if (parts.size() < 2) {
                        throw new IllegalArgumentException("ALL_OF needs two parts: " + id);
                    }
                    List<String> unmodifiable = Collections.unmodifiableList(parts);
                    return new Rule(id, sourceId, kind, pattern, priority, null, unmodifiable);
                default:
                    throw new IllegalArgumentException("unsupported kind " + kind + " for " + id);
            }

        }
        String match(String original, String normalized) {
            switch (kind) {
                case Contract.KIND_LITERAL:
                    return normalized.contains(pattern) ? pattern : null;
                case Contract.KIND_REGEX:
                    Matcher matcher = regex.matcher(original);
                    return matcher.find() ? matcher.group() : null;
                case Contract.KIND_ALL_OF:
                    for (String part : allOf) {
                        if (!normalized.contains(part)) return null;
                    }
                    return String.join(" + ", allOf);
                default:
        return null;
            }
        }
    }
}
