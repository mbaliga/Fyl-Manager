import { render } from "preact";
import "./shell/styles.css";
import { App } from "./shell/App";
import { Session } from "./core/session";
import { BlobSource } from "./core/source";
import { FylzHost, detectHost, StandaloneHost } from "./core/host";
import { createRegistry } from "./plugins";

const host = detectHost();
const session = new Session(createRegistry(), host);
session.setTheme(matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");

// Test/dev hook (no effect on behaviour): lets e2e tests inspect state.
(window as unknown as { __fv: unknown }).__fv = { session };

async function pick() {
  const w = window as unknown as { showOpenFilePicker?: (o?: unknown) => Promise<{ getFile(): Promise<File> }[]> };
  if (w.showOpenFilePicker && !navigator.webdriver) {
    try {
      const [h] = await w.showOpenFilePicker();
      if (h) return void (await session.open(new BlobSource(await h.getFile()), { fsHandle: h }));
    } catch (e) {
      if ((e as Error).name === "AbortError") return;
    }
  }
  document.querySelector<HTMLInputElement>('[data-testid="file-input"]')?.click();
}

render(<App session={session} onPick={() => void pick()} />, document.getElementById("app")!);

if (host instanceof FylzHost) {
  host.onOpen = (src, o) => { session.setTheme(o.theme); void session.open(src, {}); };
  host.onTheme = (t) => session.setTheme(t);
  host.onClose = () => void session.close().then((ok) => host.sendClosed(ok));
  host.start();
} else if (host instanceof StandaloneHost) {
  // PWA file handling (launchQueue) — Phase 4 wires the service worker; this is harmless without it.
  const lq = (window as unknown as { launchQueue?: { setConsumer(cb: (p: { files: { getFile(): Promise<File> }[] }) => void): void } }).launchQueue;
  lq?.setConsumer(async (p) => { const h = p.files[0]; if (h) await session.open(new BlobSource(await h.getFile()), { fsHandle: h }); });
}
