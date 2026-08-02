package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mbaliga.fylz.model.ThemeMode

private val Radium = Color(0xFFC7EF9E)
private val ColdCyan = Color(0xFF35E0FF)
private val Ink = Color(0xFFE9E9E4)
private val Field = Color(0xFF121212)
private val Pane = Color(0xFF1A1A1A)
private val Edge = Color(0xFF303030)

private val DarkScheme = darkColorScheme(
    primary = Radium,
    onPrimary = Color(0xFF17210E),
    secondary = ColdCyan,
    onSecondary = Color(0xFF002027),
    background = Field,
    onBackground = Ink,
    surface = Pane,
    onSurface = Ink,
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFC8C8C1),
    outline = Edge,
    error = Color(0xFFFFB4AB),
)

private val OledScheme = DarkScheme.copy(
    background = Color.Black,
    surface = Color(0xFF080808),
    surfaceVariant = Color(0xFF111111),
)

private val MonoScheme = DarkScheme.copy(
    primary = Color(0xFFF1F1EC),
    onPrimary = Color(0xFF121212),
    secondary = Color(0xFFBDBDB7),
    onSecondary = Color(0xFF151515),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3D651D),
    onPrimary = Color.White,
    secondary = Color(0xFF006879),
    onSecondary = Color.White,
    background = Color(0xFFF7F7F2),
    onBackground = Color(0xFF191C17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C17),
    surfaceVariant = Color(0xFFE7E9E0),
    onSurfaceVariant = Color(0xFF44483F),
    outline = Color(0xFF75796E),
)

@Composable
fun FylzTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colors = when (mode) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkScheme else LightScheme
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.OLED -> OledScheme
        ThemeMode.MONO -> MonoScheme
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
