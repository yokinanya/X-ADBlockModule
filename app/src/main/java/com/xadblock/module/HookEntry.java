package com.xadblock.module;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * X-ADBlock entry point. Hooks the official X Android app (com.twitter.android),
 * removes posts whose text/url matches the cloud/local keyword rulesets.
 *
 * Reverse-engineering notes (X 12.22.0, see orchestra/reverse-findings-x-12.22.0.md):
 *  - UrtTimelinePost real dex name: com.x.models.timelines.items.l1
 *  - Main-feed compose entry: com.x.urt.ui.o#c(...) arg[0] = kotlinx.collections.immutable.b
 *  - Fallback closure/remember classes: com.x.urt.ui.h / com.x.urt.ui.j (ctor arg[0] same)
 */
public final class HookEntry extends XposedModule {
    private static final String TAG = "[X-ADBlock]";
    private static volatile HookEntry activeInstance;

    private final List<HookHandle> hookHandles = new CopyOnWriteArrayList<>();

    public HookEntry() {
        activeInstance = this;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.twitter.android".equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        log(Log.INFO, TAG, "loading into " + param.getPackageName());

        try {
            hookApplicationAttach();
            TimelineFilter.install(param.getClassLoader());
        } catch (Throwable failure) {
            log(Log.ERROR, TAG, "failed to install target hooks", failure);
            logThrowable(failure);
        }
        // Browsing history is independent from filtering: keep it alive even if the
        // feed filter failed to install, and never let it break the filter either.
        PostViewTracker.install(param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        TimelineFilter.clearState();
        PostViewTracker.clearState();
        RuleBridge.clearState();
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        ClassLoader classLoader = null;
        for (HookHandle handle : param.getOldHookHandles()) {
            if (classLoader == null) {
                classLoader = handle.getExecutable().getDeclaringClass().getClassLoader();
            }
            handle.unhook();
        }
        hookHandles.clear();
        if (classLoader != null) {
            try {
                hookApplicationAttach();
                TimelineFilter.install(classLoader);
            } catch (Throwable failure) {
                log(Log.ERROR, TAG, "failed to reinstall hooks after hot reload", failure);
                logThrowable(failure);
            }
            PostViewTracker.install(classLoader);
        }
    }

    private void hookApplicationAttach() throws Throwable {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        registerHook(attach, "application_attach", chain -> {
            Object result = chain.proceed();
            Object context = chain.getArg(0);
            if (context instanceof Context) {
                HookLogSink.init((Context) context);
                RuleBridge.initialize((Context) context);
            }
            return result;
        });
        log(Log.INFO, TAG, "Application.attach hook installed");
    }

    static HookHandle registerHook(
            Executable executable, String id, XposedInterface.Hooker hooker) {
        HookEntry instance = activeInstance;
        if (instance == null) {
            throw new IllegalStateException("X-ADBlock module entry is not active");
        }
        return instance.register(executable, id, hooker);
    }

    static SharedPreferences remotePreferences(String group) {
        HookEntry instance = activeInstance;
        if (instance == null) {
            throw new IllegalStateException("X-ADBlock module entry is not active");
        }
        return instance.getRemotePreferences(group);
    }

    private HookHandle register(
            Executable executable, String id, XposedInterface.Hooker hooker) {
        HookHandle handle = hook(executable)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        hookHandles.add(handle);
        return handle;
    }

    static void log(String message) {
        HookEntry instance = activeInstance;
        if (instance != null) {
            instance.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
        HookLogSink.log(message);
    }

    static void logThrowable(Throwable throwable) {
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        for (String line : writer.toString().split("\\r?\\n")) {
            HookLogSink.log(line);
        }
    }
}
