package io.github.mbaliga.fylz.decoder;

// A liveness check and content-type sniffing (M2.5's fylz-sniff crate, over the isolation/
// timeout/crash-recovery contract M2.4 built -- docs/agent/MASTER_PLAN.md section 4.4). Real
// format DECODING (structure, thumbnails) lands on this interface in later milestones (M6+).
interface IDecoderService {
    boolean ping();

    // pfd is read-only and caller-owned; the service must not write through it or assume it
    // stays valid after this call returns.
    String sniff(in ParcelFileDescriptor pfd);
}
