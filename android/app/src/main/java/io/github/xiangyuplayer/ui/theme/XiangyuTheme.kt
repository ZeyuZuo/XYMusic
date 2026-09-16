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
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9D1B9),
    onPrimary = Color(0xFF263D2C),
    primaryContainer = Color(0xFF3B5341),
    onPrimaryContainer = Color(0xFFD5E8D5),
    background = Color(0xFF111510),
    surface = Color(0xFF111510),
    surfaceVariant = Color(0xFF293027),
)

@Composable
fun XiangyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (rememberActualSystemDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
