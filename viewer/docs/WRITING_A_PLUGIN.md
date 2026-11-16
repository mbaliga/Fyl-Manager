# Writing a plugin

A plugin is a **descriptor** (tiny, always bundled) plus a **view module** (lazy `import()`).

```
src/plugins/<id>/index.ts   descriptor: id, name, maxLevel, sizeLimits, formats, match(), load()
src/plugins/<id>/view.ts    default export: { mount(ctx) → ViewHandle }
```

## 1. Descriptor

```ts
import type { FormatPlugin } from "../../core/types";

export const fooPlugin: FormatPlugin = {
  id: "foo",
  name: "Foo",
  maxLevel: 3,                          // highest rung of the capability ladder you can reach
  sizeLimits: { view: 64 * 1024 * 1024 }, // above → session falls back to Hex; `edit` → view-only
  formats: ["foo", "bar"],              // shown in the README table (npm run format-table)
  match(info, head) {                   // info: FileInfo, head: first 64 KB
    return info.detected === "application/x-foo"
      ? { confidence: 0.9, level: 3, label: "Foo viewer" }
      : null;
  },
  load: () => import("./view").then((m) => m.default),
};
```

Keep the descriptor free of heavy imports (the initial bundle budget is 150 KB gz; `npm run build` enforces it).
Register it in `src/plugins/index.ts` (order = tie-break). Confidence guide: 0.9 dedicated format, 0.6 generic text/code, 0.01 hex fallback.

## 2. View module

```ts
const mod: PluginModule = {
  async mount(ctx) {
    // ctx.file (FileSource: read(offset,len) / blob() / stream()), ctx.info, ctx.container,
    // ctx.mode ("view"|"edit"), ctx.level, ctx.host, ctx.signal (aborted on close), ctx.theme
    return {
      isDirty: () => false,
      onDirtyChange(cb) { /* call cb(true/false) */ },
      markSaved() {},                 // reset your baseline after a successful save
      serialize: async () => blob,    // required when level >= 4
      commands: () => [{ id, label, run }],   // toolbar "Tools" menu
      setTheme(t) {},
      unmount() { /* remove DOM, revoke object URLs, terminate workers */ },
    };
  },
};
export default mod;
```

Rules:
- **Check `ctx.file.size` before `blob()`.** Big files must use range reads.
- **Never `eval`** file content. Untrusted HTML-like content only inside `sandboxedFrame()` / `buildSrcdoc()` (`core/security.ts`): `<iframe sandbox>` with no `allow-scripts`/`allow-same-origin`, DOMPurify-sanitised.
- **Colours** come from `--fv-*` CSS variables (see `shell/styles.css`). No hard-coded colours.
- **No network.** Bundle workers/WASM (`new Worker(new URL(..., import.meta.url))`, or copy to `public/` via `scripts/copy-assets.ts`).
- Heavy parsing goes in a Web Worker (`src/workers/`).
- Long lists → `lib/vlist.ts` (`VirtualList`); tables → `lib/grid.ts` (`DataGrid`).
- Throw `ViewerError("friendly message")` for expected failures; the session falls back to Hex and shows a notice.
- Level ≥ 4 plugins: do not write anything yourself. The session calls `serialize()`, re-detects the output (type must not change) and passes it to `host.save()`.

## 3. Tests

- Add a fixture to `scripts/make-fixtures.ts` (generate it; commit hand-made files only when generation is impractical, < 100 KB).
- `test/*.test.ts`: detection + a serialize round-trip (edit → serialize → re-open → same content).
- `e2e/*.spec.ts`: open the fixture (`openFile`), `expectPlugin(page, id, level)`, assert no console errors, and for editors do one edit → save round-trip (`saveDownload`).
- Add the plugin to the table in `e2e/phase1.spec.ts`.
