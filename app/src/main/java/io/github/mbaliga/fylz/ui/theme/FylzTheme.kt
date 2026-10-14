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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.model.ThemeMode

/**
 * Space Grotesk (SIL Open Font License 1.1; the licence text ships in the APK at
 * `app/src/main/assets/fonts/SpaceGrotesk-OFL.txt`, and is summarised in `THIRD_PARTY_NOTICES.md`
 * under "Fonts") -- bundled as `res/font/space_grotesk.ttf`, Google Fonts' own single variable-font
 * file (weight axis, Light-Bold), rather than four separate static files. Each [Font] entry below
 * points at that one file with a different [FontVariation.Settings] weight-axis value, which is
 * the standard way to expose a variable font's weight range as an ordinary [FontFamily] that
 * `FontWeight.Medium`/`.SemiBold`/etc. resolve against normally.
 *
 * Judgment call (`docs/agent/PROGRESS.md`): this is used ONLY by the folder card's name/subtitle
 * text (`ui/components/FolderCard.kt`), the one place the task's Figma exports actually specify
 * it. [FylzTypography] below deliberately keeps `FontFamily.SansSerif` for everything else --
 * swapping the app's whole typeface was not in the brief (only the folder-card SVGs name a
 * typeface at all) and would be a far larger, riskier visual change than the folder-card redesign
 * this font was added for.
 */
@OptIn(ExperimentalTextApi::class)
val SpaceGrotesk: FontFamily = FontFamily(
    Font(R.font.space_grotesk, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * Builds the Material3 [ColorScheme] for one light/dark half of a [ThemePalette].
 *
 * The eight colours the JSON schema defines map straight onto the `ColorScheme` field of the same
 * name. Material3's other tokens (the containers, their on-colours, `onPrimary`, `inversePrimary`)
 * would otherwise fall back to the fixed baseline purple tonal palette, which made every FAB,
 * FilterChip, SegmentedButton, tonal button and tertiary notice ignore the chosen accent. They are
 * therefore derived here from the palette's own accent colours, so the one accent the user picks
 * drives every accent-role element with no call-site changes:
 *
 * - `onPrimary`/`onSecondary`/`onTertiary`: black or white, whichever reads on that accent.
 * - `primaryContainer`/`secondaryContainer`/`tertiaryContainer`: the accent tinted over `surface`
 *   (18% light, 30% dark), with `onSurface` as the on-colour.
 * - `inversePrimary`: the accent to draw on `inverseSurface` (the selection bar, tab strip and
 *   view-mode bar). [inversePrimary] defaults to this half's own `primary`; [FylzTheme] passes the
 *   *other* half's primary, because `inverseSurface` is dark in light mode and light in dark mode,
 *   and each preset already defines a light/dark pair.
 */
internal fun PaletteColors.toColorScheme(dark: Boolean, inversePrimary: Color = primary): ColorScheme {
    fun on(accent: Color): Color = if (accent.luminance() > 0.5f) Color.Black else Color.White
    fun tint(accent: Color): Color = accent.copy(alpha = if (dark) 0.30f else 0.18f).compositeOver(surface)
    return if (dark) {
        darkColorScheme(
            primary = primary, secondary = secondary, tertiary = tertiary,
            background = background, surface = surface, surfaceVariant = surfaceVariant,
            onBackground = onBackground, onSurface = onSurface,
            onPrimary = on(primary), onSecondary = on(secondary), onTertiary = on(tertiary),
            primaryContainer = tint(primary), onPrimaryContainer = onSurface,
            secondaryContainer = tint(secondary), onSecondaryContainer = onSurface,
            tertiaryContainer = tint(tertiary), onTertiaryContainer = onSurface,
            inversePrimary = inversePrimary,
        )
    } else {
        lightColorScheme(
            primary = primary, secondary = secondary, tertiary = tertiary,
            background = background, surface = surface, surfaceVariant = surfaceVariant,
            onBackground = onBackground, onSurface = onSurface,
            onPrimary = on(primary), onSecondary = on(secondary), onTertiary = on(tertiary),
            primaryContainer = tint(primary), onPrimaryContainer = onSurface,
            secondaryContainer = tint(secondary), onSecondaryContainer = onSurface,
            tertiaryContainer = tint(tertiary), onTertiaryContainer = onSurface,
            inversePrimary = inversePrimary,
        )
    }
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
        darkTheme -> palette.dark.toColorScheme(dark = true, inversePrimary = palette.light.primary)
        else -> palette.light.toColorScheme(dark = false, inversePrimary = palette.dark.primary)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FylzTypography,
        content = content,
    )
}
