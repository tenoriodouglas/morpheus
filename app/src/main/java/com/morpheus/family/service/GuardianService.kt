package com.morpheus.family.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.location.LocationReporter
import com.morpheus.family.notify.ChildNotifications
import com.morpheus.family.R
import com.morpheus.family.admin.ProtectionManager
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.schedule.ScheduleEnforcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Long-lived supervisor on the child device. Shows the permanent, transparent
 * "this device is managed" notification (required by Google Play policy for
 * monitoring apps) and re-applies the schedule whenever it is (re)started —
 * on boot, after updates, and when the parent pushes a new policy.
 *
 * Built to survive being closed like a messaging app: START_STICKY, a restart
 * alarm on task removal, and a WorkManager watchdog (see [com.morpheus.family.work]).
 */
class GuardianService : LifecycleService() {

    private var policyListener: ListenerRegistration? = null

    // Serialize policy application so out-of-order Firestore snapshots can't
    // re-apply a stale block after a newer "unblock" already ran.
    private val applyMutex = Mutex()

    // Snapshots are stamped in arrival order (the listener callback is
    // single-threaded); the coroutines that apply them can win the mutex in a
    // different order, so we drop any snapshot older than the newest applied.
    private val policySeq = AtomicLong(0)
    private var lastAppliedSeq = 0L

    // Last enforced (schedule, appPolicy) so a snapshot that changed nothing but
    // the heartbeat/liveUntil doesn't re-persist and re-arm alarms every tick.
    // Touched only under applyMutex.
    private var lastEnforced: Pair<Schedule, AppPolicy>? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        startRemoteSync()
        startStatusTicker()
    }

    /**
     * Start in the foreground with the correct type mask. On Android 14+ the
     * location FGS type is only added when location permission is held, otherwise
     * the start throws; wrapped in runCatching so a background revive that isn't
     * allowed doesn't crash the process.
     */
    private fun startForegroundCompat() {
        val notif = ChildNotifications.ongoing(this, getString(R.string.managed_text))
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (LocationReporter.hasPermission(this)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(ChildNotifications.ONGOING_ID, notif, type)
            } else {
                startForeground(ChildNotifications.ONGOING_ID, notif)
            }
        }.isSuccess
        // A started service that never reaches foreground is killed (with an ANR
        // crash) by the system; stop cleanly instead. The watchdog revives us
        // once starting in the foreground is allowed again.
        if (!ok) stopSelf()
    }

    /**
     * Publish the transparency snapshot once a minute so the parent dashboard
     * shows live screen time and the current app. Stops with the service.
     */
    private fun startStatusTicker() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { ScheduleEnforcer.uploadStatus(applicationContext) }
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    /** Subscribe to the parent's live policy updates (no-op without Firebase). */
    private fun startRemoteSync() {
        val ctx = applicationContext
        RemoteRepository.ensureSignedIn(ctx)
        lifecycleScope.launch {
            val prefs = Prefs(ctx)
            // Re-attach the Firestore listener only when the paired id truly
            // changes — DataStore re-emits the whole prefs on every unrelated edit.
            prefs.pairedIdFlow.distinctUntilChanged().collect { pairId ->
                policyListener?.remove()
                lastEnforced = null // new document: force the next apply
                policyListener = if (pairId.isNullOrBlank()) null else
                    RemoteRepository.listenPolicy(ctx, pairId) { policy ->
                        // Stamp arrival order on the (serialized) callback thread.
                        val seq = policySeq.incrementAndGet()
                        lifecycleScope.launch(Dispatchers.IO) {
                            applyMutex.withLock {
                                // Drop a snapshot that a newer one already superseded.
                                if (seq < lastAppliedSeq) return@withLock
                                lastAppliedSeq = seq

                                if (policy.releaseRequestedAt > prefs.lastRelease()) {
                                    // Parent asked to remove protection: disable
                                    // the schedule, remember the request, tear down.
                                    prefs.setLastRelease(policy.releaseRequestedAt)
                                    prefs.setSchedule(policy.schedule.copy(enabled = false))
                                    LocationReporter.stopLive(ctx)
                                    ProtectionManager.release(ctx)
                                    return@withLock
                                }

                                // Live-streaming window is a cheap, idempotent
                                // timestamp update — always refresh it so the map
                                // stays live even when nothing else changed.
                                LocationReporter.setLive(
                                    ctx, pairId, policy.appPolicy.geofence, policy.liveUntil,
                                )

                                // Only persist + re-enforce when the enforced
                                // policy actually changed (time-based re-checks are
                                // driven separately by the boundary alarm).
                                val enforced = policy.schedule to policy.appPolicy
                                if (enforced != lastEnforced) {
                                    lastEnforced = enforced
                                    prefs.setSchedule(policy.schedule)
                                    prefs.setAppPolicy(policy.appPolicy)
                                    ScheduleEnforcer.apply(ctx)
                                }
                            }
                        }
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Refresh trusted time (network) then enforce.
        lifecycleScope.launch(Dispatchers.IO) { ScheduleEnforcer.syncAndApply(applicationContext) }
        return START_STICKY
    }

    /**
     * The child swiped the app away. START_STICKY isn't guaranteed after a task
     * swipe, so schedule a near-immediate self-restart — the supervisor must keep
     * running even when the app is closed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = applicationContext
        val restart = PendingIntent.getForegroundService(
            app,
            RESTART_REQ,
            Intent(app, GuardianService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, restart,
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        policyListener?.remove()
        policyListener = null
        // Stop any live-location streaming so it can't outlive the service and
        // keep the GPS awake after we're gone.
        LocationReporter.stopLive(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val STATUS_INTERVAL_MS = 60_000L
        private const val RESTART_REQ = 7200

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, GuardianService::class.java))
            }
        }
    }
}
