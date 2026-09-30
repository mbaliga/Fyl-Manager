package io.github.mbaliga.fylz.actions

import androidx.compose.ui.input.key.Key

/** One key model; `ctrl` and `meta` are kept distinct (design §2.2) -- nothing binds `meta` in
 * MC.0, but a future Ubuntu Touch/Linux desktop chord will need it. */
data class KeyChord(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)
