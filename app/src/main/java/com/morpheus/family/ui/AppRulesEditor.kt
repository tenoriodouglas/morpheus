package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.AppRule
import com.morpheus.family.data.BlockWindowData
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.KnownApps
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.launch

/**
 * Parent-side editor for a child's [AppPolicy]: total daily screen budget,
 * per-app time windows and daily limits, and Device Owner restrictions. Every
 * change is persisted locally and pushed to the child in real time.
 */
@Composable
fun AppRulesEditor(prefs: Prefs, child: ChildRef) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policyState by prefs.childAppPolicyFlow(child.id).collectAsState(initial = AppPolicy())
    val p = policyState ?: AppPolicy()

    fun persist(newP: AppPolicy) {
        scope.launch {
            prefs.setChildAppPolicy(child.id, newP)
            RemoteRepository.pushAppPolicy(context, child.id, newP)
        }
    }

    fun updateRule(updated: AppRule) =
        persist(p.copy(apps = p.apps.map { if (it.packageName == updated.packageName) updated else it }))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tempo de tela e apps", style = MaterialTheme.typography.titleMedium)

        MinuteStepper("Tempo total de tela/dia", p.dailyScreenBudgetMinutes, step = 30) {
            persist(p.copy(dailyScreenBudgetMinutes = it))
        }

        AddAppMenu(existing = p.apps.map { it.packageName }) { known ->
            persist(p.copy(apps = p.apps + AppRule(known.packageName, known.label)))
        }

        p.apps.forEach { rule ->
            AppRuleCard(
                rule = rule,
                onChange = ::updateRule,
                onRemove = { persist(p.copy(apps = p.apps.filterNot { it.packageName == rule.packageName })) },
            )
        }

        Text("Restrições (requer Device Owner)", style = MaterialTheme.typography.titleMedium)
        RestrictionSwitch("Bloquear instalar novos apps", p.restrictions.blockAppInstall) {
            persist(p.copy(restrictions = p.restrictions.copy(blockAppInstall = it)))
        }
        RestrictionSwitch("Impedir alterar data/hora", p.restrictions.lockDateTime) {
            persist(p.copy(restrictions = p.restrictions.copy(lockDateTime = it)))
        }
        RestrictionSwitch("Exigir hora automática", p.restrictions.requireAutoTime) {
            persist(p.copy(restrictions = p.restrictions.copy(requireAutoTime = it)))
        }
    }
}

@Composable
private fun AddAppMenu(existing: List<String>, onPick: (com.morpheus.family.data.KnownApp) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val available = KnownApps.ALL.filterNot { it.packageName in existing }
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, enabled = available.isNotEmpty()) {
            Text(if (available.isEmpty()) "Todos os apps da lista já adicionados" else "+ Adicionar app")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            available.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.label) },
                    onClick = { open = false; onPick(app) },
                )
            }
        }
    }
}

@Composable
private fun RestrictionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AppRuleCard(rule: AppRule, onChange: (AppRule) -> Unit, onRemove: () -> Unit) {
    val hasWindow = rule.windows.isNotEmpty()
    val window = rule.windows.firstOrNull() ?: BlockWindowData(22 * 60, 6 * 60 + 30)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rule.label.ifBlank { rule.packageName }, style = MaterialTheme.typography.titleMedium)

            MinuteStepper("Limite diário", rule.dailyLimitMinutes, step = 15) {
                onChange(rule.copy(dailyLimitMinutes = it))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bloquear em horário")
                Switch(
                    checked = hasWindow,
                    onCheckedChange = { on ->
                        onChange(rule.copy(windows = if (on) listOf(window) else emptyList()))
                    },
                )
            }
            if (hasWindow) {
                TimeStepper("Início", window.startMinutes) {
                    onChange(rule.copy(windows = listOf(window.copy(startMinutes = it))))
                }
                TimeStepper("Fim", window.endMinutes) {
                    onChange(rule.copy(windows = listOf(window.copy(endMinutes = it))))
                }
            }

            TextButton(onClick = onRemove) { Text("Remover app") }
        }
    }
}
