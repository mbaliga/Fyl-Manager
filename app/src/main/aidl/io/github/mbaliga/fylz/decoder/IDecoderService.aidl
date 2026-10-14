package io.github.mbaliga.fylz.decoder;

import io.github.mbaliga.fylz.decoder.ArchiveExtractResult;
import io.github.mbaliga.fylz.decoder.ArchiveInspection;
import io.github.mbaliga.fylz.decoder.ArchiveLimits;
import io.github.mbaliga.fylz.decoder.ArchiveWriteOptions;
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult;

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

    // M3.4 (DESIGN-M34-SELECTIVE-EXTRACT.md section 2.3): one pass over the archive writing every
    // selected entry -- `ordinalsBitmap` is a bitmap of header ordinals (bit 8*i+j of byte i), which
    // the service turns into exact ordinal ranges in-process -- as FZX1 frames into `sink`
    // (ExtractFrameReader is the reader). The header pass first runs the selection-scoped size policy
    // under `limits`; a refusal leaves the stream empty. The result is the authority for the outcome
    // (OK, REFUSED, CORRUPT, LIMIT_EXCEEDED, CANCELLED, ...) and carries the entry and byte counts
    // and the ordinal the pass stopped at. Ownership as for listArchive. The caller binds this on
    // the isolated extraction instance (`:decoders:extract`), never the browsing one.
    ArchiveExtractResult extractRanges(in ParcelFileDescriptor archive, in ArchiveLimits limits, in byte[] ordinalsBitmap, in ParcelFileDescriptor sink);

    // M3.5a (DESIGN-M35-CREATE.md section 2.5): one write pass. `input` is the read end of a pipe
    // the client owns, carrying FZW1-framed source bytes; `output` is the write end of a second
    // pipe the client owns, the raw archive stream. The engine pins its own thread's locale to
    // C.UTF-8 for the call and poisons its writer before freeing on any abort, so a failed or
    // cancelled write never quietly ships a well-formed truncated archive. The result is the
    // authority for the outcome (OK, UNSUPPORTED, CORRUPT, PROTOCOL_ERROR, CANCELLED, ...) and
    // carries the engine's own entry/byte counts. Ownership as for listArchive: the service takes
    // ownership of each pfd's dup and closes it when done; the caller's own descriptors are
    // untouched. The caller binds this on the isolated write instance (`:decoders:write`), never
    // the browsing or extraction ones (the concurrency invariant design section 2.3 states).
    ArchiveWriteResult writeArchive(in ParcelFileDescriptor input, in ArchiveWriteOptions options, in ParcelFileDescriptor output);
}
