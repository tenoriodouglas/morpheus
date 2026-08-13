package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import kotlinx.coroutines.launch

/**
 * First-run screen: the same APK becomes either the controlling (parent) app or
 * the enforced (child) app depending on the choice made here.
 */
@Composable
fun ModeSelectionScreen(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Morpheus",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Controle de tempo de tela para a família. Escolha como este aparelho será usado.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = { scope.launch { prefs.setMode(AppMode.CHILD) } },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Este é o celular do FILHO")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { scope.launch { prefs.setMode(AppMode.PARENT) } },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Este é o celular do RESPONSÁVEL")
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "No modo Filho, o aparelho mostra sempre um aviso de que é gerenciado. " +
                "O Morpheus não faz gravação oculta de áudio, câmera ou tela.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
