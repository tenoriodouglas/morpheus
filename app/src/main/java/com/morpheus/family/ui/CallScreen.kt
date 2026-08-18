package com.morpheus.family.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.morpheus.family.call.CallManager
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen call overlay. Rendered above everything (see MainActivity) whenever
 * there's an incoming/outgoing/active call, so it works from any screen. Audio
 * calls show a simple card; video calls render the remote camera full-screen with
 * the local camera as a small picture-in-picture.
 */
@Composable
fun CallHost() {
    val context = LocalContext.current
    val ui by CallManager.ui.collectAsState()
    val videoUi by CallManager.video.collectAsState()
    if (ui.state == CallManager.State.IDLE) return

    // Accepting needs mic (+ camera for a video call).
    val acceptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val needCam = CallManager.ui.value.video
        val ok = granted[Manifest.permission.RECORD_AUDIO] == true &&
            (!needCam || granted[Manifest.permission.CAMERA] == true)
        if (ok) CallManager.accept(context)
    }
    fun tryAccept() {
        val perms = acceptPerms(ui.video)
        if (perms.all { has(context, it) }) CallManager.accept(context) else acceptLauncher.launch(perms)
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xE60E2038)) {
        Box(Modifier.fillMaxSize()) {
            // Video surfaces sit behind the controls.
            if (ui.video) {
                VideoRenderer(
                    track = videoUi.remote,
                    eglContext = videoUi.eglBase?.eglBaseContext,
                    modifier = Modifier.fillMaxSize(),
                )
                VideoRenderer(
                    track = videoUi.local,
                    eglContext = videoUi.eglBase?.eglBaseContext,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .width(110.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    mirror = true,
                )
            }

            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = if (ui.video) Arrangement.Bottom else Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!ui.video || ui.state != CallManager.State.ACTIVE) {
                    Text(if (ui.video) "📹" else "📞", fontSize = 56.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ui.peer.ifBlank { "Chamada" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        statusText(ui),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFB9CCE6),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(if (ui.video) 24.dp else 40.dp))
                }

                CallControls(ui, onAccept = { tryAccept() })
            }
        }
    }
}

@Composable
private fun CallControls(ui: CallManager.CallUi, onAccept: () -> Unit) {
    when (ui.state) {
        CallManager.State.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PixelButton(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) { Text("✅ Atender") }
            PixelButton(
                onClick = { CallManager.hangup() },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) { Text("✖ Recusar") }
        }
        CallManager.State.ACTIVE -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.video) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PixelButton(
                        onClick = { CallManager.toggleCamera() },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) { Text(if (ui.cameraOn) "📷 Câmera on" else "🚫 Câmera off") }
                    PixelButton(
                        onClick = { CallManager.switchCamera() },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) { Text("🔄 Virar") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PixelButton(
                    onClick = { CallManager.toggleMute() },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) { Text(if (ui.muted) "🔊 Ativar mic" else "🔇 Mudo") }
                PixelButton(
                    onClick = { CallManager.hangup() },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) { Text("📴 Encerrar") }
            }
        }
        else -> PixelButton(
            onClick = { CallManager.hangup() },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ) { Text("📴 Cancelar") }
    }
}

private fun statusText(ui: CallManager.CallUi): String = when (ui.state) {
    CallManager.State.OUTGOING -> "Chamando…"
    CallManager.State.INCOMING -> if (ui.video) "Chamada de vídeo recebida" else "Chamada de áudio recebida"
    CallManager.State.ACTIVE -> if (ui.muted) "Em chamada · microfone mudo" else "Em chamada"
    else -> ""
}

/** Renders a WebRTC [VideoTrack] into a SurfaceViewRenderer, releasing on dispose. */
@Composable
private fun VideoRenderer(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    if (track == null || eglContext == null) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        onRelease = { renderer ->
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        },
    )
}

/**
 * A "call" button that ensures the needed permissions (mic, plus camera for a
 * [video] call) before starting the call.
 */
@Composable
fun CallButton(
    label: String,
    modifier: Modifier = Modifier,
    video: Boolean = false,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val perms = acceptPerms(video)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (perms.all { granted[it] == true }) onReady() }
    PixelButton(
        onClick = { if (perms.all { has(context, it) }) onReady() else launcher.launch(perms) },
        modifier = modifier,
    ) { Text(label) }
}

private fun acceptPerms(video: Boolean): Array<String> =
    if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    else arrayOf(Manifest.permission.RECORD_AUDIO)

private fun has(context: android.content.Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
