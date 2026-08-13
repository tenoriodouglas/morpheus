package com.morpheus.family.data

/**
 * A child device managed from the parent app. [id] is the pairing code shown on
 * the child's screen (and the Firestore document key); [name] is a friendly
 * label the parent assigns (e.g. "João").
 */
data class ChildRef(
    val id: String,
    val name: String,
)
