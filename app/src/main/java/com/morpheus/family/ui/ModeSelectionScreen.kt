package com.morpheus.family.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("🛡️", fontSize = 40.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Morpheus", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tempo de tela saudável para a família",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        RoleCard(
            emoji = "🧑‍💼",
            title = "Sou o responsável",
            subtitle = "Controlo e acompanho os aparelhos",
            container = MaterialTheme.colorScheme.primaryContainer,
            onColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { scope.launch { prefs.setMode(AppMode.PARENT) } },
        )
        Spacer(Modifier.height(16.dp))
        RoleCard(
            emoji = "📱",
            title = "Este é o celular do filho",
            subtitle = "Aplica os horários e proteções aqui",
            container = MaterialTheme.colorScheme.secondaryContainer,
            onColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = { scope.launch { prefs.setMode(AppMode.CHILD) } },
        )

        Spacer(Modifier.height(28.dp))
        Text(
            "No celular do filho o aparelho mostra sempre que é gerenciado. " +
                "O Morpheus nunca grava áudio, câmera ou tela às escondidas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    subtitle: String,
    container: androidx.compose.ui.graphics.Color,
    onColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onColor),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(emoji, fontSize = 34.sp)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
