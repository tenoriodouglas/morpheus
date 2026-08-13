package com.morpheus.family.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.morpheus.family.data.Geofence
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.tasks.await

/**
 * Reads the child's current location and uploads it for the parent to see.
 * Also evaluates an optional [Geofence] locally and raises an enter/exit alert
 * when the child crosses it. Transparent: the managed-device notice is always
 * shown, and location is disclosed in the privacy policy.
 */
object LocationReporter {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Fetch the current location, upload it, and evaluate the geofence. */
    // Permission is verified via hasPermission() before any location call below;
    // lint can't see through that helper, so suppress its false positive.
    @SuppressLint("MissingPermission")
    suspend fun reportOnce(context: Context, pairId: String, geofence: Geofence, nowMillis: Long) {
        if (pairId.isBlank() || !hasPermission(context)) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location: Location = runCatching {
            client.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        }.getOrNull() ?: runCatching { client.lastLocation.await() }.getOrNull() ?: return

        RemoteRepository.reportLocation(context, pairId, location.latitude, location.longitude, nowMillis)
        evaluateGeofence(context, pairId, geofence, location, nowMillis)
    }

    private suspend fun evaluateGeofence(
        context: Context,
        pairId: String,
        geofence: Geofence,
        location: Location,
        nowMillis: Long,
    ) {
        if (!geofence.enabled) return
        val target = Location("geofence").apply {
            latitude = geofence.lat
            longitude = geofence.lng
        }
        val inside = location.distanceTo(target) <= geofence.radiusMeters
        val prefs = Prefs(context)
        val wasInside = prefs.geofenceInside()
        if (wasInside == null || wasInside != inside) {
            prefs.setGeofenceInside(inside)
            if (wasInside != null) {
                val type = if (inside) "geofence_enter" else "geofence_exit"
                RemoteRepository.reportAlert(context, pairId, type, nowMillis)
            }
        }
    }
}
