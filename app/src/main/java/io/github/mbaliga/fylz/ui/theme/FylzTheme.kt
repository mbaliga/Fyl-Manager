package io.github.mbaliga.fylz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
 * Builds all fifteen M3 [Typography] roles on [family] -- the one seam that lets CLI and Vintage
 * pull every label onto a monospace face without every call site deciding that for themselves.
 *
 * Build 11.5 closes the last of the Roboto leaks: [bodyLarge], [titleSmall], [displaySmall],
 * [headlineLarge] and [headlineMedium] were never overridden, so text riding those roles (body
 * copy, list subtitles, big numerals) quietly fell through to Compose's system-default Roboto
 * even though every *other* role already carried the app's own face -- exactly the kind of
 * font-family drift the owner's "only Hyle fonts" note was about. Sizes/line-heights for the five
 * newly-added roles match Material 3's own baseline scale for that role; only the family and
 * weight are the app's, same as the roles that were already overridden.
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
    displaySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
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
    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
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
        // Ground colour normalized to the spec's near-white/near-black family, decoupled from the
        // moss/ink/clay/electric accent tint that used to leak into surface/background themselves
        // (the old FAFCFA/FDFEFD pair reads as pure white with a faint green cast, not the
        // "near-white (#F7F8FA..#F9F9F9 family), NOT pure white" the Build 11.5 spec calls for).
        // surfaceContainer/surfaceContainerHigh step down from that same neutral family rather than
        // the old moss-tinted ramp, so elevation reads as pure lightness change, not a colour shift.
        darkTheme -> darkColorScheme(
            primary = accent.darkPrimary,
            secondary = accent.darkSecondary,
            surface = Color(0xFF121314),
            surfaceContainer = Color(0xFF1B1C1E),
            surfaceContainerHigh = Color(0xFF26282B),
            background = Color(0xFF0E0F10),
        )
        else -> lightColorScheme(
            primary = accent.lightPrimary,
            secondary = accent.lightSecondary,
            surface = Color(0xFFF8F9FA),
            surfaceContainer = Color(0xFFEEEFF1),
            surfaceContainerHigh = Color(0xFFE3E5E8),
            background = Color(0xFFF9F9FA),
        )
    }

    // Bundled rather than the platform's stock sans -- Build 11.5 swaps Space Grotesk (a stock
    // outside import) for Hyle Grotesk Classic, the Hyle-native face built on Space Grotesk with
    // Archivo letterforms substituted (see docs/FONTS.md): the owner's "only Hyle fonts" note,
    // satisfied without losing the geometric-grotesk look the rest of the chrome (tab band,
    // selection row, actions bar -- ChromeTokens.kt's own chromeFontFamily()) is built around.
    // Light rides along even though no role above requests it yet, so it's available the moment
    // one does rather than needing a second FontFamily edit here. VINTAGE/CLI still win outright
    // via themeStyle.monospace, unchanged from before.
    val hyleGroteskClassic = remember {
        FontFamily(
            Font(R.font.hyle_grotesk_classic_light, FontWeight.Light),
            Font(R.font.hyle_grotesk_classic_regular, FontWeight.Normal),
            Font(R.font.hyle_grotesk_classic_medium, FontWeight.Medium),
            Font(R.font.hyle_grotesk_classic_bold, FontWeight.Bold),
        )
    }

    // Hyle's radius vocabulary caps at 16dp (FylzGeometry.RadiusXl) -- M3's stock Shapes() default
    // (extraLarge = 28dp) is exactly the "20dp cards" the owner flagged as off-vocabulary, and
    // FylzTheme previously passed no Shapes at all, so every unstyled Card/Surface/Sheet was
    // quietly drawing that stock ramp. extraLarge collapses onto the same 16dp as large because
    // Hyle has no radius step above RadiusXl.
    val shapes = remember {
        Shapes(
            extraSmall = RoundedCornerShape(FylzGeometry.RadiusSm),
            small = RoundedCornerShape(FylzGeometry.RadiusMd),
            medium = RoundedCornerShape(FylzGeometry.RadiusLg),
            large = RoundedCornerShape(FylzGeometry.RadiusXl),
            extraLarge = RoundedCornerShape(FylzGeometry.RadiusXl),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = fylzTypography(if (themeStyle.monospace) FontFamily.Monospace else hyleGroteskClassic),
        shapes = shapes,
        content = content,
    )
}
