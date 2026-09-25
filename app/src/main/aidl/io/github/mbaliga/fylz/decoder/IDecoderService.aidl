package io.github.mbaliga.fylz.decoder;

import io.github.mbaliga.fylz.decoder.ArchiveExtractResult;
import io.github.mbaliga.fylz.decoder.ArchiveInspection;
import io.github.mbaliga.fylz.decoder.ArchiveLimits;

// A liveness check, content-type sniffing (M2.5's fylz-sniff crate), archive inspection (M3.2's
// fylz-archive crate) and archive browsing (M3.3: the full listing and single entries, streamed
// through pipes the client owns), over the isolation/timeout/crash-recovery contract M2.4 built --
// docs/agent/MASTER_PLAN.md section 4.4. Previews and thumbnails land on this interface in later
// milestones (M6+).
interface IDecoderService {
    boolean ping();

    // pfd is read-only and caller-owned; the service must not write through it or assume it
    // stays valid after this call returns.
    String sniff(in ParcelFileDescriptor pfd);

    // `archive` is read-only, caller-owned, and seekable (the client guarantees that -- the app's
    // ArchiveSource stages non-seekable input to cache first, DESIGN-M32-SEEKABLE-PFD.md section
    // 2.3); it is not valid after this call returns. The result carries the archive's structure
    // and at most `maxRows` entries (MASTER_PLAN section 4.4: "structure as Parcelables"), or an
    // outcome other than OK with the engine's own message -- never an exception across Binder.
    ArchiveInspection inspectArchive(in ParcelFileDescriptor archive, in ArchiveLimits limits, int maxRows);

    // `archive` is caller-owned and seekable (ArchiveSource guarantees it). `sink` is the write end
    // of a pipe the client created; the service takes ownership of its dup and closes it when done.
    // The full entry table is written into `sink` in the ArchiveListingCodec format, one record
    // per header as it is read; the summary comes back with `rows` empty (and `partial = true` if
    // the pass stopped on a damaged header after some entries). DESIGN-M33-ARCHIVE-BROWSING.md
    // section 2.2.
    ArchiveInspection listArchive(in ParcelFileDescriptor archive, in ArchiveLimits limits, in ParcelFileDescriptor sink);

    // Streams the bytes of the entry at raw header `ordinal` (whose path must equal `expectedPath`
    // byte for byte) into `sink`, under the extraction caps in `limits`. Stops reading the archive
    // as soon as the entry is written. Ownership as for listArchive.
    ArchiveExtractResult extractEntry(in ParcelFileDescriptor archive, int ordinal, String expectedPath, in ArchiveLimits limits, in ParcelFileDescriptor sink);
}
