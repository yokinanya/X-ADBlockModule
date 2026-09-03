package com.xadblock.module.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Download + parse a remote keyword file into rules for one subscription. */
object RuleSync {

    data class SyncResult(val status: String, val error: String = "", val ruleCount: Int = 0)

    /** Fetches text; returns null when server answers 304 (not modified). */
    suspend fun download(url: String, etag: String?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "X-ADBlockModule/0.1 (LSPosed keyword filter)")
                setRequestProperty("Accept", "application/vnd.github.v3.raw, text/plain, */*")
                setRequestProperty("Accept-Encoding", "gzip")
                if (!etag.isNullOrEmpty()) {
                    setRequestProperty("If-None-Match", etag)
                }
            }
            try {
                val code = connection.responseCode
                when (code) {
                    304 -> null to null
                    in 200..299 -> readText(connection) to connection.getHeaderField("ETag")
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }

    private fun readText(connection: HttpURLConnection): String {
        connection.inputStream.use { raw ->
            val input: InputStream = if ("gzip" == connection.contentEncoding) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return input.bufferedReader().readText()
        }
    }
}
