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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.R
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

/**
 * Builds the ten roles the app actually styles, all riding [family] -- the one seam that lets
 * CLI and Vintage pull every label onto a monospace face without every call site deciding that
 * for themselves.
 *
 * Widened from the original six: [labelLarge], [labelSmall], [bodySmall] and [titleLarge] used to
 * fall through to Compose's own default type scale, which is why the selection pill, room
 * headings, the breadcrumb and the search hint kept reading in the system font even once [family]
 * carried the app's own look everywhere else. Sizes and line heights otherwise match Material 3's
 * own scale for these roles -- only the family and weight are the app's.
 */
private fun fylzTypography(family: FontFamily) = Typography(
    // No display-sized surface used these before the landing hero; the wordmark itself stays on
    // its own bundled Hyle face local to LandingSplash.kt rather than riding these -- what these
    // give the rest of the app is a display register that isn't just the stock Material default.
    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun FylzTheme(
    themeMode: ThemeMode,
    accentPreset: AccentPreset,
    dynamicColor: Boolean,
    themeStyle: ThemeStyle = ThemeStyle.NEO,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val accent = accentPreset.colors()

    // themeStyle.scheme() wins outright when it returns one -- Vintage/Retro/CLI are a fixed
    // palette, not a wallpaper-derived accent, so they never reach the dynamic-colour arms below.
    val colorScheme = themeStyle.scheme(darkTheme) ?: when {
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

    // Bundled rather than the platform's stock sans -- Space Grotesk's geometric forms are what
    // the rest of the chrome (tab band, selection row, actions bar) is built around, and a system
    // font substitution here would make every other role in this Typography visibly disagree with
    // them. VINTAGE/CLI still win outright via themeStyle.monospace, unchanged from before.
    val spaceGrotesk = remember {
        FontFamily(
            Font(R.font.space_grotesk_regular, FontWeight.Normal),
            Font(R.font.space_grotesk_medium, FontWeight.Medium),
            Font(R.font.space_grotesk_bold, FontWeight.Bold),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = fylzTypography(if (themeStyle.monospace) FontFamily.Monospace else spaceGrotesk),
        content = content,
    )
}
