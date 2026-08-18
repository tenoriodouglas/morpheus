package com.morpheus.family.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.morpheus.family.R

/**
 * Playful "game" type system (both SIL OFL 1.1; licenses in docs/licenses):
 *  - [BungeeFamily]: a bold, chunky arcade-poster face for splash titles and
 *    headlines — full of character but far more legible than a strict 8-bit font.
 *  - [FredokaFamily]: a rounded, friendly face that stays crisp at body sizes, so
 *    every card, button and helper line reads clearly for a parent (and a kid).
 */
val BungeeFamily = FontFamily(Font(R.font.bungee_regular, FontWeight.Normal))

val FredokaFamily = FontFamily(
    Font(R.font.fredoka_regular, FontWeight.Normal),
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

// Bungee is chunky, so its sizes are dialed down with generous line heights.
val MorpheusTypography = Typography(
    displayLarge = TextStyle(fontFamily = BungeeFamily, fontSize = 28.sp, lineHeight = 38.sp),
    displayMedium = TextStyle(fontFamily = BungeeFamily, fontSize = 24.sp, lineHeight = 32.sp),
    displaySmall = TextStyle(fontFamily = BungeeFamily, fontSize = 20.sp, lineHeight = 28.sp),
    headlineLarge = TextStyle(fontFamily = BungeeFamily, fontSize = 22.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FredokaFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp,
    ),
)
