package io.github.mbaliga.fylz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode

private data class AccentColors(
    val lightPrimary: Color,
    val lightSecondary: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
)

private fun AccentPreset.colors(): AccentColors = when (this) {
    AccentPreset.MOSS -> AccentColors(
        lightPrimary = Color(0xFF315F49),
        lightSecondary = Color(0xFF52645A),
        darkPrimary = Color(0xFF9BD3B3),
        darkSecondary = Color(0xFFB9CCBF),
    )
    AccentPreset.INK -> AccentColors(
        lightPrimary = Color(0xFF3E5268),
        lightSecondary = Color(0xFF565E68),
        darkPrimary = Color(0xFFA8C9EA),
        darkSecondary = Color(0xFFBEC7D2),
    )
    AccentPreset.CLAY -> AccentColors(
        lightPrimary = Color(0xFF84523D),
        lightSecondary = Color(0xFF705A50),
        darkPrimary = Color(0xFFFFB69A),
        darkSecondary = Color(0xFFE1BFB1),
    )
    AccentPreset.ELECTRIC -> AccentColors(
        lightPrimary = Color(0xFF4D57A7),
        lightSecondary = Color(0xFF5D5D72),
        darkPrimary = Color(0xFFBEC2FF),
        darkSecondary = Color(0xFFC6C4DD),
    )
}

private val FylzTypography = Typography(
    // No display-sized surface used these before the landing hero; the wordmark itself stays on
    // its own bundled Hyle face local to LandingSplash.kt rather than riding these -- what these
    // give the rest of the app is a display register that isn't just the stock Material default.
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
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

@Composable
fun FylzTheme(
    themeMode: ThemeMode,
    accentPreset: AccentPreset,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val accent = accentPreset.colors()

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = accent.darkPrimary,
            secondary = accent.darkSecondary,
            surface = Color(0xFF111413),
            surfaceContainer = Color(0xFF191D1B),
            surfaceContainerHigh = Color(0xFF232825),
            background = Color(0xFF0E1110),
        )
        else -> lightColorScheme(
            primary = accent.lightPrimary,
            secondary = accent.lightSecondary,
            surface = Color(0xFFFAFCFA),
            surfaceContainer = Color(0xFFF1F4F1),
            surfaceContainerHigh = Color(0xFFE8ECE8),
            background = Color(0xFFFDFEFD),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FylzTypography,
        content = content,
    )
}
