package com.alad1nks.jaiqal.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = Color(0xFF286C3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFADF2B5),
    onPrimaryContainer = Color(0xFF002108),
    secondary = Color(0xFF526350),
    secondaryContainer = Color(0xFFD5E8D0),
    tertiary = Color(0xFF39656A),
    tertiaryContainer = Color(0xFFBCEBF0),
    surface = Color(0xFFF8FBF4),
    surfaceVariant = Color(0xFFDEE5DA),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91D59B),
    onPrimary = Color(0xFF003913),
    primaryContainer = Color(0xFF0B5225),
    onPrimaryContainer = Color(0xFFADF2B5),
    secondary = Color(0xFFBACCB5),
    secondaryContainer = Color(0xFF3B4B39),
    tertiary = Color(0xFFA1CED3),
    tertiaryContainer = Color(0xFF204D52),
    surface = Color(0xFF101510),
    surfaceVariant = Color(0xFF424940),
    error = Color(0xFFFFB4AB),
)

@Immutable
data class JaiqalSpacing(
    val extraSmall: androidx.compose.ui.unit.Dp = 4.dp,
    val small: androidx.compose.ui.unit.Dp = 8.dp,
    val medium: androidx.compose.ui.unit.Dp = 16.dp,
    val large: androidx.compose.ui.unit.Dp = 24.dp,
    val extraLarge: androidx.compose.ui.unit.Dp = 32.dp,
)

val LocalJaiqalSpacing = staticCompositionLocalOf { JaiqalSpacing() }

object JaiqalTheme {
    val spacing: JaiqalSpacing
        @Composable get() = LocalJaiqalSpacing.current
}

@Composable
fun JaiqalTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalJaiqalSpacing provides JaiqalSpacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MaterialTheme.typography,
            shapes = androidx.compose.material3.Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
