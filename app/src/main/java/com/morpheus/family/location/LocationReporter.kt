package com.morpheus.family.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.morpheus.family.data.Geofence
import com.morpheus.family.data.LocationHistory
import com.morpheus.family.data.LocationPoint
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Whether location also works with the app in the background. Android 10+
     * gates this behind a separate grant that can only be requested *after* the
     * foreground one; before API 29 the foreground grant already covers it.
     */
    fun hasBackgroundPermission(context: Context): Boolean {
        if (!hasPermission(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether the system will let us run unthrottled. Without this, Doze can
     * delay location updates for long stretches, so the setup wizard asks for it
     * whenever location is enabled.
     */
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fetch the current location, upload it (+route), and evaluate the geofence. */
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

        // Fake-GPS guard: warn the parent when a fix looks mocked, but STILL upload
        // it (flagged) — many real devices and every emulator mark all fixes as
        // mock, so dropping them would break location entirely.
        val mock = isMock(location)
        if (mock) alertMock(context, pairId, nowMillis)
        recordAndUpload(context, pairId, location.latitude, location.longitude, nowMillis, mock)
        evaluateGeofence(context, pairId, geofence, location, nowMillis)
    }

    /**
     * Upload the current position and, at most every [ROUTE_MIN_SPACING_MS],
     * append it to the pruned 24h route. Route growth is decoupled from the
     * (much faster) live cadence so the Firestore doc stays small.
     *
     * The route read-modify-write is guarded by [routeMutex] so a live-stream fix
     * and a periodic fix can't interleave and drop points; the two uploads (live
     * position, and position+route) are collapsed into a single Firestore write.
     */
    private suspend fun recordAndUpload(
        context: Context,
        pairId: String,
        lat: Double,
        lng: Double,
        at: Long,
        mock: Boolean = false,
    ) {
        // Serialize the append and decide, under the lock, whether this fix also
        // grows the route — otherwise two concurrent callers both read the old
        // history and one overwrites the other's appended point.
        val historyJson: String? = routeMutex.withLock {
            if (at - lastRouteAppend < ROUTE_MIN_SPACING_MS) return@withLock null
            lastRouteAppend = at
            val prefs = Prefs(context)
            val history = prefs.locationHistory().appendPruned(LocationPoint(lat, lng, at), at)
            prefs.setLocationHistory(history)
            LocationHistory.encode(history)
        }
        RemoteRepository.reportLocation(context, pairId, lat, lng, at, historyJson, at, mock)
    }

    private val routeMutex = Mutex()

    @Volatile
    private var lastRouteAppend = 0L

    @Volatile
    private var liveUntil = 0L

    @Volatile
    private var liveCallback: LocationCallback? = null

    /**
     * Turn frequent live streaming on/off. While [untilMillis] is in the future
     * and permission is held, the child pushes a fix every ~15s at high accuracy;
     * once the window lapses (checked on each fix) it auto-stops back to the
     * periodic cadence. Idempotent.
     */
    // setLive/stopLive are @Synchronized so the "already streaming?" check and the
    // callback (un)registration are atomic — concurrent callers (the parent's
    // policy pushes on an IO thread, and the streaming callback stopping itself on
    // the main thread) can't both pass the null check and register two callbacks.
    @Synchronized
    @SuppressLint("MissingPermission")
    fun setLive(context: Context, pairId: String, geofence: Geofence, untilMillis: Long) {
        liveUntil = untilMillis
        val now = System.currentTimeMillis()
        val app = context.applicationContext
        if (untilMillis <= now || pairId.isBlank() || !hasPermission(app)) {
            stopLive(app)
            return
        }
        if (liveCallback != null) return // already streaming
        val client = LocationServices.getFusedLocationProviderClient(app)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LIVE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LIVE_INTERVAL_MS)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val n = System.currentTimeMillis()
                if (n > liveUntil) { stopLive(app); return }
                val mock = isMock(loc)
                if (mock) alertMock(app, pairId, n)
                scope.launch {
                    recordAndUpload(app, pairId, loc.latitude, loc.longitude, n, mock)
                    evaluateGeofence(app, pairId, geofence, loc, n)
                }
            }
        }
        liveCallback = cb
        runCatching { client.requestLocationUpdates(request, cb, Looper.getMainLooper()) }
    }

    @Synchronized
    fun stopLive(context: Context) {
        val cb = liveCallback ?: return
        liveCallback = null
        runCatching {
            LocationServices.getFusedLocationProviderClient(context.applicationContext)
                .removeLocationUpdates(cb)
        }
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

    /** True if this fix came from a mock/fake-GPS provider. */
    @Suppress("DEPRECATION") // isFromMockProvider is the pre-API-31 equivalent of isMock.
    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock else loc.isFromMockProvider

    @Volatile
    private var lastMockAlert = 0L

    /** Warn the parent a fake location was detected, at most every [MOCK_ALERT_MIN_MS]. */
    private fun alertMock(context: Context, pairId: String, now: Long) {
        if (pairId.isBlank() || now - lastMockAlert < MOCK_ALERT_MIN_MS) return
        lastMockAlert = now
        runCatching { RemoteRepository.reportAlert(context, pairId, "location_mock", now) }
    }

    private const val LIVE_INTERVAL_MS = 15_000L
    private const val ROUTE_MIN_SPACING_MS = 60_000L
    private const val MOCK_ALERT_MIN_MS = 5L * 60 * 1000
}
