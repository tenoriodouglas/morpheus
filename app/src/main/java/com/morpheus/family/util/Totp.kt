package com.morpheus.family.util

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time code (RFC 6238, HMAC-SHA1, 30s step, 6 digits) used for the
 * child app-open lock. Parent and child share [lockSecret] (app-internal, never
 * shown to the child); the parent displays the current [code] and the child
 * accepts it — plus a fixed emergency PIN — to unlock.
 *
 * Pure JVM (java.util.Base64 + javax.crypto), so it's unit-testable off-device.
 */
object Totp {

    const val STEP_MS = 30_000L
    private const val DIGITS = 6

    /** A fresh random secret (160-bit), Base64-encoded for storage/transport. */
    fun randomSecretBase64(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** The current code for [secretBase64] at [nowMillis]. */
    fun code(secretBase64: String, nowMillis: Long): String =
        hotp(secretBase64, nowMillis / STEP_MS)

    /** Seconds left before the code rolls over (for a countdown). */
    fun secondsRemaining(nowMillis: Long): Int =
        ((STEP_MS - (nowMillis % STEP_MS)) / 1000L).toInt()

    /**
     * Whether [entered] matches the code for [secretBase64] around [nowMillis],
     * allowing ±[skewSteps] windows for clock differences between the phones.
     */
    fun valid(secretBase64: String, entered: String, nowMillis: Long, skewSteps: Int = 1): Boolean {
        if (secretBase64.isBlank()) return false
        val e = entered.trim()
        if (e.length != DIGITS) return false
        val counter = nowMillis / STEP_MS
        for (i in -skewSteps..skewSteps) {
            if (hotp(secretBase64, counter + i) == e) return true
        }
        return false
    }

    private fun hotp(secretBase64: String, counter: Long): String {
        val key = runCatching { Base64.getDecoder().decode(secretBase64) }.getOrNull() ?: return ""
        if (key.isEmpty()) return ""
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(msg)
        val offset = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[offset].toInt() and 0x7f) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }
}
