package com.fastcom.chat

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenCacheTest {
    @Test
    fun reusesUntilAMinuteBeforeExpiry() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = TokenCache({ SessionToken("t${++calls}", 1800) }, { now })

        assertEquals("t1", cache.get())
        now = 1_739_000
        assertEquals("t1", cache.get())
        now = 1_741_000
        assertEquals("t2", cache.get())
        assertEquals("t3", cache.get(forceRefresh = true))
    }

    @Test
    fun keepsATokenWithoutExpiryUntilForced() = runBlocking {
        var calls = 0
        val cache = TokenCache({ SessionToken("plain").also { calls++ } }, { 1_000_000_000L })
        cache.get()
        cache.get()
        assertEquals(1, calls)
    }
}
