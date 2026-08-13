package com.morpheus.family.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1B2A4A)
private val NavyLight = Color(0xFF39508A)
private val Amber = Color(0xFFF2A03D)

private val LightColors = lightColorScheme(
    primary = Navy,
    secondary = NavyLight,
    tertiary = Amber,
)

private val DarkColors = darkColorScheme(
    primary = NavyLight,
    secondary = Navy,
    tertiary = Amber,
)

@Composable
fun MorpheusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
