package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.Prefs
import com.morpheus.family.provisioning.DeviceOwnerProvisioning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Parent-side generator for the **Device Owner provisioning QR**. The parent
 * fills in the URL that serves the signed release APK; the app auto-fills the
 * signing checksum (from itself — correct when this is the release build) and
 * renders the QR the freshly-reset child device scans during setup.
 */
@Composable
fun DeviceOwnerSetupCard(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apkUrl by remember { mutableStateOf("") }
    var checksum by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apkUrl = prefs.deviceOwnerApkUrlFlow.first()
        if (checksum.isBlank()) checksum = DeviceOwnerProvisioning.signatureChecksum(context)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Modo máximo (Device Owner)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Ocultar" else "Configurar")
                }
            }

            if (expanded) {
                Text(
                    "Proteção inuninstalável, sem computador. Requer resetar o celular do filho. " +
                        "Informe a URL do APK assinado (release) para gerar o QR.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = apkUrl,
                    onValueChange = { apkUrl = it; scope.launch { prefs.setDeviceOwnerApkUrl(it.trim()) } },
                    label = { Text("URL do APK assinado") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = checksum,
                    onValueChange = { checksum = it.trim() },
                    label = { Text("Checksum da assinatura (auto)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Wi-Fi SSID (opcional, para baixar o APK)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Wi-Fi senha (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (apkUrl.isNotBlank() && checksum.isNotBlank()) {
                    val payload = remember(apkUrl, checksum, ssid, pass) {
                        DeviceOwnerProvisioning.buildQrPayload(apkUrl, checksum, ssid, pass)
                    }
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QrCode(payload, sizePx = 700, modifier = Modifier.size(240.dp))
                        Text(
                            "No celular do filho: resete de fábrica → na tela de boas-vindas, " +
                                "toque 6× na tela → escaneie este QR. O Morpheus será instalado " +
                                "como Device Owner (impossível de desinstalar).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        "Informe a URL do APK para gerar o QR.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
