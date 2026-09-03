package com.xadblock.module.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream
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

    private fun append(text: String) {
        val context = contextRef ?: return
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver: ContentResolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            var uri: Uri? = null
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(FILE_NAME),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                }
            }
            if (uri == null) {
                uri = resolver.insert(collection, values)
            }
            if (uri == null) return

            val old: ByteArray = try {
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            } catch (ignored: Throwable) {
                ByteArray(0)
            }
            var next = old + text.toByteArray()
            if (next.size > MAX_BYTES) {
                val truncated = next.copyOfRange(next.size - (MAX_BYTES * 0.6).toInt(), next.size)
                next = "... [log truncated] ...\n".toByteArray() + truncated
            }
            resolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(next)
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (failure: Throwable) {
            android.util.Log.e("X-ADBlock", "module log append failed", failure)
        }
    }
}
