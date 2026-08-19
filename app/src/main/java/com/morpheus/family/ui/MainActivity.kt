package com.morpheus.family.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.morpheus.family.BuildConfig
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.ui.theme.MorpheusTheme
import com.morpheus.family.update.GithubUpdater
import com.morpheus.family.update.UpdateInfo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
                        // Silent update check on every open (renders nothing).
                        PlayUpdateGate()
                        // Debug builds (installed from GitHub) update from GitHub.
                        if (BuildConfig.DEBUG) GithubUpdateGate()

                        val mode by prefs.modeFlow.collectAsState(initial = AppMode.UNSET)
                        when (mode) {
                            AppMode.UNSET -> ModeSelectionScreen(prefs)
                            AppMode.CHILD -> ChildScreen(prefs)
                            AppMode.PARENT -> ParentScreen(prefs)
                        }

                        // Full-screen call overlay, above whatever screen is shown.
                        CallHost()
                    }
                }
            }
        }
    }
}

/**
 * Debug-only: offers to update from GitHub when a newer APK is published there.
 * On the release build [GithubUpdater.checkForUpdate] returns null, so this is
 * inert even though the composable is only invoked under BuildConfig.DEBUG.
 */
@Composable
private fun GithubUpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var installing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = GithubUpdater.checkForUpdate()
    }

    val info = update ?: return
    AlertDialog(
        onDismissRequest = { if (!installing) update = null },
        title = { Text("Atualização disponível") },
        text = {
            Text(
                "Uma versão mais nova (${info.versionName} #${info.versionCode}) está no GitHub. " +
                    "Quer baixar e instalar agora?",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !installing,
                onClick = {
                    if (!GithubUpdater.canInstall(context)) {
                        GithubUpdater.openInstallPermissionSettings(context)
                        return@TextButton
                    }
                    installing = true
                    scope.launch {
                        val ok = GithubUpdater.downloadAndInstall(context, info)
                        installing = false
                        if (ok) update = null
                    }
                },
            ) { Text(if (installing) "Baixando…" else "Atualizar") }
        },
        dismissButton = {
            TextButton(enabled = !installing, onClick = { update = null }) { Text("Agora não") }
        },
    )
}

/**
 * Checks Play for a newer version whenever the app resumes and, if one exists,
 * launches the immediate in-app update flow (one tap, no Store navigation). Also
 * resumes an update already in progress. No-ops on sideloaded/debug builds, where
 * Play can't serve updates. Emits no UI.
 */
@Composable
private fun PlayUpdateGate() {
    val context = LocalContext.current
    val updateManager = remember { AppUpdateManagerFactory.create(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* If the user backs out, we simply re-prompt on the next open. */ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateManager.appUpdateInfo.addOnSuccessListener { info ->
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val inProgress = info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    if ((available || inProgress) && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        runCatching {
                            updateManager.startUpdateFlowForResult(
                                info,
                                launcher,
                                AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                            )
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
}
