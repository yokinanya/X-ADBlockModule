package com.xadblock.module.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Module-side log writer to /sdcard/Download/xadblock_module.log via MediaStore
 * (no storage permission needed on Android 10+). Cap ~512KB newest lines.
 */
object ModuleLogger {
    private const val FILE_NAME = "xadblock_module.log"
    private const val MAX_BYTES = 512 * 1024
    private val queue = ArrayBlockingQueue<String>(4096)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var started = false
    private var contextRef: Context? = null
    private var cachedUri: Uri? = null

    @Synchronized
    fun init(context: Context) {
        if (!started) {
            started = true
            contextRef = context.applicationContext
            Thread { flushLoop() }.apply {
                name = "xadblock-module-log"
                isDaemon = true
                start()
            }
        }
    }

    fun log(message: String) {
        queue.offer("${time.format(Date())} $message")
    }

    private fun flushLoop() {
        while (true) {
            try {
                val lines = ArrayList<String>()
                var deadline = System.currentTimeMillis() + 2000
                while (true) {
                    val line = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                        ?: break
                    lines.add(line)
                    if (System.currentTimeMillis() >= deadline) break
                }
                if (lines.isNotEmpty()) {
                    append(lines.joinToString("\n") + "\n")
                }
            } catch (ignored: InterruptedException) {
                return
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Appends through MediaStore. A reinstall orphans the previous entry (the new install
     * no longer owns it), so a failed write now falls back to a freshly created entry
     * instead of silently dropping every log line.
     */
    private fun append(text: String) {
        val context = contextRef ?: return
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target = cachedUri
                ?: findExisting(resolver, collection)
                ?: insertNew(resolver, collection)
            if (target != null && writeAll(resolver, target, text)) {
                cachedUri = target
                return
            }
            cachedUri = null
            val fresh = insertNew(resolver, collection) ?: return
            if (writeAll(resolver, fresh, text)) {
                cachedUri = fresh
                android.util.Log.i("X-ADBlock", "module log switched to a new file: " + fresh)
            }
        } catch (failure: Throwable) {
            android.util.Log.e("X-ADBlock", "module log append failed", failure)
        }
    }

    private fun findExisting(resolver: ContentResolver, collection: Uri): Uri? {
        return try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(FILE_NAME),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                } else {
                    null
                }
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun insertNew(resolver: ContentResolver, collection: Uri): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        return try {
            resolver.insert(collection, values)
        } catch (ignored: Throwable) {
            null
        }
    }

    /** Rewrites the file with the newest ~512KB; false means the entry is not writable. */
    private fun writeAll(resolver: ContentResolver, uri: Uri, text: String): Boolean {
        return try {
            val old: ByteArray = try {
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            } catch (ignored: Throwable) {
                ByteArray(0)
            }
            var next = old + text.toByteArray()
            if (next.size > MAX_BYTES) {
                val kept = next.copyOfRange(next.size - (MAX_BYTES * 0.6).toInt(), next.size)
                next = "... [log truncated] ...\n".toByteArray() + kept
            }
            val stream = resolver.openOutputStream(uri, "wt") ?: return false
            stream.use { output -> output.write(next) }
            true
        } catch (failure: Throwable) {
            android.util.Log.e("X-ADBlock", "module log write failed", failure)
            false
        }
    }
}
