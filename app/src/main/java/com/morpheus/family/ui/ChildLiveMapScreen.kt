package com.morpheus.family.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.ListenerRegistration
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.ChildStatus
import com.morpheus.family.data.LocationHistory
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Cap for a single live-viewing session (see the heartbeat loop below). */
private const val LIVE_SESSION_MS = 30L * 60 * 1000

/**
 * Live location + last-24h route on a keyless OpenStreetMap (osmdroid) map.
 *
 * While this screen is open it keeps a short live-streaming window alive on the
 * child (via [RemoteRepository.requestLive]); the child auto-reverts to the
 * battery-saving cadence once the parent leaves. The marker follows the child in
 * real time; the polyline draws the trail from [RemoteRepository.listenLocationHistory].
 */
@Composable
fun ChildLiveMapScreen(prefs: Prefs, child: ChildRef, onBack: () -> Unit) {
    // System back gesture / button returns to the child's control screen instead
    // of leaving the app (navigation here is state-based, not on the back stack).
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val remoteAvailable = remember { RemoteRepository.available(context) }

    var lat by remember { mutableStateOf<Double?>(null) }
    var lng by remember { mutableStateOf(0.0) }
    var at by remember { mutableStateOf(0L) }
    var route by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    DisposableEffect(child.id, remoteAvailable) {
        val regs = mutableListOf<ListenerRegistration>()
        if (remoteAvailable) {
            RemoteRepository.listenLocation(context, child.id) { la, ln, t ->
                lat = la; lng = ln; at = t
            }?.let { regs.add(it) }
            RemoteRepository.listenLocationHistory(context, child.id) { json, _ ->
                route = LocationHistory.decode(json).points.map { GeoPoint(it.lat, it.lng) }
            }?.let { regs.add(it) }
        }
        onDispose { regs.forEach { it.remove() } }
    }

    // Keep a live-streaming window open on the child while this screen is shown,
    // but cap the whole session: if the parent leaves the map open for hours the
    // child shouldn't stream high-accuracy GPS (and drain its battery) forever.
    // The child auto-reverts to the periodic cadence once the last window lapses.
    LaunchedEffect(child.id, remoteAvailable) {
        if (!remoteAvailable) return@LaunchedEffect
        val sessionEnd = System.currentTimeMillis() + LIVE_SESSION_MS
        while (System.currentTimeMillis() < sessionEnd) {
            RemoteRepository.requestLive(context, child.id, System.currentTimeMillis() + 120_000L)
            delay(60_000L)
        }
    }

    val mapView = remember {
        // Configure osmdroid BEFORE constructing MapView: the tile cache
        // (SqlTileWriter) reads osmdroidTileCache at construction time, so a
        // late config leaves the map grey.
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            val base = File(context.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }
    val marker = remember { Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
    val polyline = remember { Polyline().apply { outlinePaint.strokeWidth = 8f } }
    var centeredOnce by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        mapView.overlays.add(polyline)
        mapView.overlays.add(marker)
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Update the marker as live fixes arrive.
    LaunchedEffect(lat, lng) {
        val la = lat ?: return@LaunchedEffect
        val point = GeoPoint(la, lng)
        marker.position = point
        marker.title = child.name
        if (!centeredOnce) {
            mapView.controller.setCenter(point)
            centeredOnce = true
        } else {
            mapView.controller.animateTo(point)
        }
        mapView.invalidate()
    }

    // Redraw the 24h route as history arrives.
    LaunchedEffect(route) {
        polyline.setPoints(route)
        mapView.invalidate()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Mapa de ${child.name}", style = MaterialTheme.typography.headlineSmall)

            val now = System.currentTimeMillis()
            val fresh = at > 0L && now - at <= ChildStatus.STALE_MS
            Text(
                when {
                    !remoteAvailable -> "Controle remoto não ativado."
                    lat == null -> "Aguardando a localização do celular do filho…"
                    fresh -> "🟢 Ao vivo · rota das últimas 24h (${route.size} pontos)"
                    else -> "🟡 Última posição recebida · rota das últimas 24h"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Text(
                "A linha mostra o trajeto do aparelho nas últimas 24 horas. Requer que a " +
                    "localização esteja ativada no celular do filho.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
