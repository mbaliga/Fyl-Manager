import type { FormatPlugin } from "../../core/types";

export const geoPlugin: FormatPlugin = {
  id: "geo",
  name: "Geo plot",
  maxLevel: 3,
  sizeLimits: { view: 64 * 1024 * 1024 },
  formats: ["geojson", "gpx", "kml"],
  match(info) {
    return info.isText && ["geojson", "gpx", "kml"].includes(info.ext) ? { confidence: 0.93, level: 3, label: "Offline SVG plot (no map tiles)" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
