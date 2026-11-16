import { el } from "../../lib/format";
import { lengthKm, parseGeoJson, parseGpx, parseKml, project, type Shape } from "./parse";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const NS = "http://www.w3.org/2000/svg";
const COLORS = ["var(--fv-accent)", "var(--fv-syn-string)", "var(--fv-syn-number)", "var(--fv-syn-keyword)", "var(--fv-syn-type)"];

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const text = await (await ctx.file.blob()).text();
    const shapes: Shape[] = ctx.info.ext === "gpx" ? parseGpx(text) : ctx.info.ext === "kml" ? parseKml(text) : parseGeoJson(text);
    const W = 800, H = 600;
    const { fn, bbox } = project(shapes, W, H);
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    svg.setAttribute("class", "fv-geo-svg");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Geographic plot");
    svg.dataset.testid = "geo-svg";
    const groups = [...new Set(shapes.map((s) => s.group))];
    const mk = <K extends keyof SVGElementTagNameMap>(t: K, a: Record<string, string>) => { const e = document.createElementNS(NS, t); for (const [k, v] of Object.entries(a)) e.setAttribute(k, v); return e; };
    for (const s of shapes) {
      const color = COLORS[groups.indexOf(s.group) % COLORS.length]!;
      const p = s.pts.filter((q) => isFinite(q[0]) && isFinite(q[1])).map(fn);
      let node: SVGElement;
      if (s.kind === "point") node = mk("circle", { cx: String(p[0]?.[0] ?? 0), cy: String(p[0]?.[1] ?? 0), r: "5", fill: color });
      else if (s.kind === "line") node = mk("polyline", { points: p.map((q) => q.join(",")).join(" "), fill: "none", stroke: color, "stroke-width": "2", "stroke-linejoin": "round" });
      else node = mk("polygon", { points: p.map((q) => q.join(",")).join(" "), fill: color, "fill-opacity": "0.25", stroke: color, "stroke-width": "2" });
      if (s.name) { const t = document.createElementNS(NS, "title"); t.textContent = s.name; node.append(t); }
      svg.append(node);
    }
    const counts = groups.map((g) => `${shapes.filter((s) => s.group === g).length} ${g}`).join(", ");
    const km = shapes.filter((s) => s.kind === "line").reduce((a, s) => a + lengthKm(s.pts), 0);
    const root = el("div", { class: "fv-geo" });
    const side = el("aside", { class: "fv-geo-side", "data-testid": "geo-info" },
      el("strong", { text: `${shapes.length} shapes` }), el("div", { text: counts || "nothing to plot" }),
      ...(km > 0 ? [el("div", { text: `Path length ≈ ${km.toFixed(2)} km` })] : []),
      ...(bbox ? [el("div", { class: "fv-muted", text: `lon ${bbox.minX.toFixed(4)}…${bbox.maxX.toFixed(4)} · lat ${bbox.minY.toFixed(4)}…${bbox.maxY.toFixed(4)}` })] : []),
      el("div", { class: "fv-muted", text: "Offline plot — no map tiles." }),
      el("ul", {}, ...shapes.filter((s) => s.name).slice(0, 200).map((s) => el("li", { text: s.name! }))));
    root.append(el("div", { class: "fv-geo-stage" }, svg), side);
    ctx.container.append(root);
    return { isDirty: () => false, onDirtyChange: () => {}, unmount: () => root.remove() };
  },
};
export default mod;
