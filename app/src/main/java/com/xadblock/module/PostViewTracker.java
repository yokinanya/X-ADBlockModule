package com.xadblock.module;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Records the posts the user actually opens (browsing history), inside the X process.
 *
 * Reverse-engineering notes (X 12.22.0, see orchestra/reverse-findings-x-12.22.0.md):
 *  - Only the tapped post of a detail page is wrapped in a focal state object:
 *    com.x.urt.items.post.y (FocalPostState), constructor arg[0] / field a =
 *    com.x.urt.items.post.g5 (TimelinePostState, implemented by b5). Replies and
 *    feed rows never go through it, so one hit here == one opened post.
 *  - Fields read off the post state (b5):
 *      a -> entryId (String)          g -> text (String)
 *      b -> postId (com.x.models.z5, toString() is the numeric id)
 *      e -> author (com.x.models.mh, C() is the @handle, getName() the display name)
 *  - Permalink shape mirrors com.x.models.s5#getUrl(): https://x.com/<handle>/status/<id>,
 *    falling back to https://x.com/i/status/<id> when the handle cannot be trusted.
 */
final class PostViewTracker {

    private static final String FOCAL_POST_STATE = "com.x.urt.items.post.y";
    private static final String POST_STATE_INTERFACE = "com.x.urt.items.post.g5";
    private static final String POST_ID_CLASS = "com.x.models.z5";
    private static final String AUTHOR_INTERFACE = "com.x.models.mh";

    private static final String[] HANDLE_ACCESSORS = {
        "C", "getScreenName", "getHandle", "getUsername", "screenName", "handle"
    };
    private static final String[] NAME_ACCESSORS = {"getName", "a", "getDisplayName"};

    private static final Pattern HANDLE_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,20}");
    private static final Pattern POST_ID_PATTERN = Pattern.compile("[0-9]{5,25}");

    /** Same post re-composed within this window counts as the same visit. */
    private static final long SAME_POST_WINDOW_MS = 60_000L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicLong DIAG_LAST_VIEW = new AtomicLong();
    private static final AtomicLong DIAG_LAST_ERROR = new AtomicLong();
    private static final Map<Class<?>, StateAccessors> ACCESSORS = new ConcurrentHashMap<>();

    private static volatile Field focalStateField;
    private static volatile Class<?> postStateInterface;
    private static volatile Class<?> postIdClass;
    private static volatile Class<?> authorInterface;
    private static volatile String lastPostId;
    private static volatile long lastPostAt;

    private PostViewTracker() {}

    static void clearState() {
        INSTALLED.set(false);
        focalStateField = null;
        postStateInterface = null;
        postIdClass = null;
        authorInterface = null;
        lastPostId = null;
        lastPostAt = 0L;
        ACCESSORS.clear();
    }

    static void install(ClassLoader classLoader) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class<?> focalClass = Class.forName(FOCAL_POST_STATE, false, classLoader);
            postStateInterface = Class.forName(POST_STATE_INTERFACE, false, classLoader);
            postIdClass = Class.forName(POST_ID_CLASS, false, classLoader);
            authorInterface = Class.forName(AUTHOR_INTERFACE, false, classLoader);

            Field stateField = null;
            for (Field field : focalClass.getDeclaredFields()) {
                if (field.getType() == postStateInterface) {
                    field.setAccessible(true);
                    stateField = field;
                    break;
                }
            }
            if (stateField == null) {
                HookEntry.log("view tracker: focal post state field not found on " + FOCAL_POST_STATE);
                return;
            }
            focalStateField = stateField;

            int hooked = 0;
            for (Constructor<?> constructor : focalClass.getDeclaredConstructors()) {
                boolean carriesState = false;
                for (Class<?> type : constructor.getParameterTypes()) {
                    if (type == postStateInterface) {
                        carriesState = true;
                        break;
                    }
                }
                if (!carriesState) {
                    continue;
                }
                try {
                    HookEntry.registerHook(constructor,
                            "focal-post-view$" + constructor.getParameterCount(), chain -> {
                        Object result = chain.proceed();
                        try {
                            onFocalPost(chain.getThisObject());
                        } catch (Throwable failure) {
                            logError("focal post read failed: " + failure);
                        }
                        return result;
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // keep scanning the remaining constructors
                }
            }
            if (hooked == 0) {
                HookEntry.log("view tracker: no focal post constructor matched");
                return;
            }
            INSTALLED.set(true);
            HookEntry.log(" hooked focal post state constructors: " + hooked);
        } catch (Throwable failure) {
            HookEntry.log("view tracker install failed: " + failure);
        }
    }

    private static void onFocalPost(Object focalState) throws Throwable {
        Field stateField = focalStateField;
        if (focalState == null || stateField == null || !RuleBridge.isRecordingViews()) {
            return;
        }
        Object postState = stateField.get(focalState);
        if (postState == null) {
            return;
        }
        StateAccessors accessors = accessorsFor(postState.getClass());
        if (accessors == null) {
            return;
        }
        String postId = accessors.postId(postState);
        if (postId == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (postId.equals(lastPostId) && now - lastPostAt < SAME_POST_WINDOW_MS) {
            return;
        }
        lastPostId = postId;
        lastPostAt = now;

        String text = accessors.text(postState);
        if (text != null && text.trim().equals(RuleBridge.getMarkText())) {
            return; // placeholder of a post this module blocked; nothing worth remembering
        }
        Object author = accessors.author(postState);
        String handle = normalizeHandle(firstString(author, HANDLE_ACCESSORS));
        String displayName = firstString(author, NAME_ACCESSORS);
        RuleBridge.recordView(postId, permalink(postId, handle), handle, displayName, text);
        if (diagAllowed(DIAG_LAST_VIEW, 10_000L)) {
            HookEntry.log("diag|VIEW " + postId + (handle == null ? "" : " @" + handle));
        }
    }

    static String permalink(String postId, String handle) {
        if (handle != null && HANDLE_PATTERN.matcher(handle).matches()) {
            return "https://x.com/" + handle + "/status/" + postId;
        }
        return "https://x.com/i/status/" + postId;
    }

    static String normalizeHandle(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstString(Object target, String[] names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                if (method.getReturnType() != String.class || method.getParameterCount() != 0) {
                    continue;
                }
                method.setAccessible(true);
                Object value = method.invoke(target);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // try the next candidate accessor
            }
        }
        return null;
    }

    private static StateAccessors accessorsFor(Class<?> type) {
        StateAccessors cached = ACCESSORS.get(type);
        if (cached == null) {
            cached = StateAccessors.resolve(type, postIdClass, authorInterface);
            ACCESSORS.put(type, cached);
        }
        return cached.usable() ? cached : null;
    }

    private static boolean diagAllowed(AtomicLong marker, long intervalMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        long previous = marker.get();
        return now - previous >= intervalMs && marker.compareAndSet(previous, now);
    }

    private static void logError(String message) {
        if (diagAllowed(DIAG_LAST_ERROR, 30_000L)) {
            HookEntry.log("view tracker: " + message);
        }
    }

    /** Per-post-state-class field handles; b5 in practice, resolved by type where possible. */
    private static final class StateAccessors {
        private final Field postId;
        private final Field author;
        private final Field text;

        private StateAccessors(Field postId, Field author, Field text) {
            this.postId = postId;
            this.author = author;
            this.text = text;
        }

        static StateAccessors resolve(Class<?> type, Class<?> postIdClass, Class<?> authorInterface) {
            Field postIdField = null;
            Field authorField = null;
            Field textField = null;
            for (Field field : type.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (postIdField == null && postIdClass != null && fieldType == postIdClass) {
                    field.setAccessible(true);
                    postIdField = field;
                } else if (authorField == null && authorInterface != null
                        && authorInterface.isAssignableFrom(fieldType) && !fieldType.isPrimitive()) {
                    field.setAccessible(true);
                    authorField = field;
                } else if (textField == null && fieldType == String.class && "g".equals(field.getName())) {
                    field.setAccessible(true);
                    textField = field;
                }
            }
            return new StateAccessors(postIdField, authorField, textField);
        }

        boolean usable() {
            return postId != null;
        }

        String postId(Object state) throws Throwable {
            Object value = postId.get(state);
            if (value == null) {
                return null;
            }
            String asText = String.valueOf(value).trim();
            if (POST_ID_PATTERN.matcher(asText).matches()) {
                return asText;
            }
            try {
                Method idAccessor = value.getClass().getMethod("a");
                if (idAccessor.getReturnType() == long.class) {
                    idAccessor.setAccessible(true);
                    String fallback = String.valueOf(idAccessor.invoke(value));
                    return POST_ID_PATTERN.matcher(fallback).matches() ? fallback : null;
                }
            } catch (Throwable ignored) {
                // fall through
            }
            return null;
        }

        Object author(Object state) throws Throwable {
            return author == null ? null : author.get(state);
        }

        String text(Object state) throws Throwable {
            if (text == null) {
                return null;
            }
            Object value = text.get(state);
            return value instanceof String ? (String) value : null;
        }
    }
}
