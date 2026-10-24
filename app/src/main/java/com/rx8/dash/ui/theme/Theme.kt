package com.rx8.dash.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rx8.dash.core.AlertLevel
import com.rx8.dash.data.DashboardPalette

/** Dashboard-specific colours that Material3's ColorScheme has no slot for. */
@Immutable
data class DashColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val needle: Color,
    val gaugeTrack: Color,
    val gaugeFill: Color,
    val tick: Color,
    val text: Color,
    val textDim: Color,
    val redline: Color,
    val ok: Color,
    val caution: Color,
    val warning: Color,
    val info: Color,
) {
    fun forLevel(level: AlertLevel): Color = when (level) {
        AlertLevel.INFO -> info
        AlertLevel.CAUTION -> caution
        AlertLevel.WARNING -> warning
    }
}

fun dashColorsFor(palette: DashboardPalette): DashColors = when (palette) {
    // Blue body colour. Red is kept for redline, warnings and high values only.
    DashboardPalette.AURORA_BLUE -> DashColors(
        background = Color(0xFF05080F),
        surface = Color(0xFF0E141F),
        surfaceHigh = Color(0xFF18212F),
        accent = Color(0xFF2F8CFF),
        needle = Color(0xFFBFE0FF),
        gaugeTrack = Color(0xFF1F2A3B),
        gaugeFill = Color(0xFF3C9DFF),
        tick = Color(0xFFA9BCD6),
        text = Color(0xFFF1F6FF),
        textDim = Color(0xFF7F90A8),
        redline = Color(0xFFFF2D3A),
        ok = Color(0xFF34C759),
        caution = Color(0xFFFFB020),
        warning = Color(0xFFFF3B30),
        info = Color(0xFF6CC4FF),
    )
    DashboardPalette.RENESIS_RED -> DashColors(
        background = Color(0xFF07080A),
        surface = Color(0xFF121418),
        surfaceHigh = Color(0xFF1C1F25),
        accent = Color(0xFFE0262E),
        needle = Color(0xFFFF3B30),
        gaugeTrack = Color(0xFF2A2D33),
        gaugeFill = Color(0xFFE0262E),
        tick = Color(0xFFB9BEC7),
        text = Color(0xFFF2F3F5),
        textDim = Color(0xFF8A9099),
        redline = Color(0xFFFF1A1A),
        ok = Color(0xFF34C759),
        caution = Color(0xFFFFB020),
        warning = Color(0xFFFF3B30),
        info = Color(0xFF4DA3FF),
    )
    DashboardPalette.NIGHT_AMBER -> DashColors(
        background = Color(0xFF000000),
        surface = Color(0xFF0D0A05),
        surfaceHigh = Color(0xFF1A140A),
        accent = Color(0xFFFF9F1A),
        needle = Color(0xFFFFB347),
        gaugeTrack = Color(0xFF2A1F0E),
        gaugeFill = Color(0xFFE08A12),
        tick = Color(0xFFB88A4A),
        text = Color(0xFFFFC266),
        textDim = Color(0xFF8C6A3A),
        redline = Color(0xFFFF4A1A),
        ok = Color(0xFF9CCC4A),
        caution = Color(0xFFFFB020),
        warning = Color(0xFFFF4A1A),
        info = Color(0xFFFFD08A),
    )
    DashboardPalette.CLASSIC_WHITE -> DashColors(
        background = Color(0xFF0B0C0E),
        surface = Color(0xFF16181C),
        surfaceHigh = Color(0xFF22252B),
        accent = Color(0xFFE6E8EC),
        needle = Color(0xFFFF5A36),
        gaugeTrack = Color(0xFF30343B),
        gaugeFill = Color(0xFFDDE1E6),
        tick = Color(0xFFE6E8EC),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFF9AA0A8),
        redline = Color(0xFFFF3B30),
        ok = Color(0xFF34C759),
        caution = Color(0xFFFFB020),
        warning = Color(0xFFFF3B30),
        info = Color(0xFF7FB8FF),
    )
}

val LocalDashColors = staticCompositionLocalOf { dashColorsFor(DashboardPalette.AURORA_BLUE) }

private fun schemeFor(c: DashColors): ColorScheme = darkColorScheme(
    primary = c.accent,
    onPrimary = Color.Black,
    secondary = c.accent,
    onSecondary = Color.Black,
    tertiary = c.info,
    background = c.background,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceHigh,
    onSurfaceVariant = c.textDim,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceHigh,
    surfaceContainerHighest = c.surfaceHigh,
    surfaceContainerLow = c.surface,
    surfaceContainerLowest = c.background,
    error = c.warning,
    onError = Color.Black,
    outline = c.textDim,
)

private val DashTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 64.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
)

/** Dark automotive theme. Rx8DashApp re-applies it with the palette chosen in Settings. */
@Composable
fun Rx8DashTheme(
    palette: DashboardPalette = DashboardPalette.AURORA_BLUE,
    content: @Composable () -> Unit,
) {
    val colors = dashColorsFor(palette)
    CompositionLocalProvider(LocalDashColors provides colors) {
        MaterialTheme(colorScheme = schemeFor(colors), typography = DashTypography, content = content)
    }
}
