package com.morpheus.family.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.ui.theme.MorpheusTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)

        setContent {
            MorpheusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val mode by prefs.modeFlow.collectAsState(initial = AppMode.UNSET)
                    when (mode) {
                        AppMode.UNSET -> ModeSelectionScreen(prefs)
                        AppMode.CHILD -> ChildScreen(prefs)
                        AppMode.PARENT -> ParentScreen(prefs)
                    }
                }
            }
        }
    }
}
