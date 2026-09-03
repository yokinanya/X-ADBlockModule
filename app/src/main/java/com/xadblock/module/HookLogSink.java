package com.xadblock.module;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes the injected-process log into /sdcard/Download/xadblock_hook.log via
 * MediaStore (no storage permission needed on Android 10+). The file is capped
 * to ~512KB, keeping the newest lines, so adb-pulling is always cheap.
 */
public final class HookLogSink {
    private static final String FILE_NAME = "xadblock_hook.log";
    private static final long MAX_BYTES = 512 * 1024;
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(4096);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Context context;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private HookLogSink() {}

    public static void init(Context appContext) {
        context = appContext;
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread flusher = new Thread(HookLogSink::flushLoop, "xadblock-hook-log");
        flusher.setDaemon(true);
        flusher.start();
    }

    public static void log(String message) {
        QUEUE.offer(TIME.format(new Date()) + " " + message);
    }

    private static void flushLoop() {
        while (true) {
            try {
                ArrayList<String> lines = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 2000;
                while (true) {
                    String line = QUEUE.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (line == null) {
                        break;
                    }
                    lines.add(line);
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                }
                if (!lines.isEmpty()) {
                    append(String.join("\n", lines) + "\n");
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable ignored) {
                // logging must never break the injected process
            }
        }
    }

    private static final StringBuilder FALLBACK = new StringBuilder(16 * 1024);

    /** Returns and clears pending fallback lines (delivered via heartbeat extras). */
    public static synchronized String drainFallback() {
        if (FALLBACK.length() == 0) {
            return null;
        }
        String value = FALLBACK.toString();
        FALLBACK.setLength(0);
        return value;
    }

    private static synchronized void pushFallback(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        FALLBACK.append(text);
        if (FALLBACK.length() > 16 * 1024) {
            FALLBACK.delete(0, FALLBACK.length() - 16 * 1024);
        }
    }

    private static void append(String text) {
        if (context == null || Build.VERSION.SDK_INT < 29) {
            pushFallback(text);
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = null;
            try (Cursor cursor = resolver.query(
                    collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{FILE_NAME},
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    uri = Uri.withAppendedPath(collection, cursor.getLong(0) + "");
                    ContentValues update = new ContentValues();
                    update.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                    update.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
                    update.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    resolver.update(uri, update, null, null);
                } else {
                    uri = resolver.insert(collection, values);
                }
            }

            if (uri == null) {
                return;
            }
            byte[] old = new byte[0];
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input != null) {
                    old = readAll(input);
                }
            } catch (Throwable ignored) {
                old = new byte[0];
            }
            byte[] next = concat(old, text.getBytes(StandardCharsets.UTF_8));
            if (next.length > MAX_BYTES) {
                int keepFrom = Math.max(0, next.length - (int) (MAX_BYTES * 0.6));
                byte[] trimmed = copyOfRange(next, keepFrom, next.length);
                next = new byte[]{(byte) 0xEF};
                // prepend a marker line to indicate truncation
                next = concat("\n... [log truncated] ...\n".getBytes(StandardCharsets.UTF_8), trimmed);
                // enforce cap again
                if (next.length > MAX_BYTES) {
                    next = copyOfRange(next, next.length - (int) (MAX_BYTES * 0.9), next.length);
                }
            }
            ContentValues finalValues = new ContentValues();
            finalValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, finalValues, null, null);
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output != null) {
                    output.write(next);
                }
            } finally {
                ContentValues clear = new ContentValues();
                clear.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, clear, null, null);
            }
        } catch (Throwable ignored) {
            pushFallback(text);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] copyOfRange(byte[] bytes, int from, int to) {
        byte[] out = new byte[Math.max(0, to - from)];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) {
            o.write(buffer, 0, read);
        }
        return o.toByteArray();
    }
}
