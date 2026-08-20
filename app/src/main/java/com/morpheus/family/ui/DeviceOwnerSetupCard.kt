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
 * Kept apart from the family/settings menu on purpose: it is only relevant when
 * setting up a clean device, and it walks the parent through the whole flow —
 * factory reset, the 6-tap gesture on the welcome screen, scanning the QR — plus
 * generates the provisioning QR itself. Reached from its own entry, not buried in
 * the settings dashboard.
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

    LaunchedEffect(Unit) {
        apkUrl = prefs.deviceOwnerApkUrlFlow.first()
        if (checksum.isBlank()) checksum = DeviceOwnerProvisioning.signatureChecksum(context)
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
                        "1. Aqui embaixo, informe a URL onde o APK assinado (release) está hospedado " +
                            "e, se quiser, o Wi-Fi. O QR aparece automaticamente.",
                        "2. No celular do filho, faça um RESET DE FÁBRICA " +
                            "(Configurações → Sistema → Opções de redefinição → Apagar todos os dados). " +
                            "Isso apaga o aparelho.",
                        "3. Ligue o celular. Na 1ª tela de boas-vindas (a de escolher idioma, ANTES de " +
                            "entrar em qualquer conta Google), toque 6 VEZES no mesmo ponto da tela. " +
                            "Isso abre o leitor de QR de configuração.",
                        "4. Se pedir, conecte no Wi-Fi (ou já preencha o Wi-Fi abaixo para o QR fazer isso).",
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

            // ---- QR generator -----------------------------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gerar o QR de configuração", style = MaterialTheme.typography.titleMedium)
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
                            QrCode(payload, sizePx = 700, modifier = Modifier.size(260.dp))
                            Text(
                                "Escaneie este QR no celular do filho recém-resetado, na tela de " +
                                    "boas-vindas (toque 6× para abrir o leitor).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            "Informe a URL do APK assinado para gerar o QR.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
