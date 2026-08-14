package com.morpheus.family.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.admin.ProtectionManager
import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.enforcement.UsageTracker
import com.morpheus.family.location.LocationReporter
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.service.GuardianService
import com.morpheus.family.util.Pin
import kotlinx.coroutines.launch
import java.util.UUID

/** The friendly Morpheus mascot — a sleepy little owl that "guards the night". */
private const val MASCOT = "🦉"

private data class SetupStep(
    val emoji: String,
    val title: String,
    val desc: String,
    val done: Boolean?, // null = can't be detected (e.g. battery optimization)
    val required: Boolean,
    val onClick: () -> Unit,
)

/**
 * Child device: a warm, playful screen. During setup it's a friendly one-step-at-a-time
 * wizard; once ready it becomes a cheerful "you're protected" home with the pairing code,
 * the rest schedule and the help buttons. Nothing here is hidden — that's deliberate.
 */
@Composable
fun ChildScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    val pairId by prefs.pairedIdFlow.collectAsState(initial = null)
    val schedule by prefs.scheduleFlow.collectAsState(initial = null)
    val appPolicy by prefs.appPolicyFlow.collectAsState(initial = AppPolicy())
    val parentPin by prefs.parentPinFlow.collectAsState(initial = null)
    var showRemove by remember { mutableStateOf(false) }
    // Location is opt-in and gated behind a prominent disclosure (Play policy).
    var showLocationDisclosure by remember { mutableStateOf(false) }
    var showBackgroundDisclosure by remember { mutableStateOf(false) }

    LaunchedEffect(pairId) {
        if (pairId.isNullOrBlank()) {
            prefs.setPairedId(UUID.randomUUID().toString().take(8).uppercase())
        } else {
            GuardianService.start(context)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++; GuardianService.start(context) }
    // Foreground location first; Android only accepts the background request
    // afterwards, and as a prompt of its own.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        refresh++
        val ok = granted.values.any { it }
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) showBackgroundDisclosure = true
        if (ok) GuardianService.start(context)
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++ }

    // Re-evaluate the checklist whenever we return from a Settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifReady = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val vpnReady = remember(refresh) { VpnService.prepare(context) == null }
    val adminReady = remember(refresh) { isAdminActive(context) }
    val usageReady = remember(refresh) { UsageTracker.hasUsageAccess(context) }
    val locReady = remember(refresh) { LocationReporter.hasPermission(context) }
    val locBgReady = remember(refresh) { LocationReporter.hasBackgroundPermission(context) }
    val batteryOk = remember(refresh) { LocationReporter.isBatteryUnrestricted(context) }

    val steps = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(SetupStep("🔔", "Ligar os avisos", "Para mostrar quando é hora de descansar.", notifReady, true) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        add(SetupStep("🌐", "Cuidar da internet", "Deixa o responsável combinar os horários da internet.", vpnReady, true) {
            VpnService.prepare(context)?.let { vpnLauncher.launch(it) } ?: run { refresh++ }
        })
        add(SetupStep("🛡️", "Escudo protetor", "Mantém o Morpheus seguro no celular.", adminReady, true) {
            adminLauncher.launch(adminIntent(context))
        })
        add(SetupStep("⏱️", "Contador de tempo", "Mostra quanto tempo cada app foi usado.", usageReady, false) {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        add(SetupStep("📍", "Localização", "Mostra ao responsável onde este celular está.", locReady, false) {
            showLocationDisclosure = true
        })
        // Only worth asking once the foreground grant exists.
        if (locReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(SetupStep("🗺️", "Localização o tempo todo", "Necessário para os avisos de área segura.", locBgReady, false) {
                showBackgroundDisclosure = true
            })
        }
        add(SetupStep("🔋", "Sempre desperto", "Mantém o Morpheus rodando mesmo com o app fechado.", batteryOk, false) {
            runCatching { context.startActivity(batteryIntent(context)) }
        })
    }
    val required = steps.filter { it.required }
    val optional = steps.filter { !it.required }
    val requiredDone = required.count { it.done == true }
    val allRequiredDone = requiredDone == required.size
    val currentStep = required.firstOrNull { it.done != true }

    val snackbar = remember { SnackbarHostState() }
    val remoteReady = remember(refresh) { RemoteRepository.available(context) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MascotHeader(protected = allRequiredDone)

            if (!allRequiredDone && currentStep != null) {
                WizardCard(currentStep, requiredDone, required.size)
                PairingCard(pairId)
            } else {
                HappyProtectedCard()
                LiveBlockCard(schedule, appPolicy)
                val id = pairId
                if (!id.isNullOrBlank()) HelpCard(
                    enabled = remoteReady,
                    onExtra = {
                        scope.launch {
                            RemoteRepository.reportRequest(
                                context, id, "extra", "Pediu mais tempo", System.currentTimeMillis(),
                            )
                            snackbar.showSnackbar("✅ Pedido enviado ao responsável!")
                        }
                    },
                    onSos = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            RemoteRepository.reportAlert(context, id, "sos", now)
                            runCatching {
                                LocationReporter.reportOnce(context, id, prefs.appPolicy().geofence, now)
                            }
                            snackbar.showSnackbar("🆘 Aviso enviado ao responsável!")
                        }
                    },
                )
                PairingCard(pairId)
                if (optional.any { it.done != true }) {
                    Text("Extras (se quiser) ✨", style = MaterialTheme.typography.titleMedium)
                    optional.forEach { OptionalRow(it) }
                }
            }

            TransparencyCard()

            RemoveProtectionCard(onClick = { showRemove = true })
        }
    }

    if (showLocationDisclosure) {
        LocationDisclosureDialog(
            onAccept = {
                showLocationDisclosure = false
                locationLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onDismiss = { showLocationDisclosure = false },
        )
    }

    if (showBackgroundDisclosure) {
        BackgroundLocationDialog(
            onContinue = {
                showBackgroundDisclosure = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLocationLauncher.launch(
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    )
                }
            },
            onDismiss = { showBackgroundDisclosure = false },
        )
    }

    if (showRemove) {
        RemoveProtectionDialog(
            expectedPinHash = parentPin,
            onDismiss = { showRemove = false },
            onConfirmed = {
                ProtectionManager.release(context)
                showRemove = false
            },
        )
    }
}

/**
 * Tells the child, in plain language, exactly what the guardian can see. The
 * whole point of the app is transparent supervision, so this is not optional.
 */
@Composable
private fun TransparencyCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("👀 O que o responsável vê", style = MaterialTheme.typography.titleSmall)
            Text("• Quanto tempo você usa o celular por dia", style = MaterialTheme.typography.bodySmall)
            Text("• Quais apps você mais usa e qual está aberto agora", style = MaterialTheme.typography.bodySmall)
            Text("• Se a tela está ligada e quais apps estão bloqueados", style = MaterialTheme.typography.bodySmall)
            Text(
                "• Onde este celular está — só se você permitir a localização",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Ele NÃO vê suas mensagens, fotos, senhas ou o que aparece na tela. " +
                    "O Morpheus nunca liga o microfone nem a câmera.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Parent-only escape hatch: tear down protection on this device so it can be uninstalled. */
@Composable
private fun RemoveProtectionCard(onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Opções do responsável", style = MaterialTheme.typography.titleSmall)
            Text(
                "Para desinstalar o Morpheus, primeiro remova a proteção aqui.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClick) { Text("Remover proteção deste aparelho") }
        }
    }
}

@Composable
private fun RemoveProtectionDialog(
    expectedPinHash: String?,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remover proteção?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Isto desliga o bloqueio e a proteção contra remoção. Depois você poderá " +
                        "desinstalar o app em Configurações → Apps → Morpheus.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (expectedPinHash != null) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit); error = false },
                        label = { Text("PIN do responsável") },
                        singleLine = true,
                        isError = error,
                    )
                    if (error) Text("PIN incorreto", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (expectedPinHash == null || Pin.hash(pin) == expectedPinHash) onConfirmed()
                else error = true
            }) { Text("Remover proteção") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MascotHeader(protected: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(MASCOT, fontSize = 34.sp) }
        }
        Column {
            Text("Oi! Eu sou o Morpheus", style = MaterialTheme.typography.titleLarge)
            Text(
                if (protected) "Estou de olho por você 💜" else "Vamos preparar seu celular?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HappyProtectedCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🎉", fontSize = 48.sp)
            Text("Tudo certinho!", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Seu celular está protegido pelo Morpheus.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PairingCard(pairId: String?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🔗 Conectar com o responsável", style = MaterialTheme.typography.titleMedium)
            Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                Box(Modifier.padding(12.dp)) {
                    pairId?.let { QrCode(QR_PREFIX + it, modifier = Modifier.size(180.dp)) }
                }
            }
            Text(pairId ?: "…", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "O responsável escaneia este QR — ou digita o código acima.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WizardCard(step: SetupStep, doneCount: Int, total: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Passo ${doneCount + 1} de $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { doneCount.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(step.emoji, fontSize = 40.sp) }
            }
            Text(step.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                step.desc,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = step.onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Vamos lá!", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun OptionalRow(step: SetupStep) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(step.emoji, fontSize = 26.sp)
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    step.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.done == true) {
                Text("✓", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                TextButton(onClick = step.onClick) { Text("Ligar") }
            }
        }
    }
}

@Composable
private fun LiveBlockCard(schedule: Schedule?, appPolicy: AppPolicy) {
    val now = System.currentTimeMillis()
    val s = schedule
    val manual = s?.manualState(now) ?: Schedule.NONE
    var blocked = s?.isBlockedAt(now) ?: false
    val homework = appPolicy.homeworkActive(now)
    if (manual != Schedule.ALLOW && homework) blocked = true

    val statusText = when {
        manual == Schedule.BLOCK -> "🚫 Internet bloqueada agora pelo responsável"
        homework && manual != Schedule.ALLOW -> "📚 Modo tarefa: internet e apps pausados"
        blocked -> "🚫 Internet bloqueada agora (hora de descanso)"
        manual == Schedule.ALLOW -> "🔓 Internet liberada pelo responsável"
        else -> "✅ Tudo liberado agora. Aproveite! 🎈"
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (blocked) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (blocked) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Agora", style = MaterialTheme.typography.titleMedium)
            Text(statusText, style = MaterialTheme.typography.bodyLarge)
            if (s != null && s.enabled && s.windows.isNotEmpty()) {
                Text(
                    "Horário de descanso: " + s.windows.joinToString(", ") { it.label() },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Combinado com o seu responsável.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HelpCard(enabled: Boolean, onExtra: () -> Unit, onSos: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Precisa de ajuda?", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onExtra,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("⏰  Pedir mais um tempinho") }
            Button(
                onClick = onSos,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) { Text("🆘  Preciso de ajuda agora") }
            if (!enabled) {
                Text(
                    "Estes botões precisam de conexão com o responsável. " +
                        "Peça para ele parear este celular.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(MorpheusDeviceAdminReceiver.component(context))
}

private fun adminIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, MorpheusDeviceAdminReceiver.component(context))
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Impede a desinstalação e permite aplicar os horários definidos pelo responsável.",
        )
    }

// Direct "ignore battery optimizations" dialog for this app. Requires the
// REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared in the manifest).
@SuppressLint("BatteryLife")
private fun batteryIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
