package com.morpheus.family.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.data.Geofence
import com.morpheus.family.data.Prefs
import com.morpheus.family.enforcement.UsageTracker
import com.morpheus.family.location.LocationReporter
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.service.GuardianService
import kotlinx.coroutines.launch
import java.util.UUID

private data class SetupStep(
    val emoji: String,
    val title: String,
    val desc: String,
    val done: Boolean?, // null = can't be detected (e.g. battery optimization)
    val required: Boolean,
    val onClick: () -> Unit,
)

/**
 * Child device: friendly step-by-step setup wizard (one action at a time),
 * pairing QR, and a transparent "protected" status. Nothing here is hidden —
 * that transparency is deliberate.
 */
@Composable
fun ChildScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    val pairId by prefs.pairedIdFlow.collectAsState(initial = null)
    val schedule by prefs.scheduleFlow.collectAsState(initial = null)

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
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++; GuardianService.start(context) }
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
    val a11yReady = remember(refresh) { isAccessibilityEnabled(context) }
    val usageReady = remember(refresh) { UsageTracker.hasUsageAccess(context) }
    val locReady = remember(refresh) { LocationReporter.hasPermission(context) }

    val steps = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(SetupStep("🔔", "Notificações", "Mostrar o aviso de que este aparelho é gerenciado.", notifReady, true) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        add(SetupStep("🌐", "Bloqueio de internet", "Permite cortar a internet nos horários definidos pelo responsável.", vpnReady, true) {
            VpnService.prepare(context)?.let { vpnLauncher.launch(it) } ?: run { refresh++ }
        })
        add(SetupStep("🛡️", "Proteção contra remoção", "Impede que o app seja desinstalado facilmente.", adminReady, true) {
            adminLauncher.launch(adminIntent(context))
        })
        add(SetupStep("📵", "Bloqueio de apps", "Bloquear apps específicos em certos horários.", a11yReady, false) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        add(SetupStep("⏱️", "Limites por app", "Contar o tempo de uso de cada app.", usageReady, false) {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        add(SetupStep("📍", "Localização e SOS", "Mostrar a localização ao responsável e ativar o SOS.", locReady, false) {
            locationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        })
        add(SetupStep("🔋", "Bateria", "Manter a proteção sempre ativa em segundo plano.", null, false) {
            runCatching { context.startActivity(batteryIntent()) }
        })
    }
    val required = steps.filter { it.required }
    val optional = steps.filter { !it.required }
    val requiredDone = required.count { it.done == true }
    val allRequiredDone = requiredDone == required.size
    val currentStep = required.firstOrNull { it.done != true }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Celular do filho", style = MaterialTheme.typography.headlineSmall)

        PairingCard(pairId)

        if (!allRequiredDone && currentStep != null) {
            WizardCard(currentStep, requiredDone, required.size)
        } else {
            SuccessBanner()
        }

        if (allRequiredDone && optional.any { it.done != true }) {
            Text("Deixe ainda melhor (opcional)", style = MaterialTheme.typography.titleMedium)
            optional.forEach { OptionalRow(it) }
        }

        ScheduleCard(schedule?.let { it.enabled to it.windows.map { w -> w.label() } })

        val id = pairId
        if (!id.isNullOrBlank()) HelpCard(
            onExtra = {
                scope.launch {
                    RemoteRepository.reportRequest(context, id, "extra", "Pediu mais tempo", System.currentTimeMillis())
                }
            },
            onSos = {
                scope.launch {
                    val now = System.currentTimeMillis()
                    RemoteRepository.reportAlert(context, id, "sos", now)
                    LocationReporter.reportOnce(context, id, Geofence(), now)
                }
            },
        )

        ProtectionLevelsCard()
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
            Text("Conectar ao responsável", style = MaterialTheme.typography.titleMedium)
            Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                Box(Modifier.padding(12.dp)) {
                    pairId?.let { QrCode(QR_PREFIX + it, modifier = Modifier.size(180.dp)) }
                }
            }
            Text(pairId ?: "…", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Escaneie este QR no celular do responsável — ou informe o código acima.",
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
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Configuração — passo ${doneCount + 1} de $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { doneCount.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(step.emoji, fontSize = 34.sp) }
            }
            Text(step.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                step.desc,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = step.onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Ativar agora")
            }
        }
    }
}

@Composable
private fun SuccessBanner() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("✅", fontSize = 30.sp)
            Column {
                Text("Tudo pronto!", style = MaterialTheme.typography.titleLarge)
                Text("Este aparelho está protegido e gerenciado.", style = MaterialTheme.typography.bodyMedium)
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
            Text(step.emoji, fontSize = 24.sp)
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    step.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.done == true) {
                Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            } else {
                TextButton(onClick = step.onClick) { Text("Ativar") }
            }
        }
    }
}

@Composable
private fun ScheduleCard(state: Pair<Boolean, List<String>>?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Horários bloqueados", style = MaterialTheme.typography.titleMedium)
            if (state == null || !state.first) {
                Text("Nenhum bloqueio ativo no momento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.second.forEach { Text("• $it") }
            }
            Text(
                "Definidos pelo responsável. Este aparelho é gerenciado pelo Morpheus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpCard(onExtra: () -> Unit, onSos: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Precisa de algo?", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onExtra, modifier = Modifier.fillMaxWidth()) {
                Text("Pedir mais tempo ao responsável")
            }
            Button(
                onClick = onSos,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) { Text("🆘  Enviar SOS") }
        }
    }
}

@Composable
private fun ProtectionLevelsCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proteção contra remoção", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Padrão: pode ser desativado nas Configurações, mas o responsável é avisado na hora.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "• Máximo (Device Owner): impossível de desinstalar. Configurado ao resetar o aparelho e " +
                    "escanear o QR de provisionamento — sem precisar de computador.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun isAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(MorpheusDeviceAdminReceiver.component(context))
}

/** Whether our AppBlockAccessibilityService is enabled in system settings. */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return flat.split(':').any { it.contains(context.packageName, ignoreCase = true) }
}

private fun adminIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, MorpheusDeviceAdminReceiver.component(context))
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Impede a desinstalação e permite aplicar os horários definidos pelo responsável.",
        )
    }

private fun batteryIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
