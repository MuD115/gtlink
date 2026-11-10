package com.nmnd.gtlink.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.palettes.TonalPalette

// GT Link's colour system.
//
// Same approach as NewsPull: every tone below is a real HCT tonal-palette step from
// material-color-utilities, not hand-picked hex. Unlike NewsPull there is exactly one
// brand palette and no variant picker, so the hues and tones are plain constants.
//
// Brand-locked: no wallpaper/dynamic-colour path. A watch companion should look like
// the same sporty, warm-red-on-near-black product on every phone.

// Warm red-orange, Huawei-adjacent. Kept a few degrees off the error hue (25) and at a
// slightly lower chroma so "brand accent" and "something failed" never read as the same red.
private val accentPalette = TonalPalette.fromHueAndChroma(34.0, 76.0)
private val secondaryPalette = TonalPalette.fromHueAndChroma(210.0, 30.0) // steel blue
private val tertiaryPalette = TonalPalette.fromHueAndChroma(150.0, 40.0) // healthy / connected green

// Neutrals carry a trace of the accent hue so surfaces read as warm-tinted rather than
// dead grey, and cards separate from the background by hue as well as lightness.
private val neutralPalette = TonalPalette.fromHueAndChroma(34.0, 4.0)
private val neutralVariantPalette = TonalPalette.fromHueAndChroma(34.0, 10.0)

// Standard M3 error red. Red means something broke, and nothing else, so it must not
// drift with the brand palette.
private val errorPalette = TonalPalette.fromHueAndChroma(25.0, 84.0)

// Not part of the M3 roles: "connecting" needs an amber that is neither the brand
// orange nor the error red, and the metric colours need their own hues so heart rate,
// steps, calories and distance stay tellable apart at a glance.
private val amberPalette = TonalPalette.fromHueAndChroma(85.0, 70.0)
private val heartPalette = TonalPalette.fromHueAndChroma(10.0, 80.0)
private val distancePalette = TonalPalette.fromHueAndChroma(290.0, 50.0)

// Background tone: near-black in dark so the app sits flush with the watch's AMOLED
// look; barely-off-white in light.
private const val DARK_BASE = 5
private const val LIGHT_BASE = 98

private const val DARK_ACCENT_TONE = 80
private const val LIGHT_ACCENT_TONE = 40

private fun tone(palette: TonalPalette, t: Int) = Color(palette.tone(t))

fun schemeFor(dark: Boolean): ColorScheme = if (dark) darkScheme() else lightScheme()

private fun darkScheme(): ColorScheme {
    // Container tones step up from the background rather than sitting at fixed values,
    // so cards stay separated from the near-black ground.
    val base = DARK_BASE
    val a = accentPalette
    val s = secondaryPalette
    val t = tertiaryPalette
    val n = neutralPalette
    val nv = neutralVariantPalette

    return darkColorScheme(
        primary = tone(a, DARK_ACCENT_TONE),
        onPrimary = tone(a, 20),
        primaryContainer = tone(a, 30),
        onPrimaryContainer = tone(a, 90),
        inversePrimary = tone(a, 40),

        secondary = tone(s, DARK_ACCENT_TONE),
        onSecondary = tone(s, 20),
        secondaryContainer = tone(s, 30),
        onSecondaryContainer = tone(s, 90),

        tertiary = tone(t, DARK_ACCENT_TONE),
        onTertiary = tone(t, 20),
        tertiaryContainer = tone(t, 30),
        onTertiaryContainer = tone(t, 90),

        background = tone(n, base),
        onBackground = tone(n, 90),
        surface = tone(n, base),
        onSurface = tone(n, 90),
        surfaceVariant = tone(nv, base + 26),
        onSurfaceVariant = tone(nv, 80),
        surfaceContainerLowest = tone(n, maxOf(0, base - 2)),
        surfaceContainerLow = tone(n, base + 4),
        surfaceContainer = tone(n, base + 6),
        surfaceContainerHigh = tone(n, base + 10),
        surfaceContainerHighest = tone(n, base + 16),
        surfaceBright = tone(n, base + 18),
        surfaceDim = tone(n, base),

        outline = tone(nv, 55),
        outlineVariant = tone(nv, base + 22),

        error = tone(errorPalette, 80),
        onError = tone(errorPalette, 20),
        errorContainer = tone(errorPalette, 30),
        onErrorContainer = tone(errorPalette, 90),

        inverseSurface = tone(n, 90),
        inverseOnSurface = tone(n, 20),
        scrim = Color.Black,
    )
}

private fun lightScheme(): ColorScheme {
    val base = LIGHT_BASE
    val a = accentPalette
    val s = secondaryPalette
    val t = tertiaryPalette
    val n = neutralPalette
    val nv = neutralVariantPalette

    return lightColorScheme(
        primary = tone(a, LIGHT_ACCENT_TONE),
        onPrimary = tone(a, 100),
        primaryContainer = tone(a, 90),
        onPrimaryContainer = tone(a, 10),
        inversePrimary = tone(a, 80),

        secondary = tone(s, LIGHT_ACCENT_TONE),
        onSecondary = tone(s, 100),
        secondaryContainer = tone(s, 90),
        onSecondaryContainer = tone(s, 10),

        tertiary = tone(t, LIGHT_ACCENT_TONE),
        onTertiary = tone(t, 100),
        tertiaryContainer = tone(t, 90),
        onTertiaryContainer = tone(t, 10),

        background = tone(n, base),
        onBackground = tone(n, 10),
        surface = tone(n, base),
        onSurface = tone(n, 10),
        surfaceVariant = tone(nv, 90),
        onSurfaceVariant = tone(nv, 30),
        surfaceContainerLowest = tone(n, 100),
        surfaceContainerLow = tone(n, minOf(100, base - 3)),
        surfaceContainer = tone(n, minOf(100, base - 5)),
        surfaceContainerHigh = tone(n, minOf(100, base - 7)),
        surfaceContainerHighest = tone(n, minOf(100, base - 9)),
        surfaceBright = tone(n, minOf(100, base - 1)),
        surfaceDim = tone(n, minOf(100, base - 12)),

        outline = tone(nv, 50),
        outlineVariant = tone(nv, 80),

        error = tone(errorPalette, 40),
        onError = tone(errorPalette, 100),
        errorContainer = tone(errorPalette, 90),
        onErrorContainer = tone(errorPalette, 10),

        inverseSurface = tone(n, 20),
        inverseOnSurface = tone(n, 95),
        scrim = Color.Black,
    )
}

fun extrasFor(dark: Boolean): GtExtras =
    if (dark) {
        GtExtras(
            connected = tone(tertiaryPalette, 80),
            connecting = tone(amberPalette, 80),
            // Quiet on purpose: "not connected" is a resting state, not an alarm.
            disconnected = tone(neutralVariantPalette, 60),
            batteryLow = tone(errorPalette, 80),
            // Below body text in hierarchy: the value is the content, the timestamp a footnote.
            metadata = tone(neutralVariantPalette, 60),
            // Hairline around cards. Anchored to the background tone so it stays a
            // whisper over near-black rather than a bright outline.
            cardStroke = tone(neutralVariantPalette, DARK_BASE + 16),
            heart = tone(heartPalette, 70),
            steps = tone(secondaryPalette, 75),
            calories = tone(accentPalette, 75),
            distance = tone(distancePalette, 75),
        )
    } else {
        GtExtras(
            connected = tone(tertiaryPalette, 40),
            connecting = tone(amberPalette, 45),
            disconnected = tone(neutralVariantPalette, 50),
            batteryLow = tone(errorPalette, 40),
            metadata = tone(neutralVariantPalette, 40),
            cardStroke = tone(neutralVariantPalette, 88),
            heart = tone(heartPalette, 45),
            steps = tone(secondaryPalette, 45),
            calories = tone(accentPalette, 50),
            distance = tone(distancePalette, 45),
        )
    }
