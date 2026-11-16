export type Pt = [number, number]; // [lon, lat]
export interface Shape { kind: "point" | "line" | "poly"; pts: Pt[]; name?: string; group: string }

function geom(g: any, name: string | undefined, out: Shape[]) { // eslint-disable-line @typescript-eslint/no-explicit-any
  if (!g || typeof g !== "object") return;
  const c = g.coordinates;
  switch (g.type) {
    case "Point": out.push({ kind: "point", pts: [[c[0], c[1]]], name, group: "points" }); break;
    case "MultiPoint": for (const p of c) out.push({ kind: "point", pts: [[p[0], p[1]]], name, group: "points" }); break;
    case "LineString": out.push({ kind: "line", pts: c.map((p: number[]) => [p[0], p[1]]), name, group: "lines" }); break;
    case "MultiLineString": for (const l of c) out.push({ kind: "line", pts: l.map((p: number[]) => [p[0], p[1]]), name, group: "lines" }); break;
    case "Polygon": for (const r of c.slice(0, 1)) out.push({ kind: "poly", pts: r.map((p: number[]) => [p[0], p[1]]), name, group: "polygons" }); break;
    case "MultiPolygon": for (const poly of c) out.push({ kind: "poly", pts: poly[0].map((p: number[]) => [p[0], p[1]]), name, group: "polygons" }); break;
    case "GeometryCollection": for (const x of g.geometries ?? []) geom(x, name, out); break;
  }
}

export function parseGeoJson(text: string): Shape[] {
  const j = JSON.parse(text);
  const out: Shape[] = [];
  const feat = (f: any) => geom(f.geometry, f.properties?.name ?? f.properties?.title ?? f.id?.toString(), out); // eslint-disable-line @typescript-eslint/no-explicit-any
  if (j.type === "FeatureCollection") (j.features ?? []).forEach(feat);
  else if (j.type === "Feature") feat(j);
  else geom(j, undefined, out);
  return out;
}

const num = (s: string | null) => Number(s);
export function parseGpx(text: string): Shape[] {
  const d = new DOMParser().parseFromString(text, "application/xml");
  if (d.querySelector("parsererror")) throw new Error("Invalid GPX XML");
  const out: Shape[] = [];
  d.querySelectorAll("wpt").forEach((w) => out.push({ kind: "point", pts: [[num(w.getAttribute("lon")), num(w.getAttribute("lat"))]], name: w.querySelector("name")?.textContent ?? undefined, group: "waypoints" }));
  d.querySelectorAll("trk").forEach((t) => t.querySelectorAll("trkseg").forEach((s) => out.push({ kind: "line", pts: Array.from(s.querySelectorAll("trkpt")).map((p) => [num(p.getAttribute("lon")), num(p.getAttribute("lat"))] as Pt), name: t.querySelector("name")?.textContent ?? undefined, group: "tracks" })));
  d.querySelectorAll("rte").forEach((r) => out.push({ kind: "line", pts: Array.from(r.querySelectorAll("rtept")).map((p) => [num(p.getAttribute("lon")), num(p.getAttribute("lat"))] as Pt), name: r.querySelector("name")?.textContent ?? undefined, group: "routes" }));
  return out;
}

const coords = (s: string): Pt[] => s.trim().split(/\s+/).map((t) => t.split(",").map(Number)).filter((a) => a.length >= 2 && a.every((n) => Number.isFinite(n))).map((a) => [a[0]!, a[1]!] as Pt);
export function parseKml(text: string): Shape[] {
  const d = new DOMParser().parseFromString(text, "application/xml");
  if (d.querySelector("parsererror")) throw new Error("Invalid KML XML");
  const out: Shape[] = [];
  d.querySelectorAll("Placemark").forEach((p) => {
    const name = p.querySelector(":scope > name")?.textContent ?? undefined;
    p.querySelectorAll("Point > coordinates").forEach((c) => out.push({ kind: "point", pts: coords(c.textContent ?? "").slice(0, 1), name, group: "points" }));
    p.querySelectorAll("LineString > coordinates").forEach((c) => out.push({ kind: "line", pts: coords(c.textContent ?? ""), name, group: "lines" }));
    p.querySelectorAll("Polygon outerBoundaryIs coordinates").forEach((c) => out.push({ kind: "poly", pts: coords(c.textContent ?? ""), name, group: "polygons" }));
  });
  return out;
}

export function haversineKm(a: Pt, b: Pt): number {
  const R = 6371, rad = Math.PI / 180;
  const dLat = (b[1] - a[1]) * rad, dLon = (b[0] - a[0]) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
export function lengthKm(pts: Pt[]): number { let s = 0; for (let i = 1; i < pts.length; i++) s += haversineKm(pts[i - 1]!, pts[i]!); return s; }

export function project(shapes: Shape[], w: number, h: number, pad = 24) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const s of shapes) for (const [x, y] of s.pts) { if (!isFinite(x) || !isFinite(y)) continue; minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
  if (!isFinite(minX)) return { fn: (_p: Pt): Pt => [w / 2, h / 2], bbox: null };
  const k = Math.cos((((minY + maxY) / 2) * Math.PI) / 180) || 1; // equirectangular, scaled by mean latitude
  const sx = (maxX - minX) * k || 1e-6, sy = maxY - minY || 1e-6;
  const scale = Math.min((w - 2 * pad) / sx, (h - 2 * pad) / sy);
  const ox = (w - sx * scale) / 2, oy = (h - sy * scale) / 2;
  return { fn: (p: Pt): Pt => [ox + (p[0] - minX) * k * scale, h - (oy + (p[1] - minY) * scale)], bbox: { minX, maxX, minY, maxY } };
}
