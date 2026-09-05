package com.xadblock.module;

import com.xadblock.module.data.Contract;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * Main-feed filter (X 12.22.0 layout):
 *  - primary hook: com.x.urt.ui.o#c(...) static method, arg[0] is the immutable feed list;
 *  - fallback hooks: com.x.urt.ui.h / com.x.urt.ui.j constructors with the same arg[0].
 *
 * Match handling per display mode:
 *  - REMOVE: matched UrtTimelinePost items are dropped from the list;
 *  - MARK: matched rows keep avatar/name and the body is replaced by the mark text
 *    (b5 text + displayTextRange reset), so the effect is visible for verification.
 */
final class TimelineFilter {
    private static final String TAG = "[X-ADBlock]";
    private static final String UI_ENTRY = "com.x.urt.ui.o";
    private static final String UI_LAMBDA_H = "com.x.urt.ui.h";
    private static final String UI_LAMBDA_J = "com.x.urt.ui.j";
    private static final String TIMELINE_TYPE = "com.x.models.timelines.v";
    private static final String TIMELINE_POST = "com.x.models.timelines.items.l1";
    private static final String TIMELINE_ITEM_IF = "com.x.models.timelines.items.p0";
    private static final String IMMUTABLE_NAMESPACE = "kotlinx.collections.immutable";

    /** Concrete PostResult implementations (h6) whose getText() renders the body. */
    private static final String[] POST_RESULT_IMPLS = {
            "com.x.models.fg", "com.x.models.n5", "com.x.models.o1",
            "com.x.models.i6", "com.x.models.l8", "com.x.models.r0"
    };

    private static final AtomicBoolean FILTER_INSTALLED = new AtomicBoolean(false);
    /** Human readable install result, surfaced through the heartbeat self-check. */
    private static volatile String INSTALL_SUMMARY = "not-installed";

    static String installSummary() {
        return INSTALL_SUMMARY;
    }
    private static final java.util.concurrent.atomic.AtomicInteger FILTER_LOG_TICKS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger POST_CTOR_HOOKS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger MARK_HOOKS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** entryId -> true once firstMatch has evaluated this post (miss or hit). */
    private static final java.util.Set<String> EVALUATED_IDS =
            java.util.Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 8192;
                }
            });

    /** entryId -> markText for posts matched and held in place (MARK mode). */
    private static final Map<String, String> BLOCKED_IDS = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, String>(2048, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 5000;
                }
            });

    /** postResult instance -> markText; renderer-held objects hit directly in getText(). */
    private static final Map<Object, String> BLOCKED_POST_RESULTS =
            java.util.Collections.synchronizedMap(
            new LinkedHashMap<Object, String>(2048, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, String> eldest) {
                    return size() > 5000;
                }
            });

    private static volatile Class<?> timelinePostClass;
    private static volatile Class<?> timelineItemInterface;
    private static volatile Method postTextMethod;
    private static volatile Method postUrlMethod;
    private static volatile Method postEntryIdMethod;
    private static volatile Method postAuthorMethod;
    private static volatile Method postGrokMethod;
    private static volatile Method authorVerifiedMethod;
    private static volatile Method postResultAuthorMethod;
    private static volatile java.lang.reflect.Field postResultAuthorField;
    private static volatile Class<?> postResultAuthorClass;
    private static volatile java.lang.reflect.Field POST_RESULT_FIELD;

    private static final String[] AUTHOR_ACCESSOR_NAMES = {
            "getUser", "getAuthor", "getAccount", "getUserResult", "getAuthorResult",
            "user", "author", "account"
    };
    private static final String[] AUTHOR_HANDLE_NAMES = {
            "C", "w", "getScreenName", "getHandle", "getUsername",
            "getAccountName", "screenName", "handle", "username"
    };
    private static final Map<Class<?>, AuthorTextAccessors> AUTHOR_TEXT_ACCESSORS =
            new ConcurrentHashMap<>();

    private TimelineFilter() {}

    static void clearState() {
        FILTER_INSTALLED.set(false);
        INSTALL_SUMMARY = "not-installed";
        POST_CTOR_HOOKS.set(0);
        MARK_HOOKS.set(0);
        synchronized (EVALUATED_IDS) {
            EVALUATED_IDS.clear();
        }
        BLOCKED_IDS.clear();
        BLOCKED_POST_RESULTS.clear();
        TEXT_MARK_CACHE.clear();
        timelinePostClass = null;
        timelineItemInterface = null;
        postTextMethod = null;
        postUrlMethod = null;
        postEntryIdMethod = null;
        postAuthorMethod = null;
        postGrokMethod = null;
        authorVerifiedMethod = null;
        postResultAuthorMethod = null;
        postResultAuthorField = null;
        postResultAuthorClass = null;
        POST_RESULT_FIELD = null;
        AUTHOR_TEXT_ACCESSORS.clear();
        i5RenderClass = null;
        i5InvokeMethod = null;
        I5_B5_FIELD = null;
        B5_ENTRY_ID_FIELD = null;
        B5_TEXT_FIELD = null;
        B5_RANGE_FIELD = null;
    }

    static void invalidateMatches() {
        synchronized (EVALUATED_IDS) {
            EVALUATED_IDS.clear();
        }
        BLOCKED_IDS.clear();
        BLOCKED_POST_RESULTS.clear();
        TEXT_MARK_CACHE.clear();
    }

    static void install(ClassLoader classLoader) throws Throwable {
        try {
            PostAccessors accessors = resolvePostAccessors(classLoader);
            timelinePostClass = accessors.postClass;
            postTextMethod = accessors.text;
            postUrlMethod = accessors.url;
            postEntryIdMethod = accessors.entryId;
            postAuthorMethod = accessors.author;
            postGrokMethod = accessors.grok;
            authorVerifiedMethod = accessors.authorVerified;
            timelineItemInterface = findClass(TIMELINE_ITEM_IF, classLoader);

            Class<?> uiEntryClass = findClass(UI_ENTRY, classLoader);
            int installedEntry = 0;
            for (Method method : uiEntryClass.getDeclaredMethods()) {
                if (isFeedEntryMethod(method)) {
                        HookEntry.registerHook(
                            method,
                            hookId("feed-entry", method),
                            createFeedListCallback(method.getParameterTypes()[0]));
                    installedEntry++;
                    HookEntry.log(" hooked feed entry method: " + method);
                }
            }
            if (installedEntry == 0) {
                throw new IllegalStateException("no feed entry method matched on " + UI_ENTRY);
            }

            int installedLambda = 0;
            installedLambda += hookListLambdaCtor(classLoader, UI_LAMBDA_H, "h");
            installedLambda += hookListLambdaCtor(classLoader, UI_LAMBDA_J, "j");
            if (installedLambda == 0) {
                HookEntry.log(" warning: no feed lambda constructor matched");
            }
            hookPostConstructors();
            hookPostTextOverride();
            hookPostResultImplementations();
            hookRenderSkipChain();
            hookPostRowSkip();
            hookMarkPlaceholder();

            FILTER_INSTALLED.set(true);
            INSTALL_SUMMARY = "ok entry=" + installedEntry + " lambda=" + installedLambda
                    + " post=" + POST_CTOR_HOOKS.get() + " mark=" + MARK_HOOKS.get();
            HookEntry.log(" feed filter installed (entry=" + installedEntry
                    + ", lambda=" + installedLambda + ", post=" + POST_CTOR_HOOKS.get()
                    + ", mark=" + MARK_HOOKS.get() + ")");
        } catch (Throwable throwable) {
            INSTALL_SUMMARY = "FAILED: " + throwable;
            HookEntry.log(" FAILED to install feed filter");
            HookEntry.logThrowable(throwable);
            throw throwable;
        }
    }

    private static boolean isFeedEntryMethod(Method method) {
        if (!Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length < 21) {
            return false;
        }
        if (!isImmutableListInterface(parameterTypes[0])) {
            return false;
        }
        return TIMELINE_TYPE.equals(parameterTypes[1].getName());
    }

    private static boolean isImmutableListInterface(Class<?> type) {
        if (!type.isInterface()) {
            return false;
        }
        String name = type.getName();
        return name.startsWith(IMMUTABLE_NAMESPACE)
                && List.class.isAssignableFrom(type);
    }

    private static int hookListLambdaCtor(final ClassLoader classLoader, String className, String label) {
        try {
            Class<?> lambdaClass = findClass(className, classLoader);
            int installed = 0;
            for (Constructor<?> constructor : lambdaClass.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length < 5) {
                    continue;
                }
                if (!isImmutableListInterface(parameterTypes[0])) {
                    continue;
                }
                if (!TIMELINE_TYPE.equals(parameterTypes[1].getName())) {
                    continue;
                }
                if (!(parameterTypes[2] == boolean.class)) {
                    continue;
                }
                HookEntry.registerHook(
                    constructor,
                    hookId("feed-lambda-" + label, constructor),
                    createFeedListCallback(parameterTypes[0]));
                installed++;
                HookEntry.log(" hooked feed lambda ctor " + label + ": " + constructor);
            }
            return installed;
        } catch (Throwable throwable) {
            HookEntry.log(" FAILED to hook " + className + " ctor");
            HookEntry.logThrowable(throwable);
            return 0;
        }
    }

    /**
     * Page-source independent detection: every UrtTimelinePost instance is tested
     * the moment it is constructed (main timeline, tweet detail "related",
     * comment threads, search results - any surface).
     */
    /** Marks concrete PostResult implementations so renderer-held objects also flip. */
    private static void hookPostResultImplementations() {
        for (String implName : POST_RESULT_IMPLS) {
            try {
                Class<?> implClass = findClass(implName, timelinePostClass.getClassLoader());
                hookAllStringGetters(implClass, false);
                HookEntry.log(" hooked post result string getters: " + implName);
            } catch (Throwable ignored) {
            }
        }
        // Note: UrtTimelinePost (l1) getters are intentionally NOT hooked - the renderer
        // reads through the postResult implementations (fg/n5/o1/r0/i6/l8) directly,
        // and hooking l1 would add per-render reflection and recursion risk.
    }

    /**
     * Wraps every public no-arg String getter so render-side reads return the
     * mark text. For l1 (post) lookup uses entryId; for postResult implementations
     * lookup uses the instance itself.
     */
    /** LRU cache for text-level matching (render hot path must stay O(1)). */
    private static final Map<String, String> TEXT_MARK_CACHE =
            java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, String>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 1024;
                }
            });

    /**
     * Wraps every public no-arg String getter of the postResult implementations.
     * Lookup is instance-keyed; text-level fallback re-matches the returned text
     * with an LRU cache so repeated render calls stay cheap.
     */
    private static void hookAllStringGetters(Class<?> target, boolean keyByEntryId) {
        for (Method method : target.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != String.class
                    || "toString".equals(method.getName())
                    || "c".equals(method.getName())
                    || "getEntryId".equals(method.getName())) {
                continue;
            }
            try {
                final String getterName = method.getDeclaringClass().getName() + "#" + method.getName();
                HookEntry.registerHook(method, hookId("post-result-" + getterName, method), chain -> {
                    Object result = chain.proceed();
                    try {
                        Object instance = chain.getThisObject();
                        if (instance == null) return result;
                        String mark = BLOCKED_POST_RESULTS.get(instance);
                        if (mark == null && result instanceof String) {
                            String text = (String) result;
                            if (!text.isEmpty()) {
                                String cached = TEXT_MARK_CACHE.get(text);
                                if (cached == null) {
                                    cached = RuleBridge.matchText(text);
                                    TEXT_MARK_CACHE.put(text, cached);
                                }
                                if (cached != null) {
                                    mark = cached;
                                }
                            }
                        }
                            if (mark != null && !mark.isEmpty()) {
                                diagMark(getterName);
                            }
                    } catch (Throwable failure) {
                        HookEntry.log("string getter inspection failed: " + getterName + ": " + failure);
                    }
                    return result;
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookPostConstructors() {
        Class<?> post = timelinePostClass;
        if (post == null) {
            return;
        }
        int installed = 0;
        for (Constructor<?> constructor : post.getDeclaredConstructors()) {
            try {
                HookEntry.registerHook(constructor, hookId("post-constructor", constructor), chain -> {
                    Object result = chain.proceed();
                    try {
                        Object item = chain.getThisObject();
                        if (item == null) return result;
                        String entryId = invokeString(item, postEntryIdMethod);
                        if (entryId == null || !entryId.startsWith("conversationthread-")) {
                            return result;
                        }
                        if (BLOCKED_IDS.containsKey(entryId)) return result;
                        String text = invokeString(item, postTextMethod);
                        String url = invokeString(item, postUrlMethod);
                        boolean isGrok = isGrokPost(item);
                        boolean isVerified = isVerifiedPost(item);
                        String author = resolveAuthorText(item);
                        RuleBridge.Match match = RuleBridge.firstMatch(
                                text, url, author, entryId, isGrok, isVerified);
                        if (match != null) {
                            BLOCKED_IDS.put(entryId, RuleBridge.getMarkText());
                            rememberPostResult(item, RuleBridge.getMarkText());
                            diagHit(entryId + " " + (text == null
                                    ? "<null>" : text.substring(0, Math.min(32, text.length()))));
                            RuleBridge.recordBlock(match, text, url, author);
                        }
                    } catch (Throwable failure) {
                        HookEntry.log("post constructor inspection failed: " + failure);
                    }
                    return result;
                });
                installed++;
            } catch (Throwable ignored) {
            }
        }
        POST_CTOR_HOOKS.set(installed);
        HookEntry.log(" hooked UrtTimelinePost constructors: " + installed);
    }

    /**
     * Marks blocked post text as 銆屽凡灞忚斀銆?directly in the rendering path
     * (URT renderer calls UrtTimelinePost.getText() unconditionally per item).
     */
    private static void hookPostTextOverride() {
        // Disabled: rewriting UrtTimelinePost.getText() crashes rich-text span slicing.
        return;
    }

    private static final class PostAccessors {
        final Class<?> postClass;
        final Method text;
        final Method url;
        final Method entryId;
        final Method author;
        final Method grok;
        final Method authorVerified;

        PostAccessors(Class<?> postClass, Method text, Method url, Method entryId, Method author,
                      Method grok, Method authorVerified) {
            this.postClass = postClass;
            this.text = text;
            this.url = url;
            this.entryId = entryId;
            this.author = author;
            this.grok = grok;
            this.authorVerified = authorVerified;
        }
    }

    private static PostAccessors resolvePostAccessors(ClassLoader classLoader) throws Throwable {
        Class<?> postClass = findClass(TIMELINE_POST, classLoader);
        Method text = findNoArgMethod(postClass, "getText");
        Method url = findNoArgMethod(postClass, "getUrl");
        if (text == null) {
            throw new IllegalStateException("UrtTimelinePost.getText() not found on " + TIMELINE_POST);
        }
        Method entryId = findNoArgMethod(postClass, "c");
        if (entryId == null) {
            entryId = findNoArgMethod(postClass, "getEntryId");
        }
        // s5#i() -> author (hh), s5#p() -> grok reply marker.
        Method author = findNoArgMethod(postClass, "i");
        Method grok = findNoArgMethod(postClass, "p");
        Method authorVerified = author == null ? null
                : findNoArgBooleanMethod(author.getReturnType(), "s");
        return new PostAccessors(postClass, text, url, entryId, author, grok, authorVerified);
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        if (clazz == null || name == null) return null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    private static Method findNoArgBooleanMethod(Class<?> clazz, String name) {
        if (clazz == null || name == null) return null;
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0
                    && (method.getReturnType() == boolean.class
                    || method.getReturnType() == Boolean.class)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_ENTRY = new java.util.concurrent.atomic.AtomicLong();

    /** Render-skip chain (X 12.22.0, verified against classes4.dex):
     *  l1 (UrtTimelinePost) -> presenter w4/d1 (field b = l1) -> b5 render state
     *  (field a = entryId String) -> whole-row composable
     *  com.x.jetfuel.v2.element.attribute.h#a(b5, c3, h5, Modifier, Composer, int),
     *  which draws avatar + user name + content per LazyColumn item, and inside it
     *  the per-post content lambda com.x.urt.items.post.i5 (Function2
     *  (Composer,Integer)->Unit, field a = b5) is created for the body.
     *  Whole-row skip: return early from h.a when the b5 entryId is blocked.
     *  Fallback skip: return early from i5.invoke when its captured b5 is blocked
     *  (covers surfaces that compose content without the h.a row wrapper).
     */
    private static volatile Class<?> i5RenderClass;
    private static volatile Method i5InvokeMethod;
    private static volatile java.lang.reflect.Field I5_B5_FIELD;      // i5.a -> b5
    private static volatile java.lang.reflect.Field B5_ENTRY_ID_FIELD; // b5.a -> String entryId

    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_I5 =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_ROW =
            new java.util.concurrent.atomic.AtomicLong();

    private static void diagSkip(String key) {
        if (diagAllowed(DIAG_LAST_I5, 10000)) {
            HookEntry.log("diag|I5-SKIP " + key);
        }
    }

    private static void diagRowSkip(String key) {
        if (diagAllowed(DIAG_LAST_ROW, 10000)) {
            HookEntry.log("diag|ROW-SKIP " + key);
        }
    }

    /** Resolves the b5 entryId accessor shared by both skip levels. */
    private static boolean resolveRenderFields() {
        ClassLoader loader = timelinePostClass.getClassLoader();
        try {
            Class<?> b5Class = findClass("com.x.urt.items.post.b5", loader);
            if (B5_ENTRY_ID_FIELD == null) {
                for (java.lang.reflect.Field field : b5Class.getDeclaredFields()) {
                    if ("a".equals(field.getName()) && field.getType() == String.class) {
                        field.setAccessible(true);
                        B5_ENTRY_ID_FIELD = field;
                        break;
                    }
                }
            }
            if (I5_B5_FIELD == null && i5RenderClass == null) {
                i5RenderClass = findClass("com.x.urt.items.post.i5", loader);
                for (java.lang.reflect.Field field : i5RenderClass.getDeclaredFields()) {
                    if ("a".equals(field.getName()) && field.getType() == b5Class) {
                        field.setAccessible(true);
                        I5_B5_FIELD = field;
                        break;
                    }
                }
            }
        } catch (Throwable failure) {
            HookEntry.log("render skip class load failed: " + failure);
            return false;
        }
        return B5_ENTRY_ID_FIELD != null;
    }

    private static void hookRenderSkipChain() {
        if (!resolveRenderFields()) {
            return;
        }
        if (i5RenderClass == null) {
            HookEntry.log("i5 render class not resolved");
            return;
        }
        final java.lang.reflect.Field b5Field = I5_B5_FIELD;
        final java.lang.reflect.Field entryField = B5_ENTRY_ID_FIELD;
        for (Method method : i5RenderClass.getDeclaredMethods()) {
            if ("invoke".equals(method.getName())
                    && method.getParameterTypes().length == 2
                    && method.getReturnType() == Object.class) {
                i5InvokeMethod = method;
                try {
                    HookEntry.registerHook(method, hookId("render-content", method), chain -> {
                        try {
                            Object i5 = chain.getThisObject();
                            if (i5 != null && !BLOCKED_IDS.isEmpty()
                                    && RuleBridge.getDisplayMode() == Contract.DISPLAY_MODE_REMOVE) {
                                Object b5 = b5Field.get(i5);
                                if (b5 != null) {
                                    Object entryId = entryField.get(b5);
                                    if (entryId instanceof String
                                            && BLOCKED_IDS.containsKey((String) entryId)) {
                                        diagSkip((String) entryId);
                                        return null;
                                    }
                                }
                            }
                        } catch (Throwable failure) {
                            HookEntry.log("content render inspection failed: " + failure);
                        }
                        return chain.proceed();
                    });
                    HookEntry.log(" hooked i5.invoke skip (b5.entryId)");
                } catch (Throwable failure) {
                    HookEntry.log("i5.invoke hook failed: " + failure);
                }
                return;
            }
        }
        HookEntry.log("i5.invoke(Composer,int) not found");
    }

    /** Whole-row skip: com.x.jetfuel.v2.element.attribute.h#a(b5,...) is the
     *  LazyColumn item composable that draws avatar, user name and content. */
    private static void hookPostRowSkip() {
        if (!resolveRenderFields()) {
            return;
        }
        Class<?> rowClass;
        try {
                rowClass = findClass("com.x.jetfuel.v2.element.attribute.h",
                    timelinePostClass.getClassLoader());
        } catch (Throwable failure) {
            HookEntry.log("post row class load failed: " + failure);
            return;
        }
        final java.lang.reflect.Field entryField = B5_ENTRY_ID_FIELD;
        int hooked = 0;
        for (Method method : rowClass.getDeclaredMethods()) {
            if (!"a".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] pts = method.getParameterTypes();
            if (pts.length < 6) {
                continue;
            }
            if (!"com.x.urt.items.post.b5".equals(pts[0].getName())) {
                continue;
            }
            if (!"androidx.compose.runtime.Composer".equals(pts[pts.length - 2].getName())) {
                continue;
            }
            try {
                HookEntry.registerHook(method, hookId("render-row", method), chain -> {
                    try {
                        if (!BLOCKED_IDS.isEmpty()
                                && RuleBridge.getDisplayMode() == Contract.DISPLAY_MODE_REMOVE) {
                            Object b5 = chain.getArg(0);
                            if (b5 != null) {
                                Object entryId = entryField.get(b5);
                                if (entryId instanceof String
                                        && BLOCKED_IDS.containsKey((String) entryId)) {
                                    diagRowSkip((String) entryId);
                                    return null;
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        HookEntry.log("row render inspection failed: " + failure);
                    }
                    return chain.proceed();
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        HookEntry.log(" hooked post row composable h.a: " + hooked);
    }

    /** MARK-mode placeholder: com.x.urt.items.post.b5 is the per-post render state
     *  built by w4/d1. When "显示已屏蔽占位" is on, the row is kept and the body
     *  text (b5.g) is replaced by the mark text and displayTextRange (b5.i) is reset to
     *  X's rich-text span slicing (displayTextRange) stays in bounds. REMOVE mode
     *  skips the whole row via hookPostRowSkip/hookRenderSkipChain instead. */
    private static volatile java.lang.reflect.Field B5_TEXT_FIELD;
    private static volatile java.lang.reflect.Field B5_RANGE_FIELD;   // b5.i -> com.x.models.text.i (displayTextRange)

    private static void hookMarkPlaceholder() {
        if (!resolveRenderFields()) {
            return;
        }
        Class<?> b5Class;
        try {
                b5Class = findClass("com.x.urt.items.post.b5",
                    timelinePostClass.getClassLoader());
        } catch (Throwable failure) {
            HookEntry.log("mark placeholder class load failed: " + failure);
            return;
        }
        if (B5_TEXT_FIELD == null) {
            for (java.lang.reflect.Field field : b5Class.getDeclaredFields()) {
                if ("g".equals(field.getName()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    B5_TEXT_FIELD = field;
                    break;
                }
            }
        }
        if (B5_TEXT_FIELD == null) {
            HookEntry.log("mark placeholder b5.text field not found");
            return;
        }
        if (B5_RANGE_FIELD == null) {
            for (java.lang.reflect.Field field : b5Class.getDeclaredFields()) {
                if ("i".equals(field.getName()) && field.getType().getName().equals("com.x.models.text.i")) {
                    field.setAccessible(true);
                    B5_RANGE_FIELD = field;
                    break;
                }
            }
        }
        final java.lang.reflect.Field entryField = B5_ENTRY_ID_FIELD;
        final java.lang.reflect.Field textField = B5_TEXT_FIELD;
        final java.lang.reflect.Field rangeField = B5_RANGE_FIELD;
        java.lang.reflect.Constructor<?> rangeCtor = null;
        if (rangeField != null) {
            try {
                rangeCtor = rangeField.getType().getDeclaredConstructor(int.class, int.class);
                rangeCtor.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        final java.lang.reflect.Constructor<?> rangeCtorF = rangeCtor;
        int hooked = 0;
        for (Constructor<?> constructor : b5Class.getDeclaredConstructors()) {
            try {
                HookEntry.registerHook(constructor, hookId("render-placeholder", constructor), chain -> {
                    Object result = chain.proceed();
                    try {
                        if (RuleBridge.getDisplayMode() != Contract.DISPLAY_MODE_MARK
                                || BLOCKED_IDS.isEmpty()) {
                            return result;
                        }
                        Object b5 = chain.getThisObject();
                        if (b5 == null) return result;
                        Object entryId = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                        if (!(entryId instanceof String)) {
                            entryId = entryField.get(b5);
                        }
                        if (!(entryId instanceof String)) return result;
                        String mark = BLOCKED_IDS.get((String) entryId);
                        if (mark == null || mark.isEmpty()) return result;
                        textField.set(b5, mark);
                        if (rangeField != null && rangeCtorF != null) {
                            rangeField.set(b5, rangeCtorF.newInstance(0, mark.length()));
                        }
                    } catch (Throwable failure) {
                        HookEntry.log("placeholder construction failed: " + failure);
                    }
                    return result;
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        MARK_HOOKS.set(hooked);
        HookEntry.log(" hooked mark placeholder b5.text: " + hooked);
    }

    private static XposedInterface.Hooker createFeedListCallback(final Class<?> immutableInterface) {
        return chain -> {
                if (!FILTER_INSTALLED.get()) {
                    return chain.proceed();
                }
                if (chain.getArgs().isEmpty() || chain.getArg(0) == null) {
                    return chain.proceed();
                }
                if (diagAllowed(DIAG_LAST_ENTRY, 5000)) {
                    Object list = chain.getArg(0);
                    int size = (list instanceof List<?>) ? ((List<?>) list).size() : -1;
                    HookEntry.log("diag|ENTRY " + chain.getExecutable() + " listSize=" + size
                            + " arg1=" + (chain.getArgs().size() > 1 && chain.getArg(1) != null
                            ? chain.getArg(1).getClass().getName() : "<null>"));
                }
                Object originalList = chain.getArg(0);
                if (!(originalList instanceof List<?>)) {
                    return chain.proceed();
                }
                Object argsAtOne = chain.getArgs().size() > 1 ? chain.getArg(1) : null;
                if (argsAtOne == null || !TIMELINE_TYPE.equals(argsAtOne.getClass().getName())) {
                    return chain.proceed();
                }
                List<?> filtered = filter((List<?>) originalList);
                if (filtered == null) {
                    return chain.proceed();
                }
                if (filtered.size() == ((List<?>) originalList).size()) {
                    return chain.proceed();
                }
                Object filteredList = createImmutableListProxy(
                        immutableInterface, originalList, filtered);
                Object[] args = chain.getArgs().toArray(new Object[0]);
                args[0] = filteredList;
                return chain.proceed(args);
        };
    }

    private static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, true, classLoader);
    }

    private static String hookId(String prefix, Executable executable) {
        return prefix + ":" + executable.getDeclaringClass().getName()
                + ":" + executable.getName() + ":" + executable.getParameterCount();
    }

    private static List<?> filter(List<?> original) {
        // MARK mode keeps rows in the list: detection runs in the l1 ctor hook,
        // placeholder runs in the b5 render hook. Scanning the list here would
        // cost 1600-rule matching per post on the UI thread for zero effect.
        if (RuleBridge.getDisplayMode() != Contract.DISPLAY_MODE_REMOVE) {
            return null;
        }
        try {
            int removed = 0;
            ArrayList<Object> out = new ArrayList<>(original.size());
            long listStart = System.currentTimeMillis();
            for (Object item : original) {
                if (timelinePostClass == null || !timelinePostClass.isInstance(item)) {
                    out.add(item);
                    continue;
                }
                String entryIdEarly = invokeString(item, postEntryIdMethod);
                if (entryIdEarly == null || !entryIdEarly.startsWith("conversationthread-")) {
                    out.add(item);
                    continue;
                }
                if (entryIdEarly != null) {
                    if (BLOCKED_IDS.containsKey(entryIdEarly)) {
                        removed++;
                        continue;
                    }
                    if (EVALUATED_IDS.contains(entryIdEarly)) {
                        out.add(item);
                        continue;
                    }
                }
                String text = invokeString(item, postTextMethod);
                String url = invokeString(item, postUrlMethod);
                String entryId = entryIdEarly;
                boolean isGrok = isGrokPost(item);
                boolean isVerified = isVerifiedPost(item);
                String author = resolveAuthorText(item);
                RuleBridge.Match match = RuleBridge.firstMatch(
                        text, url, author, entryId, isGrok, isVerified);
                if (entryId != null && hasEvaluationInput(text, url, author, isGrok)) {
                    EVALUATED_IDS.add(entryId);
                }
                if (match != null) {
                    removed++;
                    if (entryId != null) {
                        BLOCKED_IDS.put(entryId, RuleBridge.getMarkText());
                        rememberPostResult(item, RuleBridge.getMarkText());
                        RuleBridge.recordBlock(match, text, url, author);
                    } else {
                        RuleBridge.recordBlock(match, text, url, author);
                    }
                    continue;
                }
                out.add(item);
            }
            if (removed > 0 && FILTER_LOG_TICKS.incrementAndGet() % 50 == 1) {
                HookEntry.log("filter: list=" + original.size() + " removed=" + removed
                        + " mode=" + RuleBridge.getDisplayMode()
                        + " ms=" + (System.currentTimeMillis() - listStart));
            }
            return out.size() == original.size() ? null : out;
        } catch (Throwable throwable) {
            HookEntry.log("filter failed; keeping original list");
            HookEntry.logThrowable(throwable);
            return null;
        }
    }

    private static boolean isGrokPost(Object item) {
        if (postGrokMethod == null) return false;
        Object value = invokeValue(item, postGrokMethod);
        return value != null;
    }

    private static boolean isVerifiedPost(Object item) {
        Object author = invokeValue(item, postAuthorMethod);
        return Boolean.TRUE.equals(invokeValue(author, authorVerifiedMethod));
    }

    private static boolean hasEvaluationInput(String text, String url, String author,
                                              boolean isGrok) {
        return isGrok || hasText(text) || hasText(url) || hasText(author);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static String resolveAuthorText(Object item) {
        Object author = invokeValue(item, postAuthorMethod);
        String postAuthor = authorObjectText(author);
        if (postAuthor != null) return postAuthor;
        return authorObjectText(resolvePostResultAuthor(item));
    }

    private static Object resolvePostResultAuthor(Object item) {
        Object postResult = resolvePostResult(item);
        if (postResult == null) return null;
        Class<?> resultClass = postResult.getClass();
        if (postResultAuthorClass != resultClass) {
            postResultAuthorMethod = findAuthorObjectMethod(resultClass);
            postResultAuthorField = findAuthorObjectField(resultClass);
            postResultAuthorClass = resultClass;
        }
        Object author = invokeValue(postResult, postResultAuthorMethod);
        if (author != null) return author;
        if (postResultAuthorField != null) {
            try {
                author = postResultAuthorField.get(postResult);
            } catch (Throwable ignored) {
                author = null;
            }
            if (author != null) return author;
        }
        for (Method method : resultClass.getDeclaredMethods()) {
            if (!isAuthorObjectMethod(method)) continue;
            Object candidate = invokeValue(postResult, method);
            if (candidate != null) {
                postResultAuthorMethod = method;
                return candidate;
            }
        }
        for (java.lang.reflect.Field field : resultClass.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                    || field.getType() == String.class || !isAuthorObjectName(name)) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object candidate = field.get(postResult);
                if (candidate != null) {
                    postResultAuthorField = field;
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }
        for (java.lang.reflect.Field field : resultClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                    || field.getType() == String.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object candidate = field.get(postResult);
                if (authorObjectText(candidate) != null) {
                    postResultAuthorField = field;
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object resolvePostResult(Object item) {
        if (item == null) return null;
        try {
            java.lang.reflect.Field field = POST_RESULT_FIELD;
            if (field == null || !field.getDeclaringClass().isInstance(item)) {
                field = item.getClass().getDeclaredField("a");
                field.setAccessible(true);
                POST_RESULT_FIELD = field;
            }
            return field.get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findAuthorObjectMethod(Class<?> type) {
        for (String name : AUTHOR_ACCESSOR_NAMES) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && isAuthorObjectMethod(method)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (isAuthorObjectMethod(method)) return method;
        }
        return null;
    }

    private static java.lang.reflect.Field findAuthorObjectField(Class<?> type) {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (isAuthorObjectName(name) && !field.getType().isPrimitive()
                    && field.getType() != String.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static boolean isAuthorObjectMethod(Method method) {
        if (method.getParameterTypes().length != 0
                || method.getReturnType().isPrimitive()
                || method.getReturnType() == String.class
                || method.getReturnType() == Void.TYPE) {
            return false;
        }
        return isAuthorObjectName(method.getName().toLowerCase(Locale.ROOT));
    }

    private static boolean isAuthorObjectName(String name) {
        return (name.contains("user") || name.contains("author") || name.contains("account"))
                && !name.contains("reply") && !name.contains("mention")
                && !name.contains("quote") && !name.contains("parent")
                && !name.contains("retweet");
    }

    private static String authorObjectText(Object author) {
        if (author == null) return null;
        AuthorTextAccessors accessors = AUTHOR_TEXT_ACCESSORS.computeIfAbsent(
                author.getClass(), TimelineFilter::resolveAuthorTextAccessors);
        String firstValue = null;
        for (Method method : accessors.handles) {
            String value = invokeString(author, method);
            if (value == null || value.trim().isEmpty()) continue;
            String trimmed = value.trim();
            if (trimmed.startsWith("@")) return trimmed;
            if (firstValue == null) firstValue = trimmed;
        }
        if (firstValue == null) firstValue = findAuthorHandleField(author);
        if (firstValue == null) return null;
        return firstValue.startsWith("@") ? firstValue : "@" + firstValue;
    }

    private static String findAuthorHandleField(Object author) {
        for (java.lang.reflect.Field field : author.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!(name.contains("handle") || name.contains("screen") || name.contains("user"))) {
                continue;
            }
            try {
                field.setAccessible(true);
                String value = (String) field.get(author);
                if (value != null && !value.trim().isEmpty()) return value.trim();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static AuthorTextAccessors resolveAuthorTextAccessors(Class<?> type) {
        return new AuthorTextAccessors(findStringMethods(type, AUTHOR_HANDLE_NAMES));
    }

    private static List<Method> findStringMethods(Class<?> type, String[] names) {
        List<Method> methods = new ArrayList<>();
        for (String name : names) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == String.class) {
                    method.setAccessible(true);
                    methods.add(method);
                }
            }
        }
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == String.class) {
                    if (!methods.contains(method)) methods.add(method);
                }
            }
        }
        return methods;
    }

    private static final class AuthorTextAccessors {
        final Method[] handles;

        AuthorTextAccessors(List<Method> handles) {
            this.handles = handles.toArray(new Method[0]);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_CT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_BP = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_SG = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_MARK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_MISS = new java.util.concurrent.atomic.AtomicLong();

    private static boolean diagAllowed(java.util.concurrent.atomic.AtomicLong last, long windowMs) {
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous < windowMs) {
            return false;
        }
        return last.compareAndSet(previous, now);
    }

    private static void diagHit(String key) {
        if (diagAllowed(DIAG_LAST_CT, 10000)) {
            HookEntry.log("diag|CT " + key);
        }
    }

    private static void diagBind(String key) {
        if (diagAllowed(DIAG_LAST_BP, 10000)) {
            HookEntry.log("diag|BP " + key);
        }
    }

    private static void diagGetter(String key) {
        if (diagAllowed(DIAG_LAST_SG, 10000)) {
            HookEntry.log("diag|SG " + key);
        }
    }

    private static void diagMark(String key) {
        if (diagAllowed(DIAG_LAST_MARK, 10000)) {
            HookEntry.log("diag|MARK-HIT " + key);
        }
    }

    private static void diagMiss(String key) {
        if (diagAllowed(DIAG_LAST_MISS, 10000)) {
            HookEntry.log("diag|SG-MISS " + key);
        }
    }

    /** Links the l1 instance to its contained postResult object for render-time marking. */
    private static void rememberPostResult(Object item, String markText) {
        try {
            java.lang.reflect.Field field = POST_RESULT_FIELD;
            if (field == null) {
                field = item.getClass().getDeclaredField("a");
                field.setAccessible(true);
                POST_RESULT_FIELD = field;
            }
            Object postResult = field.get(item);
            if (postResult != null) {
                BLOCKED_POST_RESULTS.put(postResult, markText);
            }
        } catch (Throwable failure) {
            HookEntry.log("diag|rememberPostResult FAILED: " + failure);
        }
    }

    private static String invokeString(Object receiver, Method method) {
        Object value = invokeValue(receiver, method);
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeValue(Object receiver, Method method) {
        if (method == null) return null;
        try {
            if (!method.isAccessible()) {
                method.setAccessible(true);
            }
            return method.invoke(receiver);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object createImmutableListProxy(
            Class<?> immutableInterface, Object originalList, List<?> filteredList) {
        return Proxy.newProxyInstance(
                immutableInterface.getClassLoader(),
                new Class<?>[]{immutableInterface},
                new FilteredListHandler(immutableInterface, originalList, filteredList));
    }

    private static final class FilteredListHandler implements InvocationHandler {
        private final Class<?> interfaceType;
        private final Object originalList;
        private final List<?> filteredList;

        private FilteredListHandler(Class<?> interfaceType, Object originalList, List<?> filteredList) {
            this.interfaceType = interfaceType;
            this.originalList = originalList;
            this.filteredList = filteredList;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int paramCount = method.getParameterTypes().length;

            if ("equals".equals(name) && paramCount == 1) return filteredList.equals(args[0]);
            if ("hashCode".equals(name) && paramCount == 0) return filteredList.hashCode();
            if ("toString".equals(name) && paramCount == 0) return filteredList.toString();

            if ("subList".equals(name) && paramCount == 2
                    && method.getReturnType() == interfaceType) {
                Object originalSub = invokeDelegate(method, originalList, args);
                if (originalSub instanceof List<?>) {
                    return Proxy.newProxyInstance(
                            method.getReturnType().getClassLoader(),
                            new Class<?>[]{method.getReturnType()},
                            new FilteredListHandler(method.getReturnType(), originalSub,
                                    filteredList.subList((Integer) args[0], (Integer) args[1])));
                }
                return originalSub;
            }

            // Everything readable must be served from the filtered list, including
            // immutable-list specific methods declared on the kotlinx interface itself.
            // Fall back to the original object only when delegating fails (never for reads).
            try {
                return method.invoke(filteredList, args);
            } catch (Throwable throwable) {
                return invokeDelegate(method, originalList, args);
            }
        }

        private static Object invokeDelegate(Method method, Object receiver, Object[] args)
                throws Throwable {
            try {
                return method.invoke(receiver, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                throw cause == null ? exception : cause;
            }
        }
    }

}
