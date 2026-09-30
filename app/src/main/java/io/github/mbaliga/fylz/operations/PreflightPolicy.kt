package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.VolumeInfo

/**
 * One top-level item a transfer is about to attempt, as [PreflightPolicy] needs to know it.
 * Gathered by [gatherPreflightItems] from a real [DocNode] tree before the transfer starts.
 */
data class PreflightItem(
    val sourceUri: Uri,
    val name: String,
    val isDirectory: Boolean,
    /** This item's own size, or -- for a directory -- every nested file's size summed by walking
     * its real tree ([gatherPreflightItems]): the free-space rule needs the total a copy will
     * actually write, not just what one directory entry reports. */
    val totalBytes: Long?,
)

/** One problem [PreflightPolicy.evaluate] found with a specific [item]. */
sealed interface PreflightProblem {
    val item: PreflightItem

    /** [item]'s name contains a character vfat/exfat cannot store. Fixable with
     * [PreflightPolicy.sanitizedName]. */
    data class IllegalCharacters(override val item: PreflightItem, val characters: Set<Char>) : PreflightProblem

    /** [item]'s name ends in a space or a dot, which vfat/exfat silently trim or reject. Fixable
     * with [PreflightPolicy.sanitizedName]. */
    data class TrailingSpaceOrDot(override val item: PreflightItem) : PreflightProblem

    /** [item]'s name collides with [collidesWithName] only once case is ignored, on a destination
     * that cannot tell them apart -- not fixable by [PreflightPolicy.sanitizedName], since
     * replacing characters does nothing about two names differing only in case. */
    data class NameCollision(override val item: PreflightItem, val collidesWithName: String) : PreflightProblem

    /** [item]'s own name is longer than [limitBytes] once UTF-8 encoded. */
    data class NameTooLong(override val item: PreflightItem, val limitBytes: Int) : PreflightProblem

    /** [item] is a single file over vfat's own 4 GiB − 1 byte ceiling, which the filesystem
     * cannot represent at all, regardless of free space. */
    data class FileTooLargeForVfat(override val item: PreflightItem) : PreflightProblem
}

/** [availableBytes] falls short of [requiredBytes] (already including the safety margin) at the
 * destination. */
data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long)

data class PreflightResult(
    val problems: List<PreflightProblem>,
    val insufficientSpace: InsufficientSpace? = null,
) {
    val isClean: Boolean get() = problems.isEmpty() && insufficientSpace == null
}

/**
 * P1.5: a pure policy over what a copy or move is about to attempt, run once against the
 * destination's [VolumeInfo] before [FileOperationService.transfer] ever starts -- catching a
 * class of failure that today only ever surfaces mid-copy, one item at a time, as a bare
 * provider exception with no chance to fix the name or skip just that item first.
 *
 * Scope, stated up front: every rule here runs against the TOP-LEVEL items the user selected
 * ([gatherPreflightItems]'s own `sources`), not a full recursive listing of a folder's own
 * contents -- a bad name several levels inside a large copied tree is not individually flagged.
 * That matches the sheet this feeds: "skip this item" / "auto-rename" only make sense against
 * something the user actually selected, not an arbitrary nested file they never saw. Free space is
 * the one rule that does look inside a directory, via [gatherPreflightItems]'s own recursive sum,
 * because a free-space check that ignored a folder's real contents would not be a check at all.
 */
object PreflightPolicy {

    /** vfat's own hard ceiling: a 32-bit unsigned byte-count field, so no single file can be this
     * size or larger there, regardless of how much free space exists. */
    const val VFAT_MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1

    /** Refuse when the destination's free space would fall under required-plus-this-fraction. */
    private const val FREE_SPACE_MARGIN = 0.05

    /** 255 UTF-8 bytes is the per-component name ceiling on every filesystem Android actually
     * exposes here -- ext4, f2fs, vfat and exfat all cap one path component at this, independent
     * of overall path length (which this policy has no destination-path plumbing to compute
     * anyway -- see the class doc's own scope note). */
    const val NAME_LENGTH_LIMIT_BYTES = 255

    private val FAT_ILLEGAL_CHARACTERS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    private val FAT_FAMILY = setOf("vfat", "exfat")

    fun evaluate(items: List<PreflightItem>, volume: VolumeInfo): PreflightResult {
        val problems = mutableListOf<PreflightProblem>()
        val isVfat = volume.filesystemType == "vfat"
        val isFatFamily = volume.filesystemType in FAT_FAMILY

        items.forEach { item ->
            if (isVfat && !item.isDirectory && (item.totalBytes ?: 0L) > VFAT_MAX_FILE_BYTES) {
                problems += PreflightProblem.FileTooLargeForVfat(item)
            }
            if (isFatFamily) {
                val illegal = item.name.toSet().intersect(FAT_ILLEGAL_CHARACTERS)
                if (illegal.isNotEmpty()) problems += PreflightProblem.IllegalCharacters(item, illegal)
                if (item.name.lastOrNull().let { it == ' ' || it == '.' }) {
                    problems += PreflightProblem.TrailingSpaceOrDot(item)
                }
            }
            if (item.name.toByteArray(Charsets.UTF_8).size > NAME_LENGTH_LIMIT_BYTES) {
                problems += PreflightProblem.NameTooLong(item, NAME_LENGTH_LIMIT_BYTES)
            }
        }

        if (isFatFamily || volume.caseInsensitive) {
            items.groupBy { it.name.lowercase() }
                .values
                .filter { it.size > 1 }
                .forEach { collidingGroup ->
                    val first = collidingGroup.first()
                    collidingGroup.drop(1).forEach { item ->
                        problems += PreflightProblem.NameCollision(item, first.name)
                    }
                }
        }

        return PreflightResult(problems, checkFreeSpace(items, volume.freeBytes))
    }

    /**
     * [displayName] with every character [PreflightProblem.IllegalCharacters] would flag replaced
     * by `_`, and a trailing space or dot also replaced -- the sheet's "auto-rename" choice, in
     * the brief's own words. Meaningless (and never called by the sheet) for a
     * [PreflightProblem.NameCollision], [PreflightProblem.NameTooLong] or
     * [PreflightProblem.FileTooLargeForVfat] problem, none of which a character substitution
     * fixes.
     */
    fun sanitizedName(displayName: String): String {
        val replaced = displayName.map { char -> if (char in FAT_ILLEGAL_CHARACTERS) '_' else char }.joinToString("")
        val lastChar = replaced.lastOrNull() ?: return replaced
        return if (lastChar == ' ' || lastChar == '.') replaced.dropLast(1) + '_' else replaced
    }

    private fun checkFreeSpace(items: List<PreflightItem>, freeBytes: Long?): InsufficientSpace? {
        if (freeBytes == null || freeBytes < 0L) return null
        // Known sizes only -- an item whose recursive walk could not read a nested size
        // contributes nothing here rather than failing the whole check; a possible undercount on
        // an unreadable item is an acceptable trade for an otherwise-useful early warning, and the
        // transfer's own writes still fail correctly if this ever proves too optimistic.
        val required = safeSum(items.mapNotNull { it.totalBytes }) ?: return null
        val requiredWithMargin = safeSum(listOf(required, marginFor(required))) ?: return null
        return if (freeBytes < requiredWithMargin) InsufficientSpace(requiredWithMargin, freeBytes) else null
    }

    private fun marginFor(bytes: Long): Long = (bytes * FREE_SPACE_MARGIN).toLong()

    private fun safeSum(values: List<Long>): Long? {
        var total = 0L
        values.forEach { value ->
            if (value < 0L || Long.MAX_VALUE - total < value) return null
            total += value
        }
        return total
    }
}
