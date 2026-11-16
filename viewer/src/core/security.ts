import DOMPurify from "dompurify";

/** DOMPurify config for untrusted markup shown inside a sandboxed iframe. */
export const SANITIZE_CONFIG = {
  USE_PROFILES: { html: true, svg: true, svgFilters: false, mathMl: false },
  FORBID_TAGS: ["script", "iframe", "object", "embed", "form", "base", "meta", "link", "foreignObject", "style"],
  FORBID_ATTR: ["srcset", "formaction", "ping"],
  ALLOW_DATA_ATTR: false,
};

/** Hook: strip any remote URL (we are offline-only and must not leak requests). */
let hooked = false;
function installHooks() {
  if (hooked) return;
  hooked = true;
  DOMPurify.addHook("afterSanitizeAttributes", (node) => {
    for (const attr of ["href", "src", "xlink:href", "poster"]) {
      const v = node.getAttribute?.(attr);
      if (!v) continue;
      const ok = v.startsWith("#") || v.startsWith("data:image/") || v.startsWith("blob:");
      if (!ok) {
        if (attr === "href" && /^(https?:|mailto:)/i.test(v) && node.tagName === "A") {
          node.setAttribute("rel", "noopener noreferrer");
          node.setAttribute("target", "_blank");
        } else node.removeAttribute(attr);
      }
    }
  });
}

export function sanitizeHtml(html: string): string {
  installHooks();
  return DOMPurify.sanitize(html, SANITIZE_CONFIG as never) as unknown as string;
}

export function sanitizeSvg(svg: string): string {
  installHooks();
  return DOMPurify.sanitize(svg, {
    USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: ["script", "foreignObject", "iframe"],
    FORBID_ATTR: ["srcset"],
  } as never) as unknown as string;
}

export interface SandboxOptions {
  /** CSS injected into the frame; uses theme tokens passed in. */
  css?: string;
  onLink?(href: string): void;
}

const BASE_CSS = (dark: boolean) => `
  :root{color-scheme:${dark ? "dark" : "light"}}
  body{font:15px/1.6 system-ui,sans-serif;margin:16px;color:${dark ? "#e6e6e6" : "#1a1a1a"};background:${dark ? "#161616" : "#fff"};overflow-wrap:anywhere}
  img,svg,video{max-width:100%;height:auto}
  table{border-collapse:collapse}td,th{border:1px solid ${dark ? "#444" : "#ccc"};padding:4px 8px}
  pre,code{font-family:ui-monospace,monospace;background:${dark ? "#222" : "#f4f4f4"};border-radius:4px}
  pre{padding:8px;overflow:auto}code{padding:0 3px}
  a{color:${dark ? "#8ab4f8" : "#0b57d0"}}
  blockquote{border-left:3px solid ${dark ? "#555" : "#ccc"};margin-left:0;padding-left:12px;color:${dark ? "#aaa" : "#555"}}
`;

/**
 * Create a sandboxed iframe (no allow-scripts, no allow-same-origin) with sanitized srcdoc.
 * The caller passes *already sanitized* or sanitizable html; we sanitize again defensively.
 */
export function buildSrcdoc(html: string, theme: "light" | "dark", opts: SandboxOptions = {}): string {
  const clean = sanitizeHtml(html);
  return (
    `<!doctype html><html><head><meta charset="utf-8">` +
    `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:">` +
    `<style>${BASE_CSS(theme === "dark")}${opts.css ?? ""}</style></head><body>${clean}</body></html>`
  );
}

export function sandboxedFrame(container: HTMLElement, html: string, theme: "light" | "dark", opts: SandboxOptions = {}): HTMLIFrameElement {
  const frame = document.createElement("iframe");
  frame.setAttribute("sandbox", ""); // nothing: no scripts, no same-origin, no popups
  frame.className = "fv-sandbox";
  frame.title = "Sandboxed preview";
  frame.referrerPolicy = "no-referrer";
  frame.srcdoc = buildSrcdoc(html, theme, opts);
  container.appendChild(frame);
  return frame;
}

/** Render raw SVG text inside a sandboxed frame. */
export function sandboxedSvg(container: HTMLElement, svg: string, theme: "light" | "dark"): HTMLIFrameElement {
  const frame = document.createElement("iframe");
  frame.setAttribute("sandbox", "");
  frame.className = "fv-sandbox";
  frame.title = "Sandboxed SVG";
  const clean = sanitizeSvg(svg);
  frame.srcdoc =
    `<!doctype html><html><head><meta charset="utf-8">` +
    `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">` +
    `<style>html,body{margin:0;height:100%;display:flex;align-items:center;justify-content:center;background:${theme === "dark" ? "#161616" : "#fff"}}svg{max-width:100%;max-height:100%}</style></head><body>${clean}</body></html>`;
  container.appendChild(frame);
  return frame;
}
