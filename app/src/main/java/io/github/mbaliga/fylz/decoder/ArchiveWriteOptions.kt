package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The write engine's format/filter pair (`fylz_archive::write::WriteFormat`'s uniffi twin,
 * `WriteFormatRecord`; `docs/agent/DESIGN-M35-CREATE.md` section 2.4/2.5). Which numeric
 * `level` means what for a given format -- the sheet's Fast/Normal/Best tiers -- is
 * `CompressPlanner`'s table, not this type's: a Parcelable crossing Binder carries the already-
 * resolved level, never a tier name.
 */
enum class ArchiveWriteFormat {
    ZIP,
    TAR_GZ,
    TAR_XZ,
    TAR_ZSTD,
    TAR_BZIP2,
    TAR_LZ4,
}

/**
 * `IDecoderService.writeArchive`'s options (design section 2.5): crosses Binder as a Parcelable,
 * mirrors the uniffi `WriteOptionsRecord` the engine actually takes. [ArchiveRecordMapping.kt]'s
 * `toRecord()` is the pure copy a JVM test proves without the native library, the same pattern
 * every other `decoder.*` Parcelable already follows.
 */
@Parcelize
data class ArchiveWriteOptions(
    val format: ArchiveWriteFormat,
    val level: Int,
) : Parcelable
