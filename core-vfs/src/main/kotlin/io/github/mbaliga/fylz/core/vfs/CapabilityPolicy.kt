package io.github.mbaliga.fylz.core.vfs

import io.github.mbaliga.fylz.core.model.ItemCapability

/**
 * A provider-capability-gated user action: something a person asks Fylz to do that only makes
 * sense if the backend actually supports it.
 *
 * Deliberately narrower than the app's own action vocabulary (`FylzAction` in `ui/`, which also
 * has entries like "clear selection" or "open PDF tools" that no provider capability could ever
 * gate). This enum is only the subset a [CapabilityPolicy] decision is meaningful for.
 */
enum class UserAction {
    COPY,
    MOVE,
    RENAME,
    CREATE_FILE,
    CREATE_FOLDER,
    LIST,
    MOVE_TO_TRASH,
    RESTORE_FROM_TRASH,
    DELETE_PERMANENTLY,
    SEARCH_BY_NAME,
    SEARCH_CONTENT,
}

/** The decision for one action against one offered capability set. */
data class CapabilityDecision(
    val action: UserAction,
    val allowed: Boolean,
    val missing: Set<ItemCapability>,
)

/**
 * WP-1.2's other half: the vocabulary is [ItemCapability]; this is what a command requires of
 * it. "Commands declare required capabilities; UI derives visibility/enabled state and can
 * explain what's missing" — the plan's own words, and its own next clause is the scope note
 * that matters here: *"(surfaces in Phase 2)."* This object is that declaration, tested and
 * ready, with no UI wired to it yet. Acceptance law #1 — "no action appears unless the
 * selected items and destination can support it" — is what this makes checkable; making it
 * checked is a later phase's work, not a claim this file makes about itself.
 */
object CapabilityPolicy {

    private val required: Map<UserAction, Set<ItemCapability>> = mapOf(
        UserAction.COPY to setOf(ItemCapability.READ, ItemCapability.COPY),
        UserAction.MOVE to setOf(ItemCapability.READ, ItemCapability.MOVE),
        UserAction.RENAME to setOf(ItemCapability.RENAME),
        UserAction.CREATE_FILE to setOf(ItemCapability.CREATE_FILE),
        UserAction.CREATE_FOLDER to setOf(ItemCapability.CREATE_DIRECTORY),
        UserAction.LIST to setOf(ItemCapability.LIST),
        UserAction.MOVE_TO_TRASH to setOf(ItemCapability.TRASH),
        UserAction.RESTORE_FROM_TRASH to setOf(ItemCapability.RESTORE_TRASH),
        UserAction.DELETE_PERMANENTLY to setOf(ItemCapability.DELETE_PERMANENT),
        UserAction.SEARCH_BY_NAME to setOf(ItemCapability.NATIVE_SEARCH),
        UserAction.SEARCH_CONTENT to setOf(ItemCapability.READ, ItemCapability.CONTENT_SEARCH),
    )

    /** What [action] needs. Never empty — every [UserAction] declares at least one requirement. */
    fun requirements(action: UserAction): Set<ItemCapability> =
        required.getValue(action)

    /**
     * Whether [offered] — a location's or an item's own [io.github.mbaliga.fylz.core.model.ItemSnapshot.capabilities]
     * — covers what [action] requires.
     */
    fun canPerform(action: UserAction, offered: Set<ItemCapability>): Boolean =
        offered.containsAll(requirements(action))

    /** What [offered] is missing to perform [action]; empty exactly when [canPerform] is true. */
    fun missing(action: UserAction, offered: Set<ItemCapability>): Set<ItemCapability> =
        requirements(action) - offered

    fun decide(action: UserAction, offered: Set<ItemCapability>): CapabilityDecision {
        val gap = missing(action, offered)
        return CapabilityDecision(action, gap.isEmpty(), gap)
    }

    /**
     * A short, factual sentence for a refused action — never blank, never a bare capability
     * name. "acceptance law #1" is only honestly satisfied if the UI can say WHY an action is
     * absent when asked, not just hide it silently.
     */
    fun explain(decision: CapabilityDecision): String? {
        if (decision.allowed) return null
        val labels = decision.missing.sortedBy(ItemCapability::name).joinToString(", ") { it.label() }
        return "This location does not support: $labels."
    }

    private fun ItemCapability.label(): String = name.lowercase().replace('_', ' ')
}
