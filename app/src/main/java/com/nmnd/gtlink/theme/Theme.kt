package com.nmnd.gtlink.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * The decisions that make GT Link look like GT Link but have no home in ColorScheme.
 * Each value encodes a rule rather than being a spare colour, and both the dark and
 * light sets are derived from the same tonal palettes as the scheme.
 *
 * Read via LocalGtExtras.current; never hardcode these at a call site.
 */
@Immutable
data class GtExtras(
    /** Link is up and the watch is talking. */
    val connected: Color,
    /** Scanning, pairing or reconnecting: in flight, not yet trustworthy. */
    val connecting: Color,
    /** No link. A resting state, deliberately not red. */
    val disconnected: Color,
    /** Watch battery low. Shares the error hue: it is the one thing that needs action. */
    val batteryLow: Color,
    /** Timestamps, firmware strings, MAC addresses. Below body text in hierarchy. */
    val metadata: Color,
    /** Hairline around cards. Separates without announcing. */
    val cardStroke: Color,
    /** Heart-rate accent. */
    val heart: Color,
    /** Step-count accent. */
    val steps: Color,
    /** Calorie accent. */
    val calories: Color,
    /** Distance accent. */
    val distance: Color,
)

val LocalGtExtras = staticCompositionLocalOf {
    // Never actually read: GTLinkTheme always provides a real value. Present so a stray
    // preview outside the theme renders rather than crashing.
    extrasFor(dark = true)
}

private val GtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun GTLinkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Generating a scheme runs real tonal maths over several palettes, so it is
    // remembered rather than recomputed on every recomposition.
    val colorScheme = remember(dark) { schemeFor(dark) }
    val extras = remember(dark) { extrasFor(dark) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // The context can be a wrapper (e.g. inside a dialog or a themed context),
            // so unwrap rather than cast.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            // Bar icons follow the scheme the app resolved; today that always equals
            // the system setting, but keeping it tied to `dark` stays correct if an
            // override is ever added.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalGtExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GtTypography,
            shapes = GtShapes,
            content = content,
        )
    }
}
