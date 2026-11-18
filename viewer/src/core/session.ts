import { BlobSource } from "./source";
import { detect, detectFromHead, sameFamily, HEAD_SIZE } from "./detect";
import { effectiveLevel, type Candidate, type Registry } from "./registry";
import { FylzHost, StandaloneHost } from "./host";
import { createStore } from "../lib/store";
import type { Command, FileInfo, FileSource, FormatPlugin, HostApi, Level, ViewHandle } from "./types";

export interface SessionState {
  status: "idle" | "loading" | "ready" | "error";
  info: FileInfo | null;
  source: FileSource | null;
  candidates: Candidate[];
  pluginId: string | null;
  mode: "view" | "edit";
  level: Level;
  dirty: boolean;
  saving: boolean;
  commands: Command[];
  canEdit: boolean;
  depth: number;
  notice: string | null;
  error: string | null;
  theme: "light" | "dark";
}

interface Frame { source: FileSource; handle?: unknown }

export interface OpenOptions { pluginId?: string; wantEdit?: boolean; fsHandle?: unknown; nested?: boolean }

export class Session {
  readonly store = createStore<SessionState>({
    status: "idle", info: null, source: null, candidates: [], pluginId: null, mode: "view", level: 0,
    dirty: false, saving: false, commands: [], canEdit: false, depth: 0, notice: null, error: null,
    theme: "light",
  });
  private stage: HTMLElement | null = null;
  private handle: ViewHandle | null = null;
  private abort: AbortController | null = null;
  private gen = 0;
  private head: Uint8Array = new Uint8Array(0);
  private stack: Frame[] = [];
  /** Shell supplies this: resolves true if the user agrees to discard changes. */
  confirmDiscard: () => Promise<boolean> = async () => true;

  constructor(readonly registry: Registry, readonly host: HostApi) {}

  attach(stage: HTMLElement) {
    this.stage = stage;
  }

  get state(): SessionState {
    return this.store.get();
  }

  setTheme(theme: "light" | "dark") {
    this.store.set({ theme });
    document.documentElement.dataset.theme = theme;
    this.handle?.setTheme?.(theme);
  }

  async open(source: FileSource, opts: OpenOptions = {}): Promise<void> {
    if (this.state.dirty && !(await this.confirmDiscard())) return;
    if (opts.nested && this.state.source) this.stack.push({ source: this.state.source });
    else if (!opts.nested && opts.pluginId === undefined) this.stack = [];
    await this.load(source, opts);
  }

  async back(): Promise<void> {
    const f = this.stack[this.stack.length - 1];
    if (!f) return;
    if (this.state.dirty && !(await this.confirmDiscard())) return;
    this.stack.pop();
    await this.load(f.source, {});
  }

  private async load(source: FileSource, opts: OpenOptions): Promise<void> {
    const gen = ++this.gen;
    this.teardown();
    this.store.set({ status: "loading", error: null, notice: null, dirty: false, commands: [], depth: this.stack.length, source });
    if (this.host instanceof StandaloneHost) this.host.setTarget(source.name, opts.fsHandle as never);
    try {
      const { info, head } = await detect(source);
      if (gen !== this.gen) return;
      this.head = head;
      const candidates = this.registry.openAs(info, head);
      this.store.set({ info, candidates });
      let chosen: Candidate | undefined = opts.pluginId ? candidates.find((c) => c.plugin.id === opts.pluginId) : candidates[0];
      if (!chosen) chosen = candidates[0];
      if (!chosen) throw new Error("No viewer available");
      await this.mount(chosen, opts.wantEdit ?? false, gen);
    } catch (e) {
      if (gen !== this.gen) return;
      this.host.log("error", "open failed", String(e));
      this.store.set({ status: "error", error: e instanceof Error ? e.message : String(e) });
    }
  }

  async switchPlugin(pluginId: string, wantEdit = false): Promise<void> {
    const { source, candidates } = this.state;
    if (!source) return;
    if (this.state.dirty && !(await this.confirmDiscard())) return;
    const c = candidates.find((x) => x.plugin.id === pluginId);
    if (!c) return;
    const gen = ++this.gen;
    this.teardown();
    this.store.set({ status: "loading", dirty: false, commands: [], notice: null });
    await this.mount(c, wantEdit, gen);
  }

  async setEditMode(edit: boolean): Promise<void> {
    if (!this.state.pluginId) return;
    if (!edit && this.state.dirty && !(await this.confirmDiscard())) return;
    await this.switchPluginForce(this.state.pluginId, edit);
  }

  private async switchPluginForce(id: string, edit: boolean) {
    const c = this.state.candidates.find((x) => x.plugin.id === id);
    if (!c) return;
    const gen = ++this.gen;
    this.teardown();
    this.store.set({ status: "loading", dirty: false, commands: [] });
    await this.mount(c, edit, gen);
  }

  private async mount(cand: Candidate, wantEdit: boolean, gen: number): Promise<void> {
    const { source, info } = this.state;
    if (!source || !info || !this.stage) throw new Error("session not attached");
    let plugin: FormatPlugin = cand.plugin;
    let match = cand.match;
    let { level, mode, tooBigToView } = effectiveLevel(plugin, match, info.size, this.host.canWrite, wantEdit);
    let notice: string | null = null;
    if (tooBigToView) {
      const hex = this.registry.get("hex");
      if (hex && hex !== plugin) {
        notice = `${info.name} is larger than ${plugin.name} can open (${plugin.sizeLimits?.view} bytes) — showing Hex.`;
        plugin = hex;
        match = { confidence: 1, level: 1, label: "Hex" };
        level = 1; mode = "view";
      }
    }
    const canEdit = plugin.maxLevel >= 4 && effectiveLevel(plugin, match, info.size, this.host.canWrite, true).mode === "edit";
    this.abort = new AbortController();
    this.stage.replaceChildren();
    const container = document.createElement("div");
    container.className = "fv-plugin";
    container.dataset.plugin = plugin.id;
    this.stage.appendChild(container);
    try {
      const mod = await plugin.load();
      if (gen !== this.gen) return;
      const handle = await mod.mount({
        file: source, info, container, mode, level, host: this.host, signal: this.abort.signal, theme: this.state.theme,
        openNested: (s) => void this.open(s, { nested: true }),
        openAs: (id) => void this.switchPlugin(id),
      });
      if (gen !== this.gen) { handle.unmount(); return; }
      this.handle = handle;
      handle.onDirtyChange((d) => {
        this.store.set({ dirty: d });
        if (this.host instanceof FylzHost) this.host.sendDirty(d);
      });
      handle.onCommandsChange?.(() => this.store.set({ commands: handle.commands?.() ?? [] }));
      this.store.set({
        status: "ready", pluginId: plugin.id, mode, level, canEdit, notice,
        commands: handle.commands?.() ?? [], dirty: handle.isDirty(), error: null,
      });
    } catch (e) {
      if (gen !== this.gen) return;
      this.host.log("error", `plugin ${plugin.id} failed`, String(e));
      const hex = this.registry.get("hex");
      if (hex && plugin.id !== "hex") {
        const msg = e instanceof Error ? e.message : String(e);
        this.stage.replaceChildren();
        this.abort?.abort();
        const c2 = this.state.candidates.find((x) => x.plugin.id === "hex") ?? { plugin: hex, match: { confidence: 1, level: 1 as Level, label: "Hex" } };
        await this.mount(c2, false, gen);
        this.store.set({ notice: `${plugin.name} could not open this file (${msg}). Showing Hex instead.` });
      } else throw e;
    }
  }

  private teardown() {
    this.abort?.abort();
    this.abort = null;
    try { this.handle?.unmount(); } catch (e) { this.host.log("warn", "unmount failed", String(e)); }
    this.handle = null;
    this.stage?.replaceChildren();
  }

  /** Run a Command by id (toolbar). */
  async runCommand(id: string): Promise<void> {
    const c = this.handle?.commands?.().find((x) => x.id === id);
    if (!c) return;
    try { await c.run(); } catch (e) { this.fail(e); }
    this.store.set({ commands: this.handle?.commands?.() ?? [] });
  }

  private fail(e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    this.host.log("error", msg);
    this.store.set({ error: msg, saving: false });
  }

  dismissError() {
    this.store.set({ error: null, notice: null });
  }

  /** Serialize and sanity-check; returns the blob or null (error already surfaced). */
  private async produce(): Promise<Blob | null> {
    const h = this.handle;
    const { info } = this.state;
    if (!h?.serialize || !info) { this.fail(new Error("This view cannot save.")); return null; }
    const blob = await h.serialize();
    if (blob.size === 0 && info.size > 0) throw new Error("Refusing to save: output is empty.");
    const bs = new BlobSource(blob, info.name);
    const head = await bs.read(0, HEAD_SIZE);
    const out = await detectFromHead(bs, head);
    if (!sameFamily(info, out)) {
      throw new Error(`Refusing to save: output type changed (${info.detected ?? "unknown"} → ${out.detected ?? "unknown"}).`);
    }
    return blob;
  }

  async save(): Promise<boolean> {
    if (this.state.saving) return false;
    this.store.set({ saving: true, error: null });
    try {
      const blob = await this.produce();
      if (!blob) return false;
      await this.host.save(blob);
      this.handle?.markSaved?.();
      this.store.set({ saving: false, dirty: this.handle?.isDirty() ?? false, notice: "Saved." });
      return true;
    } catch (e) {
      this.fail(e);
      return false;
    }
  }

  async saveAs(): Promise<boolean> {
    if (this.state.saving || !this.host.saveAs) return false;
    this.store.set({ saving: true, error: null });
    try {
      const blob = await this.produce();
      if (!blob) return false;
      await this.host.saveAs(blob, this.state.info?.name ?? "file");
      this.handle?.markSaved?.();
      this.store.set({ saving: false, dirty: this.handle?.isDirty() ?? false, notice: "Saved." });
      return true;
    } catch (e) {
      if ((e as Error).name === "AbortError") { this.store.set({ saving: false }); return false; }
      this.fail(e);
      return false;
    }
  }

  /** Close the current file. Returns false if the user kept the file open. */
  async close(): Promise<boolean> {
    if (this.state.dirty && !(await this.confirmDiscard())) return false;
    ++this.gen;
    this.teardown();
    this.stack = [];
    this.store.set({ status: "idle", info: null, source: null, candidates: [], pluginId: null, dirty: false, commands: [], depth: 0, notice: null, error: null });
    return true;
  }

  getHead(): Uint8Array {
    return this.head;
  }
}
