package io.github.mbaliga.fylz.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which palette is active: one of the bundled [BuiltInThemePresets], or the user's own JSON --
 * a separate axis from [io.github.mbaliga.fylz.model.ThemeMode] (system/light/dark stays the
 * light-vs-dark *selector*; this is *which colours*). */
sealed interface ActiveTheme {
    data class BuiltIn(val id: String) : ActiveTheme
    data class Custom(val json: String) : ActiveTheme
}

/**
 * The active theme palette (owner request, see `ThemeJson.kt`'s own doc for the schema) --
 * persisted the same SharedPreferences-file-per-store shape every other store in this app uses
 * ([io.github.mbaliga.fylz.operations.VerifySettings], `PinchSettingsStore`, ...). Two keys, not
 * one: [ActiveTheme.BuiltIn] stores an id, [ActiveTheme.Custom] stores the full JSON text
 * verbatim -- so a custom theme, once applied, survives a process restart exactly as pasted, the
 * same guarantee `SessionStoreTest` pins for session JSON.
 *
 * [resolved] is what [io.github.mbaliga.fylz.ui.theme.FylzTheme] actually consumes: the already-
 * parsed [ThemePalette], recomputed whenever [selection] changes. Nothing malformed ever reaches
 * it -- [setCustom] validates before persisting or updating either flow ("never a partial
 * application"), and a selection read back from disk that somehow fails to parse (SharedPreferences
 * edited by hand, say) falls back to the bundled default rather than crashing or leaving [resolved]
 * stale.
 */
class ThemePaletteStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _selection = MutableStateFlow(readSelection())
    val selection: StateFlow<ActiveTheme> = _selection.asStateFlow()

    private val _resolved = MutableStateFlow(resolve(_selection.value))
    val resolved: StateFlow<ThemePalette> = _resolved.asStateFlow()

    fun setBuiltIn(id: String) {
        val next = ActiveTheme.BuiltIn(id)
        persist(next)
        _selection.value = next
        _resolved.value = resolve(next)
    }

    /** Validates [json] before touching persistence or [resolved] at all -- a malformed custom
     * theme is refused with the specific error [ThemeJson.parse] found, and the previously active
     * theme stays active, unchanged. */
    fun setCustom(json: String): Result<Unit> = ThemeJson.parse(json).map { palette ->
        val next = ActiveTheme.Custom(json)
        persist(next)
        _selection.value = next
        _resolved.value = palette
    }

    /** The last custom JSON this store held, whether or not it is the currently active
     * selection -- so re-opening "Custom…" after switching to a built-in still offers the text
     * back for editing rather than a blank field. */
    fun lastCustomJson(): String? = preferences.getString(LAST_CUSTOM_KEY, null)

    private fun resolve(selection: ActiveTheme): ThemePalette = when (selection) {
        is ActiveTheme.BuiltIn -> BuiltInThemePresets.load(selection.id).getOrElse { BuiltInThemePresets.fallback() }
        is ActiveTheme.Custom -> ThemeJson.parse(selection.json).getOrElse { BuiltInThemePresets.fallback() }
    }

    private fun persist(selection: ActiveTheme) {
        val editor = preferences.edit()
        when (selection) {
            is ActiveTheme.BuiltIn -> editor.putString(KIND_KEY, KIND_BUILT_IN).putString(VALUE_KEY, selection.id)
            is ActiveTheme.Custom -> editor.putString(KIND_KEY, KIND_CUSTOM).putString(VALUE_KEY, selection.json).putString(LAST_CUSTOM_KEY, selection.json)
        }
        check(editor.commit()) { "Could not save the active theme" }
    }

    private fun readSelection(): ActiveTheme {
        val kind = preferences.getString(KIND_KEY, null)
        val value = preferences.getString(VALUE_KEY, null)
        return when {
            kind == KIND_CUSTOM && value != null && ThemeJson.parse(value).isSuccess -> ActiveTheme.Custom(value)
            kind == KIND_BUILT_IN && value != null && value in BuiltInThemePresets.ids -> ActiveTheme.BuiltIn(value)
            else -> ActiveTheme.BuiltIn(BuiltInThemePresets.DEFAULT_ID)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_theme_settings"
        const val KIND_KEY = "kind"
        const val VALUE_KEY = "value"
        const val LAST_CUSTOM_KEY = "last_custom"
        const val KIND_BUILT_IN = "builtin"
        const val KIND_CUSTOM = "custom"
    }
}
