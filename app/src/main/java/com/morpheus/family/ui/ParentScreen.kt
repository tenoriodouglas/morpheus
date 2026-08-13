package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.BlockWindow
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.launch

/**
 * Parent device: enters the child's pairing code, edits the block schedule and
 * pushes it. When Firebase is configured, changes reach the child in real time;
 * otherwise this screen still edits the local policy (useful on the child device
 * itself, or for a single-phone setup).
 */
@Composable
fun ParentScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pairId by prefs.pairedIdFlow.collectAsState(initial = null)
    val schedule by prefs.scheduleFlow.collectAsState(initial = Schedule())

    var codeInput by remember { mutableStateOf("") }
    val remoteAvailable = remember { RemoteRepository.available(context) }

    val current = schedule ?: Schedule()
    val window = current.windows.firstOrNull() ?: BlockWindow(22 * 60, 6 * 60 + 30)

    fun persist(newSchedule: Schedule) {
        scope.launch {
            prefs.setSchedule(newSchedule)
            pairId?.let { RemoteRepository.pushPolicy(context, it, newSchedule) }
        }
    }

    fun updateWindow(newWindow: BlockWindow) {
        val rest = current.windows.drop(1)
        persist(current.copy(windows = listOf(newWindow) + rest))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Modo Responsável", style = MaterialTheme.typography.headlineMedium)

        // Pairing.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parear com o celular do filho", style = MaterialTheme.typography.titleMedium)
                if (pairId.isNullOrBlank()) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.uppercase() },
                        label = { Text("Código exibido no celular do filho") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { if (codeInput.isNotBlank()) scope.launch { prefs.setPairedId(codeInput.trim()) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Conectar") }
                } else {
                    Text("Pareado: $pairId")
                    if (!remoteAvailable) {
                        Text(
                            "Firebase não configurado neste build — as mudanças só se aplicam localmente. " +
                                "Veja o README para ativar o controle remoto.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Master switch.
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bloqueio por horário ativo", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = current.enabled,
                    onCheckedChange = { persist(current.copy(enabled = it)) },
                )
            }
        }

        // Window editor.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Janela de bloqueio", style = MaterialTheme.typography.titleMedium)
                TimeStepper("Início", window.startMinutes) {
                    updateWindow(window.copy(startMinutes = it))
                }
                TimeStepper("Fim", window.endMinutes) {
                    updateWindow(window.copy(endMinutes = it))
                }
                Text(
                    "Durante esta janela, a internet do celular do filho fica bloqueada.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Immediate block.
        Button(
            onClick = {
                persist(current.copy(manualBlockUntil = System.currentTimeMillis() + 60 * 60 * 1000))
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Bloquear agora por 1 hora") }

        OutlinedButton(
            onClick = { persist(current.copy(manualBlockUntil = 0L)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancelar bloqueio imediato") }
    }
}

/** Simple +/- 15 minute stepper rendering a HH:MM label. */
@Composable
private fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((minutes - 15 + 1440) % 1440) }) { Text("−") }
            Text(
                "%02d:%02d".format(minutes / 60, minutes % 60),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { onChange((minutes + 15) % 1440) }) { Text("+") }
        }
    }
}
