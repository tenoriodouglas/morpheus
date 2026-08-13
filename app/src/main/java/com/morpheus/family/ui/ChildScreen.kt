package com.morpheus.family.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.data.Prefs
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
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++; GuardianService.start(context) }
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh++ }

    val vpnReady = remember(refresh) { VpnService.prepare(context) == null }
    val adminReady = remember(refresh) { isAdminActive(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Modo Filho", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Código de pareamento", style = MaterialTheme.typography.labelLarge)
                Text(
                    pairId ?: "…",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    "Digite este código no celular do responsável para conectar os dois aparelhos.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Ative as proteções (uma única vez):", style = MaterialTheme.typography.titleMedium)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SetupButton("1. Permitir notificações") {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SetupButton(if (vpnReady) "2. Bloqueio de internet: OK" else "2. Autorizar bloqueio de internet") {
            VpnService.prepare(context)?.let { vpnLauncher.launch(it) } ?: run { refresh++ }
        }
        SetupButton(if (adminReady) "3. Proteção anti-desinstalação: OK" else "3. Ativar proteção anti-desinstalação") {
            adminLauncher.launch(adminIntent(context))
        }
        SetupButton("4. Desativar otimização de bateria") {
            runCatching { context.startActivity(batteryIntent()) }
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
    }
}

@Composable
private fun SetupButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(label)
    }
}

private fun isAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(MorpheusDeviceAdminReceiver.component(context))
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
