import type { FileSource } from "../core/types";

const CHUNK = 4 << 20;

/** Stream-hash a source in a worker with progress and cancel. */
export async function sha256(src: FileSource, onProgress?: (done: number, total: number) => void, signal?: AbortSignal): Promise<string> {
  const worker = new Worker(new URL("../workers/hash.worker.ts", import.meta.url), { type: "module" });
  let inflight = 0;
  let wake: (() => void) | null = null;
  try {
    const result = new Promise<string>((resolve, reject) => {
      worker.onmessage = (e) => {
        if (e.data.type === "done") resolve(e.data.hash);
        else if (e.data.type === "ack") { inflight--; wake?.(); }
      };
      worker.onerror = (e) => reject(new Error(e.message));
    });
    let off = 0;
    while (off < src.size) {
      if (signal?.aborted) throw new DOMException("cancelled", "AbortError");
      const b = await src.read(off, CHUNK);
      if (b.length === 0) break;
      const n = b.length;
      const copy = b.byteOffset === 0 && b.byteLength === b.buffer.byteLength ? b : b.slice();
      worker.postMessage({ type: "chunk", bytes: copy.buffer }, [copy.buffer as ArrayBuffer]);
      off += n;
      inflight++;
      onProgress?.(off, src.size);
      while (inflight >= 2) await new Promise<void>((r) => { wake = r; });
    }
    worker.postMessage({ type: "end" });
    return await result;
  } finally {
    worker.terminate();
  }
}
