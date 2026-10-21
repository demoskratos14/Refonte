package com.aventure.desdice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Tests de la politique de réessai automatique de MistralClient (erreurs réseau et 429). */
class MistralClientRetryTest {

    // ---- quelles erreurs sont retentées ----

    @Test
    fun onlyTheRateLimitIsRetriedAmongHttpErrors() {
        assertTrue(MistralClient.isRetryableHttpCode(429))
        for (code in listOf(400, 401, 403, 404, 422, 500, 502, 503)) {
            assertFalse("code $code", MistralClient.isRetryableHttpCode(code))
        }
    }

    @Test
    fun networkErrorsAreRetried() {
        assertTrue(MistralClient.isRetryableException(IOException("coupée")))
        assertTrue(MistralClient.isRetryableException(SocketTimeoutException()))
        assertTrue(MistralClient.isRetryableException(UnknownHostException("api.mistral.ai")))
        assertTrue(MistralClient.isRetryableException(ConnectException()))
    }

    @Test
    fun nonNetworkErrorsAreNotRetried() {
        assertFalse(MistralClient.isRetryableException(IllegalStateException("bug")))
        assertFalse(MistralClient.isRetryableException(RuntimeException("autre")))
    }

    // ---- attente entre deux essais ----

    @Test
    fun retryDelay_growsWithEachAttempt() {
        assertEquals(1_500L, MistralClient.retryDelayMs(1, 0L))
        assertEquals(3_000L, MistralClient.retryDelayMs(2, 0L))
        assertEquals(6_000L, MistralClient.retryDelayMs(3, 0L))
    }

    @Test
    fun retryDelay_givesUpWhenTheWaitWouldBeTooLong() {
        assertEquals(-1L, MistralClient.retryDelayMs(4, 0L)) // 12 s
    }

    @Test
    fun retryDelay_honoursTheServerRetryAfterWhenReasonable() {
        assertEquals(5_000L, MistralClient.retryDelayMs(1, 5_000L))
        // Jamais plus court que l'attente de base.
        assertEquals(1_500L, MistralClient.retryDelayMs(1, 800L))
    }

    @Test
    fun retryDelay_givesUpWhenTheServerAsksForALongWait() {
        assertEquals(-1L, MistralClient.retryDelayMs(1, 30_000L))
    }

    // ---- en-tête Retry-After ----

    @Test
    fun parseRetryAfter_readsSeconds() {
        assertEquals(3_000L, MistralClient.parseRetryAfterMs("3"))
        assertEquals(2_000L, MistralClient.parseRetryAfterMs(" 2 "))
    }

    @Test
    fun parseRetryAfter_ignoresMissingOrUnreadableValues() {
        assertEquals(0L, MistralClient.parseRetryAfterMs(null))
        assertEquals(0L, MistralClient.parseRetryAfterMs(""))
        assertEquals(0L, MistralClient.parseRetryAfterMs("abc"))
        assertEquals(0L, MistralClient.parseRetryAfterMs("0"))
        assertEquals(0L, MistralClient.parseRetryAfterMs("-5"))
    }

    @Test
    fun parseRetryAfter_capsAbsurdValues() {
        assertEquals(3_600_000L, MistralClient.parseRetryAfterMs("99999"))
    }
}
