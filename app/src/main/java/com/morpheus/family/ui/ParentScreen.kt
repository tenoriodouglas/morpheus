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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.ListenerRegistration
import com.morpheus.family.data.BlockWindow
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.util.Pin
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parent device. A dashboard lists every managed child; selecting one opens its
 * own schedule + app-rules editor. Each child has an independent policy pushed to
 * its own Firestore document, so one parent phone controls many child phones.
 * If a parent PIN is set, it gates the whole screen.
 */
@Composable
fun ParentScreen(prefs: Prefs) {
    val pinHash by prefs.parentPinFlow.collectAsState(initial = null)
    var unlocked by remember { mutableStateOf(false) }

    val hash = pinHash
    if (hash != null && !unlocked) {
        PinGate(expectedHash = hash, onUnlock = { unlocked = true })
    } else {
        ParentHome(prefs)
    }
}

@Composable
private fun ParentHome(prefs: Prefs) {
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
private fun PinGate(expectedHash: String, onUnlock: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Morpheus", style = MaterialTheme.typography.headlineMedium)
        Text("Digite o PIN do responsável", modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter(Char::isDigit); error = false },
            label = { Text("PIN") },
            singleLine = true,
            isError = error,
        )
        if (error) Text("PIN incorreto", color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                if (com.morpheus.family.util.Pin.hash(input) == expectedHash) onUnlock() else error = true
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Entrar") }
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
    var confirmRelease by remember { mutableStateOf<ChildRef?>(null) }

    // Listen for tamper alerts from each child (e.g. clock tampering).
    val alerts = remember { mutableStateMapOf<String, Pair<String, Long>>() }
    DisposableEffect(children, remoteAvailable) {
        val regs = mutableListOf<ListenerRegistration>()
        if (remoteAvailable) {
            children.forEach { child ->
                RemoteRepository.listenAlert(context, child.id) { type, at ->
                    alerts[child.id] = type to at
                }?.let { regs.add(it) }
            }
        }
        onDispose { regs.forEach { it.remove() } }
    }

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

        // Tamper alerts.
        children.forEach { child ->
            alerts[child.id]?.let { (type, at) ->
                val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                val what = if (type == "time_tamper") "possível alteração da hora do sistema"
                else "evento de segurança"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ ${child.name}: $what",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text("Detectado em ${fmt.format(Date(at))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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

        SecurityPinCard(prefs)

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
                onRequestRelease = { confirmRelease = child },
            )
        }
    }

    val releasing = confirmRelease
    if (releasing != null) {
        AlertDialog(
            onDismissRequest = { confirmRelease = null },
            title = { Text("Remover proteção de ${releasing.name}?") },
            text = {
                Text(
                    if (remoteAvailable)
                        "O celular de ${releasing.name} vai desativar o bloqueio e a proteção " +
                            "anti-desinstalação assim que estiver online. Depois disso o app pode " +
                            "ser desinstalado normalmente. Esta ação não pode ser desfeita à distância."
                    else
                        "O Firebase não está configurado, então não é possível remover à distância. " +
                            "No celular do filho: Configurações → Segurança → Apps de administração → " +
                            "Morpheus → Desativar, e então desinstale. Vou apenas remover ${releasing.name} " +
                            "desta lista.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (remoteAvailable) {
                            RemoteRepository.requestRelease(
                                context, releasing.id, System.currentTimeMillis(),
                            )
                        }
                        prefs.removeChild(releasing.id)
                    }
                    confirmRelease = null
                }) { Text(if (remoteAvailable) "Remover proteção" else "Remover da lista") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun SecurityPinCard(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val pinHash by prefs.parentPinFlow.collectAsState(initial = null)
    var input by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PIN do responsável", style = MaterialTheme.typography.titleMedium)
            if (pinHash == null) {
                Text(
                    "Defina um PIN para proteger o acesso a este painel.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit) },
                    label = { Text("Novo PIN (mín. 4 dígitos)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { if (input.length >= 4) scope.launch { prefs.setParentPin(Pin.hash(input)); input = "" } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Definir PIN") }
            } else {
                Text("PIN ativo.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { scope.launch { prefs.setParentPin(null) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remover PIN") }
            }
        }
    }
}

@Composable
private fun ChildCard(
    prefs: Prefs,
    child: ChildRef,
    onOpen: () -> Unit,
    onBlockNow: () -> Unit,
    onRequestRelease: () -> Unit,
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
            TextButton(onClick = onRequestRelease) { Text("Remover proteção / desinstalar") }
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

        AppRulesEditor(prefs, child)
    }
}
