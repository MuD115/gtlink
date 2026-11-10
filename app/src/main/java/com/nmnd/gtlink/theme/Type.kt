package com.nmnd.gtlink.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nmnd.gtlink.R

// Three families, same voices as NewsPull: a serif for display, a geometric sans for
// chrome, a mono for numbers and technical readouts. No Arabic face: the app's own
// copy is Latin, and anything the watch sends is rendered as plain figures.

/** Display sizes only: screen titles and the device name. Never body copy. */
val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal),
)

/** UI chrome: buttons, labels, settings rows, body text. */
val Outfit = FontFamily(
    Font(R.font.outfit_light, FontWeight.Light),
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
)

/** Timestamps, MAC addresses, stat numbers: the instrument-readout voice. */
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val GtTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DmSerifDisplay, fontWeight = FontWeight.Normal,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DmSerifDisplay, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DmSerifDisplay, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Normal,
        fontSize = 14.5f.sp, lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp,
    ),
)

/**
 * The big number on a stat tile (steps, bpm, battery %). Mono keeps digits the same
 * width so a live-updating heart rate doesn't jitter the layout as it ticks.
 */
val MetricNumber = TextStyle(
    fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
    fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
)
