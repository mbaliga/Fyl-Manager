import { useEffect, useRef, useState } from "preact/hooks";
import { useStore } from "../lib/store";
import { formatBytes } from "../lib/format";
import { BlobSource } from "../core/source";
import { LEVEL_NAMES } from "../core/types";
import type { Session } from "../core/session";

interface Props { session: Session; onPick: () => void; onPickHandle?: () => void }

async function openFiles(session: Session, files: FileList | File[]) {
  const f = files[0];
  if (f) await session.open(new BlobSource(f), {});
}

export function App({ session, onPick }: Props) {
  const s = useStore(session.store);
  const stageRef = useRef<HTMLDivElement>(null);
  const [over, setOver] = useState(false);
  const [showInfo, setShowInfo] = useState(false);
  const [confirm, setConfirm] = useState<null | ((v: boolean) => void)>(null);
  const infoRef = useRef<HTMLDivElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (stageRef.current) session.attach(stageRef.current);
    session.confirmDiscard = () => new Promise<boolean>((resolve) => setConfirm(() => (v: boolean) => { setConfirm(null); resolve(v); }));
  }, [session]);

  // info panel: mount the info plugin for the current file
  useEffect(() => {
    const host = infoRef.current;
    if (!host || !showInfo || !s.source || !s.info) return;
    const ac = new AbortController();
    let unmount: (() => void) | undefined;
    const infoPlugin = session.registry.get("info");
    infoPlugin?.load().then(async (m) => {
      if (ac.signal.aborted) return;
      const h = await m.mount({ file: s.source!, info: s.info!, container: host, mode: "view", level: 0, host: session.host, signal: ac.signal, theme: s.theme });
      unmount = () => h.unmount();
    });
    return () => { ac.abort(); unmount?.(); host.replaceChildren(); };
  }, [showInfo, s.source, s.info, session]);

  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => { if (session.state.dirty) { e.preventDefault(); e.returnValue = ""; } };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [session]);

  const idle = s.status === "idle";

  return (
    <div
      class="fv-app"
      onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); if (e.dataTransfer?.files.length) void openFiles(session, e.dataTransfer.files); }}
    >
      <header class="fv-toolbar">
        {session.host.kind === "standalone" && <button class="fv-btn" onClick={onPick} data-testid="open">Open…</button>}
        {s.depth > 0 && <button class="fv-btn" onClick={() => void session.back()} data-testid="back">← Back</button>}
        {s.info && (
          <span class="fv-title" title={s.info.name} data-testid="title">
            {s.info.name}{s.dirty && <span class="fv-dirty" data-testid="dirty" title="Unsaved changes"> ●</span>}
          </span>
        )}
        {s.info && s.status !== "idle" && (
          <>
            <select
              class="fv-input" aria-label="Open as" data-testid="open-as" value={s.pluginId ?? ""}
              onChange={(e) => void session.switchPlugin((e.currentTarget as HTMLSelectElement).value)}
            >
              {s.candidates.map((c) => <option value={c.plugin.id}>{c.plugin.name}{c.plugin.id === s.candidates[0]?.plugin.id ? " (best)" : ""}</option>)}
            </select>
            <span class="fv-badge" data-testid="level" title={LEVEL_NAMES[s.level]}>L{s.level} {LEVEL_NAMES[s.level]}</span>
            <span class="fv-muted">{formatBytes(s.info.size)}</span>
          </>
        )}
        <span class="fv-spacer" />
        {s.status === "ready" && s.canEdit && (
          <button class={`fv-btn ${s.mode === "edit" ? "fv-btn-on" : ""}`} aria-pressed={s.mode === "edit"} data-testid="edit-toggle" onClick={() => void session.setEditMode(s.mode !== "edit")}>
            {s.mode === "edit" ? "Editing" : "Edit"}
          </button>
        )}
        {s.mode === "edit" && s.status === "ready" && (
          <>
            <button class="fv-btn fv-btn-primary" data-testid="save" disabled={!s.dirty || s.saving} onClick={() => void session.save()}>
              {s.saving ? "Saving…" : session.host.kind === "standalone" ? "Save" : "Save"}
            </button>
            {session.host.saveAs && <button class="fv-btn" data-testid="save-as" disabled={s.saving} onClick={() => void session.saveAs()}>Save as…</button>}
          </>
        )}
        {s.commands.length > 0 && (
          <details class="fv-menu">
            <summary class="fv-btn" data-testid="more">⋯ Tools</summary>
            <div class="fv-menu-pop" role="menu">
              {s.commands.map((c) => (
                <button role="menuitem" data-cmd={c.id} disabled={c.enabled ? !c.enabled() : false}
                  onClick={(e) => { (e.currentTarget.closest("details") as HTMLDetailsElement).open = false; void session.runCommand(c.id); }}>
                  {c.label}
                </button>
              ))}
            </div>
          </details>
        )}
        {s.info && <button class={`fv-btn ${showInfo ? "fv-btn-on" : ""}`} aria-pressed={showInfo} data-testid="info-toggle" onClick={() => setShowInfo(!showInfo)}>Info</button>}
        {session.host.kind === "fylz" && session.host.openExternal && s.info && (
          <button class="fv-btn" onClick={() => session.host.openExternal?.()}>Open in…</button>
        )}
        <button class="fv-btn" aria-label="Toggle theme" data-testid="theme" onClick={() => session.setTheme(s.theme === "dark" ? "light" : "dark")}>{s.theme === "dark" ? "☀" : "☾"}</button>
        {s.info && session.host.kind === "standalone" && <button class="fv-btn" aria-label="Close file" data-testid="close" onClick={() => void session.close()}>✕</button>}
      </header>

      {s.error && <div class="fv-banner fv-banner-error" role="alert" data-testid="error">{s.error}<button class="fv-btn" onClick={() => session.dismissError()}>Dismiss</button></div>}
      {s.notice && !s.error && <div class="fv-banner fv-banner-notice" role="status" data-testid="notice">{s.notice}<button class="fv-btn" onClick={() => session.dismissError()}>Dismiss</button></div>}

      <main class="fv-main">
        {idle && (
          <div class={`fv-drop ${over ? "fv-over" : ""}`} data-testid="dropzone">
            <h1>Fylz Viewer</h1>
            <p>Every file is inspectable. Drop a file here, or choose one.</p>
            {session.host.kind === "standalone"
              ? <button class="fv-btn fv-btn-primary" onClick={() => onPick()}>Open a file…</button>
              : <p>Waiting for Fylz…</p>}
          </div>
        )}
        {s.status === "loading" && <div class="fv-banner" data-testid="loading">Opening…</div>}
        <div class="fv-stage" ref={stageRef} hidden={idle} data-testid="stage" data-plugin={s.pluginId ?? ""} data-level={s.level} data-mode={s.mode} data-status={s.status} />
        {showInfo && s.info && <aside class="fv-infopanel" ref={infoRef} data-testid="info-panel" />}
      </main>

      <input ref={fileInput} type="file" hidden data-testid="file-input" onChange={(e) => { const t = e.currentTarget as HTMLInputElement; if (t.files) void openFiles(session, t.files); t.value = ""; }} />
      {confirm && (
        <div class="fv-dialog-back" role="dialog" aria-modal="true" aria-label="Discard changes?">
          <div class="fv-dialog">
            <strong>Discard unsaved changes?</strong>
            <span class="fv-muted">Your edits to {s.info?.name} have not been saved.</span>
            <div class="fv-dialog-actions">
              <button class="fv-btn" data-testid="keep" onClick={() => confirm(false)}>Keep editing</button>
              <button class="fv-btn fv-btn-primary" data-testid="discard" onClick={() => confirm(true)}>Discard</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
