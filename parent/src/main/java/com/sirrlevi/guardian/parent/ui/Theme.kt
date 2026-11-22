package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.red
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sirrlevi.guardian.parent.ThemeMode

/** Extra semantic colours the stock Material scheme has no slot for. */
@Immutable
class GuardianColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val card: Color,
    val cardRaised: Color,
    val stroke: Color,
    val muted: Color,
    val chart: List<Color>
)

val LocalGuardianColors = staticCompositionLocalOf {
    GuardianColors(Color.Green, Color.Yellow, Color.Red, Color.Blue, Color.DarkGray, Color.Gray, Color.Gray, Color.Gray, listOf(Color.Gray))
}

private val ChartPalette = listOf(
    Color(0xFF62D7C5), Color(0xFF7FB2FF), Color(0xFFFFC16B), Color(0xFFB79CFF),
    Color(0xFFFF8FB1), Color(0xFF8EE08A), Color(0xFFFF9F6B), Color(0xFF6BD6FF)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF62D7C5), onPrimary = Color(0xFF04201C),
    primaryContainer = Color(0xFF123F46), onPrimaryContainer = Color(0xFFB9F3EA),
    secondary = Color(0xFF7FB2FF), onSecondary = Color(0xFF06223F),
    tertiary = Color(0xFFFFC16B), onTertiary = Color(0xFF3A2500),
    background = Color(0xFF070E1A), onBackground = Color(0xFFF3F7FD),
    surface = Color(0xFF0E1B2E), onSurface = Color(0xFFF3F7FD),
    surfaceVariant = Color(0xFF16283F), onSurfaceVariant = Color(0xFF9DB1C9),
    surfaceContainer = Color(0xFF0E1B2E), surfaceContainerHigh = Color(0xFF16283F), surfaceContainerHighest = Color(0xFF1C3250),
    outline = Color(0xFF2A4361), outlineVariant = Color(0xFF1E3350),
    error = Color(0xFFFF8A8A), onError = Color(0xFF410002)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B8577), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE9), onPrimaryContainer = Color(0xFF04332D),
    secondary = Color(0xFF2F6FD6), onSecondary = Color.White,
    tertiary = Color(0xFFB36A00), onTertiary = Color.White,
    background = Color(0xFFF3F6FB), onBackground = Color(0xFF0E1A2B),
    surface = Color.White, onSurface = Color(0xFF0E1A2B),
    surfaceVariant = Color(0xFFE6EDF6), onSurfaceVariant = Color(0xFF50627A),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEAF0F8), surfaceContainerHighest = Color(0xFFDFE8F3),
    outline = Color(0xFFB8C6D8), outlineVariant = Color(0xFFD7E0EC),
    error = Color(0xFFC62828), onError = Color.White
)

private val GuardianTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

private val GuardianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun GuardianTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    val extras = if (dark) {
        GuardianColors(
            success = Color(0xFF67DDB4), warning = Color(0xFFFFC16B), danger = Color(0xFFFF8A8A), info = Color(0xFF7FB2FF),
            card = Color(0xFF0F1E33), cardRaised = Color(0xFF16283F), stroke = Color(0xFF223A57), muted = Color(0xFF9DB1C9),
            chart = ChartPalette
        )
    } else {
        GuardianColors(
            success = Color(0xFF138A5E), warning = Color(0xFFB36A00), danger = Color(0xFFC62828), info = Color(0xFF2F6FD6),
            card = Color.White, cardRaised = Color(0xFFEAF0F8), stroke = Color(0xFFD7E0EC), muted = Color(0xFF50627A),
            chart = listOf(
                Color(0xFF0B8577), Color(0xFF2F6FD6), Color(0xFFD98A00), Color(0xFF7A55D6),
                Color(0xFFD6457B), Color(0xFF3E9E3A), Color(0xFFD9602B), Color(0xFF1A9AC7)
            )
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalGuardianColors provides extras) {
        MaterialTheme(colorScheme = scheme, typography = GuardianTypography, shapes = GuardianShapes, content = content)
    }
}

val gColors: GuardianColors
    @Composable get() = LocalGuardianColors.current

@Composable
fun isDark(): Boolean = MaterialTheme.colorScheme.background.red < 0.5f
