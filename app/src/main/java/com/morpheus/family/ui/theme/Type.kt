package com.morpheus.family.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.morpheus.family.R

/**
 * Retro-arcade type system (Bomberman-style).
 *
 * Two pixel families, both SIL OFL 1.1 (license texts in docs/licenses):
 *  - [PressStart2P]: the chunky 8-bit arcade face, reserved for big splash titles
 *    and screen headlines — unreadable in paragraphs, glorious as a logotype.
 *  - [PixelifySans]: a pixel face that stays legible at body sizes, so every card,
 *    button and helper line still reads clearly for a parent (and a kid).
 */
val PressStart2P = FontFamily(Font(R.font.press_start_2p, FontWeight.Normal))

val PixelifySans = FontFamily(
    Font(R.font.pixelify_sans_regular, FontWeight.Normal),
    Font(R.font.pixelify_sans_medium, FontWeight.Medium),
    Font(R.font.pixelify_sans_semibold, FontWeight.SemiBold),
    Font(R.font.pixelify_sans_bold, FontWeight.Bold),
)

// Press Start 2P renders very large and has no descender room, so its sizes are
// dialed down and given generous line heights.
val MorpheusTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PressStart2P, fontWeight = FontWeight.Normal,
        fontSize = 30.sp, lineHeight = 42.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PressStart2P, fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 34.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PressStart2P, fontWeight = FontWeight.Normal,
        fontSize = 19.sp, lineHeight = 28.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PressStart2P, fontWeight = FontWeight.Normal,
        fontSize = 20.sp, lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Bold,
        fontSize = 27.sp, lineHeight = 33.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Bold,
        fontSize = 23.sp, lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PixelifySans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
)
