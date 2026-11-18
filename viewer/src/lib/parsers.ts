import type { StructKind } from "./kinds";
export { kindOf, type StructKind } from "./kinds";
import { parse as parseYaml, YAMLParseError } from "yaml";
import { parse as parseToml, TomlError } from "smol-toml";


export interface ParseError { message: string; from?: number; line?: number; col?: number }
export interface ParseResult { value?: unknown; error?: ParseError }


/** Strip // and /* *\/ comments and trailing commas, preserving offsets (replaced by spaces). */
export function stripJsonc(src: string): string {
  let out = "", i = 0, inStr = false;
  while (i < src.length) {
    const c = src[i]!, n = src[i + 1];
    if (inStr) { out += c; if (c === "\\") { out += n ?? ""; i += 2; continue; } if (c === '"') inStr = false; i++; continue; }
    if (c === '"') { inStr = true; out += c; i++; continue; }
    if (c === "/" && n === "/") { while (i < src.length && src[i] !== "\n") { out += " "; i++; } continue; }
    if (c === "/" && n === "*") { out += "  "; i += 2; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) { out += src[i] === "\n" ? "\n" : " "; i++; } out += "  "; i += 2; continue; }
    out += c; i++;
  }
  return out.replace(/,(\s*[}\]])/g, " $1");
}

function jsonError(e: unknown, text: string): ParseError {
  const msg = String((e as Error).message);
  let from: number | undefined;
  const m = /position (\d+)/.exec(msg);
  if (m) from = Number(m[1]);
  else {
    const lc = /line (\d+) column (\d+)/.exec(msg);
    if (lc) {
      const lines = text.split("\n");
      from = lines.slice(0, Number(lc[1]) - 1).reduce((a, l) => a + l.length + 1, 0) + Number(lc[2]) - 1;
    }
  }
  return { message: msg, from };
}

export function xmlToValue(node: Element): unknown {
  const obj: Record<string, unknown> = {};
  if (node.attributes.length) {
    const a: Record<string, string> = {};
    for (const at of Array.from(node.attributes)) a[at.name] = at.value;
    obj["@attributes"] = a;
  }
  const kids = Array.from(node.children);
  const text = Array.from(node.childNodes).filter((n) => n.nodeType === 3).map((n) => n.textContent ?? "").join("").trim();
  if (!kids.length && !node.attributes.length) return text;
  for (const k of kids) {
    const v = xmlToValue(k);
    if (k.nodeName in obj) {
      const prev = obj[k.nodeName];
      obj[k.nodeName] = Array.isArray(prev) ? [...prev, v] : [prev, v];
    } else obj[k.nodeName] = v;
  }
  if (text) obj["#text"] = text;
  return obj;
}

export function parseStructured(kind: StructKind, text: string): ParseResult {
  try {
    switch (kind) {
      case "json": return { value: JSON.parse(text) };
      case "jsonc": return { value: JSON.parse(stripJsonc(text)) };
      case "ndjson": {
        const out: unknown[] = [];
        let off = 0;
        for (const line of text.split("\n")) {
          if (line.trim()) {
            try { out.push(JSON.parse(line)); } catch (e) { const er = jsonError(e, line); return { error: { message: er.message, from: off + (er.from ?? 0) } }; }
          }
          off += line.length + 1;
        }
        return { value: out };
      }
      case "yaml": return { value: parseYaml(text) };
      case "toml": return { value: parseToml(text) };
      case "xml": {
        const doc = new DOMParser().parseFromString(text, "application/xml");
        const err = doc.querySelector("parsererror");
        if (err) return { error: { message: (err.textContent ?? "XML parse error").split("\n")[0]!.slice(0, 200) } };
        return { value: { [doc.documentElement.nodeName]: xmlToValue(doc.documentElement) } };
      }
    }
  } catch (e) {
    if (kind === "yaml" && e instanceof YAMLParseError) return { error: { message: e.message.split("\n")[0]!, from: e.pos?.[0] } };
    if (kind === "toml" && e instanceof TomlError) return { error: { message: e.message.split("\n")[0]!, line: e.line, col: e.column } };
    return { error: jsonError(e, text) };
  }
}
