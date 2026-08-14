package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Google Play's "prominent disclosure" for location, shown *before* the runtime
 * permission prompt — never after, and never bundled into another screen.
 *
 * It must name the feature, say that collection continues while the app is
 * closed, and let the user decline. This dialog is also what a Play reviewer
 * looks for in the background-location demo video.
 */
@Composable
fun LocationDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compartilhar a localização com o responsável?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "O Morpheus coleta dados de localização para mostrar ao responsável " +
                        "onde este aparelho está e para avisá-lo quando o aparelho sair de " +
                        "uma área segura definida por ele (por exemplo, casa ou escola).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "A localização é coletada MESMO COM O APP FECHADO OU FORA DE USO, " +
                        "para que os avisos de área funcionem a qualquer momento.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "A posição é enviada apenas ao responsável pareado com este aparelho. " +
                        "Não vendemos nem compartilhamos esses dados com terceiros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Aceitar e continuar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Agora não") } },
    )
}

/**
 * Second-stage disclosure: Android requires "Allow all the time" to be granted
 * from a separate prompt, and on Android 11+ the system sends the user to
 * Settings instead of showing a dialog — so explain what to tap there.
 */
@Composable
fun BackgroundLocationDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permitir localização o tempo todo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Para os avisos de área segura funcionarem com o app fechado, o Android " +
                        "pede uma permissão extra.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Na próxima tela, escolha “Permitir o tempo todo”.",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
