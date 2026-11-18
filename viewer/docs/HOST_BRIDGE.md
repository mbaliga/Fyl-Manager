# Host bridge protocol (v1)

The viewer runs inside a host: the standalone PWA (`StandaloneHost`) or the Fylz Android app (`FylzHost`).
`FylzHost` talks to Android over a message channel. The same protocol runs over two transports:

| Transport | Used by | How |
|---|---|---|
| **Android** | production | `WebViewCompat.addWebMessageListener(webView, "fylzBridge", allowedOrigins, listener)`. The viewer finds `window.fylzBridge` (an object with `postMessage(string \| ArrayBuffer)` and an `onmessage` setter). |
| **Parent window** | `dev/fylz-host.html` mock + e2e | `window.parent.postMessage({fylz: 1, ...msg}, "*")`; selected with `?host=fylz` when embedded in an iframe. `ArrayBuffer`s travel inside the message (transferred). |

## Framing on Android

Control messages are JSON **strings**. A message that carries bytes is sent as **two** consecutive messages, in order:

1. a JSON string header with all fields except `bytes`, plus `"binary": true`;
2. an `ArrayBuffer` (`WebMessageCompat.TYPE_ARRAY_BUFFER`) with the payload.

The receiver pairs a binary message with the immediately preceding `binary:true` header. (`androidTransport()` in `src/core/host.ts`.)

## Messages

### host → viewer

| type | fields | notes |
|---|---|---|
| `open` | `fileId, name, size, mime?, lastModified?, canWrite, theme` | `theme` is `"light"` or `"dark"`. Viewer opens the file; all reads are lazy via `read`. |
| `chunk` | `reqId, bytes` *(binary)* or `reqId, error` | Reply to `read`. May be shorter than requested only at EOF. |
| `saved` | `saveId, ok, error?` | Completes a `save`/`saveAs` (after the host has persisted, e.g. through Safety History). |
| `theme` | `mode` | Live theme change. |
| `close` | — | Back-press / host wants to close. Viewer shows its own "discard changes?" prompt if dirty and answers with `closed`. |

### viewer → host

| type | fields | notes |
|---|---|---|
| `ready` | `protocol` | Sent once the app is mounted. Host answers with `open`. |
| `read` | `reqId, fileId, offset, length` | Range read (SAF `DocumentFile`/`ParcelFileDescriptor`). Hex view on a 4 GB file issues many small reads: keep them cheap. Lengths ≤ 4 MiB. |
| `save` | `fileId, saveId, seq, final, bytes` *(binary)* | Whole-file replacement, uploaded in 1 MiB chunks (`seq` 0..n, `final` on the last). Host assembles, writes, replies `saved`. Never sent without an explicit user action. |
| `saveAs` | `fileId, saveId, name, seq, final, bytes` | Same, but as a new file with suggested `name`. |
| `openExternal` | `fileId` | "Open in another app". |
| `dirty` | `fileId, dirty` | Lets the host intercept back-press / confirm. |
| `closed` | `fileId, ok` | Answer to `close`: `ok=false` means the user chose to keep editing. |
| `log` | `level, msg, data?` | Diagnostics; host may forward to logcat. |

## Ordering and errors

- `reqId`/`saveId` are viewer-chosen integers, unique per session; replies echo them.
- The host must answer every `read` exactly once (`chunk` with `bytes` or `error`).
- If `save` fails the host sends `saved {ok:false, error}`; the viewer shows the error and keeps the document dirty. Nothing is written by the viewer itself.
- Protocol version is `1`; unknown message types must be ignored on both sides.

## Mock host

`/dev/fylz-host.html` embeds the viewer in an iframe with `?host=fylz`, serves a chosen file with configurable per-read latency
and records saves. The e2e tests drive it through `window.mockHost` (`openFile(name, bytes, opts)`, `saves`, `sendClose()`).
