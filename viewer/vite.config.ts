import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

// App CSP (docs/DECISIONS.md): injected only in production builds; the dev server needs inline HMR.
const CSP =
  "default-src 'self'; connect-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; " +
  "worker-src 'self' blob:; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
  "font-src 'self' blob: data:; frame-src 'self' blob:; object-src 'none'";

function csp(): Plugin {
  return {
    name: "fv-csp",
    apply: "build",
    transformIndexHtml: (html) =>
      html.replace("<head>", `<head>\n    <meta http-equiv="Content-Security-Policy" content="${CSP}" />`),
  };
}

export default defineConfig({
  base: "./",
  esbuild: { jsx: "automatic", jsxImportSource: "preact" },
  plugins: [csp()],
  build: {
    target: "es2022",
    outDir: "dist",
    chunkSizeWarningLimit: 2000,
    rollupOptions: { input: { main: "index.html", host: "dev/fylz-host.html" } },
  },
  worker: { format: "es" },
  test: { environment: "node", include: ["test/**/*.test.ts"], exclude: ["e2e/**"] },
});
