package com.zapp.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ParserTest {

    @Test
    fun m3uReadsChannelsGroupsAndGuideLink() {
        val text = """
            #EXTM3U url-tvg="http://example.com/guide.xml.gz,http://backup/guide.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-logo="http://logo/bbc1.png" group-title="UK, Entertainment",BBC One HD
            http://example.com/stream/1.ts
            #EXTINF:-1 tvg-name="Sky News",
            #EXTGRP:News
            http://example.com/stream/2.ts
            #EXTINF:-1,
            http://example.com/stream/3.ts
        """.trimIndent()
        val result = M3uParser.parse(BufferedReader(StringReader(text)))

        assertEquals("http://example.com/guide.xml.gz", result.epgUrl)
        assertEquals(3, result.channels.size)

        val bbc = result.channels[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("bbc1.uk", bbc.epgId)
        assertEquals("http://logo/bbc1.png", bbc.logo)
        assertEquals("UK, Entertainment", bbc.group)
        assertEquals("http://example.com/stream/1.ts", bbc.url)

        val sky = result.channels[1]
        assertEquals("Sky News", sky.name)
        assertEquals("News", sky.group)

        assertEquals("Channel 3", result.channels[2].name)
        assertNull(result.channels[2].group)
    }

    @Test
    fun xmltvTimesRespectOffsets() {
        // 2026-11-25 09:30:00 UTC
        val utc = 1_795_599_000_000L
        assertEquals(utc, EpgParser.parseTime("20261125093000 +0000"))
        assertEquals(utc, EpgParser.parseTime("20261125103000 +0100"))
        assertEquals(utc, EpgParser.parseTime("20261125043000 -0500"))
        assertEquals(utc, EpgParser.parseTime("20261125093000"))
        assertEquals(utc, EpgParser.parseTime("202611250930 +0000"))
        assertNull(EpgParser.parseTime("2026"))
        assertNull(EpgParser.parseTime(null))
    }

    @Test
    fun channelNamesMatchLoosely() {
        assertEquals(EpgParser.normalizeName("BBC One"), EpgParser.normalizeName("UK: BBC One HD"))
        assertEquals(EpgParser.normalizeName("Sky Sports Main Event"), EpgParser.normalizeName("Sky Sports Main Event (FHD)"))
        // Timeshift channels stay different.
        assert(EpgParser.normalizeName("ITV1 +1") != EpgParser.normalizeName("ITV1"))
    }
}
