package com.morpheus.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** +/- 15 minute stepper rendering a HH:MM label (minutes-from-midnight). */
@Composable
fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((minutes - 15 + 1440) % 1440) }) { Text("−") }
            Text(
                "%02d:%02d".format(minutes / 60, minutes % 60),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { onChange((minutes + 15) % 1440) }) { Text("+") }
        }
    }
}

/** Generic integer stepper with a unit suffix and a floor. */
@Composable
fun ValueStepper(label: String, value: Int, step: Int, suffix: String, min: Int = 0, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
            Text(
                "$value$suffix",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
        }
    }
}

/** Friendly duration label: "ilimitado" / "45 min" / "1h 30min" / "8h". */
fun durationLabel(minutes: Int): String = when {
    minutes <= 0 -> "ilimitado"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}min"
}

/** +/- [step] minute stepper for a duration; 0 renders as "ilimitado". */
@Composable
fun MinuteStepper(label: String, minutes: Int, step: Int = 15, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange((minutes - step).coerceAtLeast(0)) }) { Text("−") }
            Text(
                durationLabel(minutes),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(onClick = { onChange(minutes + step) }) { Text("+") }
        }
    }
}
