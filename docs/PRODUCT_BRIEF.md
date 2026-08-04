# Fylz product brief

## Product statement

Fylz is a minimal Android file workspace for people who use a phone or tablet as a serious computer. It combines dependable file operations, desktop-grade spatial navigation, previews and lightweight editing with optional local intelligence that never becomes a prerequisite for basic file management.

## The problem

Android already has good file utilities, but the default experience is optimized around finding, cleaning and sharing individual items. It is not a persistent multi-folder workspace. Desktop systems make it normal to keep several locations open, inspect a file without leaving context, compare metadata, resolve conflicts intentionally, tag or collect related items and work with network locations. Android power-user managers provide many of these capabilities, but often with dense interfaces, inconsistent interaction patterns or weak privacy boundaries.

## Product wedge

1. **Workspace, not cleaner.** Tabs, back/forward history, adaptive panes and a persistent preview.
2. **Agent-artifact native.** Markdown, logs, JSONL, diffs, plans and source text are first-class documents.
3. **Minimal surface, deep capability.** Traditional mode exposes familiar controls; immersive mode removes permanent chrome and uses contextual/floating panes.
4. **Local-first intelligence.** Deterministic organization works without a model. Downloadable local models and user-selected cloud providers are optional adapters.
5. **Operations must be trustworthy.** Long-running work is queued, inspectable, cancellable and recoverable. Destructive actions are explicit.

## Primary users

- Android tablet and foldable users doing desktop-like work
- Developers and AI-agent users reviewing generated plans, diffs, logs and Markdown on-device
- Privacy-conscious users who need a capable open-source manager
- People moving work among phone storage, removable media, NAS and self-hosted services
- Users scanning and filing physical documents without a separate scanner app

## Experience modes

### Traditional

A familiar top bar and persistent navigation. Wide screens default to left navigation, central file list and right preview. All panes can be collapsed or pinned.

### Immersive

The file surface owns the screen. Search, operations and preview appear only when invoked. The preview can become a draggable, resizable floating pane. Immersive mode must always include a visible escape route.

## Non-negotiable principles

- Basic file management never depends on AI, an account or a network.
- AI suggestions create reversible virtual views by default; they do not silently move or rename files.
- Cloud processing is opt-in for each provider and each operation.
- Passwords and API keys are never logged and are not stored in plaintext.
- Open-core boundaries are explicit. No private Studio code, assets, secrets or history enter this repository.
- Every capability has an empty, error, interrupted and recovery state.
