package com.xadblock.module;

import com.xadblock.module.data.Contract;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
    private static final java.util.concurrent.atomic.AtomicInteger FILTER_LOG_TICKS =
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
    private static volatile Method authorNameMethod;
    private static volatile Method authorHandleMethod;
    private static volatile Method authorHandle2Method;

    private TimelineFilter() {}

    static void install(ClassLoader classLoader) throws Throwable {
        try {
            PostAccessors accessors = resolvePostAccessors(classLoader);
            timelinePostClass = accessors.postClass;
            postTextMethod = accessors.text;
            postUrlMethod = accessors.url;
            postEntryIdMethod = accessors.entryId;
            postAuthorMethod = accessors.author;
            postGrokMethod = accessors.grok;
            authorNameMethod = accessors.authorName;
            authorHandleMethod = accessors.authorHandle;
            authorHandle2Method = accessors.authorHandle2;
            timelineItemInterface = XposedHelpers.findClass(TIMELINE_ITEM_IF, classLoader);

            Class<?> uiEntryClass = XposedHelpers.findClass(UI_ENTRY, classLoader);
            int installedEntry = 0;
            for (Method method : uiEntryClass.getDeclaredMethods()) {
                if (isFeedEntryMethod(method)) {
                    XposedBridge.hookMethod(method,
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
            HookEntry.log(" feed filter installed (entry=" + installedEntry
                    + ", lambda=" + installedLambda + ")");
        } catch (Throwable throwable) {
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
            Class<?> lambdaClass = XposedHelpers.findClass(className, classLoader);
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
                XposedBridge.hookMethod(constructor,
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
                Class<?> implClass = XposedHelpers.findClass(implName, timelinePostClass.getClassLoader());
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
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object instance = param.thisObject;
                            if (instance == null) return;
                            String mark = BLOCKED_POST_RESULTS.get(instance);
                            if (mark == null) {
                                Object result = param.getResult();
                                if (result instanceof String) {
                                    String text = (String) result;
                                    if (text != null && !text.isEmpty()) {
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
                            }
                            // NOTE: result rewriting is DISABLED - X renders rich-text spans
                            // (DisplayTextRange) against the original text, replacing it with
                            // a shorter string crashes with "Error slicing text". Detection and
                            // history stay active; hiding must happen at the data/list level.
                            if (false && mark != null && !mark.equals(param.getResult())) {
                                param.setResult(mark);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
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
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object item = param.thisObject;
                            if (item == null) {
                                return;
                            }
                            String entryId = invokeString(item, postEntryIdMethod);
                            if (entryId == null || !entryId.startsWith("conversationthread-")) {
                                return;
                            }
                            if (BLOCKED_IDS.containsKey(entryId)) {
                                return;
                            }
                            String text = invokeString(item, postTextMethod);
                            String url = invokeString(item, postUrlMethod);
                            boolean isGrok = isGrokPost(item);
                            String author = resolveAuthorText(item);
                            RuleBridge.Match match =
                                    RuleBridge.firstMatch(text, url, author, entryId, isGrok);
                            if (match != null) {
                                BLOCKED_IDS.put(entryId, RuleBridge.getMarkText());
                                rememberPostResult(item, RuleBridge.getMarkText());
                                diagHit(entryId + " " + (text == null ? "<null>" : text.substring(0, Math.min(32, text.length()))));
                                RuleBridge.recordBlock(match, text);
                            }
                        } catch (Throwable ignored) {
                            // detection is best-effort; rendering stays intact
                        }
                    }
                });
                installed++;
            } catch (Throwable ignored) {
            }
        }
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
        final Method authorName;
        final Method authorHandle;
        final Method authorHandle2;

        PostAccessors(Class<?> postClass, Method text, Method url, Method entryId, Method author,
                      Method grok, Method authorName, Method authorHandle, Method authorHandle2) {
            this.postClass = postClass;
            this.text = text;
            this.url = url;
            this.entryId = entryId;
            this.author = author;
            this.grok = grok;
            this.authorName = authorName;
            this.authorHandle = authorHandle;
            this.authorHandle2 = authorHandle2;
        }
    }

    private static PostAccessors resolvePostAccessors(ClassLoader classLoader) throws Throwable {
        Class<?> postClass = XposedHelpers.findClass(TIMELINE_POST, classLoader);
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
        Method authorName = null;
        Method authorHandle = null;
        Method authorHandle2 = null;
        if (author != null && author.getReturnType() != null) {
            try {
                authorName = findNoArgMethod(author.getReturnType(), "getName");
                authorHandle = findNoArgMethod(author.getReturnType(), "C");
                authorHandle2 = findNoArgMethod(author.getReturnType(), "w");
            } catch (Throwable ignored) {
            }
        }
        return new PostAccessors(postClass, text, url, entryId, author, grok,
                authorName, authorHandle, authorHandle2);
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
            Class<?> b5Class = XposedHelpers.findClass("com.x.urt.items.post.b5", loader);
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
                i5RenderClass = XposedHelpers.findClass("com.x.urt.items.post.i5", loader);
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
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object i5 = param.thisObject;
                                if (i5 == null || BLOCKED_IDS.isEmpty()) return;
                                if (RuleBridge.getDisplayMode() != Contract.DISPLAY_MODE_REMOVE) return;
                                Object b5 = b5Field.get(i5);
                                if (b5 == null) return;
                                Object entryId = entryField.get(b5);
                                if (entryId instanceof String
                                        && BLOCKED_IDS.containsKey((String) entryId)) {
                                    param.setResult(null);
                                    diagSkip((String) entryId);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
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
            rowClass = XposedHelpers.findClass("com.x.jetfuel.v2.element.attribute.h",
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
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (BLOCKED_IDS.isEmpty()) return;
                            if (RuleBridge.getDisplayMode() != Contract.DISPLAY_MODE_REMOVE) return;
                            Object b5 = param.args[0];
                            if (b5 == null) return;
                            Object entryId = entryField.get(b5);
                            if (!(entryId instanceof String)
                                    || !BLOCKED_IDS.containsKey((String) entryId)) {
                                return;
                            }
                            param.setResult(null);
                            diagSkip((String) entryId);
                        } catch (Throwable ignored) {
                        }
                    }
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
            b5Class = XposedHelpers.findClass("com.x.urt.items.post.b5",
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
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (RuleBridge.getDisplayMode() != Contract.DISPLAY_MODE_MARK) {
                                return;
                            }
                            if (BLOCKED_IDS.isEmpty()) return;
                            Object b5 = param.thisObject;
                            if (b5 == null) return;
                            Object entryId = param.args.length > 0 ? param.args[0] : null;
                            if (!(entryId instanceof String)) {
                                try {
                                    entryId = entryField.get(b5);
                                } catch (Throwable ignored) {
                                    return;
                                }
                            }
                            String mark = BLOCKED_IDS.get((String) entryId);
                            if (mark == null || mark.isEmpty()) return;
                            textField.set(b5, mark);
                            if (rangeField != null && rangeCtorF != null) {
                                rangeField.set(b5, rangeCtorF.newInstance(0, mark.length()));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        HookEntry.log(" hooked mark placeholder b5.text: " + hooked);
    }

    private static XC_MethodHook createFeedListCallback(final Class<?> immutableInterface) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!FILTER_INSTALLED.get()) {
                    return;
                }
                Object[] args = param.args;
                if (args == null || args.length == 0 || args[0] == null) {
                    return;
                }
                if (diagAllowed(DIAG_LAST_ENTRY, 5000)) {
                    Object list = args[0];
                    int size = (list instanceof List<?>) ? ((List<?>) list).size() : -1;
                    HookEntry.log("diag|ENTRY " + param.method + " listSize=" + size
                            + " arg1=" + (args.length > 1 && args[1] != null ? args[1].getClass().getName() : "<null>"));
                }
                Object originalList = args[0];
                if (!(originalList instanceof List<?>)) {
                    return;
                }
                Object argsAtOne = args[1];
                if (argsAtOne == null || !TIMELINE_TYPE.equals(argsAtOne.getClass().getName())) {
                    return;
                }
                List<?> filtered = filter((List<?>) originalList);
                if (filtered == null) {
                    return;
                }
                if (filtered.size() == ((List<?>) originalList).size()) {
                    return;
                }
                args[0] = createImmutableListProxy(
                        immutableInterface, originalList, filtered);
            }
        };
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
                String author = resolveAuthorText(item);
                RuleBridge.Match match = RuleBridge.firstMatch(text, url, author, entryId, isGrok);
                if (entryId != null) {
                    EVALUATED_IDS.add(entryId);
                }
                if (match != null) {
                    removed++;
                    if (entryId != null) {
                        BLOCKED_IDS.put(entryId, RuleBridge.getMarkText());
                        rememberPostResult(item, RuleBridge.getMarkText());
                        RuleBridge.recordBlock(match, text);
                    } else {
                        RuleBridge.recordBlock(match, text);
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

    private static String resolveAuthorText(Object item) {
        if (postAuthorMethod == null) return null;
        Object author = invokeValue(item, postAuthorMethod);
        if (author == null) return null;
        StringBuilder sb = new StringBuilder();
        appendIfNotNull(sb, invokeString(author, authorNameMethod));
        appendIfNotNull(sb, invokeString(author, authorHandleMethod));
        appendIfNotNull(sb, invokeString(author, authorHandle2Method));
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendIfNotNull(StringBuilder sb, String value) {
        if (value == null) return;
        sb.append(value).append(' ');
    }

    private static volatile java.lang.reflect.Field POST_RESULT_FIELD;

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
