package com.xadblock.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PostViewTrackerTest {

    @Test
    public void buildsHandlePermalink() {
        assertEquals("https://x.com/someone/status/1234567890",
                PostViewTracker.permalink("1234567890", "someone"));
    }

    @Test
    public void fallsBackWhenHandleIsMissingOrUntrusted() {
        assertEquals("https://x.com/i/status/1234567890",
                PostViewTracker.permalink("1234567890", null));
        assertEquals("https://x.com/i/status/1234567890",
                PostViewTracker.permalink("1234567890", "示例用户"));
        assertEquals("https://x.com/i/status/1234567890",
                PostViewTracker.permalink("1234567890", "not a handle"));
    }

    @Test
    public void normalizesHandles() {
        assertEquals("someone", PostViewTracker.normalizeHandle("@someone"));
        assertEquals("someone", PostViewTracker.normalizeHandle("  someone "));
        assertNull(PostViewTracker.normalizeHandle("@"));
        assertNull(PostViewTracker.normalizeHandle("   "));
        assertNull(PostViewTracker.normalizeHandle(null));
    }
}
