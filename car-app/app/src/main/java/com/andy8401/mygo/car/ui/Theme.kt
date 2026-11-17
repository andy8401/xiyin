package com.andy8401.mygo.car.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MUSE visual identity, from the UI redesign spec.
 *
 * The palette is intentionally tiny: near-black ink, one warm-gold accent,
 * paper white and a dark espresso for raised surfaces. No gradients or glow
 * on the chrome itself — the only artwork gradient lives in the cover art.
 */
object CarColors {
    val Ink = Color(0xFF0F0E0E)
    val Gold = Color(0xFFE8C268)
    val Paper = Color(0xFFF2F2F2)
    val Espresso = Color(0xFF2E2620)

    val Background = Ink
    val Surface = Color(0xFF171513)
    val SurfaceHigh = Espresso
    val GoldDim = Color(0xFFF0D79A)
    val Text = Paper
    val TextMuted = Color(0xFF9E938A)
    val Divider = Color(0xFF3A322C)
    val Error = Color(0xFFFF8A80)

    // Aliases kept so the rest of the UI (and downstream forks) keep compiling
    // against the old accent name while the actual colour is MUSE gold.
    val Cyan get() = Gold
    val CyanDim get() = GoldDim
}

private val CarScheme = darkColorScheme(
    primary = CarColors.Gold,
    onPrimary = CarColors.Ink,
    secondary = CarColors.GoldDim,
    onSecondary = CarColors.Ink,
    background = CarColors.Background,
    onBackground = CarColors.Text,
    surface = CarColors.Surface,
    onSurface = CarColors.Text,
    surfaceVariant = CarColors.SurfaceHigh,
    onSurfaceVariant = CarColors.TextMuted,
    error = CarColors.Error,
    outline = CarColors.Divider
)

private val CarShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    // The identity system locks the app icon to a 22.5% corner radius; 22dp
    // is the closest match for the large cards and the player sheet.
    large = RoundedCornerShape(22.dp)
)

private val CarTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontSize = 30.sp),
        headlineMedium = headlineMedium.copy(fontSize = 24.sp),
        titleLarge = titleLarge.copy(fontSize = 20.sp),
        titleMedium = titleMedium.copy(fontSize = 17.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp)
    )
}

/** Minimum comfortable touch target on a head unit. */
const val TouchTarget = 56

@Composable
fun CarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CarScheme, shapes = CarShapes, typography = CarTypography, content = content)
}
