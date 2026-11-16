import { EditorState, Compartment, type Extension } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, dropCursor, rectangularSelection, highlightSpecialChars } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { searchKeymap, highlightSelectionMatches, openSearchPanel } from "@codemirror/search";
import { indentOnInput, bracketMatching, foldGutter, foldKeymap, syntaxHighlighting, HighlightStyle, LanguageDescription, indentUnit } from "@codemirror/language";
import { closeBrackets, closeBracketsKeymap, autocompletion, completionKeymap } from "@codemirror/autocomplete";
import { languages } from "@codemirror/language-data";
import { tags as t } from "@lezer/highlight";
import { el } from "../../lib/format";
import { applyEol, decode, detectEol, encode, ENCODINGS, normalizeEncoding, normalizeEol, type Encoding, type Eol } from "../../lib/textcodec";
import type { Command, ViewContext, ViewHandle } from "../../core/types";

const fvTheme = EditorView.theme({
  "&": { color: "var(--fv-fg)", backgroundColor: "var(--fv-bg)", height: "100%", fontSize: "13px" },
  ".cm-scroller": { fontFamily: "var(--fv-mono)", lineHeight: "1.5" },
  ".cm-content": { caretColor: "var(--fv-accent)" },
  ".cm-cursor": { borderLeftColor: "var(--fv-accent)" },
  ".cm-gutters": { backgroundColor: "var(--fv-bg-subtle)", color: "var(--fv-fg-muted)", border: "none" },
  ".cm-activeLine": { backgroundColor: "var(--fv-hover)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--fv-hover)" },
  "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, ::selection": { backgroundColor: "var(--fv-selection) !important" },
  ".cm-selectionMatch": { backgroundColor: "var(--fv-selection)" },
  ".cm-searchMatch": { backgroundColor: "var(--fv-warn-bg)", outline: "1px solid var(--fv-warn)" },
  ".cm-searchMatch-selected": { backgroundColor: "var(--fv-selection)" },
  ".cm-panels": { backgroundColor: "var(--fv-bg-subtle)", color: "var(--fv-fg)", borderColor: "var(--fv-border)" },
  ".cm-panels input, .cm-panels button": { font: "inherit", color: "var(--fv-fg)", background: "var(--fv-bg)", border: "1px solid var(--fv-border)", borderRadius: "4px", padding: "2px 6px" },
  ".cm-tooltip": { backgroundColor: "var(--fv-bg-subtle)", border: "1px solid var(--fv-border)", color: "var(--fv-fg)" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--fv-danger)" },
});

const fvHighlight = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.operatorKeyword, t.controlKeyword], color: "var(--fv-syn-keyword)" },
  { tag: [t.string, t.special(t.string), t.regexp], color: "var(--fv-syn-string)" },
  { tag: [t.number, t.bool, t.null, t.atom], color: "var(--fv-syn-number)" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "var(--fv-syn-comment)", fontStyle: "italic" },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.definition(t.variableName)], color: "var(--fv-syn-func)" },
  { tag: [t.typeName, t.className, t.namespace], color: "var(--fv-syn-type)" },
  { tag: [t.propertyName, t.attributeName], color: "var(--fv-syn-prop)" },
  { tag: [t.heading, t.strong], fontWeight: "bold", color: "var(--fv-syn-keyword)" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: [t.link, t.url], color: "var(--fv-accent)", textDecoration: "underline" },
  { tag: [t.tagName, t.angleBracket], color: "var(--fv-syn-keyword)" },
  { tag: t.invalid, color: "var(--fv-danger)" },
]);

export const MAX_EDIT_BYTES = 20 * 1024 * 1024;

export interface TextCoreOptions {
  /** Language override by filename (e.g. "x.md"); defaults to the file name. */
  languageName?: string;
  extraExtensions?: Extension[];
  onDoc?: (text: string) => void;
}

export interface TextCore {
  root: HTMLElement;
  body: HTMLElement;
  editorHost: HTMLElement;
  view: EditorView;
  getText(): string;
  setText(text: string): void;
  setReadOnly(ro: boolean): void;
  handle: ViewHandle;
  readonly encoding: Encoding;
  readonly eol: Eol;
  refreshStatus(): void;
  notifyDirty(): void;
  addCommands(cmds: Command[]): void;
}

/** Shared by the text, markdown and json plugins. */
export async function createTextCore(ctx: ViewContext, opts: TextCoreOptions = {}): Promise<TextCore> {
  const { file, info } = ctx;
  if (file.size > MAX_EDIT_BYTES) throw new Error("File too large for the editor");
  const bytes = new Uint8Array(await (await file.blob()).arrayBuffer());
  const enc0 = normalizeEncoding(info.encoding);
  const raw = decode(bytes, enc0);
  let eol: Eol = detectEol(raw);
  const initial = normalizeEol(raw);
  let encoding: Encoding = enc0;

  const root = el("div", { class: "fv-text" });
  const body = el("div", { class: "fv-text-body" });
  const editorHost = el("div", { class: "fv-text-editor" });
  const status = el("div", { class: "fv-statusbar", role: "status" });
  body.append(editorHost);
  root.append(body, status);
  ctx.container.append(root);

  const readOnly = new Compartment();
  const lang = new Compartment();
  const wrap = new Compartment();
  const dirtyCbs: ((d: boolean) => void)[] = [];
  const cmdCbs: (() => void)[] = [];
  let baseline = initial;
  let baseEol = eol, baseEnc = encoding;
  let dirty = false;
  let wrapOn = false;
  let ro = ctx.mode !== "edit";
  let langLabel = "Plain text";

  const check = () => {
    const d = view.state.doc.toString() !== baseline || eol !== baseEol || encoding !== baseEnc;
    if (d !== dirty) { dirty = d; dirtyCbs.forEach((cb) => cb(d)); }
  };

  const updateStatus = () => {
    const sel = view.state.selection.main;
    const line = view.state.doc.lineAt(sel.head);
    status.replaceChildren(
      el("span", { text: `Ln ${line.number}, Col ${sel.head - line.from + 1}` }),
      el("span", { text: encoding.toUpperCase() }),
      el("span", { text: eol === "\r\n" ? "CRLF" : eol === "\r" ? "CR" : "LF" }),
      el("span", { text: langLabel }),
      el("span", { text: ro ? "Read-only" : "Editing" }),
    );
  };

  const view = new EditorView({
    parent: editorHost,
    state: EditorState.create({
      doc: initial,
      extensions: [
        lineNumbers(), highlightActiveLineGutter(), highlightSpecialChars(), history(), foldGutter(), drawSelection(), dropCursor(),
        EditorState.allowMultipleSelections.of(true), indentOnInput(), bracketMatching(), closeBrackets(), autocompletion(),
        rectangularSelection(), highlightActiveLine(), highlightSelectionMatches(), indentUnit.of("  "),
        syntaxHighlighting(fvHighlight), fvTheme,
        keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, ...foldKeymap, ...completionKeymap, indentWithTab]),
        readOnly.of([EditorState.readOnly.of(ro), EditorView.editable.of(!ro)]),
        lang.of([]), wrap.of([]),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) { check(); opts.onDoc?.(u.state.doc.toString()); }
          if (u.docChanged || u.selectionSet) updateStatus();
        }),
        ...(opts.extraExtensions ?? []),
      ],
    }),
  });

  // Language loads lazily and never blocks the first paint.
  const desc = LanguageDescription.matchFilename(languages, opts.languageName ?? info.name);
  if (desc) {
    langLabel = desc.name;
    desc.load().then((support) => {
      if (ctx.signal.aborted) return;
      view.dispatch({ effects: lang.reconfigure(support) });
      updateStatus();
    }).catch(() => {});
  }
  updateStatus();

  const extraCommands: Command[] = [];
  const bump = () => cmdCbs.forEach((c) => c());
  const commands = (): Command[] => [
    { id: "find", label: "Find / replace", icon: "search", run: () => { openSearchPanel(view); view.focus(); } },
    { id: "wrap", label: wrapOn ? "Wrap: on" : "Wrap: off", run: () => { wrapOn = !wrapOn; view.dispatch({ effects: wrap.reconfigure(wrapOn ? EditorView.lineWrapping : []) }); bump(); } },
    ...(ro ? [] : [
      { id: "eol", label: `Line ending: ${eol === "\r\n" ? "CRLF" : eol === "\r" ? "CR" : "LF"}`, run: () => { eol = eol === "\n" ? "\r\n" : "\n"; check(); updateStatus(); bump(); } },
      { id: "enc", label: `Encoding: ${encoding}`, run: () => { encoding = ENCODINGS[(ENCODINGS.indexOf(encoding) + 1) % ENCODINGS.length]!; check(); updateStatus(); bump(); } },
    ]),
    ...extraCommands,
  ];

  const handle: ViewHandle = {
    isDirty: () => dirty,
    onDirtyChange: (cb) => void dirtyCbs.push(cb),
    markSaved() { baseline = view.state.doc.toString(); baseEol = eol; baseEnc = encoding; check(); },
    async serialize() {
      return new Blob([encode(applyEol(view.state.doc.toString(), eol), encoding) as BlobPart]);
    },
    commands,
    onCommandsChange: (cb) => void cmdCbs.push(cb),
    unmount() { view.destroy(); root.remove(); },
  };
  ctx.signal.addEventListener("abort", () => { try { view.destroy(); } catch { /* already gone */ } });

  return {
    root, body, editorHost, view, handle,
    get encoding() { return encoding; },
    get eol() { return eol; },
    getText: () => view.state.doc.toString(),
    setText(text) { view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } }); },
    setReadOnly(v) { ro = v; view.dispatch({ effects: readOnly.reconfigure([EditorState.readOnly.of(v), EditorView.editable.of(!v)]) }); updateStatus(); },
    refreshStatus: updateStatus,
    notifyDirty: check,
    addCommands(c) { extraCommands.push(...c); bump(); },
  };
}
