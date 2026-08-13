package com.morpheus.family.util

import java.security.MessageDigest

/** Tiny helper to hash the parent PIN before storing it (never store plaintext). */
object Pin {
    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
