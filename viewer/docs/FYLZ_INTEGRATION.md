# Integrating Fylz Viewer into the Fylz Android app

Goal: open any file in Fylz in a WebView that runs the viewer, with range reads served from SAF, saves routed
through Safety History, and the Fylz theme mapped on. Protocol details: [`HOST_BRIDGE.md`](HOST_BRIDGE.md).
Fylz behaviour is unchanged until you wire the pieces below; a protocol stub is already in the repo:
`app/src/main/java/io/github/mbaliga/fylz/viewer/FylzViewerBridge.kt`.

## 1. Ship the build as app assets

```bash
cd viewer && npm ci && npm run build        # static site in viewer/dist/
rm -rf ../app/src/main/assets/viewer && cp -r dist ../app/src/main/assets/viewer
```

`dist/` is fully self-contained (JS, CSS, workers, WASM, pdf.js CMaps). `dist/sw.js` / `manifest.webmanifest` are for the PWA and are harmless inside the app
(the viewer does not register the service worker when it detects the Fylz host). Add `app/src/main/assets/viewer/` to the release checklist; ~10 MB uncompressed.

## 2. Serve it with `WebViewAssetLoader`

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .setDomain("appassets.androidplatform.net")
    .addPathHandler("/viewer/", WebViewAssetLoader.AssetsPathHandler(context))   // → assets/viewer/
    .build()

webView.settings.apply {
    javaScriptEnabled = true
    allowFileAccess = false; allowContentAccess = false      // the viewer never needs them
    domStorageEnabled = true                                  // localStorage (per-viewer prefs only)
}
webView.webViewClient = object : WebViewClientCompat() {
    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = assetLoader.shouldInterceptRequest(r.url)
}
webView.loadUrl("https://appassets.androidplatform.net/viewer/index.html?host=fylz")
```

Make sure `.wasm` is served as `application/wasm` (AssetsPathHandler infers it from the extension; verify with a 7z in the debug build) and that the WebView
is not given `setBlockNetworkLoads(false)` rules that conflict: the viewer makes **no** network requests.
Add `androidx.webkit:webkit` to `app/build.gradle.kts` (currently not a dependency).

## 3. Wire the message channel

```kotlin
val bridge = FylzViewerBridge(channel, backend, fileId, isDark = { theme.isDark }, onDirty = { vm.dirty = it }, onClosed = { ok -> if (ok) finish() })

WebViewCompat.addWebMessageListener(webView, "fylzBridge", setOf("https://appassets.androidplatform.net"),
    object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(view: WebView, message: WebMessageCompat, origin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
            if (!isMainFrame) return
            when (message.type) {
                WebMessageCompat.TYPE_STRING -> io.launch { bridge.onViewerMessage(message.data!!) }
                WebMessageCompat.TYPE_ARRAY_BUFFER -> io.launch { bridge.onViewerBytes(message.arrayBuffer) }
            }
        }
    })
```

- The listener is registered **before** `loadUrl`, so `window.fylzBridge` exists when the viewer starts; it then sends `ready` and you answer with `open` (the stub does this).
- `ViewerChannel.sendJson` → `reply.postMessage(String)`; `sendBinary` → `reply.postMessage(headerJson)` then `reply.postMessage(bytes)` (use the `ByteArray` overload; requires `WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER`).
  **Send header+bytes back-to-back from one coroutine** so pairs are never interleaved.
- Run `onViewerMessage`/`onViewerBytes` on a single-threaded dispatcher (or a `Mutex`) to preserve message order; the stub assumes in-order delivery.
- Allowed origin must be exactly the asset-loader origin; never `*`.

## 4. Range reads from SAF

Implement `ViewerFileBackend.readRange` with a cached `ParcelFileDescriptor` (`contentResolver.openFileDescriptor(uri, "r")`) and a `FileChannel`:

```kotlin
override fun readRange(offset: Long, length: Int): ByteArray {
    val ch = FileInputStream(pfd.fileDescriptor).channel
    val buf = ByteBuffer.allocate(minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0))
    var pos = offset
    while (buf.hasRemaining()) { val n = ch.read(buf, pos); if (n < 0) break; pos += n }
    return buf.array().copyOf(buf.position())
}
```

Keep the descriptor open for the lifetime of the viewer screen. For remote/virtual providers that do not support seeking, download to cache first and read from there.
The viewer asks for 64 KB first (type detection), then only what is on screen (hex), or the whole file for formats that need it (PDF, images, text ≤ 20 MB).

## 5. Saves → Safety History

`replaceContent(bytes)` must go through the same code path as other in-place edits in Fylz so the previous version lands in Safety History
(`history/` package). Never write directly to the URI from the viewer glue. `writeNew` is for "Save as…" (suggest the same folder). Throwing makes the viewer
show the error and stay dirty; nothing is lost. The viewer only saves on an explicit user action and refuses output whose detected type differs from the input.

## 6. Theme

Send `theme` on open (`isDark`) and again on `UiModeManager`/Compose theme change via `bridge.setTheme(dark)`. The viewer's colours are CSS variables `--fv-*`
(see `viewer/src/shell/styles.css`); to match Hyle exactly, inject an override after load:

```kotlin
webView.evaluateJavascript("""document.documentElement.style.setProperty('--fv-accent','${hyle.accent.hex}')""", null)
```

Variables: `--fv-bg, --fv-bg-subtle, --fv-fg, --fv-fg-muted, --fv-border, --fv-accent, --fv-accent-fg, --fv-hover, --fv-selection, --fv-danger(-bg), --fv-warn(-bg), --fv-ok, --fv-radius, --fv-font, --fv-mono, --fv-syn-*`.

## 7. Back press → `close` → dirty prompt

```kotlin
BackHandler { bridge.requestClose() }          // viewer shows "Discard unsaved changes?" if dirty
// onClosed(ok): ok == true → finish(); ok == false → user chose to keep editing, do nothing
```

If the WebView is unresponsive, fall back to `onDirty`'s last value: dirty → show a native confirm, else finish.

## 8. "Open in another app"

The viewer's "Open in…" button (docx/pptx/etc.) sends `openExternal`; implement `ViewerFileBackend.openExternally()` with an `ACTION_VIEW` chooser for the file's content URI (grant read permission).

## 9. Checklist for device testing

- [ ] 7z / xz archive listing (WASM served with correct MIME type)
- [ ] PDF with CJK text (CMaps present under `assets/viewer/pdfjs/`)
- [ ] 1 GB file in Hex: scrolls to the end, range reads stay small
- [ ] Edit text → Save → new version appears in Safety History; failed save keeps dirty state
- [ ] Back press with unsaved edits → prompt → Keep / Discard
- [ ] Airplane mode: everything above still works
- [ ] Dark/light switching while a file is open
