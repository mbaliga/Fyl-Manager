// Light helper (kept separate so plugin descriptors don't pull in the YAML/TOML parsers).
export type StructKind = "json" | "jsonc" | "ndjson" | "yaml" | "toml" | "xml";

export function kindOf(ext: string): StructKind | null {
  switch (ext) {
    case "json": case "geojson": case "ipynb": return "json";
    case "jsonc": return "jsonc";
    case "ndjson": case "jsonl": return "ndjson";
    case "yaml": case "yml": return "yaml";
    case "toml": return "toml";
    case "xml": case "xhtml": case "svg": case "gpx": case "kml": case "plist": return "xml";
    default: return null;
  }
}
