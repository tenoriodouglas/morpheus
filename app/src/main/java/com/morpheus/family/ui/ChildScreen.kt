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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
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

/**
 * Child device: shows the pairing code, walks the guardian through the one-time
 * consents, then displays a transparent "protected" status. Nothing here is
 * hidden from the person using the phone — that transparency is deliberate.
 */
@Composable
fun ChildScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }

    val pairId by prefs.pairedIdFlow.collectAsState(initial = null)
    val schedule by prefs.scheduleFlow.collectAsState(initial = null)

    // Ensure a pairing code exists and the guardian service is running.
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

    // Refresh the live checklist whenever we return from a Settings screen.
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
    val coreReady = notifReady && vpnReady && adminReady

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Modo Filho", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pareamento", style = MaterialTheme.typography.labelLarge)
                pairId?.let { code ->
                    QrCode(QR_PREFIX + code, modifier = Modifier.size(200.dp))
                }
                Text(pairId ?: "…", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Escaneie este QR no celular do responsável — ou digite o código acima, " +
                        "caso não consiga ler o QR.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (coreReady) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "✅ Tudo pronto! Este aparelho está protegido.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Text("Ative as proteções (uma única vez):", style = MaterialTheme.typography.titleMedium)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SetupButton(notifReady, "Permitir notificações") {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SetupButton(vpnReady, "Autorizar bloqueio de internet") {
            VpnService.prepare(context)?.let { vpnLauncher.launch(it) } ?: run { refresh++ }
        }
        SetupButton(adminReady, "Ativar proteção anti-desinstalação") {
            adminLauncher.launch(adminIntent(context))
        }
        SetupButton(null, "Desativar otimização de bateria") {
            runCatching { context.startActivity(batteryIntent()) }
        }
        SetupButton(a11yReady, "Ativar bloqueio de apps (Acessibilidade)") {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        SetupButton(usageReady, "Permitir acesso de uso (limites por app)") {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        SetupButton(locReady, "Permitir localização (mapa e SOS)") {
            locationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        Text(
            "Acessibilidade, acesso de uso e localização habilitam bloqueio de apps, " +
                "limites diários e mapa (não necessários no modo Device Owner).",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Níveis de proteção contra remoção", style = MaterialTheme.typography.titleSmall)
                Text(
                    "• Device Admin (atual): pode ser desativado em Configurações → Segurança → " +
                        "Apps de administração. Se isso acontecer, o responsável é avisado na hora.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Device Owner (máximo): impossível de desinstalar. É configurado ao resetar o " +
                        "aparelho e escanear o QR de provisionamento no assistente inicial — sem " +
                        "precisar de computador. Recomendado para proteção total.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Horários bloqueados", style = MaterialTheme.typography.titleMedium)
                val s = schedule
                if (s == null || !s.enabled) {
                    Text("Nenhum bloqueio ativo no momento.")
                } else {
                    s.windows.forEach { Text("• ${it.label()}") }
                }
                Text(
                    "Definidos pelo responsável. Este aparelho é gerenciado pelo Morpheus.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Requests + SOS.
        val id = pairId
        if (!id.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Precisa de algo?", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = {
                            scope.launch {
                                RemoteRepository.reportRequest(
                                    context, id, "extra", "Pediu mais tempo", System.currentTimeMillis(),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pedir mais tempo ao responsável") }
                    Button(
                        onClick = {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                RemoteRepository.reportAlert(context, id, "sos", now)
                                LocationReporter.reportOnce(context, id, Geofence(), now)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🆘 Enviar SOS") }
                }
            }
        }
    }
}

@Composable
private fun SetupButton(done: Boolean?, label: String, onClick: () -> Unit) {
    val prefix = when (done) {
        true -> "✓  "
        false -> "○  "
        null -> ""
    }
    if (done == true) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(prefix + label)
        }
    } else {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(prefix + label)
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
        putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            MorpheusDeviceAdminReceiver.component(context),
        )
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Impede a desinstalação e permite aplicar os horários definidos pelo responsável.",
        )
    }

private fun batteryIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
