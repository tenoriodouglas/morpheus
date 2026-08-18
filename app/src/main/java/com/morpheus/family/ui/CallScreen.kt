package com.morpheus.family.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.morpheus.family.call.CallManager

/**
 * Full-screen call overlay. Rendered above everything (see MainActivity) whenever
 * there's an incoming/outgoing/active call, so it works from any screen.
 */
@Composable
fun CallHost() {
    val context = LocalContext.current
    val ui by CallManager.ui.collectAsState()
    if (ui.state == CallManager.State.IDLE) return

    val micForAccept = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) CallManager.accept(context) }

    Surface(Modifier.fillMaxSize(), color = Color(0xE60E2038)) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📞", fontSize = 64.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                ui.peer.ifBlank { "Chamada" },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (ui.state) {
                    CallManager.State.OUTGOING -> "Chamando…"
                    CallManager.State.INCOMING -> "Chamada de áudio recebida"
                    CallManager.State.ACTIVE -> if (ui.muted) "Em chamada · microfone mudo" else "Em chamada"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB9CCE6),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))

            when (ui.state) {
                CallManager.State.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PixelButton(
                        onClick = {
                            if (hasMic(context)) CallManager.accept(context)
                            else micForAccept.launch(Manifest.permission.RECORD_AUDIO)
                        },
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
                CallManager.State.ACTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                else -> PixelButton(
                    onClick = { CallManager.hangup() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) { Text("📴 Cancelar") }
            }
        }
    }
}

/**
 * A "call" button that ensures the microphone permission before starting a call.
 */
@Composable
fun CallButton(label: String, modifier: Modifier = Modifier, onReady: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onReady() }
    PixelButton(
        onClick = {
            if (hasMic(context)) onReady() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = modifier,
    ) { Text(label) }
}

private fun hasMic(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
