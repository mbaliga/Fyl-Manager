import { RangeSource } from "./source";
import type { FileSource, HostApi } from "./types";

type LogLevel = "info" | "warn" | "error";
const logTo = (level: LogLevel, msg: string, data?: unknown) =>
  (level === "error" ? console.error : level === "warn" ? console.warn : console.info)(`[fylz-viewer] ${msg}`, data ?? "");

function download(blob: Blob, name: string) {
  const a = document.createElement("a");
  const url = URL.createObjectURL(blob);
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

// ------------------------------------------------------------------ standalone

interface FsHandle {
  name: string;
  createWritable(): Promise<{ write(b: Blob): Promise<void>; close(): Promise<void> }>;
}

export class StandaloneHost implements HostApi {
  readonly kind = "standalone" as const;
  readonly canWrite = true;
  private handle: FsHandle | null = null;
  private name = "untitled";
  /** Set by the session on every open: the current file name and optional FS Access handle. */
  setTarget(name: string, handle?: FsHandle | null) {
    this.name = name;
    this.handle = handle ?? null;
  }
  async save(blob: Blob): Promise<void> {
    if (this.handle) {
      const w = await this.handle.createWritable();
      await w.write(blob);
      await w.close();
      return;
    }
    download(blob, this.name);
  }
  async saveAs(blob: Blob, suggestedName: string): Promise<void> {
    const picker = (window as unknown as { showSaveFilePicker?: (o: unknown) => Promise<FsHandle> }).showSaveFilePicker;
    if (picker && !navigator.webdriver) {
      const h = await picker({ suggestedName });
      const w = await h.createWritable();
      await w.write(blob);
      await w.close();
      this.handle = h;
      this.name = h.name;
      return;
    }
    download(blob, suggestedName);
  }
  log = logTo;
}

// ------------------------------------------------------------------ fylz bridge

/** Wire messages. See docs/HOST_BRIDGE.md. */
export type HostToViewer =
  | { type: "open"; fileId: string; name: string; size: number; mime?: string; lastModified?: number; canWrite: boolean; theme: "light" | "dark" }
  | { type: "chunk"; reqId: number; bytes?: ArrayBuffer; error?: string }
  | { type: "theme"; mode: "light" | "dark" }
  | { type: "saved"; saveId: number; ok: boolean; error?: string }
  | { type: "close" };

export type ViewerToHost =
  | { type: "ready"; protocol: number }
  | { type: "read"; reqId: number; fileId: string; offset: number; length: number }
  | { type: "save"; fileId: string; saveId: number; seq: number; final: boolean; bytes: ArrayBuffer }
  | { type: "saveAs"; fileId: string; saveId: number; name: string; seq: number; final: boolean; bytes: ArrayBuffer }
  | { type: "openExternal"; fileId: string }
  | { type: "dirty"; fileId: string; dirty: boolean }
  | { type: "closed"; fileId: string; ok: boolean }
  | { type: "log"; level: LogLevel; msg: string; data?: unknown };

export const PROTOCOL_VERSION = 1;
export const SAVE_CHUNK = 1 << 20;

export interface Transport {
  send(msg: ViewerToHost): void;
  onMessage(cb: (msg: HostToViewer) => void): void;
}

/**
 * Android transport: `window.fylzBridge` registered with WebViewCompat.addWebMessageListener.
 * JSON control messages are strings; binary payloads are an ArrayBuffer message that follows
 * a JSON header carrying `binary: true`.
 */
export function androidTransport(bridge: {
  postMessage(m: string | ArrayBuffer): void;
  onmessage: ((e: { data: unknown }) => void) | null;
}): Transport {
  return {
    send(msg) {
      if ("bytes" in msg && msg.bytes) {
        const { bytes, ...hdr } = msg;
        bridge.postMessage(JSON.stringify({ ...hdr, binary: true }));
        bridge.postMessage(bytes);
      } else bridge.postMessage(JSON.stringify(msg));
    },
    onMessage(cb) {
      let pending: (Record<string, unknown> & { type: string }) | null = null;
      bridge.onmessage = (e) => {
        const d = e.data;
        if (typeof d === "string") {
          const m = JSON.parse(d);
          if (m.binary) pending = m;
          else cb(m);
        } else if (d instanceof ArrayBuffer && pending) {
          const m = pending;
          pending = null;
          delete m.binary;
          cb({ ...m, bytes: d } as unknown as HostToViewer);
        }
      };
    },
  };
}

/** window.postMessage transport, used by the mock host page (dev/fylz-host.html) in an iframe. */
export function parentTransport(): Transport {
  return {
    send: (msg) => {
      const transfer = "bytes" in msg && msg.bytes ? [msg.bytes] : [];
      window.parent.postMessage({ fylz: 1, ...msg }, "*", transfer);
    },
    onMessage(cb) {
      window.addEventListener("message", (e) => {
        if (e.source === window.parent && e.data && e.data.fylz === 1) cb(e.data as HostToViewer);
      });
    },
  };
}

export class FylzHost implements HostApi {
  readonly kind = "fylz" as const;
  canWrite = false;
  fileId = "";
  private reqId = 1;
  private saveId = 1;
  private reads = new Map<number, { resolve(b: Uint8Array): void; reject(e: Error): void }>();
  private saves = new Map<number, { resolve(): void; reject(e: Error): void }>();
  onOpen: (src: FileSource, opts: { theme: "light" | "dark"; canWrite: boolean }) => void = () => {};
  onClose: () => void = () => {};
  onTheme: (t: "light" | "dark") => void = () => {};

  constructor(private readonly t: Transport) {
    t.onMessage((m) => this.handle(m));
  }

  start() {
    this.t.send({ type: "ready", protocol: PROTOCOL_VERSION });
  }

  private handle(m: HostToViewer) {
    switch (m.type) {
      case "open": {
        this.fileId = m.fileId;
        this.canWrite = m.canWrite;
        const src = new RangeSource(
          { name: m.name, size: m.size, lastModified: m.lastModified, mimeHint: m.mime },
          (offset, length) => this.read(offset, length),
        );
        this.onOpen(src, { theme: m.theme, canWrite: m.canWrite });
        break;
      }
      case "chunk": {
        const r = this.reads.get(m.reqId);
        if (!r) return;
        this.reads.delete(m.reqId);
        if (m.error || !m.bytes) r.reject(new Error(m.error ?? "read failed"));
        else r.resolve(new Uint8Array(m.bytes));
        break;
      }
      case "saved": {
        const s = this.saves.get(m.saveId);
        if (!s) return;
        this.saves.delete(m.saveId);
        if (m.ok) s.resolve();
        else s.reject(new Error(m.error ?? "host refused to save"));
        break;
      }
      case "theme":
        this.onTheme(m.mode);
        break;
      case "close":
        this.onClose();
        break;
    }
  }

  read(offset: number, length: number): Promise<Uint8Array> {
    const reqId = this.reqId++;
    return new Promise((resolve, reject) => {
      this.reads.set(reqId, { resolve, reject });
      this.t.send({ type: "read", reqId, fileId: this.fileId, offset, length });
    });
  }

  private async upload(blob: Blob, mk: (saveId: number, seq: number, final: boolean, bytes: ArrayBuffer) => ViewerToHost): Promise<void> {
    const saveId = this.saveId++;
    const done = new Promise<void>((resolve, reject) => this.saves.set(saveId, { resolve, reject }));
    done.catch(() => {});
    const total = Math.max(1, Math.ceil(blob.size / SAVE_CHUNK));
    for (let seq = 0; seq < total; seq++) {
      const bytes = await blob.slice(seq * SAVE_CHUNK, (seq + 1) * SAVE_CHUNK).arrayBuffer();
      this.t.send(mk(saveId, seq, seq === total - 1, bytes));
    }
    return done;
  }

  save(blob: Blob): Promise<void> {
    return this.upload(blob, (saveId, seq, final, bytes) => ({ type: "save", fileId: this.fileId, saveId, seq, final, bytes }));
  }
  saveAs(blob: Blob, name: string): Promise<void> {
    return this.upload(blob, (saveId, seq, final, bytes) => ({ type: "saveAs", fileId: this.fileId, saveId, name, seq, final, bytes }));
  }
  openExternal(): void {
    this.t.send({ type: "openExternal", fileId: this.fileId });
  }
  sendClosed(ok: boolean) {
    this.t.send({ type: "closed", fileId: this.fileId, ok });
  }
  sendDirty(dirty: boolean) {
    this.t.send({ type: "dirty", fileId: this.fileId, dirty });
  }
  log(level: LogLevel, msg: string, data?: unknown) {
    logTo(level, msg, data);
    try {
      this.t.send({ type: "log", level, msg, data: data === undefined ? undefined : JSON.parse(JSON.stringify(data)) });
    } catch { /* unserialisable data */ }
  }
}

/** Decide which host we are running under. */
export function detectHost(): StandaloneHost | FylzHost {
  const w = window as unknown as { fylzBridge?: Parameters<typeof androidTransport>[0] };
  if (w.fylzBridge) return new FylzHost(androidTransport(w.fylzBridge));
  const q = new URLSearchParams(location.search);
  if (q.get("host") === "fylz" && window.parent !== window) return new FylzHost(parentTransport());
  return new StandaloneHost();
}
