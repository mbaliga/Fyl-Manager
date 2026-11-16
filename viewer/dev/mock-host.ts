/**
 * Simulates the Android side of the Fylz bridge (docs/HOST_BRIDGE.md) so the host protocol can be
 * developed and e2e-tested without a device. Exposes `window.mockHost` for tests.
 */
interface Save { name?: string; kind: "save" | "saveAs"; bytes: Uint8Array }
interface Sent { type: string; [k: string]: unknown }

const frame = document.getElementById("viewer") as HTMLIFrameElement;
const logEl = document.getElementById("log")!;
const latencyEl = document.getElementById("latency") as HTMLInputElement;
const canWriteEl = document.getElementById("canwrite") as HTMLInputElement;

let file: { id: string; name: string; bytes: Uint8Array; mime?: string; lastModified?: number } | null = null;
let theme: "light" | "dark" = "light";
let ready = false;
let failNext: string | null = null;
const saves: Save[] = [];
const events: Sent[] = [];
const readLog: { offset: number; length: number }[] = [];
const partial = new Map<number, Uint8Array[]>();

const post = (msg: Sent, transfer: Transferable[] = []) => frame.contentWindow!.postMessage({ fylz: 1, ...msg }, "*", transfer);
const log = (s: string) => { logEl.textContent = (logEl.textContent + "\n" + s).split("\n").slice(-30).join("\n"); };

function sendOpen() {
  if (!file || !ready) return;
  post({ type: "open", fileId: file.id, name: file.name, size: file.bytes.length, mime: file.mime, lastModified: file.lastModified, canWrite: canWriteEl.checked, theme });
}

window.addEventListener("message", (e) => {
  if (e.source !== frame.contentWindow || !e.data || e.data.fylz !== 1) return;
  const m = e.data as Sent & { reqId: number; offset: number; length: number; saveId: number; seq: number; final: boolean; bytes: ArrayBuffer; name?: string };
  events.push({ ...m, bytes: undefined } as Sent);
  log(`← ${m.type}`);
  switch (m.type) {
    case "ready": ready = true; sendOpen(); break;
    case "read": {
      readLog.push({ offset: m.offset, length: m.length });
      const f = file;
      setTimeout(() => {
        if (!f) return post({ type: "chunk", reqId: m.reqId, error: "no file" });
        const slice = f.bytes.slice(m.offset, m.offset + m.length);
        post({ type: "chunk", reqId: m.reqId, bytes: slice.buffer }, [slice.buffer]);
      }, Number(latencyEl.value) || 0);
      break;
    }
    case "save":
    case "saveAs": {
      const parts = partial.get(m.saveId) ?? [];
      parts[m.seq] = new Uint8Array(m.bytes);
      partial.set(m.saveId, parts);
      if (m.final) {
        partial.delete(m.saveId);
        if (failNext) { post({ type: "saved", saveId: m.saveId, ok: false, error: failNext }); failNext = null; break; }
        const total = parts.reduce((a, p) => a + p.length, 0);
        const all = new Uint8Array(total);
        let o = 0;
        for (const p of parts) { all.set(p, o); o += p.length; }
        saves.push({ kind: m.type, name: m.name, bytes: all });
        post({ type: "saved", saveId: m.saveId, ok: true });
      }
      break;
    }
  }
});

document.getElementById("pick")!.addEventListener("change", async (e) => {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) api.openFile(f.name, new Uint8Array(await f.arrayBuffer()), { lastModified: f.lastModified, mime: f.type });
});
document.getElementById("close")!.addEventListener("click", () => post({ type: "close" }));
document.getElementById("theme")!.addEventListener("click", () => api.setTheme(theme === "light" ? "dark" : "light"));

const api = {
  openFile(name: string, bytes: Uint8Array | number[], opts: { mime?: string; lastModified?: number; canWrite?: boolean; latency?: number } = {}) {
    file = { id: `file-${Date.now()}`, name, bytes: bytes instanceof Uint8Array ? bytes : Uint8Array.from(bytes), mime: opts.mime, lastModified: opts.lastModified };
    if (opts.canWrite !== undefined) canWriteEl.checked = opts.canWrite;
    if (opts.latency !== undefined) latencyEl.value = String(opts.latency);
    sendOpen();
  },
  setTheme(t: "light" | "dark") { theme = t; post({ type: "theme", mode: t }); },
  sendClose() { post({ type: "close" }); },
  failNextSave(msg: string) { failNext = msg; },
  saves, events, readLog,
  get ready() { return ready; },
};
(window as unknown as { mockHost: typeof api }).mockHost = api;
