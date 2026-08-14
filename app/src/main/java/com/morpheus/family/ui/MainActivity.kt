package com.morpheus.family.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.ui.theme.MorpheusTheme

class MainActivity : ComponentActivity() {

    private val updateManager by lazy { AppUpdateManagerFactory.create(this) }

    // If the user backs out of an immediate update, we simply re-prompt on the
    // next open. Nothing to do with the result here.
    private val updateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)

        setContent {
            MorpheusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Keep content clear of the status/navigation bars (edge-to-edge).
                    Box(Modifier.fillMaxSize().systemBarsPadding()) {
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

    /**
     * Silent update check on every open: if Play has a newer version, launch the
     * immediate in-app update flow (one tap, no navigation to the Store). Also
     * resumes an update that was already in progress. No-ops on sideloaded/debug
     * builds, where Play can't serve updates.
     */
    override fun onResume() {
        super.onResume()
        updateManager.appUpdateInfo.addOnSuccessListener { info ->
            val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val inProgress =
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            if ((available || inProgress) && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                runCatching {
                    updateManager.startUpdateFlowForResult(
                        info,
                        updateLauncher,
                        AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                    )
                }
            }
        }
    }
}
