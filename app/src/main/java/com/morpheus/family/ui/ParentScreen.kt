package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.BlockWindow
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.launch

/**
 * Parent device. A dashboard lists every managed child; selecting one opens its
 * own schedule editor. Each child has an independent policy pushed to its own
 * Firestore document, so one parent phone controls many child phones.
 */
@Composable
fun ParentScreen(prefs: Prefs) {
    var selectedChildId by remember { mutableStateOf<String?>(null) }
    val children by prefs.childrenFlow.collectAsState(initial = emptyList())

    val selected = children.firstOrNull { it.id == selectedChildId }
    if (selected != null) {
        ChildScheduleEditor(prefs, selected, onBack = { selectedChildId = null })
    } else {
        ParentDashboard(prefs, children, onOpenChild = { selectedChildId = it })
    }
}

@Composable
private fun ParentDashboard(
    prefs: Prefs,
    children: List<ChildRef>,
    onOpenChild: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteAvailable = remember { RemoteRepository.available(context) }

    var codeInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Modo Responsável", style = MaterialTheme.typography.headlineMedium)

        if (!remoteAvailable) {
            Text(
                "Firebase não configurado neste build — as regras são salvas localmente. " +
                    "Ative o Firebase (veja o README) para controlar os filhos à distância.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Add a child.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Adicionar um filho", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nome (ex.: João)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase() },
                    label = { Text("Código exibido no celular do filho") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val id = codeInput.trim()
                        if (id.isNotBlank()) {
                            scope.launch {
                                prefs.upsertChild(ChildRef(id, nameInput.trim().ifBlank { id }))
                                // Seed the child with the default schedule and push it.
                                val seed = prefs.childSchedule(id)
                                prefs.setChildSchedule(id, seed)
                                RemoteRepository.pushPolicy(context, id, seed)
                                codeInput = ""; nameInput = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar filho") }
            }
        }

        Text(
            if (children.isEmpty()) "Nenhum filho conectado ainda."
            else "Filhos conectados (${children.size})",
            style = MaterialTheme.typography.titleMedium,
        )

        children.forEach { child ->
            ChildCard(
                prefs = prefs,
                child = child,
                onOpen = { onOpenChild(child.id) },
                onBlockNow = {
                    scope.launch {
                        val s = prefs.childSchedule(child.id)
                            .copy(manualBlockUntil = System.currentTimeMillis() + 60 * 60 * 1000)
                        prefs.setChildSchedule(child.id, s)
                        RemoteRepository.pushPolicy(context, child.id, s)
                    }
                },
                onRemove = { scope.launch { prefs.removeChild(child.id) } },
            )
        }
    }
}

@Composable
private fun ChildCard(
    prefs: Prefs,
    child: ChildRef,
    onOpen: () -> Unit,
    onBlockNow: () -> Unit,
    onRemove: () -> Unit,
) {
    val schedule by prefs.childScheduleFlow(child.id).collectAsState(initial = Schedule())
    val s = schedule ?: Schedule()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(child.name, style = MaterialTheme.typography.titleLarge)
            Text("Código: ${child.id}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (!s.enabled) "Bloqueio desligado"
                else "Janelas: " + s.windows.joinToString(", ") { it.label() },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("Editar horários") }
                OutlinedButton(onClick = onBlockNow) { Text("Bloquear 1h") }
            }
            TextButton(onClick = onRemove) { Text("Remover filho") }
        }
    }
}

@Composable
private fun ChildScheduleEditor(prefs: Prefs, child: ChildRef, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val schedule by prefs.childScheduleFlow(child.id).collectAsState(initial = Schedule())
    val current = schedule ?: Schedule()
    val window = current.windows.firstOrNull() ?: BlockWindow(22 * 60, 6 * 60 + 30)

    fun persist(newSchedule: Schedule) {
        scope.launch {
            prefs.setChildSchedule(child.id, newSchedule)
            RemoteRepository.pushPolicy(context, child.id, newSchedule)
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
        TextButton(onClick = onBack) { Text("← Voltar aos filhos") }
        Text(child.name, style = MaterialTheme.typography.headlineMedium)
        Text("Código: ${child.id}", style = MaterialTheme.typography.bodySmall)

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
                    "Durante esta janela, a internet do celular de ${child.name} fica bloqueada.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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
