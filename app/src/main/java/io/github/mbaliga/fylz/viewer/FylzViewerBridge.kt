package io.github.mbaliga.fylz.viewer

import org.json.JSONObject

/**
 * STUB — not referenced from any screen yet; it changes no Fylz behaviour.
 *
 * Android side of the Fylz Viewer host bridge. The viewer (see `viewer/` and `viewer/docs/HOST_BRIDGE.md`)
 * runs in a WebView and talks to this class through a message channel. This class holds the protocol logic
 * only and has no `androidx.webkit` dependency, so it compiles today; the WebView wiring
 * (`WebViewCompat.addWebMessageListener`) is described in `docs/FYLZ_INTEGRATION.md` and needs
 * `androidx.webkit:webkit` added to `app/build.gradle.kts` when it is hooked up.
 *
 * Threading: [onViewerMessage] / [onViewerBytes] may be called on the WebView message thread; the
 * `ViewerFileBackend` calls (range reads, writes) block, so the caller must invoke them from a
 * background dispatcher (e.g. `Dispatchers.IO`) and send replies from there.
 */
interface ViewerFileBackend {
    val name: String
    val size: Long
    val mime: String?
    val lastModified: Long?
    val canWrite: Boolean

    /** Range read (SAF `ParcelFileDescriptor` / `DocumentFile`). Return fewer bytes only at EOF. */
    fun readRange(offset: Long, length: Int): ByteArray

    /** Replace the file with [bytes]. Route this through Safety History so the previous version is kept. Throw on failure. */
    fun replaceContent(bytes: ByteArray)

    /** Save [bytes] as a new file next to the original (or via a picker) named [suggestedName]. Throw on failure. */
    fun writeNew(suggestedName: String, bytes: ByteArray)

    /** "Open in another app". */
    fun openExternally()
}

/** Outbound side of the channel: backed by the WebMessage reply proxy. */
interface ViewerChannel {
    fun sendJson(json: String)

    /** Sends [header] (with `"binary": true` added) followed by [bytes] as an ArrayBuffer message. */
    fun sendBinary(header: JSONObject, bytes: ByteArray)
}

class FylzViewerBridge(
    private val channel: ViewerChannel,
    private val backend: ViewerFileBackend,
    private val fileId: String,
    private val isDark: () -> Boolean,
    private val onDirty: (Boolean) -> Unit = {},
    private val onClosed: (ok: Boolean) -> Unit = {},
    private val onLog: (level: String, msg: String) -> Unit = { _, _ -> },
) {
    private var pendingHeader: JSONObject? = null
    private val saveParts = HashMap<Int, MutableList<ByteArray>>()

    /** Handle a string message from the viewer. */
    fun onViewerMessage(json: String) {
        val msg = JSONObject(json)
        if (msg.optBoolean("binary", false)) {
            pendingHeader = msg // the ArrayBuffer follows
            return
        }
        dispatch(msg, null)
    }

    /** Handle an ArrayBuffer message from the viewer (pairs with the preceding `binary:true` header). */
    fun onViewerBytes(bytes: ByteArray) {
        val header = pendingHeader ?: return
        pendingHeader = null
        dispatch(header, bytes)
    }

    private fun dispatch(msg: JSONObject, bytes: ByteArray?) {
        when (msg.getString("type")) {
            "ready" -> sendOpen()
            "read" -> {
                val reqId = msg.getInt("reqId")
                try {
                    val data = backend.readRange(msg.getLong("offset"), msg.getInt("length"))
                    channel.sendBinary(JSONObject().put("type", "chunk").put("reqId", reqId), data)
                } catch (e: Exception) {
                    channel.sendJson(JSONObject().put("type", "chunk").put("reqId", reqId).put("error", e.message ?: "read failed").toString())
                }
            }
            "save", "saveAs" -> if (bytes != null) collectSave(msg, bytes)
            "openExternal" -> backend.openExternally()
            "dirty" -> onDirty(msg.optBoolean("dirty", false))
            "closed" -> onClosed(msg.optBoolean("ok", true))
            "log" -> onLog(msg.optString("level", "info"), msg.optString("msg"))
        }
    }

    private fun collectSave(msg: JSONObject, bytes: ByteArray) {
        val saveId = msg.getInt("saveId")
        val parts = saveParts.getOrPut(saveId) { mutableListOf() }
        parts.add(bytes) // chunks arrive in order (seq 0..n)
        if (!msg.optBoolean("final", false)) return
        saveParts.remove(saveId)
        val all = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(all, o); o += p.size }
        val reply = JSONObject().put("type", "saved").put("saveId", saveId)
        try {
            if (msg.getString("type") == "save") backend.replaceContent(all)
            else backend.writeNew(msg.optString("name", backend.name), all)
            reply.put("ok", true)
        } catch (e: Exception) {
            reply.put("ok", false).put("error", e.message ?: "save failed")
        }
        channel.sendJson(reply.toString())
    }

    private fun sendOpen() {
        val o = JSONObject()
            .put("type", "open").put("fileId", fileId).put("name", backend.name).put("size", backend.size)
            .put("canWrite", backend.canWrite).put("theme", if (isDark()) "dark" else "light")
        backend.mime?.let { o.put("mime", it) }
        backend.lastModified?.let { o.put("lastModified", it) }
        channel.sendJson(o.toString())
    }

    /** Back press / host-initiated close. The viewer answers with `closed {ok}` after its own discard prompt. */
    fun requestClose() = channel.sendJson("""{"type":"close"}""")

    fun setTheme(dark: Boolean) = channel.sendJson(JSONObject().put("type", "theme").put("mode", if (dark) "dark" else "light").toString())
}
