/** Capability ladder. See README. */
export type Level = 0 | 1 | 2 | 3 | 4 | 5;

export const LEVEL_NAMES: Record<Level, string> = {
  0: "Identify",
  1: "Raw",
  2: "Structure",
  3: "Render",
  4: "Light edit",
  5: "Full edit",
};

export interface FileSource {
  name: string;
  size: number;
  lastModified?: number;
  mimeHint?: string;
  /** Range read. Required for big files. */
  read(offset: number, length: number): Promise<Uint8Array>;
  /** Whole file. Plugins must check size first. */
  blob(): Promise<Blob>;
  stream(): ReadableStream<Uint8Array>;
}

export interface FileInfo {
  name: string;
  ext: string;
  size: number;
  mimeHint?: string;
  /** Detected MIME type, e.g. "application/pdf". */
  detected?: string;
  /** Canonical extension of the detected type (extension to the spec, see DECISIONS.md). */
  detectedExt?: string;
  isText: boolean;
  encoding?: string;
  lastModified?: number;
}

export interface MatchResult {
  /** 0–1 */
  confidence: number;
  level: Level;
  label: string;
}

export interface FormatPlugin {
  id: string;
  name: string;
  maxLevel: Level;
  /** Bytes. Above them the session degrades to a lower level. */
  sizeLimits?: { view?: number; edit?: number };
  /** Optional flag: LGPL or otherwise flagged component (see LICENSES.md). */
  flagged?: string;
  /** Mime/extension lists used only by scripts/format-table.ts. */
  formats?: string[];
  match(info: FileInfo, head: Uint8Array): MatchResult | null;
  load(): Promise<PluginModule>;
}

export interface PluginModule {
  mount(ctx: ViewContext): Promise<ViewHandle>;
}

export interface ViewContext {
  file: FileSource;
  info: FileInfo;
  container: HTMLElement;
  mode: "view" | "edit";
  /** Effective level after size limits and host permissions. */
  level: Level;
  host: HostApi;
  signal: AbortSignal;
  theme: "light" | "dark";
  /** Ask the shell to open a nested file (archive entry, attachment...). */
  openNested?(source: FileSource): void;
  /** Ask the shell to re-open the current file with another plugin id. */
  openAs?(pluginId: string): void;
}

export interface ViewHandle {
  isDirty(): boolean;
  /** Called by the session after a successful save so the plugin can reset its baseline (extension, see DECISIONS.md). */
  markSaved?(): void;
  onDirtyChange(cb: (dirty: boolean) => void): void;
  /** Required if level >= 4. */
  serialize?(): Promise<Blob>;
  commands?(): Command[];
  /** Plugins may announce changes to commands() (enabled state etc.). */
  onCommandsChange?(cb: () => void): void;
  setTheme?(theme: "light" | "dark"): void;
  unmount(): void;
}

export interface Command {
  id: string;
  label: string;
  icon?: string;
  run(): void | Promise<void>;
  enabled?(): boolean;
}

export interface HostApi {
  kind: "standalone" | "fylz";
  canWrite: boolean;
  /** Host handles versioning (Fylz Safety History). */
  save(blob: Blob): Promise<void>;
  saveAs?(blob: Blob, suggestedName: string): Promise<void>;
  openExternal?(): void;
  log(level: "info" | "warn" | "error", msg: string, data?: unknown): void;
}

/** Thrown by plugins to surface a clear, user-facing message. */
export class ViewerError extends Error {
  constructor(message: string, public readonly hint?: string) {
    super(message);
    this.name = "ViewerError";
  }
}
