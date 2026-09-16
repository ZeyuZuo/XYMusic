package io.github.xiangyuplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF526B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8D5),
    onPrimaryContainer = Color(0xFF152D1E),
    background = Color(0xFFF9FAF6),
    surface = Color(0xFFF9FAF6),
    surfaceVariant = Color(0xFFE8ECE4),
    surfaceContainerLow = Color(0xFFF0F3ED),
    surfaceContainer = Color(0xFFEAEEE6),
    secondaryContainer = Color(0xFFE0EBDD),
    onSecondaryContainer = Color(0xFF344C39),
    onSurface = Color(0xFF1B211B),
    onSurfaceVariant = Color(0xFF586155),
    outlineVariant = Color(0xFFD0D7CB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9D1B9),
    onPrimary = Color(0xFF263D2C),
    primaryContainer = Color(0xFF3B5341),
    onPrimaryContainer = Color(0xFFD5E8D5),
    background = Color(0xFF111510),
    surface = Color(0xFF111510),
    surfaceVariant = Color(0xFF293027),
    surfaceContainerLow = Color(0xFF1A211A),
    surfaceContainer = Color(0xFF20281F),
    secondaryContainer = Color(0xFF30432F),
    onSecondaryContainer = Color(0xFFD0E6CA),
    onSurface = Color(0xFFE2E8DC),
    onSurfaceVariant = Color(0xFFBAC5B4),
    outlineVariant = Color(0xFF414B3C),
)

@Composable
fun XiangyuTheme(darkTheme: Boolean = rememberActualSystemDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
