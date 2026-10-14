# Fylz master plan: Addendum 1

Customisation, and a changed gate policy.

For Claude Code on Sonnet with extended thinking. Prepared 25 Sep 2026.

Commit this file as `docs/agent/MASTER_PLAN_ADDENDUM_1.md`. It amends `docs/agent/MASTER_PLAN.md`. **Where the two conflict, this addendum wins.** Everything not mentioned here stays as written in the master plan.

---

## A. Gate policy (replaces master plan §0.3)

Madhav reviews and device-tests everything in one pass at the end of the Android work. So gates now come in two kinds.

### 1. Review gates: log and continue

These no longer stop the run: **GATE-M1, GATE-M2, GATE-M4, GATE-M8 (technical parts only)**. At each one:

- write the milestone report (§3.5);
- add the device checks to `DEVICE_CHECKS.md`;
- append an entry to `docs/agent/REVIEW_QUEUE.md`: gate ID, what needs reviewing or testing, the relevant commits, and the risk if it turns out to be wrong;
- then continue.

### 2. Hard gates: never pass without Madhav

These involve legal exposure, publishing, or choices that are expensive to reverse. When you reach one:

- build everything up to the point of no return;
- do **not** publish, submit or enable anything;
- record it in `REVIEW_QUEUE.md`;
- continue with other independent work.

The hard gates:

- **GATE-A** (publishing packs, any GPL/AGPL add-on);
- **GATE-M8** model choice and model licence;
- **GATE-M10** (shipping an exFAT writer; the USB write pack);
- **GATE-D-cloud**;
- **GATE-UT** (OpenStore submission);
- **D5** (vault format);
- **GATE-C1** and **GATE-C2** (below).

A default the plan specifies (for example rule-based natural language only, or FAT32 write only) stays in force until the hard gate is decided.

### Housekeeping before continuing

1. The uncommitted M3.1 work (the libarchive submodule, `fylz-archive` changes, `build.rs`): make it build and test it, commit it on the main working branch as M3.1, and log it in `PROGRESS.md`.
2. Write the missing `REPORT-M1.md` and `REPORT-M2.md`. REPORT-M2 must include:
   - the APK size increase per ABI;
   - the cold-start delta, measured or marked device-needed.
3. Rename PR #19 to "Fylz: engine, Rust core and features (in progress)" and keep its description current at every milestone.

---

## B. Why customisation, and the claim it must support

**The claim:** Fylz is the most customisable file manager on any platform. It matches Dolphin and Directory Opus on layout, commands and file styling, and goes beyond them with:

- rule-based automation;
- sandboxed scripting that runs identically on Android, Ubuntu Touch and Linux;
- portable customisation bundles.

**References to match** (study their public documentation for the concepts; copy no code):

- **Dolphin:** toolbar editor, panels, per-folder view properties, service menus (`.desktop` actions with MIME conditions).
- **Directory Opus:** button and toolbar editor, file-type groups, labels and colouring rules, folder formats, filters, scripting, "Evaluator" expressions.
- **Total Commander:** button bar, user commands, colour by file type, custom columns.
- **Nautilus:** scripts folder.
- **Hazel (macOS):** folder rules with conditions and actions.
- **Finder:** tags, smart folders.

**Customisation principles:**

1. **Three tiers**, each optional:
   - no-code settings;
   - declarative actions and rules;
   - sandboxed scripts.

   A person who never opens the customisation screens loses nothing.
2. **Everything customisable is data.** It is validated against a versioned schema, stored as human-readable files, exportable, and reversible. **Reset to default** works per section and globally.
3. **Customisation can never bypass trust.**
   - Every file change made by an action, rule or script goes through the operation engine: journal, staging, verification, recycle bin, conflict policy.
   - No customisation can permanently delete.
   - A permanent delete stays a manual action with its typed confirmation.
4. **Shared across platforms.**
   - The schemas, the condition evaluator, the action runner and the script engine live in `fylz-core`.
   - Each platform only renders the editors and supplies the platform steps.
   - A bundle made on Android works on Ubuntu Touch and Linux, minus platform-only steps, which are shown as unavailable rather than failing silently.

---

## C. Architecture decisions (pre-made)

### C1. Everything the user can do is an Action

This is new foundation work, **MC.0**, to be done **before M3.2**.

- Add a new crate `core/crates/fylz-actions`, and a Kotlin `ActionRegistry` mirroring it on Android.
- **Every command** in the app becomes a registered `Action`, built-in or user-defined. Examples: copy, paste, compress, protect, rename, open with, Quick Look, new folder, sort by size, toggle hidden files, open room, jump to path.

```rust
struct ActionDef {
    id: ActionId,              // "fylz.copy", "user.resize-for-web"
    title: LocalizedString,
    icon: IconRef,             // built-in icon id or bundle-relative SVG
    when: Option<Condition>,   // availability; see C3
    placement: Vec<Placement>, // ContextMenu{group,order}, SelectionBar{order},
                               // Toolbar{order}, CommandPalette, Shortcut(KeyChord),
                               // Gesture(GestureId), QuickSettingsTile, HomeCard
    confirm: ConfirmPolicy,    // None | Always | IfCountAbove(n) | IfDestructive
    run: ActionBody,           // BuiltIn(handler_id) | Steps(Vec<Step>) | Script(ScriptRef)
    origin: Origin,            // BuiltIn | Bundle{bundle_id, version} | Local
}
```

- **Context menu, selection bar, toolbar, command palette, keyboard shortcuts and gestures are all views over the registry.** There are no hard-coded menus anywhere after MC.0.
- Refactor the existing selection bar, overflow menu and room items to registry lookups.
- Keep behaviour identical: golden UI-state tests assert the same actions appear for the same selections before and after the refactor.
- The existing `workspace/KeyboardShortcutPolicy.kt` and `DesktopWorkspacePolicy.kt` shortcut conflicts are resolved here. The **registry is the only source of shortcuts**, and conflicts are detected at load time and shown in Settings.

### C2. Storage of customisations

Customisations live as files under the app's private storage, in a `custom/` directory:

```
custom/
  profile.toml             active profile + global settings overrides
  profiles/<name>.toml     named profiles (layouts, tabs, rooms, toolbars)
  actions/*.toml           user actions
  rules/*.toml             automation rules
  scripts/*.rhai           user scripts (+ scripts/*.toml manifests)
  styles/*.toml            file-styling rules, themes
  bundles/<id>/            installed bundles, read-only, each with manifest.toml
```

- **TOML** because it is human-editable and diffs cleanly.
- Every file carries `schema = N`. Migrations in `fylz-actions` upgrade old files, keeping a backup of each one.
- Loading validates everything. An invalid file is skipped and **shown** in Settings › Customisation › Problems with the exact error. It never crashes the app and is never silently dropped.
- SQLite remains for runtime state: rule-run history and statistics. It is not used for definitions.
- **Settings export/import:** "Export my Fylz" writes a single bundle (C6) of everything under `custom/`, excluding secrets.

### C3. One condition language

**Conditions reuse the search AST.** `Condition` is `fylz-query`'s `Query` (master plan M8) plus context predicates:

| Predicate | Values |
|---|---|
| `selection.count` | op n |
| `selection.kinds` | set |
| `location.kind` | internal / sd / usb / network / archive / disk-image / recycle-bin |
| `location.path` | under / not under |
| `volume.fs` | vfat / exfat / ntfs / ext4 / … |
| `platform` | android / ubuntu-touch / linux |
| `time` | range |
| `screen` | narrow / wide |
| `input` | touch / mouse / keyboard |

- In TOML, conditions are written in the typed search syntax, for example:

  ```toml
  when = "kind:image size:>5mb selection.count:>=1"
  ```

- One parser and one evaluator serve search, actions, rules and styling. The advanced search builder UI (M8.4) doubles as the condition editor. **Build M8's AST and parser early enough for MC.2**; if M8 isn't done yet, implement the AST and typed parser first as part of MC.2, and M8 reuses them.

### C4. Steps (the declarative language, Tier 2)

A `Step` is one operation from a fixed catalogue. Each step declares the inputs it takes, the outputs it produces, and whether it is destructive.

| Step | Notes |
|---|---|
| `copy_to`, `move_to` | destination: fixed path, a picker, `{same_folder}/sub`, or a template |
| `recycle` | the only removal step there is. **No `delete` step exists** |
| `rename` | with a rename-engine preset (M7) or inline rules |
| `compress`, `extract`, `protect`, `unprotect` | M3/M5 options |
| `convert_image`, `resize_image`, `strip_metadata` | M9.6 |
| `tag`, `untag`, `set_colour_label`, `favourite` | |
| `checksum` | writes a `.sha256` next to the file, or shows it |
| `open_with` | Android: an intent to a package/activity; UT: the url-dispatcher; Linux: a desktop entry |
| `share` | |
| `send_to_remote` | a saved connection plus path |
| `make_folder`, `group_into_folder` | a template for the name |
| `run_script` | Tier 3 |
| `notify`, `ask` | a prompt with choices whose answers feed later steps |
| `exec` | **Linux desktop only:** runs a command line with file arguments; see C7 |
| `set_view`, `open_location`, `open_tab` | UI steps for workspace macros |

- Steps run as **one operation** in the transfer queue: journaled, cancellable, with per-item results.
- A multi-step action records one journal entry per step, grouped under a single **Undo** where every step is reversible. Where a step isn't reversible (`share`, `exec`), the action shows that before it runs.
- Variables:
  - `{file}`, `{name}`, `{ext}`, `{folder}`, `{date:…}`, plus all rename-engine tokens (M7.3);
  - `{output}`: the previous step's result, for example the created archive.

**Example: a user action**

```toml
schema = 1
id = "user.web-images"
title = "Resize for web and zip"
icon = "builtin:image"
when = "kind:image selection.count:>=1"
placement = [{ context_menu = { group = "convert" } }, "command_palette"]
confirm = "if_count_above:50"

[[steps]]
resize_image = { max_edge = 2048, format = "webp", quality = 82, into = "{folder}/web" }

[[steps]]
compress = { format = "zip", name = "{folder.name}-web.zip", input = "{output}" }
```

### C5. Automation rules (Tier 2, in the style of Hazel)

A rule is a trigger, then a condition, then steps.

**Triggers:**

| Trigger | Mechanism |
|---|---|
| `file_appears` in a folder | Android: FileObserver on watched folders plus MediaStore generation polling; UT and Linux: inotify |
| `drive_connected` | matched by volume UUID or label |
| `schedule` | daily / weekly / cron-lite (WorkManager on Android; systemd user timer or an in-app timer on Linux) |
| `manual` | "run this rule now" on a folder |
| `on_open_folder` | for example, apply a view or show a banner |

**Safety** (enforced in `fylz-actions`, not in the UI):

- A new rule runs in **dry-run mode** for its first N matches (default 5). Fylz shows what it *would* do, and the user approves before it goes live.
- Every rule execution is journaled with the rule's ID, and can be undone from the Activity view.
- Rate limit: 200 files per run and 20 runs per hour by default, configurable. A rule that exceeds its limit pauses itself and notifies the user.
- Loop protection: a rule's output never re-triggers the same rule; files are tagged with an in-memory "produced by rule X" set with a TTL.
- Rules touching removable drives respect the yank-safety behaviour (M10.1).
- A global **Pause all rules** switch sits in the right room and the notification.

**Built-in rule templates** (shipped as a first-party bundle, off by default):

- Sort Downloads by type after 7 days.
- Move screenshots older than 30 days to Recycle.
- Import camera card on connect (M10.6).
- Back up DCIM to a USB drive on connect.
- Unzip archives that appear in a folder.
- Auto-tag PDFs containing "invoice".

### C6. Bundles (sharing)

- **File:** `.fylzkit`, a ZIP containing:
  - `manifest.toml` (id, name, version, author, description, `min_fylz`, platforms, `permissions` for scripts, content list);
  - `actions/`, `rules/`, `scripts/`, `styles/`, `profiles/`;
  - `icons/` (SVG only; rendered sanitised, with no scripts or external references).
- **Install flow:**
  - The user opens a `.fylzkit` file (Fylz registers for it).
  - A review sheet lists every action, rule and script, and every permission, in plain language.
  - Nothing is enabled until the user approves.
  - Rules from bundles are installed **disabled**.
- **Signing (optional):**
  - Ed25519 signatures over the manifest and a content hash list.
  - Keys are shown as fingerprints.
  - Unsigned bundles install with a clear "unsigned" label.
  - Signing never unlocks extra permissions; it only identifies the author.
- **Update:** installing a newer version of the same `id` shows a diff of added, removed and changed items before replacing.
- **Community gallery later (GATE-C2, hard):**
  - A static Git repository with an `index.json` of bundles (URL, hash, signature).
  - Fetched only when the user opens the gallery. No Fylz backend, no accounts, no ratings.
  - **Build the client behind a disabled feature flag; don't point it at any URL until the gate is decided.**

### C7. Scripts (Tier 3): Rhai, sandboxed

- **Engine:** Rhai (Rust, MIT/Apache-2.0; verify the licence at the pinned version) inside `fylz-actions`. The same engine and API run on all three platforms.
- **Sandbox by construction.** A script can only call the Fylz API below. There is no raw filesystem access, network access, process spawning or module loading, except `import` of other scripts in the same bundle.
- **Limits:** set Rhai's operation limit, call-depth limit, string and array size limits, and a wall-clock timeout (default 10 s interactive, 5 min in the queue). A breach aborts with a readable error.
- **Where scripts run:** on a dedicated worker thread in the app process. Rhai is memory-safe Rust and the API is capability-checked, so no separate process is needed. Keep the API boundary such that moving scripts into the isolated process later is possible.
- **API**, organised as modules. Every call is capability-checked against the permissions granted at install:

| Module | Functions | Permission |
|---|---|---|
| `files` | `list(dir)`, `stat(f)`, `read_text(f, max)`, `read_bytes(f, max)`, `hash(f, algo)` | `read` (scoped to the folders the user grants at install, or "wherever the action is invoked") |
| `ops` | `copy`, `move`, `rename`, `recycle`, `mkdir`, `compress`, `extract`, `tag`: each **enqueues** an operation and returns its result | `write` |
| `meta` | `exif(f)`, `id3(f)`, `pdf_info(f)`, `image_size(f)` | `read` |
| `search` | `query(str) -> [file]` via `fylz-query` | `read` |
| `ui` | `ask(text, choices)`, `input(prompt)`, `notify(text)`, `progress(fraction)` | none |
| `text` | regex, date formatting, Unicode normalisation | none |
| `env` | `platform()`, `selection()`, `location()` | none |

  There is **no network module** in this version. Adding one is **GATE-C1**, a hard gate.
- **Extension points for scripts,** beyond "run as an action":
  - **Custom rename token:** a script function `token(file) -> string`, used as `{script:mytoken}` in the rename engine.
  - **Custom column:** `column(file) -> value` shown in details view. It is computed lazily in the background, cached by (file identity, mtime), and time-limited per file.
  - **Custom search predicate:** `matches(file) -> bool`, used as `script:name` in queries. It is evaluated only on candidates already filtered by the index.
  - **Rule condition:** a rule can use a script predicate.
- **Tooling:**
  - an in-app script editor (the M6 code editor with Rhai syntax highlighting);
  - **Run on selection (dry run)**, which shows what `ops` calls *would* do without executing them;
  - a log view per script.
- **Linux desktop only:** the `exec` step and a `process` script module can run external commands, with the file list passed as arguments, never through a shell. They require an explicit per-action "Allow running programs" permission. They are absent on Android and Ubuntu Touch.

### C8. Tier 1 no-code customisation: scope

| Area | What the user can change |
|---|---|
| **Profiles** | Named workspace profiles: open tabs, rooms layout, view defaults. Switch from the left room. Can bind to a trigger (for example "desktop mode connected" → Desk profile) |
| **Views** | Per-folder or per-kind view memory (list/grid/details/gallery, sort, group, icon size, density, columns); "Apply to subfolders"; view templates |
| **Details columns** | Choose, reorder and resize columns, including metadata columns (EXIF, ID3, dimensions, duration, pages, checksum-on-demand) and script columns |
| **Toolbars and bars** | Edit and reorder the toolbar, selection bar, context-menu groups and command-palette favourites (all registry placements) |
| **Rooms** | Choose what appears in the left, right and bottom rooms, and in which order; which edge opens which room; the top room's Intent content |
| **Home surface** | Show, hide and reorder cards: Recents, categories, drives, favourites, smart folders, cleanup, rules status |
| **Gestures and input** | Map edge swipes, double-tap, two-finger tap, shake, long-press and mouse buttons to any Action; remap keyboard shortcuts with conflict detection |
| **File styling rules** | In the style of Directory Opus labels and Total Commander colours: `when` condition → text colour, background tint, icon override, badge, bold or italic. Examples: "older than 1 year = dimmed", "`.psd` = purple badge", "larger than 1 GB = red size". Evaluated per row, cached |
| **Folder appearance** | A custom icon or colour per folder, stored in the database keyed by identity; optional export |
| **Theme** | Hyle-token-based: accent, surface tones, contrast level, corner radius, density, font family (from installed/bundled fonts), icon set (bundled sets plus SVG sets from bundles), AMOLED black, dynamic colour on/off. **Themes are token files only; no code** |
| **Open With defaults** | Per kind or extension, including "always preview in Fylz" |
| **Preview behaviour** | Per kind: autoplay, hover delay, the preview-pane mode (M6.8) |
| **Search** | Default scope; which suggestion types appear; saved searches pinned as tabs or rooms |
| **Language and formats** | Date and size formats, and binary vs decimal units |

The Settings › Customisation screen has:

- **Live preview** of each change.
- A **Recently changed** list with per-item undo.
- A search box over all settings (settings are also registry-indexed, so the command palette can jump to any setting).

### C9. Cross-platform split

| Piece | Where |
|---|---|
| Schemas, TOML parse and validate, migrations, the condition evaluator, the step catalogue and runner, rule engine, bundle install/verify, the Rhai engine and API | `fylz-actions` (Rust core) |
| Registry mirror, editors (Compose), platform steps (intents, WorkManager triggers) | Android `app/` |
| Editors (QML), platform steps (url-dispatcher, inotify, systemd timers, `exec`) | `ut/`, `linux/` (M13/M14) |

- Android still has Kotlin-side action handlers for built-ins until each is ported. The registry calls them through a handler-ID table. **Don't port built-in handlers to Rust just for this;** only the definitions, conditions and runner live in Rust.

---

## D. New milestone MC (customisation), and its placement

Order: **MC.0 comes first (before M3.2).** MC.1–MC.9 come after M9 and before M10, because they need M6–M9's building blocks. MC.10 lands during M13/M14.

| ID | Task | Size | Acceptance |
|---|---|---|---|
| **MC.0** | Action registry (C1): every built-in command registered; the selection bar, overflow, context menu, command palette (minimal), shortcuts and gestures all read from it; conflict detection | M | Golden tests: identical actions per selection before and after; no hard-coded menus remain (grep test on `DropdownMenuItem` outside registry renderers) |
| **MC.1** | `fylz-actions` crate: schemas (actions, rules, styles, profiles, bundle manifest), TOML load, validation, migrations, the Problems report | M | Fuzz the TOML loaders; invalid files are reported, never crash |
| **MC.2** | Condition evaluator on the `fylz-query` AST plus context predicates (C3), including the typed parser if M8 hasn't built it | M | Golden tests (≥ 100 conditions); an evaluation is ≤ 5 µs per file per condition on the benchmark device class (JVM/host benchmark acceptable) |
| **MC.3** | Tier 1 editors: views and columns, toolbars and bars, rooms, home cards, gestures and shortcuts, theme tokens, Open With defaults, preview behaviour, profiles | L | Every C8 row editable, with live preview and reset to default; settings searchable from the command palette |
| **MC.4** | File styling rules: an evaluator in core, a row-render cache, and an editor using the M8.4 builder | S | 10k-row folder scrolls at 60 fps with 20 rules active (device check) |
| **MC.5** | Tier 2 step catalogue and runner through the transfer queue; the action editor (visual step list plus a TOML view with validation) | L | Each step has a round-trip test; multi-step undo works; `recycle` is the only removal step (a test asserts no delete path exists) |
| **MC.6** | Automation rules: triggers, dry-run, rate limits, loop protection, pause-all, Activity view with undo; built-in templates bundle (disabled) | L | Trigger tests with a fake clock and fake observers; dry-run shows exact planned operations; loop test |
| **MC.7** | Tier 3 Rhai engine: API modules, capability checks, limits, dry-run, script log; extension points (rename token, column, search predicate, rule condition); the editor with highlighting | L | Sandbox escape tests (no fs/net/process access; limits enforced); API golden tests; a script column on 10k files stays responsive |
| **MC.8** | Bundles: `.fylzkit` format, the install review sheet, Ed25519 signing and verification, update diff, "Export my Fylz" | M | Tampered bundle is rejected; unsigned is labelled; export then import on a fresh install reproduces the setup |
| **MC.9** | Gallery client behind a disabled flag (C6); **GATE-C2** decides whether to enable it and at which URL | S | Flag off by default; no network call without the user opening the gallery |
| **MC.10** | UT and Linux editors in QML, platform steps; `exec` and `process` on Linux desktop only (during M13/M14) | M | A bundle exported on Android installs on UT/Linux; platform-only steps show as unavailable |

---

## E. Customisation claim checklist

Put this in `docs/agent/CUSTOMISATION_PARITY.md`, and tick it with evidence (commit or test) as MC lands.

| Capability | Dolphin | Directory Opus | Total Cmdr | Nautilus | Fylz target |
|---|---|---|---|---|---|
| Toolbar/button editor | ✓ | ✓ | ✓ | ✗ | ✓ MC.3 |
| Context-menu custom actions with type conditions | ✓ (service menus) | ✓ | ✓ | scripts only | ✓ MC.5, with full query conditions |
| Per-folder view memory | ✓ | ✓ (folder formats) | partial | partial | ✓ MC.3 |
| Custom columns | limited | ✓ | ✓ (plugins) | ✗ | ✓ metadata + script columns |
| File colouring/labels by rule | ✗ | ✓ | ✓ | ✗ | ✓ MC.4 |
| Keyboard remap | ✓ | ✓ | ✓ | limited | ✓ plus gestures and mouse buttons |
| Scripting | via service menus/shell | ✓ (JScript/VBScript) | limited | shell scripts | ✓ Rhai, sandboxed, cross-platform |
| Automation / folder rules | ✗ | ✗ (limited) | ✗ | ✗ | ✓ MC.6 |
| Shareable bundles | partial (KNS store) | ✓ (button export) | partial | ✗ | ✓ MC.8, signed, reviewable |
| Works on phones | ✗ | ✗ | Android version is separate | ✗ | ✓ |

The ticks for competitors are from general knowledge. Verify each against current documentation when writing the parity file, and correct the table where it's wrong. The claim must survive a sceptical reviewer.

---

## F. Updated order of work

1. Housekeeping (section A), including M3.1 committed.
2. **MC.0** action registry.
3. M3.2 → M3 end → M4 → M5 → M6 → M7 → M8 → M9.
4. **MC.1 → MC.9.**
5. M10 → M11 → M12.
6. Android production candidate:
   - write `docs/agent/RELEASE_CANDIDATE.md` with everything in `REVIEW_QUEUE.md` consolidated;
   - produce the full device-check list, ordered by risk;
   - **stop for Madhav's review and testing.**
7. After his sign-off: M13 (Ubuntu Touch, including MC.10), then M14 (Linux).

**Model note for Madhav:** MC.0, MC.2 and MC.7 are architecture-heavy. The decisions are made above, but a short Opus review of the MC.0 refactor and the MC.7 sandbox before continuing is worth it.
