package io.github.mbaliga.fylz.decoder;

// M2.4 trivial skeleton: a liveness check and a content-sniff stub. Real format decoding lands
// on this interface in later milestones (M6+); the isolation/timeout/crash-recovery contract
// (docs/agent/MASTER_PLAN.md section 4.4) is what this milestone actually proves out.
interface IDecoderService {
    boolean ping();

    // pfd is read-only and caller-owned; the service must not write through it or assume it
    // stays valid after this call returns.
    String sniff(in ParcelFileDescriptor pfd);
}
