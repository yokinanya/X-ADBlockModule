package com.xadblock.module;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * X-ADBlock entry point. Hooks the official X Android app (com.twitter.android),
 * removes posts whose text/url matches the cloud/local keyword rulesets.
 *
 * Reverse-engineering notes (X 12.22.0, see orchestra/reverse-findings-x-12.22.0.md):
 *  - UrtTimelinePost real dex name: com.x.models.timelines.items.l1
 *  - Main-feed compose entry: com.x.urt.ui.o#c(...) arg[0] = kotlinx.collections.immutable.b
 *  - Fallback closure/remember classes: com.x.urt.ui.h / com.x.urt.ui.j (ctor arg[0] same)
 */
public final class HookEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "[X-ADBlock]";
    private static volatile String modulePath;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        if (startupParam.modulePath == null || startupParam.modulePath.isEmpty()) {
            throw new IllegalStateException("X-ADBlock: no modulePath from Xposed");
        }
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.twitter.android".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + " loading into " + lpparam.packageName);

        hookApplicationAttach();
        TimelineFilter.install(lpparam.classLoader);
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object context = param.args[0];
                            if (context instanceof Context) {
                                HookLogSink.init((Context) context);
                                RuleBridge.initialize((Context) context);
                            }
                        }
                    });
            XposedBridge.log(TAG + " Application.attach hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " FAILED to install Application.attach hook");
            logThrowable(throwable);
        }
    }

    static String getModulePath() {
        return modulePath;
    }

    static void log(String message) {
        XposedBridge.log(TAG + " " + message);
        HookLogSink.log(message);
    }

    static void logThrowable(Throwable throwable) {
        XposedBridge.log(throwable);
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        for (String line : writer.toString().split("\\r?\\n")) {
            HookLogSink.log(line);
        }
    }
}
