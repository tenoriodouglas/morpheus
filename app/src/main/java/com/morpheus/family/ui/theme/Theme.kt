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

/**
 * Bomberman-style arcade palette: bright cobalt "blue bomber" primary, grass-green
 * "safe" secondary, explosion-orange tertiary and a bomb-fuse red error, all on a
 * pale sky-blue stage so the saturated blocks pop while the text stays readable.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E63E9),          // blue bomber
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE0FF),
    onPrimaryContainer = Color(0xFF08245E),
    secondary = Color(0xFF23A63A),        // grass / "liberado"
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBEEFC2),
    onSecondaryContainer = Color(0xFF0A3512),
    tertiary = Color(0xFFF97316),         // explosion orange / "bloqueado"
    onTertiary = Color(0xFF3A1B00),
    tertiaryContainer = Color(0xFFFFE0BB),
    onTertiaryContainer = Color(0xFF5A2900),
    background = Color(0xFFCFEBFF),        // sky-blue stage
    onBackground = Color(0xFF0E2038),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E2038),
    surfaceVariant = Color(0xFFE4EFFB),
    onSurfaceVariant = Color(0xFF3B4D66),
    outline = Color(0xFF12325E),          // deep navy pixel border
    error = Color(0xFFE5322D),            // bomb-fuse red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD6D2),
    onErrorContainer = Color(0xFF5A0A06),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA0FF),
    onPrimary = Color(0xFF06183C),
    primaryContainer = Color(0xFF264C97),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF5FCE6B),
    onSecondary = Color(0xFF06280F),
    secondaryContainer = Color(0xFF1A5827),
    onSecondaryContainer = Color(0xFFC7F1C9),
    tertiary = Color(0xFFFFA24D),
    onTertiary = Color(0xFF3A1B00),
    tertiaryContainer = Color(0xFF7A4110),
    onTertiaryContainer = Color(0xFFFFE1BE),
    background = Color(0xFF0A1730),        // night stage
    onBackground = Color(0xFFE7F0FF),
    surface = Color(0xFF122341),
    onSurface = Color(0xFFE7F0FF),
    surfaceVariant = Color(0xFF1B3157),
    onSurfaceVariant = Color(0xFFB9CCE6),
    outline = Color(0xFF8FB0E6),
    error = Color(0xFFFF6B5F),
    onError = Color(0xFF54000A),
    errorContainer = Color(0xFF8F1410),
    onErrorContainer = Color(0xFFFFD6D2),
)

// ---- Extra arcade accents (no Material slot for these) ----------------------
// Used directly by the arcade widgets for hard shadows, fuse sparks, bricks, etc.
val BombNavy = Color(0xFF0E2038)     // pixel outlines + hard drop shadows
val BombYellow = Color(0xFFFFD23F)   // coin / power-up / fuse spark
val BrickRed = Color(0xFFC8532A)     // soft-block brick
val BrickShadow = Color(0xFF8F3A1C)  // brick bevel shadow
val SkyBlue = Color(0xFF57B4F5)      // hero-panel sky
val GrassGreen = Color(0xFF35B84A)   // safe / allowed

// Blocky, minimally-rounded shapes so cards read like beveled arcade blocks.
private val MorpheusShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

@Composable
fun MorpheusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = MorpheusShapes,
        typography = MorpheusTypography,
        content = content,
    )
}
