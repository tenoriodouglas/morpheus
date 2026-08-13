package com.morpheus.family.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.KnownApps
import com.morpheus.family.ui.theme.MorpheusTheme

/**
 * Full-screen notice shown when a blocked app is opened (fallback path). It is
 * transparent by design — it tells the child the app is blocked by the guardian,
 * it does not hide anything.
 */
class BlockedAppActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = KnownApps.labelFor(pkg)

        setContent {
            MorpheusTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⛔", style = MaterialTheme.typography.displayLarge)
                        Text(
                            "$label está bloqueado agora",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "O acesso a este app foi limitado pelo responsável neste horário.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }
}
