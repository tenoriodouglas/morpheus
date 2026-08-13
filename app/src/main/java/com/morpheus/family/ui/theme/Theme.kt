package com.morpheus.family.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Friendly palette: soft indigo + mint/teal + warm coral, on light airy surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5A67D8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF11175E),
    secondary = Color(0xFF1FA98C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6F1E4),
    onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFFEF7A56),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCE),
    onTertiaryContainer = Color(0xFF5A1A08),
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE7E7F1),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF77767F),
    error = Color(0xFFC7362C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410001),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF111A6B),
    primaryContainer = Color(0xFF3A46A6),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFF8FD9C4),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF0A5140),
    onSecondaryContainer = Color(0xFFABF2DB),
    tertiary = Color(0xFFFFB59B),
    onTertiary = Color(0xFF5A1A08),
    tertiaryContainer = Color(0xFF7A3320),
    onTertiaryContainer = Color(0xFFFFDBCE),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF1B1B21),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD5),
)

// Softer, rounder shapes for a friendlier feel.
private val MorpheusShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun MorpheusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = MorpheusShapes,
        content = content,
    )
}
