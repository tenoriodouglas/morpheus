package com.morpheus.family.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import com.google.firebase.firestore.ListenerRegistration
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.BlockWindow
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.ChildStatus
import com.morpheus.family.data.Geofence
import com.morpheus.family.data.KnownApps
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.util.Pin
import com.morpheus.family.util.Totp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var liveMapChildId by remember { mutableStateOf<String?>(null) }
    val children by prefs.childrenFlow.collectAsState(initial = emptyList())

    val liveMapChild = children.firstOrNull { it.id == liveMapChildId }
    val selected = children.firstOrNull { it.id == selectedChildId }
    when {
        liveMapChild != null ->
            ChildLiveMapScreen(prefs, liveMapChild, onBack = { liveMapChildId = null })
        selected != null ->
            ChildScheduleEditor(
                prefs, selected,
                onBack = { selectedChildId = null },
                onOpenLiveMap = { liveMapChildId = it },
            )
        else -> ParentDashboard(prefs, children, onOpenChild = { selectedChildId = it })
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
    val snackbar = remember { SnackbarHostState() }

    // Listen for alerts (tamper/SOS/geofence) and requests from each child.
    val alerts = remember { mutableStateMapOf<String, Pair<String, Long>>() }
    val requests = remember { mutableStateMapOf<String, Triple<String, String, Long>>() }
    DisposableEffect(children, remoteAvailable) {
        val regs = mutableListOf<ListenerRegistration>()
        if (remoteAvailable) {
            children.forEach { child ->
                RemoteRepository.listenAlert(context, child.id) { type, at ->
                    alerts[child.id] = type to at
                }?.let { regs.add(it) }
                RemoteRepository.listenRequest(context, child.id) { type, note, at ->
                    requests[child.id] = Triple(type, note, at)
                }?.let { regs.add(it) }
            }
        }
        onDispose { regs.forEach { it.remove() } }
    }

    fun grantExtraTime(child: ChildRef) {
        scope.launch {
            val extra = System.currentTimeMillis() + 30 * 60 * 1000
            val sched = prefs.childSchedule(child.id).copy(allowUntil = extra)
            prefs.setChildSchedule(child.id, sched)
            RemoteRepository.pushPolicy(context, child.id, sched)
            val ap = prefs.childAppPolicy(child.id).copy(bonusUntil = extra)
            prefs.setChildAppPolicy(child.id, ap)
            RemoteRepository.pushAppPolicy(context, child.id, ap)
            RemoteRepository.clearRequest(context, child.id)
            requests.remove(child.id)
            snackbar.showSnackbar("✅ +30 min liberados para ${child.name}")
        }
    }
    fun denyRequest(child: ChildRef) {
        scope.launch {
            RemoteRepository.clearRequest(context, child.id)
            requests.remove(child.id)
            snackbar.showSnackbar("Pedido de ${child.name} recusado")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).arcadeGrid().padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BombSprite(size = 48.dp)
            Column {
                Text("Painel da família", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (children.isEmpty()) "Conecte o primeiro filho abaixo"
                    else "${children.size} " + if (children.size == 1) "filho protegido" else "filhos protegidos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!remoteAvailable) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("📶", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "As regras já funcionam no aparelho do filho. O controle à distância " +
                            "(ver localização, aprovar mais tempo, bloquear na hora) será ativado " +
                            "em breve numa próxima atualização.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

        // Alerts (tamper / SOS / geofence).
        children.forEach { child ->
            alerts[child.id]?.let { (type, at) ->
                val what = when (type) {
                    "time_tamper" -> "possível alteração da hora do sistema"
                    "sos" -> "🆘 SOS — pedido de ajuda!"
                    "geofence_enter" -> "chegou na área monitorada"
                    "geofence_exit" -> "saiu da área monitorada"
                    "protection_removed" -> "⛔ a proteção foi REMOVIDA neste aparelho!"
                    else -> "evento de segurança"
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ ${child.name}: $what",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text("Em ${fmt.format(Date(at))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Extra-time / unlock requests.
        children.forEach { child ->
            requests[child.id]?.let { (_, note, at) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🙋 ${child.name} pediu: ${note.ifBlank { "mais tempo" }}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Em ${fmt.format(Date(at))}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { grantExtraTime(child) }) { Text("Aprovar +30 min") }
                            OutlinedButton(onClick = { denyRequest(child) }) { Text("Negar") }
                        }
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
                val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let { raw ->
                        codeInput = raw.removePrefix(QR_PREFIX).trim().uppercase()
                    }
                }
                Button(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Aponte para o QR no celular do filho")
                                .setBeepEnabled(false)
                                // Portrait-only capture activity; keep the
                                // library from re-applying sensor orientation.
                                .setCaptureActivity(PortraitCaptureActivity::class.java)
                                .setOrientationLocked(true),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📷 Escanear QR do filho") }

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase() },
                    label = { Text("Ou digite o código exibido no celular do filho") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val id = codeInput.trim()
                        if (id.isNotBlank()) {
                            scope.launch {
                                prefs.upsertChild(ChildRef(id, nameInput.trim().ifBlank { id }))
                                RemoteRepository.joinMembership(context, id)
                                // Seed the child with the default schedule and push it.
                                val seed = prefs.childSchedule(id)
                                prefs.setChildSchedule(id, seed)
                                RemoteRepository.pushPolicy(context, id, seed)
                                val shown = nameInput.trim().ifBlank { id }
                                codeInput = ""; nameInput = ""
                                snackbar.showSnackbar("✅ $shown conectado!")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar filho") }
            }
        }

        SecurityPinCard(prefs)
        DeviceOwnerSetupCard(prefs)

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
                onSetSchedule = { sched, msg ->
                    scope.launch {
                        prefs.setChildSchedule(child.id, sched)
                        RemoteRepository.pushPolicy(context, child.id, sched)
                        snackbar.showSnackbar(
                            if (remoteAvailable) msg
                            else "Sem conexão remota — vale quando o celular sincronizar",
                        )
                    }
                },
                onRequestRelease = { confirmRelease = child },
            )
        }
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

private fun connectionStatus(lastSeen: Long): String {
    if (lastSeen <= 0L) return "⚪ Sem sinal recente"
    val min = (System.currentTimeMillis() - lastSeen) / 60000
    return when {
        min < 5 -> "🟢 Online"
        min < 60 -> "🟡 Visto há $min min"
        min < 1440 -> "⚪ Visto há ${min / 60} h"
        else -> "⚪ Visto há ${min / 1440} d"
    }
}

@Composable
private fun ChildCard(
    prefs: Prefs,
    child: ChildRef,
    onOpen: () -> Unit,
    onSetSchedule: (Schedule, String) -> Unit,
    onRequestRelease: () -> Unit,
) {
    val context = LocalContext.current
    val remoteAvailable = remember { RemoteRepository.available(context) }
    val schedule by prefs.childScheduleFlow(child.id).collectAsState(initial = Schedule())
    val s = schedule ?: Schedule()

    var lastSeen by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf(ChildStatus()) }
    DisposableEffect(child.id, remoteAvailable) {
        val regs = mutableListOf<ListenerRegistration>()
        if (remoteAvailable) {
            RemoteRepository.listenHeartbeat(context, child.id) { lastSeen = it }
                ?.let { regs.add(it) }
            RemoteRepository.listenStatus(context, child.id) { json, _ ->
                status = ChildStatus.decode(json)
            }?.let { regs.add(it) }
        }
        onDispose { regs.forEach { it.remove() } }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(child.name, style = MaterialTheme.typography.titleLarge)
            Text("Código: ${child.id}", style = MaterialTheme.typography.bodySmall)
            Text(connectionStatus(lastSeen), style = MaterialTheme.typography.bodySmall)

            // Live one-liner: what the child is doing right now.
            val now = System.currentTimeMillis()
            if (!status.isStale(now)) {
                Text(
                    when {
                        status.usingNow(now) ->
                            "📱 Usando ${status.currentAppLabel.ifBlank { status.currentApp }}"
                        status.screenOn -> "📱 Tela ligada"
                        else -> "😴 Em repouso"
                    } + " · ${formatMinutes(status.totalMinutesToday)} hoje",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Direct immediate block / unblock, no need to open the editor.
            val manual = s.manualState(now)
            val stateLabel = when {
                manual == Schedule.BLOCK -> "🔒 Internet bloqueada agora (pelo responsável)"
                manual == Schedule.ALLOW -> "🔓 Internet liberada agora (pelo responsável)"
                status.internetBlocked && !status.isStale(now) -> "🚫 Internet bloqueada (horário)"
                else -> "✅ Seguindo o horário"
            }
            Text(stateLabel, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton(
                    onClick = { onSetSchedule(blockNow(s), "🚫 Internet de ${child.name} bloqueada agora") },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) { Text("🚫 Bloquear") }
                PixelButton(
                    onClick = { onSetSchedule(allowNow(s), "🔓 Internet de ${child.name} liberada agora") },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) { Text("🔓 Liberar") }
            }
            if (manual != Schedule.NONE) {
                TextButton(onClick = {
                    onSetSchedule(followSchedule(s), "Voltou a seguir o horário")
                }) { Text("Voltar a seguir o horário") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Abrir controle") }
            }
            TextButton(onClick = onRequestRelease) { Text("Remover proteção / desinstalar") }
        }
    }
}

// ---- Immediate-action schedule transforms (shared by card and editor) --------

/** Block the internet indefinitely, right now (clears any force-allow). */
private fun blockNow(s: Schedule): Schedule = s.copy(
    manualBlockUntil = Long.MAX_VALUE,
    manualUnblockUntil = 0L,
    manualSetAt = System.currentTimeMillis(),
)

/** Force-allow the internet indefinitely, right now (overrides windows). */
private fun allowNow(s: Schedule): Schedule = s.copy(
    manualUnblockUntil = Long.MAX_VALUE,
    manualBlockUntil = 0L,
    manualSetAt = System.currentTimeMillis(),
)

/** Clear both manual overrides so the scheduled windows apply again. */
private fun followSchedule(s: Schedule): Schedule = s.copy(
    manualBlockUntil = 0L,
    manualUnblockUntil = 0L,
    manualSetAt = System.currentTimeMillis(),
)

/** Block the internet for one hour from now. */
private fun blockForOneHour(s: Schedule): Schedule = s.copy(
    manualBlockUntil = System.currentTimeMillis() + 60L * 60 * 1000,
    manualUnblockUntil = 0L,
    manualSetAt = System.currentTimeMillis(),
)

@Composable
private fun ChildScheduleEditor(
    prefs: Prefs,
    child: ChildRef,
    onBack: () -> Unit,
    onOpenLiveMap: (String) -> Unit,
) {
    // Back gesture / button returns to the children dashboard instead of exiting.
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteAvailable = remember { RemoteRepository.available(context) }

    val schedule by prefs.childScheduleFlow(child.id).collectAsState(initial = Schedule())
    val current = schedule ?: Schedule()
    val window = current.windows.firstOrNull() ?: BlockWindow(22 * 60, 6 * 60 + 30)

    val snackbar = remember { SnackbarHostState() }

    fun persist(newSchedule: Schedule, toast: String? = null) {
        scope.launch {
            prefs.setChildSchedule(child.id, newSchedule)
            RemoteRepository.pushPolicy(context, child.id, newSchedule)
            toast?.let {
                snackbar.showSnackbar(
                    if (remoteAvailable) it else "Salvo — vale quando o celular sincronizar",
                )
            }
        }
    }

    fun updateWindow(newWindow: BlockWindow) {
        val rest = current.windows.drop(1)
        persist(current.copy(windows = listOf(newWindow) + rest))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).arcadeGrid().padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Voltar aos filhos") }
        Text("Controle de ${child.name}", style = MaterialTheme.typography.headlineMedium)
        Text("Código: ${child.id}", style = MaterialTheme.typography.bodySmall)

        // 🌐 Internet — block everything or just specific apps, right now.
        InternetControlCard(
            prefs = prefs,
            child = child,
            current = current,
            remoteAvailable = remoteAvailable,
            onApply = { sched, msg -> persist(sched, msg) },
        )

        // 🗓️ Scheduled actions — the recurring block window.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🗓️ Ações agendadas", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Bloqueio por horário", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = current.enabled,
                        onCheckedChange = {
                            persist(
                                current.copy(enabled = it),
                                if (it) "Bloqueio por horário ligado" else "Bloqueio por horário desligado",
                            )
                        },
                    )
                }
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

        ChildMonitorCard(child)

        ChildAppLockCard(prefs, child)

        ChildLocationCard(prefs, child, onOpenLiveMap = { onOpenLiveMap(child.id) })

        AppRulesEditor(prefs, child)
    }
    }
}

/**
 * Parent control for the child app-open lock: enable it, read the current
 * rotating code to give the child, and set a fixed emergency PIN as a fail-safe.
 */
@Composable
private fun ChildAppLockCard(prefs: Prefs, child: ChildRef) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteAvailable = remember { RemoteRepository.available(context) }
    val policy by prefs.childAppPolicyFlow(child.id).collectAsState(initial = AppPolicy())
    var showSetPin by remember { mutableStateOf(false) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun persist(updated: AppPolicy) {
        scope.launch {
            prefs.setChildAppPolicy(child.id, updated)
            RemoteRepository.pushAppPolicy(context, child.id, updated)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🔐 Bloqueio do app do filho", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Exigir código para abrir",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = policy.lockEnabled,
                    onCheckedChange = { on ->
                        var p = policy.copy(lockEnabled = on)
                        if (on && p.lockSecret.isBlank()) p = p.copy(lockSecret = Totp.randomSecretBase64())
                        persist(p)
                    },
                )
            }
            if (policy.lockEnabled && policy.lockSecret.isNotBlank()) {
                val secs = Totp.secondsRemaining(now)
                Text("Código atual para abrir o app do filho:", style = MaterialTheme.typography.bodySmall)
                Text(
                    Totp.code(policy.lockSecret, now).chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { secs / 30f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    "Muda em ${secs}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text(
                if (policy.lockFixedPinHash.isBlank()) "PIN fixo de emergência: não definido"
                else "PIN fixo de emergência: definido ✓",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showSetPin = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (policy.lockFixedPinHash.isBlank()) "Definir PIN fixo" else "Alterar PIN fixo")
            }
            Text(
                "O código muda a cada 30s e aparece aqui. O PIN fixo é o plano B: memorize-o para " +
                    "abrir o app do filho caso você fique sem este celular. O ícone continua visível — " +
                    "isto só impede o filho de abrir o app para mexer nas configurações.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!remoteAvailable) {
                Text(
                    "Sem conexão remota — vale quando o celular do filho sincronizar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showSetPin) {
        SetFixedPinDialog(
            hasExisting = policy.lockFixedPinHash.isNotBlank(),
            onDismiss = { showSetPin = false },
            onClear = { persist(policy.copy(lockFixedPinHash = "")); showSetPin = false },
            onSet = { pin -> persist(policy.copy(lockFixedPinHash = Pin.hash(pin))); showSetPin = false },
        )
    }
}

@Composable
private fun SetFixedPinDialog(
    hasExisting: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSet: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN fixo de emergência") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Defina um PIN (mín. 4 dígitos). Memorize-o: serve para abrir o app do filho " +
                        "se você perder o acesso ao código dinâmico.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN fixo") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { onSet(pin) }) { Text("Salvar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasExisting) TextButton(onClick = onClear) { Text("Remover") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

/**
 * Internet control: block everything now (except Morpheus itself, so the child's
 * location and remote commands keep working) or cut only specific apps'
 * connection, plus the 1-hour block and "follow schedule" reset. The per-app
 * toggles edit the child's [AppPolicy] and take effect in real time.
 */
@Composable
private fun InternetControlCard(
    prefs: Prefs,
    child: ChildRef,
    current: Schedule,
    remoteAvailable: Boolean,
    onApply: (Schedule, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appPolicy by prefs.childAppPolicyFlow(child.id).collectAsState(initial = AppPolicy())
    val manual = current.manualState(System.currentTimeMillis())

    fun persistPolicy(p: AppPolicy) {
        scope.launch {
            prefs.setChildAppPolicy(child.id, p)
            RemoteRepository.pushAppPolicy(context, child.id, p)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🌐 Internet", style = MaterialTheme.typography.titleMedium)
            Text(
                when (manual) {
                    Schedule.BLOCK -> "🔒 Internet bloqueada agora pelo responsável"
                    Schedule.ALLOW -> "🔓 Internet liberada agora pelo responsável"
                    else -> "Seguindo o horário programado"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton(
                    onClick = { onApply(blockNow(current), "🚫 Internet bloqueada agora") },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) { Text("🚫 Bloquear tudo") }
                PixelButton(
                    onClick = { onApply(allowNow(current), "🔓 Internet liberada agora") },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) { Text("🔓 Liberar tudo") }
            }
            OutlinedButton(
                onClick = { onApply(blockForOneHour(current), "🚫 Bloqueado por 1 hora") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Bloquear tudo por 1 hora") }
            if (manual != Schedule.NONE) {
                TextButton(onClick = { onApply(followSchedule(current), "Voltou a seguir o horário") }) {
                    Text("Voltar a seguir o horário")
                }
            }

            HorizontalDivider()

            Text("Cortar internet de um app específico", style = MaterialTheme.typography.titleSmall)
            if (appPolicy.apps.isEmpty()) {
                Text(
                    "Adicione apps em “Tempo de tela e apps”, mais abaixo, para cortar a internet " +
                        "de um app sem bloquear o resto do aparelho.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                appPolicy.apps.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (rule.netBlocked) "🚫 " else "🌐 ") +
                                rule.label.ifBlank { KnownApps.labelFor(rule.packageName) },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = rule.netBlocked,
                            onCheckedChange = { on ->
                                persistPolicy(
                                    appPolicy.copy(
                                        apps = appPolicy.apps.map {
                                            if (it.packageName == rule.packageName) it.copy(netBlocked = on) else it
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Text(
                "Mesmo com a internet totalmente bloqueada, você continua vendo a localização do " +
                    "filho — o Morpheus mantém a própria conexão.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!remoteAvailable) {
                Text(
                    "Sem conexão remota — as ações valem quando o celular sincronizar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Address -> coordinates, so the parent never types latitude/longitude. Uses the
 * platform [Geocoder] (no maps SDK, no API key). Blocking call, hence IO.
 */
private suspend fun lookupCoordinates(context: Context, query: String): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {
        if (query.isBlank() || !Geocoder.isPresent()) return@withContext null
        @Suppress("DEPRECATION") // The async overload only exists from API 33.
        val hit = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
        }.getOrNull()?.firstOrNull() ?: return@withContext null
        hit.latitude to hit.longitude
    }

/** Coordinates -> a readable address for display, or null when unavailable. */
private suspend fun lookupAddress(context: Context, lat: Double, lng: Double): String? =
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        @Suppress("DEPRECATION")
        val hit = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
        }.getOrNull()?.firstOrNull() ?: return@withContext null
        listOfNotNull(hit.thoroughfare, hit.subLocality ?: hit.locality)
            .joinToString(", ")
            .ifBlank { hit.getAddressLine(0) }
    }

/**
 * Where the child device is, plus the safe-area (geofence) the child evaluates
 * locally. Uses a plain map intent instead of bundling a maps SDK.
 */
@Composable
private fun ChildLocationCard(prefs: Prefs, child: ChildRef, onOpenLiveMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteAvailable = remember { RemoteRepository.available(context) }

    var location by remember { mutableStateOf<Triple<Double, Double, Long>?>(null) }
    DisposableEffect(child.id, remoteAvailable) {
        val reg = if (remoteAvailable) {
            RemoteRepository.listenLocation(context, child.id) { lat, lng, at ->
                location = Triple(lat, lng, at)
            }
        } else null
        onDispose { reg?.remove() }
    }

    val current by prefs.childAppPolicyFlow(child.id).collectAsState(initial = AppPolicy())
    val fence = current.geofence

    fun persist(newFence: Geofence) {
        scope.launch {
            val updated = current.copy(geofence = newFence)
            prefs.setChildAppPolicy(child.id, updated)
            RemoteRepository.pushAppPolicy(context, child.id, updated)
        }
    }

    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("📍 Localização", style = MaterialTheme.typography.titleMedium)

            Button(onClick = onOpenLiveMap, modifier = Modifier.fillMaxWidth()) {
                Text("🗺️ Ver mapa ao vivo e rota (24h)")
            }

            val loc = location
            if (loc == null) {
                Text(
                    "Sem localização recente. Ative a localização no celular do filho " +
                        "(passo “Localização” no app dele).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Última posição em ${fmt.format(Date(loc.third))}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("%.5f, %.5f".format(loc.first, loc.second),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse(
                            "geo:${loc.first},${loc.second}?q=${loc.first},${loc.second}(${child.name})",
                        )
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abrir no mapa") }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Área segura (avisos)", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = fence.enabled,
                    onCheckedChange = { persist(fence.copy(enabled = it)) },
                )
            }

            if (fence.enabled) {
                OutlinedTextField(
                    value = fence.name,
                    onValueChange = { persist(fence.copy(name = it)) },
                    label = { Text("Nome (ex.: Casa, Escola)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Pick the centre by address instead of typing coordinates.
                var query by remember(child.id) { mutableStateOf("") }
                var searching by remember(child.id) { mutableStateOf(false) }
                var searchError by remember(child.id) { mutableStateOf<String?>(null) }
                var centreAddress by remember(child.id) { mutableStateOf<String?>(null) }

                // Show the saved centre as a readable address.
                LaunchedEffect(fence.lat, fence.lng) {
                    centreAddress = if (fence.lat == 0.0 && fence.lng == 0.0) null
                    else lookupAddress(context, fence.lat, fence.lng)
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; searchError = null },
                    label = { Text("Buscar endereço") },
                    placeholder = { Text("Rua, número, cidade") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            searching = true
                            searchError = null
                            val found = lookupCoordinates(context, query)
                            searching = false
                            if (found == null) {
                                searchError = "Endereço não encontrado. Tente incluir a cidade."
                            } else {
                                persist(fence.copy(lat = found.first, lng = found.second))
                                query = ""
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "Buscando…" else "🔎 Usar este endereço") }

                searchError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                val loc2 = location
                OutlinedButton(
                    onClick = { loc2?.let { persist(fence.copy(lat = it.first, lng = it.second)) } },
                    enabled = loc2 != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📍 Usar onde ${child.name} está agora") }

                // Current centre, as an address when we can resolve one.
                Text(
                    when {
                        fence.lat == 0.0 && fence.lng == 0.0 -> "Centro ainda não definido."
                        centreAddress != null -> "Centro: $centreAddress"
                        else -> "Centro definido ✓"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fence.lat != 0.0 || fence.lng != 0.0) {
                    OutlinedButton(
                        onClick = {
                            val uri = Uri.parse(
                                "geo:${fence.lat},${fence.lng}?q=${fence.lat},${fence.lng}(${fence.name.ifBlank { "Área segura" }})",
                            )
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ver a área no mapa") }
                }

                Text("Raio: ${fence.radiusMeters} m", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 500, 1000).forEach { r ->
                        val selected = fence.radiusMeters == r
                        if (selected) {
                            Button(onClick = { persist(fence.copy(radiusMeters = r)) }) { Text("${r}m") }
                        } else {
                            OutlinedButton(onClick = { persist(fence.copy(radiusMeters = r)) }) { Text("${r}m") }
                        }
                    }
                }
                Text(
                    "Você recebe um aviso quando o aparelho entra ou sai desta área.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Formats minutes as "2 h 15 min" / "45 min". */
private fun formatMinutes(min: Int): String = when {
    min <= 0 -> "0 min"
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

private fun reasonLabel(reason: String): String = when (reason) {
    "manual" -> "internet cortada"
    "homework" -> "modo tarefa"
    "budget" -> "limite do dia"
    "limit" -> "limite do app"
    else -> "horário"
}

/**
 * Live monitoring panel: what the child is doing right now, today's screen time,
 * the app ranking and which apps are currently blocked.
 */
@Composable
private fun ChildMonitorCard(child: ChildRef) {
    val context = LocalContext.current
    val remoteAvailable = remember { RemoteRepository.available(context) }
    var status by remember { mutableStateOf(ChildStatus()) }

    DisposableEffect(child.id, remoteAvailable) {
        val reg = if (remoteAvailable) {
            RemoteRepository.listenStatus(context, child.id) { json, _ ->
                status = ChildStatus.decode(json)
            }
        } else null
        onDispose { reg?.remove() }
    }

    // Re-render each 30s so "há X min" and the live/stale badge stay honest.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(child.id) {
        while (true) {
            delay(30_000)
            tick = System.currentTimeMillis()
        }
    }
    val now = tick

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- Right now -------------------------------------------------------
        val using = status.usingNow(now)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (using) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (using) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Agora", style = MaterialTheme.typography.titleMedium)
                when {
                    status.isStale(now) -> Text(
                        "Sem dados recentes — o celular pode estar sem internet ou desligado.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    using -> {
                        Text(
                            "📱 Usando: ${status.currentAppLabel.ifBlank { status.currentApp }}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (status.currentAppSince > 0) {
                            val mins = ((now - status.currentAppSince) / 60000L).toInt().coerceAtLeast(0)
                            Text("Há ${formatMinutes(mins)} neste app", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    status.screenOn -> Text("📱 Tela ligada", style = MaterialTheme.typography.titleLarge)
                    else -> Text("😴 Celular em repouso", style = MaterialTheme.typography.titleLarge)
                }
                if (status.internetBlocked) {
                    Text("🚫 Internet bloqueada agora", style = MaterialTheme.typography.bodyMedium)
                }
                if (status.homeworkActive) {
                    Text("📚 Modo tarefa ativo", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---- Screen time today ----------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tempo de tela hoje", style = MaterialTheme.typography.titleMedium)
                if (!status.usageAccessGranted) {
                    Text(
                        "O acesso de uso não foi liberado no celular do filho. " +
                            "Peça para ativar em “Contador de tempo”, nos extras do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        formatMinutes(status.totalMinutesToday),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if (status.budgetMinutes > 0) {
                        val frac = (status.totalMinutesToday.toFloat() / status.budgetMinutes)
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Text(
                            "de ${formatMinutes(status.budgetMinutes)} permitidos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Top apps --------------------------------------------------------
        if (status.topApps.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Apps mais usados hoje", style = MaterialTheme.typography.titleMedium)
                    val max = status.topApps.maxOf { it.minutes }.coerceAtLeast(1)
                    status.topApps.forEach { app ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    app.label.ifBlank { KnownApps.labelFor(app.pkg) },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(formatMinutes(app.minutes), style = MaterialTheme.typography.bodyMedium)
                            }
                            LinearProgressIndicator(
                                progress = { app.minutes.toFloat() / max },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- Blocked right now ----------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Apps bloqueados agora", style = MaterialTheme.typography.titleMedium)
                if (status.blockedApps.isEmpty()) {
                    Text(
                        "Nenhum app bloqueado neste momento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    status.blockedApps.forEach { app ->
                        Text(
                            "🚫 ${app.label.ifBlank { KnownApps.labelFor(app.pkg) }} — ${reasonLabel(app.reason)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
