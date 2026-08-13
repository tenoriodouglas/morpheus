package com.morpheus.family.remote

import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.Schedule

/**
 * The full policy the child reads from its Firestore document: the internet
 * [schedule], the [appPolicy] (per-app rules, budgets, device restrictions) and
 * [releaseRequestedAt] — a parent-issued timestamp that, when newer than the
 * last one the child handled, tells the child to release all protection so the
 * app can be uninstalled.
 */
data class RemotePolicy(
    val schedule: Schedule,
    val appPolicy: AppPolicy,
    val releaseRequestedAt: Long,
)
