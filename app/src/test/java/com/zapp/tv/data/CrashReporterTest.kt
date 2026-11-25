package com.zapp.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashReporterTest {
    @Test
    fun loginsAreRemovedFromReports() {
        val text = "Source error http://host:8080/live/alice/s3cret/123.ts and " +
            "http://host/player_api.php?username=alice&password=s3cret&action=x"
        val clean = CrashReporter.scrub(text)
        assertFalse(clean.contains("alice"))
        assertFalse(clean.contains("s3cret"))
        assertEquals(
            "Source error http://host:8080/live/***/***/123.ts and http://host/player_api.php?username=***&password=***&action=x",
            clean,
        )
    }
}
