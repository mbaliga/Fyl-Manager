package io.github.mbaliga.fylz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.model.ThemeMode

/** Builds the Material3 [ColorScheme] for one light/dark half of a [ThemePalette] -- every field
 * this JSON theme schema defines maps straight onto a `ColorScheme` field of the same name. */
internal fun PaletteColors.toColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = primary, secondary = secondary, tertiary = tertiary,
        background = background, surface = surface, surfaceVariant = surfaceVariant,
        onBackground = onBackground, onSurface = onSurface,
    )
} else {
    lightColorScheme(
        primary = primary, secondary = secondary, tertiary = tertiary,
        background = background, surface = surface, surfaceVariant = surfaceVariant,
        onBackground = onBackground, onSurface = onSurface,
    )
}

private val FylzTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * @param palette the active JSON theme (owner request; see `ThemeJson.kt`) -- which colours.
 * @param themeMode which of [palette]'s light/dark halves is active -- unchanged from before this
 * feature, still the separate light-vs-dark selector.
 * @param dynamicColor Material You wallpaper colour (Android 12+). Superseded by [palette] now
 * that one is always active (see docs/agent/REVIEW_QUEUE.md for why this parameter stays, at
 * `false` from every call site, rather than being removed outright); the two were never
 * user-selectable together before this feature either.
 */
@Composable
fun FylzTheme(
    themeMode: ThemeMode,
    palette: ThemePalette,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> palette.dark.toColorScheme(dark = true)
        else -> palette.light.toColorScheme(dark = false)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FylzTypography,
        content = content,
    )
}
