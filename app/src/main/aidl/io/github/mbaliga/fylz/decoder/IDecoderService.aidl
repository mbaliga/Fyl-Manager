package io.github.mbaliga.fylz.decoder;

import io.github.mbaliga.fylz.decoder.ArchiveInspection;
import io.github.mbaliga.fylz.decoder.ArchiveLimits;

// A liveness check, content-type sniffing (M2.5's fylz-sniff crate) and archive inspection
// (M3.2's fylz-archive crate), over the isolation/timeout/crash-recovery contract M2.4 built --
// docs/agent/MASTER_PLAN.md section 4.4. Entry-level decoding (browsing, previews, thumbnails)
// lands on this interface in later milestones (M3.3, M6+).
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
}
