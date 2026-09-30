package io.github.mbaliga.fylz.operations

import android.content.ContentResolver

/** What a staged item is finalised as: [requestedName], replacing [existing] when a Replace won. */
data class TargetPlan(
    val requestedName: String,
    val existing: DocNode? = null,
)

/**
 * A destination folder's children indexed by name (M3.4 section 2.2 step 6): **one** listing at
 * planning and one at claim, instead of one `queryChildDocuments` per `findChild` -- which made a
 * flat 10,000-entry extraction quadratic. Case-aware: on a FAT-family or otherwise case-insensitive
 * destination two names that differ only in case are one key. [reserve] records a name this
 * operation is about to create, so [uniqueName] never hands the same Keep-both name out twice.
 */
class NameIndex(children: Collection<DocNode>, private val caseInsensitive: Boolean) {
    private val byKey = HashMap<String, DocNode>(children.size * 4 / 3 + 16)
    private val reserved = HashSet<String>()

    init {
        children.forEach { byKey[key(it.name)] = it }
    }

    private fun key(name: String): String = if (caseInsensitive) name.lowercase() else name

    fun find(name: String): DocNode? = byKey[key(name)]

    fun contains(name: String): Boolean = key(name).let { it in byKey || it in reserved }

    fun reserve(name: String) {
        reserved += key(name)
    }

    /** The children as listed, for callers that need more than a lookup. */
    val size: Int get() = byKey.size

    /**
     * `base (2)ext`, `(3)`, ... -- the first name not present and not reserved, then reserved. The
     * same rule `FileOperationService`'s copy uses, without a provider round trip per probe.
     */
    fun uniqueName(requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (!contains(candidate)) {
                reserve(candidate)
                return candidate
            }
            index += 1
        }
    }
}

/**
 * The shared "where does this item land, and how is it finalised" logic (M3.4 section 2.5,
 * `TargetPlanning.kt`), used by copy/move (`FileOperationService`) and extraction
 * (`ArchiveExtractor`) alike. Lookups go through the [NameIndex] when one is given, else through
 * [DocNode.findChild] (one child listing per call, as before).
 */
class TargetPlanner(
    private val resolver: ContentResolver,
    private val recycleBin: RecycleBinService,
    private val destination: DocNode,
    private val index: NameIndex? = null,
) {
    private fun existingChild(name: String): DocNode? =
        if (index != null) index.find(name) else destination.findChild(resolver, name)

    /**
     * Resolves what an item named [requestedName] does about a same-named sibling under [policy]:
     * `null` means skip the item; [ConflictPolicy.ASK] reaching here is a conflict nothing
     * pre-resolved and throws (the UI resolves every top-level conflict before the transfer
     * starts); Keep-both picks the next free `name (N)`; Replace carries the existing node so
     * [finalizeTarget] can recycle it; Replace-if-newer replaces only when [sourceLastModified] is
     * later than the existing item's.
     */
    fun resolveTargetPlan(
        requestedName: String,
        sourceLastModified: Long?,
        policy: ConflictPolicy,
    ): TargetPlan? {
        val existing = existingChild(requestedName)
            ?: return TargetPlan(requestedName = requestedName)
        return when (policy) {
            ConflictPolicy.ASK -> error("A file named $requestedName already exists.")
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> TargetPlan(requestedName = uniqueName(requestedName))
            ConflictPolicy.REPLACE -> TargetPlan(requestedName = requestedName, existing = existing)
            ConflictPolicy.REPLACE_IF_NEWER -> {
                val existingModified = existing.lastModified
                val newer = sourceLastModified != null && existingModified != null && sourceLastModified > existingModified
                if (newer) TargetPlan(requestedName = requestedName, existing = existing) else null
            }
        }
    }

    /** `base (2)ext`, `(3)`, ... -- the first name no child has; through the index when there is one. */
    fun uniqueName(requestedName: String): String {
        index?.let { return it.uniqueName(requestedName) }
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (destination.findChild(resolver, candidate) == null) return candidate
            index += 1
        }
    }

    /**
     * [staged] is always still under its `.fylz-part-*` staging name at this point (P0.6): every
     * copy writes there first, regardless of conflict policy, and only reaches its real name here,
     * after verification. On a plain create or Keep-both (no [TargetPlan.existing]), that's a
     * direct rename. A Replace conflict goes through [RecycleBinService]'s shared policy (also used
     * by its own restore): the existing item is renamed aside and recycled -- into [destination]'s
     * `.fylz-trash` if it has one -- only after the replacement has actually landed under the
     * requested name, never before.
     */
    suspend fun finalizeTarget(plan: TargetPlan, staged: DocNode): DocNode {
        val existing = plan.existing
        if (existing == null) {
            return if (staged.name == plan.requestedName) staged else staged.rename(resolver, plan.requestedName)
        }
        return recycleBin.replaceWithRecycleFallback(
            destinationRoot = destination,
            existing = existing,
            staged = staged,
            requestedName = plan.requestedName,
            originalParentUri = destination.uri,
        )
    }
}
