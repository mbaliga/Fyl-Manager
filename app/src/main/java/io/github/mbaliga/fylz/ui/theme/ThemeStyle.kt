package io.github.mbaliga.fylz.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.mbaliga.fylz.ui.components.IconStyle

/**
 * How a folder draws its own material -- the thing a file-type icon can never express, since a
 * folder isn't a format. Independent of [IconStyle]: two themes can share an icon pack and still
 * disagree about whether a folder is a card, a pane of glass, or a line of text.
 */
enum class FolderMaterial {
    /** An opaque coloured body -- the folder is furniture, not a window. */
    SOLID,

    /** Translucent, whatever the folder holds showing through -- generalised to every folder. */
    FROSTED,

    /** The file-type SVG alone, no drawn surface behind it -- the icon carries the whole read. */
    ICONIC,

    /** No face at all -- a folder is a listing row, not a card. */
    TEXT,
}

/**
 * The five-theme axis the owner asked for by name -- CLI, Vintage, Retro, Neo, Glass -- each
 * bundling every visual decision a "theme" makes (icon artwork, folder material, type, palette)
 * so callers dispatch on one value instead of five unrelated preferences that happen to travel
 * together. [FylzTheme] reads [scheme] and [monospace]; [FolderFace] and [StackCard] read
 * [folderMaterial]; [EntryThumbnail] (via `ProvideIconStyle`) reads [iconStyle].
 */
enum class ThemeStyle(
    val iconStyle: IconStyle,
    val folderMaterial: FolderMaterial,
    val monospace: Boolean,
    val forcesExtensions: Boolean,
    val overridesScheme: Boolean,
) {
    /** Solid material folders, dynamic wallpaper colour -- today's app, named and given siblings. */
    NEO(
        iconStyle = IconStyle.DEFAULT,
        folderMaterial = FolderMaterial.SOLID,
        monospace = false,
        forcesExtensions = false,
        overridesScheme = false,
    ),

    /** Transparent folder material, contents showing through -- still dynamic wallpaper colour. */
    GLASS(
        iconStyle = IconStyle.GRADIENT,
        folderMaterial = FolderMaterial.FROSTED,
        monospace = false,
        forcesExtensions = false,
        overridesScheme = false,
    ),

    /** 1-bit black-and-white pixel icons, 16-bit era -- a fixed b/w scheme, not the wallpaper. */
    VINTAGE(
        iconStyle = IconStyle.VINTAGE,
        folderMaterial = FolderMaterial.ICONIC,
        monospace = true,
        forcesExtensions = true,
        overridesScheme = true,
    ),

    /** Coloured pixel icons, Windows XP era -- a fixed period palette. */
    RETRO(
        iconStyle = IconStyle.RETRO,
        folderMaterial = FolderMaterial.ICONIC,
        monospace = false,
        forcesExtensions = true,
        overridesScheme = true,
    ),

    /** Text only: structure carried by indentation and always-visible extensions, no icons drawn. */
    CLI(
        iconStyle = IconStyle.DEFAULT,
        folderMaterial = FolderMaterial.TEXT,
        monospace = true,
        forcesExtensions = true,
        overridesScheme = true,
    );

    /**
     * The fixed [ColorScheme] this style imposes, or null to keep whatever [FylzTheme] would
     * otherwise pick (dynamic wallpaper colour for Neo and Glass). [overridesScheme] mirrors the
     * nullability here so a caller can branch without invoking this for the null-returning styles.
     */
    fun scheme(dark: Boolean): ColorScheme? = when (this) {
        NEO, GLASS -> null
        VINTAGE -> if (dark) vintageDarkScheme else vintageLightScheme
        RETRO -> if (dark) retroDarkScheme else retroLightScheme
        // Deliberately ignores `dark`: a phosphor terminal doesn't have a light mode, and
        // flipping to a bright background for CLI would undercut the one thing the style is for.
        CLI -> cliScheme
    }
}

/** The icon-style/folder-material/theme preference in force for this subtree. Defaults to [ThemeStyle.NEO], today's app. */
val LocalThemeStyle: ProvidableCompositionLocal<ThemeStyle> = compositionLocalOf { ThemeStyle.NEO }

private val vintageLightScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF3A3A3A),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFFFAFAFA),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFFBDBDBD),
)

private val vintageDarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFC7C7C7),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerLow = Color(0xFF080808),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFF3A3A3A),
)

/** Windows XP "Luna" blue -- taskbar blue, the tan window chrome, the gold highlight. */
private val retroLightScheme = lightColorScheme(
    primary = Color(0xFF2A5CD7),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8A6D1A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE8A3),
    onSecondaryContainer = Color(0xFF3D2E00),
    background = Color(0xFFECE9D8),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFD4D0C8),
    onSurfaceVariant = Color(0xFF3A3A3A),
    surfaceContainer = Color(0xFFECE9D8),
    surfaceContainerHigh = Color(0xFFD4D0C8),
    surfaceContainerLow = Color(0xFFF5F3EA),
    outline = Color(0xFF919B9C),
)

/** The same era, lights off -- deep Royale navy standing in for a dark XP that never shipped. */
private val retroDarkScheme = darkColorScheme(
    primary = Color(0xFF5A8FD6),
    onPrimary = Color(0xFF0A1224),
    secondary = Color(0xFFFFC83D),
    onSecondary = Color(0xFF3D2E00),
    secondaryContainer = Color(0xFF4A3B0E),
    onSecondaryContainer = Color(0xFFFFE8A3),
    background = Color(0xFF12172B),
    onBackground = Color(0xFFECE9D8),
    surface = Color(0xFF171C33),
    onSurface = Color(0xFFECE9D8),
    surfaceVariant = Color(0xFF232B4D),
    onSurfaceVariant = Color(0xFFC7C2AE),
    surfaceContainer = Color(0xFF171C33),
    surfaceContainerHigh = Color(0xFF232B4D),
    surfaceContainerLow = Color(0xFF0E1224),
    outline = Color(0xFF5A6690),
)

/** Green phosphor on black, one amber accent for the gutter mark and whatever else needs to stand out. */
private val cliScheme = darkColorScheme(
    primary = Color(0xFF33FF66),
    onPrimary = Color(0xFF04120A),
    secondary = Color(0xFFFFB000),
    onSecondary = Color(0xFF241600),
    secondaryContainer = Color(0xFF3A2A00),
    onSecondaryContainer = Color(0xFFFFD37A),
    background = Color(0xFF0A0F0A),
    onBackground = Color(0xFF33FF66),
    surface = Color(0xFF0A0F0A),
    onSurface = Color(0xFF33FF66),
    surfaceVariant = Color(0xFF13251A),
    onSurfaceVariant = Color(0xFF2FBE5C),
    surfaceContainer = Color(0xFF0F1710),
    surfaceContainerHigh = Color(0xFF152018),
    surfaceContainerLow = Color(0xFF080C08),
    outline = Color(0xFF1F9B44),
    outlineVariant = Color(0xFF14401F),
)
