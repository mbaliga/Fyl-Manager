package io.github.mbaliga.fylz.widget

/**
 * One mounted storage volume as the Space widget reads it.
 *
 * Deliberately carries no title and no path. A removable volume's description can be its
 * filesystem label, which the owner may have chosen ("PHOTOS 2019"), so the widget words volumes
 * by kind only ([VolumeKind]) and numbers them when there is more than one removable volume.
 *
 * @param rootId the [io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider] root id, used only
 *   inside the tap intent so a row opens its own volume; it is never drawn.
 */
data class VolumeReading(
    val rootId: String,
    val primary: Boolean,
    val readOnly: Boolean,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

enum class VolumeKind { INTERNAL, REMOVABLE }

data class VolumeRow(
    val rootId: String,
    val kind: VolumeKind,
    /** 1-based among removable volumes when there are two or more of them; 0 otherwise. */
    val ordinal: Int,
    val readOnly: Boolean,
    val freeBytes: Long?,
    val totalBytes: Long?,
) {
    /** False when the volume did not report a usable size; the widget then says so in words. */
    val sizeKnown: Boolean
        get() = totalBytes != null && totalBytes > 0L && freeBytes != null && freeBytes in 0L..totalBytes

    /** Share of the volume in use, in thousandths, for a 0..1000 progress bar. */
    val usedPermille: Int?
        get() = if (!sizeKnown) null else (((totalBytes!! - freeBytes!!) * 1000L) / totalBytes).toInt()

    /** Whole percent in use, rounded half up, for the words beside the bar. */
    val usedPercent: Int?
        get() = usedPermille?.let { (it + 5) / 10 }
}

/** What the Space widget shows, independent of size; [rowsFor] picks how many rows fit. */
data class SpaceWidgetModel(val rows: List<VolumeRow>) {

    val isEmpty: Boolean get() = rows.isEmpty()

    fun rowsFor(size: WidgetSize): List<VolumeRow> = rows.take(capacity(size))

    /** Volumes that did not fit at [size], so the footer can say "and N more". */
    fun hiddenAt(size: WidgetSize): Int = (rows.size - capacity(size)).coerceAtLeast(0)

    companion object {
        /**
         * Volumes per size. LARGE draws the same two as MEDIUM, but as two-line, 48dp tap targets
         * that open their own volume; two of those are all that fit in 250x180dp.
         */
        fun capacity(size: WidgetSize): Int = when (size) {
            WidgetSize.COMPACT, WidgetSize.SMALL -> 1
            WidgetSize.MEDIUM, WidgetSize.LARGE -> 2
        }

        /** Primary volume first, then removable volumes in the order the platform listed them. */
        fun from(readings: List<VolumeReading>): SpaceWidgetModel {
            val ordered = (readings.filter { it.primary } + readings.filterNot { it.primary })
                .distinctBy(VolumeReading::rootId)
            val removableCount = ordered.count { !it.primary }
            var removableSeen = 0
            val rows = ordered.map { reading ->
                val kind = if (reading.primary) VolumeKind.INTERNAL else VolumeKind.REMOVABLE
                val ordinal = if (kind == VolumeKind.REMOVABLE && removableCount > 1) ++removableSeen else 0
                VolumeRow(
                    rootId = reading.rootId,
                    kind = kind,
                    ordinal = ordinal,
                    readOnly = reading.readOnly,
                    freeBytes = reading.freeBytes,
                    totalBytes = reading.totalBytes,
                )
            }
            return SpaceWidgetModel(rows)
        }
    }
}
