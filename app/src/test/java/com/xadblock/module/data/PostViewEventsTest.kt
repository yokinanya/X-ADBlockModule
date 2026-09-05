package com.xadblock.module.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostViewEventsTest {

    @Test
    fun `parses a full hook payload line`() {
        val entry = PostViewEvents.parse(
            "1234567890\thttps://x.com/someone/status/1234567890\tsomeone\t示例用户\t今天天气不错",
            now = 42L
        )
        assertEquals("1234567890", entry?.postId)
        assertEquals("https://x.com/someone/status/1234567890", entry?.url)
        assertEquals("someone", entry?.author)
        assertEquals("示例用户", entry?.authorName)
        assertEquals("今天天气不错", entry?.preview)
        assertEquals(42L, entry?.ts)
    }

    @Test
    fun `keeps the link when the post has no text`() {
        val entry = PostViewEvents.parse("111\thttps://x.com/i/status/111\t\t\t")
        assertEquals("111", entry?.postId)
        assertEquals("", entry?.preview)
        assertEquals("", entry?.author)
    }

    @Test
    fun `rejects lines without an id or link`() {
        assertNull(PostViewEvents.parse(""))
        assertNull(PostViewEvents.parse("1234567890"))
        assertNull(PostViewEvents.parse("\thttps://x.com/i/status/1"))
        assertNull(PostViewEvents.parse("1234567890\t"))
    }

    @Test
    fun `clamps oversized author and preview fields`() {
        val entry = PostViewEvents.parse(
            "1\thttps://x.com/i/status/1\t" + "h".repeat(200) + "\t" + "n".repeat(200) +
                "\t" + "t".repeat(900)
        )
        assertEquals(80, entry?.author?.length)
        assertEquals(80, entry?.authorName?.length)
        assertEquals(300, entry?.preview?.length)
    }

    @Test
    fun `search matches text author handle and link regardless of case`() {
        val entry = PostViewEntity(
            postId = "1",
            url = "https://x.com/SomeOne/status/1",
            author = "SomeOne",
            authorName = "示例用户",
            preview = "Hello 世界"
        )
        assertTrue(PostViewEvents.matches(entry, "世界"))
        assertTrue(PostViewEvents.matches(entry, "hello"))
        assertTrue(PostViewEvents.matches(entry, "someone"))
        assertTrue(PostViewEvents.matches(entry, "示例"))
        assertTrue(PostViewEvents.matches(entry, "status/1"))
        assertTrue(PostViewEvents.matches(entry, ""))
        assertFalse(PostViewEvents.matches(entry, "广告"))
    }
}
