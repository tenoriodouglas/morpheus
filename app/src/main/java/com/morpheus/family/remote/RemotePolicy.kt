package com.morpheus.family.remote

import com.morpheus.family.data.Schedule

/**
 * The full policy the child reads from its Firestore document: the enforcement
 * [schedule] plus [releaseRequestedAt] — a parent-issued timestamp that, when
 * newer than the last one the child handled, tells the child to release all
 * protection (device admin/owner + enforcement) so the app can be uninstalled.
 */
data class RemotePolicy(
    val schedule: Schedule,
    val releaseRequestedAt: Long,
)
