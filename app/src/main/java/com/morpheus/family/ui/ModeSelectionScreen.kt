package com.morpheus.family.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
 * the enforced (child) app depending on the choice made here. Styled as an
 * arcade title screen — bomb mascot, pixel logotype, "insert player" blocks.
 */
@Composable
fun ModeSelectionScreen(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().arcadeGrid().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BombSprite(size = 104.dp, lit = true)
        Spacer(Modifier.height(14.dp))
        PixelTitle("MORPHEUS", fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Tempo de tela saudável para a família",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(34.dp))

        RoleCard(
            emoji = "🎮",
            title = "Sou o responsável",
            subtitle = "Controlo e acompanho os aparelhos",
            container = MaterialTheme.colorScheme.primary,
            onColor = MaterialTheme.colorScheme.onPrimary,
            onClick = { scope.launch { prefs.setMode(AppMode.PARENT) } },
        )
        Spacer(Modifier.height(18.dp))
        RoleCard(
            emoji = "📱",
            title = "Este é o celular do filho",
            subtitle = "Aplica os horários e proteções aqui",
            container = MaterialTheme.colorScheme.secondary,
            onColor = MaterialTheme.colorScheme.onSecondary,
            onClick = { scope.launch { prefs.setMode(AppMode.CHILD) } },
        )

        Spacer(Modifier.height(28.dp))
        Text(
            "No celular do filho o aparelho mostra sempre que é gerenciado. " +
                "O Morpheus nunca grava áudio, câmera ou tela às escondidas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
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
    ArcadeBlock(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = container,
        contentColor = onColor,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(emoji, fontSize = 34.sp)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = onColor)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = onColor)
            }
        }
    }
}
