package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import java.util.BitSet

/** How an extraction lays its top-level items out at the destination (M3.4 section 2.2 step 4). */
enum class ExtractLayout {
    /** Extract here: one item per root entry of the selection, under its own name. */
    HERE,

    /** Extract to `<name>/`: one item, the folder, holding everything. */
    INTO_FOLDER,

    /** Extract selected entries: one item per selected entry (a file or a folder). */
    ENTRIES,
}

/**
 * One top-level item of an extraction plan (`extract_plan_items`): the tree path it is rooted at
 * (`""` for the whole selection under [ExtractLayout.INTO_FOLDER]), the name it lands under, the
 * conflict resolution the user gave it at planning, and the Keep-both name the planner chose.
 * [itemIndex] is the matching [OperationItem]'s position in the operation.
 */
data class ExtractPlanItem(
    val itemIndex: Int,
    val rootPath: String,
    val requestedName: String,
    val conflictPolicy: ConflictPolicy,
    val nameOverride: String? = null,
) {
    /** The name the item is finalised under (the override wins). */
    val finalName: String get() = nameOverride ?: requestedName
}

/**
 * The persisted half of an EXTRACT operation (M3.4 section 2.2, `extract_plans`): what to read,
 * from which archive as it was when planned, into which shape, under which limits. Written
 * atomically with its [FileOperation] before the work is enqueued, read by the worker at claim,
 * deleted wherever the operation is.
 *
 * [archiveUri] is the archive's **root document Uri** on the archive provider
 * (`ArchiveDocumentId.root(ref).toUri()`): it names the source file and the nested chain, so the
 * worker re-opens the same handle through the catalog (disk-first) and the handle's `PinnedSource`
 * opens a fresh descriptor per call. [catalogKey] pins the archive as listed
 * (`sha256(src | size | mtime)`, chained for nested archives); a different key at claim is
 * `ARCHIVE_CHANGED`. No staged path is persisted: a staged copy belongs to the handle and never
 * outlives the process, so a re-run stages again (a recorded deviation from the design's schema).
 */
data class ExtractPlan(
    val operationId: String,
    val archiveUri: Uri,
    val catalogKey: String,
    val layout: ExtractLayout,
    /** [ExtractLayout.INTO_FOLDER]'s folder name; `null` otherwise. */
    val folderName: String?,
    /** Every header ordinal the engine reads: the selection's entries plus every hardlink target. */
    val ordinals: OrdinalBitmap,
    /** `ArchiveLimits.forExtraction(...)` as planned; the total cap is recomputed at claim. */
    val limits: ArchiveLimits,
    val consent: Boolean,
    /** Every path component sanitised for a FAT-family destination (`PreflightPolicy.sanitizedName`). */
    val sanitize: Boolean,
    val cancelRequested: Boolean = false,
    val items: List<ExtractPlanItem>,
)

/**
 * A set of header ordinals as a bitmap (bit `8 * i + j` of byte `i` is ordinal `8 * i + j`), the
 * shape that crosses Binder and lives in `extract_plans.ordinals`: at the 200,000-entry listing
 * bound about 25 KiB, whatever the selection. [ranges] gives the inclusive runs the engine takes.
 */
class OrdinalBitmap private constructor(private val bits: BitSet) {
    constructor() : this(BitSet())

    fun set(ordinal: Int) {
        require(ordinal >= 0) { "ordinal $ordinal is negative" }
        bits.set(ordinal)
    }

    fun clear(ordinal: Int) = bits.clear(ordinal)

    operator fun contains(ordinal: Int): Boolean = ordinal >= 0 && bits.get(ordinal)

    val cardinality: Int get() = bits.cardinality()

    val isEmpty: Boolean get() = bits.isEmpty

    /** The highest ordinal set, or -1. */
    val last: Int get() = bits.length() - 1

    /** Every set ordinal, ascending. */
    fun ordinals(): Sequence<Int> = sequence {
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            yield(i)
            i = bits.nextSetBit(i + 1)
        }
    }

    /** The set ordinals as inclusive runs, ascending and non-overlapping. */
    fun ranges(): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = bits.nextSetBit(0)
        while (start >= 0) {
            val end = bits.nextClearBit(start)
            result += start until end
            start = bits.nextSetBit(end)
        }
        return result
    }

    /** A copy with every ordinal below [fromOrdinal] cleared and every ordinal in [minus] cleared. */
    fun after(fromOrdinal: Int, minus: OrdinalBitmap = OrdinalBitmap()): OrdinalBitmap {
        val copy = bits.clone() as BitSet
        if (fromOrdinal > 0) copy.clear(0, fromOrdinal)
        copy.andNot(minus.bits)
        return OrdinalBitmap(copy)
    }

    fun copy(): OrdinalBitmap = OrdinalBitmap(bits.clone() as BitSet)

    fun toByteArray(): ByteArray = bits.toByteArray()

    override fun equals(other: Any?): Boolean = other is OrdinalBitmap && other.bits == bits

    override fun hashCode(): Int = bits.hashCode()

    override fun toString(): String = "OrdinalBitmap(${ranges().joinToString { if (it.first == it.last) "${it.first}" else "${it.first}..${it.last}" }})"

    companion object {
        fun of(vararg ordinals: Int): OrdinalBitmap = OrdinalBitmap().apply { ordinals.forEach(::set) }

        fun fromByteArray(bytes: ByteArray): OrdinalBitmap = OrdinalBitmap(BitSet.valueOf(bytes))
    }
}
