package io.github.mbaliga.fylz.preview

import android.content.ContentResolver
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded, signature-based checks for formats extension/MIME alone can't disambiguate.
 *
 * `.ts` is the sharp case: TypeScript source and an MPEG transport stream share the extension,
 * and a provider that guesses MIME type purely from that extension (common for local/SAF
 * providers) reports the same "video/mp2t" for both -- which is exactly what [FileType]'s
 * mime-before-extension classification then trusts.
 */
object ContentSniffer {
    private const val MPEG_TS_SYNC_BYTE: Byte = 0x47
    private const val MPEG_TS_PACKET_SIZE = 188

    /** True when [header] carries the MPEG-TS sync byte (`0x47`) at offset 0 and offset 188, and
     * also at offset 376 when the header is long enough to contain it. A header no longer than one
     * packet can't confirm the repeating pattern that distinguishes real MPEG-TS from a coincidental
     * `0x47` at the start of some other format, so it's never reported as a match. */
    fun isMpegTs(header: ByteArray): Boolean {
        if (header.size <= MPEG_TS_PACKET_SIZE) return false
        if (header[0] != MPEG_TS_SYNC_BYTE) return false
        if (header[MPEG_TS_PACKET_SIZE] != MPEG_TS_SYNC_BYTE) return false
        val thirdSyncOffset = MPEG_TS_PACKET_SIZE * 2
        return header.size <= thirdSyncOffset || header[thirdSyncOffset] == MPEG_TS_SYNC_BYTE
    }
}

/**
 * The [EntryKind] preview should actually use for [entry], resolving the one extension
 * ([FileFormatRegistry]'s and [io.github.mbaliga.fylz.util.FileType]'s classification can't,
 * since it's extension/MIME driven): `.ts`. TypeScript source is the common case a general file
 * manager sees under this extension, so it's the default; sniffing the first 512 bytes for a real
 * MPEG-TS header is what overrides that default back to [entry]'s own classification. Every other
 * extension is untouched -- this returns [FileEntry.kind] immediately, no I/O.
 */
suspend fun resolvePreviewKind(entry: FileEntry, resolver: ContentResolver): EntryKind {
    if (FileFormatRegistry.compoundExtension(entry.name) != "ts") return entry.kind
    val header = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(entry.uri)?.use { input ->
                val buffer = ByteArray(512)
                val read = input.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        }.getOrNull()
    }
    return if (header != null && ContentSniffer.isMpegTs(header)) entry.kind else EntryKind.TEXT
}
