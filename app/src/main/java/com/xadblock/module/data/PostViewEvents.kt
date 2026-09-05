package com.xadblock.module.data

/**
 * Wire format and search rules for the browsing history. Deliberately free of Android
 * dependencies so both can be covered by plain JVM unit tests.
 */
object PostViewEvents {

    private const val MAX_AUTHOR = 80
    private const val MAX_PREVIEW = 300

    /** Hook payload for one opened post: postId, url, @handle, display name, text. */
    fun parse(line: String, now: Long = System.currentTimeMillis()): PostViewEntity? {
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val postId = parts[0].trim().takeIf(String::isNotEmpty) ?: return null
        val url = parts[1].trim().takeIf(String::isNotEmpty) ?: return null
        return PostViewEntity(
            postId = postId,
            url = url,
            author = parts.getOrNull(2)?.trim()?.take(MAX_AUTHOR).orEmpty(),
            authorName = parts.getOrNull(3)?.trim()?.take(MAX_AUTHOR).orEmpty(),
            preview = parts.getOrNull(4)?.trim()?.take(MAX_PREVIEW).orEmpty(),
            ts = now
        )
    }

    /** Case-insensitive keyword match across post text, author and link. */
    fun matches(entry: PostViewEntity, keyword: String): Boolean {
        if (keyword.isEmpty()) return true
        return entry.preview.contains(keyword, ignoreCase = true) ||
            entry.author.contains(keyword, ignoreCase = true) ||
            entry.authorName.contains(keyword, ignoreCase = true) ||
            entry.url.contains(keyword, ignoreCase = true)
    }
}
