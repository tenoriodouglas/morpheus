package com.morpheus.family

import com.morpheus.family.util.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TotpTest {

    // RFC 6238 test key ("12345678901234567890"), Base64-encoded as we store it.
    private val secret = Base64.getEncoder().encodeToString("12345678901234567890".toByteArray())

    @Test
    fun rfc6238_knownVector() {
        // T = 59s -> counter 1 -> 8-digit 94287082 -> 6-digit 287082.
        assertEquals("287082", Totp.code(secret, 59_000L))
    }

    @Test
    fun valid_acceptsCurrent_rejectsWrong() {
        val now = 1_000_000_000L
        val c = Totp.code(secret, now)
        assertTrue(Totp.valid(secret, c, now))
        val wrong = if (c == "000000") "111111" else "000000"
        assertFalse(Totp.valid(secret, wrong, now))
        assertFalse(Totp.valid(secret, "12345", now)) // wrong length
        assertFalse(Totp.valid("", c, now))           // no secret configured
    }

    @Test
    fun valid_toleratesClockSkew() {
        val now = 1_000_000_000L
        val prevWindow = Totp.code(secret, now - Totp.STEP_MS)
        assertTrue(Totp.valid(secret, prevWindow, now, skewSteps = 1))
    }

    @Test
    fun secondsRemaining_isWithinAStep() {
        assertTrue(Totp.secondsRemaining(1_000_000_000L) in 0..30)
    }

    @Test
    fun randomSecret_isUsableAndDistinct() {
        val a = Totp.randomSecretBase64()
        val b = Totp.randomSecretBase64()
        assertFalse(a == b)
        val now = 42_000_000L
        assertTrue(Totp.valid(a, Totp.code(a, now), now))
    }
}
