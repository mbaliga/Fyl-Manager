import type { FileInfo, FormatPlugin, Level, MatchResult } from "./types";

export interface Candidate {
  plugin: FormatPlugin;
  match: MatchResult;
}

export class Registry {
  private plugins: FormatPlugin[] = [];

  register(p: FormatPlugin): this {
    if (this.plugins.some((x) => x.id === p.id)) throw new Error(`duplicate plugin id: ${p.id}`);
    this.plugins.push(p);
    return this;
  }

  all(): readonly FormatPlugin[] {
    return this.plugins;
  }

  get(id: string): FormatPlugin | undefined {
    return this.plugins.find((p) => p.id === id);
  }

  /**
   * Run every plugin's match(). Sorted: confidence desc, then level desc, then registration order.
   * Level 0/1 universal fallbacks (info, hex) are always present as the tail.
   */
  candidates(info: FileInfo, head: Uint8Array): Candidate[] {
    const out: Candidate[] = [];
    for (const plugin of this.plugins) {
      let m: MatchResult | null = null;
      try {
        m = plugin.match(info, head);
      } catch {
        m = null;
      }
      if (m && m.confidence > 0) out.push({ plugin, match: m });
    }
    const order = new Map(this.plugins.map((p, i) => [p.id, i]));
    out.sort(
      (a, b) =>
        b.match.confidence - a.match.confidence ||
        b.match.level - a.match.level ||
        order.get(a.plugin.id)! - order.get(b.plugin.id)!,
    );
    return out;
  }

  /** Best candidate that is not the pure identify panel (info is level 0). */
  best(info: FileInfo, head: Uint8Array): Candidate | null {
    return this.candidates(info, head).find((c) => c.plugin.id !== "info") ?? null;
  }

  /**
   * "Open as…": all candidates plus Hex and Text always, deduplicated, with the primary first.
   */
  openAs(info: FileInfo, head: Uint8Array): Candidate[] {
    const list = this.candidates(info, head).filter((c) => c.plugin.id !== "info");
    for (const id of ["text", "hex"]) {
      if (!list.some((c) => c.plugin.id === id)) {
        const plugin = this.get(id);
        if (plugin) list.push({ plugin, match: { confidence: 0.001, level: (id === "hex" ? 1 : info.isText ? plugin.maxLevel : 3) as Level, label: plugin.name } });
      }
    }
    return list;
  }
}

/** Compute effective level and mode for a candidate given size limits and host permissions. */
export function effectiveLevel(
  plugin: FormatPlugin,
  match: MatchResult,
  size: number,
  canWrite: boolean,
  wantEdit: boolean,
): { level: Level; mode: "view" | "edit"; tooBigToView: boolean } {
  const lim = plugin.sizeLimits;
  const tooBigToView = lim?.view !== undefined && size > lim.view;
  let level = Math.min(match.level, plugin.maxLevel) as Level;
  if (tooBigToView) return { level: 1, mode: "view", tooBigToView };
  const tooBigToEdit = lim?.edit !== undefined && size > lim.edit;
  if (level >= 4 && (tooBigToEdit || !canWrite)) level = 3;
  const mode = wantEdit && level >= 4 ? "edit" : "view";
  return { level, mode, tooBigToView };
}
