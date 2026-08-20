package com.morpheus.family.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
 * Dedicated, standalone screen for installing Morpheus in **Device Owner mode**
 * ("Modo máximo") on a **brand-new or freshly factory-reset** child phone.
 *
 * Zero-config: the signed-APK URL and its signing checksum come from a published
 * manifest (see DeviceOwnerProvisioning.fetchManifest), so the provisioning QR is
 * ready to scan the moment this screen opens — the parent enters nothing. The URL,
 * checksum and Wi-Fi fields are tucked away as optional extras.
 */
@Composable
fun DeviceOwnerGuideScreen(prefs: Prefs, onBack: () -> Unit) {
    // State-based navigation: system back returns to the parent home, not out of app.
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apkUrl by remember { mutableStateOf("") }
    var checksum by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // null = still loading; true = auto-configured from the manifest; false = offline fallback.
    var autoOk by remember { mutableStateOf<Boolean?>(null) }
    var showWifi by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Pull the fixed signed-APK URL + its signing checksum from the published
        // manifest so the QR needs no manual input.
        val manifest = DeviceOwnerProvisioning.fetchManifest()
        if (manifest != null) {
            apkUrl = manifest.first
            checksum = manifest.second
            autoOk = true
        } else {
            // Offline: a saved override if any, else the known default URL; checksum
            // best-effort from this app's own signature (correct on a release build).
            apkUrl = prefs.deviceOwnerApkUrlFlow.first()
                .ifBlank { DeviceOwnerProvisioning.DEFAULT_APK_URL }
            checksum = DeviceOwnerProvisioning.signatureChecksum(context)
            autoOk = false
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Text("Instalar em celular novo (Modo máximo)", style = MaterialTheme.typography.headlineSmall)

            // ---- What it is & the hard requirement --------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("O que é o Modo máximo (Device Owner)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "É o único modo que bloqueia de verdade: impede desinstalar o Morpheus, " +
                            "criar segundo espaço/usuário e mexer nas opções de desenvolvedor. " +
                            "Não precisa de computador.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- The reset warning (front and center) -----------------------------
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️ Exige celular zerado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Por regra de segurança do Android, o Modo máximo só pode ser ativado num " +
                            "aparelho SEM nenhuma conta configurada. Na prática, isso significa fazer " +
                            "um RESET DE FÁBRICA no celular do filho — o que APAGA TUDO (fotos, apps, " +
                            "contas, mensagens). Por isso, use só num celular novo ou que possa ser " +
                            "apagado por completo. Não dá para ativar num celular que já está em uso " +
                            "sem apagá-lo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ---- Step by step -----------------------------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Passo a passo", style = MaterialTheme.typography.titleMedium)
                    listOf(
                        "1. O QR já vem pronto aqui embaixo — você não precisa digitar nada. " +
                            "(Se o Wi-Fi do local exigir senha, dá para incluí-lo no QR na opção abaixo.)",
                        "2. No celular do filho, faça um RESET DE FÁBRICA " +
                            "(Configurações → Sistema → Opções de redefinição → Apagar todos os dados). " +
                            "Isso apaga o aparelho.",
                        "3. Ligue o celular. Na 1ª tela de boas-vindas (a de escolher idioma, ANTES de " +
                            "entrar em qualquer conta Google), toque 6 VEZES no mesmo ponto da tela. " +
                            "Isso abre o leitor de QR de configuração.",
                        "4. Se pedir, conecte no Wi-Fi (ou já inclua o Wi-Fi no QR na opção abaixo).",
                        "5. Escaneie o QR gerado aqui. O próprio Android baixa e instala o Morpheus " +
                            "JÁ como Device Owner e conclui a configuração sozinho. Você não instala o " +
                            "app manualmente — o QR faz tudo.",
                        "6. Ao terminar, abra o Morpheus no celular do filho e pareie normalmente com " +
                            "este app do responsável. Pronto: a Proteção máxima fica ativa.",
                    ).forEach { step ->
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Obs.: em alguns fabricantes (Xiaomi/Samsung), o “segundo espaço / pasta segura” " +
                            "próprio do sistema pode não ser bloqueável mesmo com Device Owner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- The QR, ready to scan (zero input) -------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("QR de configuração", style = MaterialTheme.typography.titleMedium)

                    when (autoOk) {
                        null -> Text(
                            "Preparando o QR…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        true -> Text(
                            "✓ Link e assinatura configurados automaticamente. É só escanear.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        false -> Text(
                            "Sem internet agora: usando o link padrão. Se o QR não funcionar no " +
                                "celular do filho, conecte ESTE aparelho à internet e reabra esta tela.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (apkUrl.isNotBlank() && checksum.isNotBlank()) {
                        val payload = remember(apkUrl, checksum, ssid, pass) {
                            DeviceOwnerProvisioning.buildQrPayload(apkUrl, checksum, ssid, pass)
                        }
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QrCode(payload, sizePx = 700, modifier = Modifier.size(260.dp))
                            Text(
                                "Escaneie no celular do filho recém-resetado, na tela de boas-vindas " +
                                    "(toque 6× para abrir o leitor).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // ---- Optional: bake Wi-Fi into the QR -----------------------------
                    TextButton(onClick = { showWifi = !showWifi }) {
                        Text(if (showWifi) "Ocultar Wi-Fi" else "Incluir Wi-Fi no QR (opcional)")
                    }
                    if (showWifi) {
                        OutlinedTextField(
                            value = ssid,
                            onValueChange = { ssid = it },
                            label = { Text("Wi-Fi SSID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Wi-Fi senha") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ---- Optional: manual override (rarely needed) --------------------
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Ocultar avançado" else "Avançado")
                    }
                    if (showAdvanced) {
                        OutlinedTextField(
                            value = apkUrl,
                            onValueChange = { apkUrl = it; scope.launch { prefs.setDeviceOwnerApkUrl(it.trim()) } },
                            label = { Text("URL do APK assinado (release)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = checksum,
                            onValueChange = { checksum = it.trim() },
                            label = { Text("Checksum da assinatura") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
