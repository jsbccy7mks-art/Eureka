package com.zapp.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SimklTest {
    @Test
    fun simklTimesParse() {
        assertEquals(1_795_599_000_000L, SimklSync.parseTime("2026-11-25T09:30:00Z"))
        assertEquals(1_795_599_000_000L, SimklSync.parseTime("2026-11-25T09:30:00.000Z"))
    }
}
