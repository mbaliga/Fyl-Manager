package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.json.JSONArray
import org.json.JSONObject

/** glTF 2.0 (.gltf JSON) and GLB (binary container). */
object CadGltfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("gltf", "glb")

    private const val MAX_JSON = 6 * 1024 * 1024
    private const val GLB_JSON = 0x4E4F534AL
    private const val GLB_BIN = 0x004E4942L

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun run(source: ByteSource): InspectionResult? {
        val head = source.read(0, 12)
        if (Bytes.startsWith(head, "glTF")) return glb(source, head)
        val bytes = CadIo.readUpTo(source, 0, MAX_JSON)
        val s = CadIo.skipBom(bytes)
        var i = s
        while (i < bytes.size && (bytes[i].toInt() == 32 || bytes[i].toInt() == 10 || bytes[i].toInt() == 13 || bytes[i].toInt() == 9)) i++
        if (i >= bytes.size || bytes[i].toInt() != '{'.code) return null
        val f = CadFacts()
        if (bytes.size < source.size) {
            f.warn("JSON is larger than ${Bytes.humanSize(MAX_JSON.toLong())}; not parsed")
            return InspectionResult("gltf", "glTF (too large to parse)", "glTF JSON, ${Bytes.humanSize(source.size)}", f.list, f.warnings)
        }
        if (nestingDepth(bytes) > 200) { f.warn("JSON nesting is deeper than 200 levels; not parsed"); return InspectionResult("gltf", "glTF (not parsed)", "glTF JSON", f.list, f.warnings) }
        val json = try { JSONObject(CadIo.utf8(bytes, s)) } catch (t: Throwable) { return null }
        if (json.optJSONObject("asset") == null && json.optJSONArray("meshes") == null && json.optJSONArray("nodes") == null) return null
        describe(json, "gltf", f, null)
        return result("gltf", json, f)
    }

    private fun glb(source: ByteSource, head: ByteArray): InspectionResult? {
        if (head.size < 12) return null
        val version = Bytes.u32le(head, 4)
        val declared = Bytes.u32le(head, 8)
        val f = CadFacts()
        f.add("Container", "GLB version", version.toString())
        f.add("Container", "Declared length", "$declared bytes")
        if (declared != source.size) f.warn("Header length $declared differs from file size ${source.size}")
        if (version != 2L) f.warn("Unexpected GLB container version $version (expected 2)")
        var off = 12L
        var n = 0
        var json: JSONObject? = null
        val layout = ArrayList<String>()
        while (n < 16 && off + 8 <= source.size) {
            val h = source.read(off, 8)
            if (h.size < 8) break
            val len = Bytes.u32le(h, 0)
            val type = Bytes.u32le(h, 4)
            val tname = when (type) { GLB_JSON -> "JSON"; GLB_BIN -> "BIN"; else -> "unknown 0x" + java.lang.Long.toHexString(type) }
            layout.add("$tname ($len bytes)")
            val dataOff = off + 8
            if (dataOff + len > source.size) f.warn("Chunk ${n + 1} ($tname) extends past the end of the file")
            if (type == GLB_JSON && json == null) {
                if (len <= MAX_JSON && dataOff + len <= source.size) {
                    val d = CadIo.readUpTo(source, dataOff, len.toInt())
                    if (nestingDepth(d) > 200) {
                        f.warn("JSON nesting is deeper than 200 levels; not parsed")
                    } else {
                        json = try { JSONObject(CadIo.utf8(d)) } catch (t: Throwable) { f.warn("JSON chunk is not valid JSON"); null }
                    }
                } else f.warn("JSON chunk not parsed (too large or truncated)")
            }
            off = dataOff + len
            if (len % 4 != 0L) off += 4 - len % 4
            n++
        }
        f.add("Container", "Chunks", layout.mapIndexed { i, s -> "${i + 1}: $s" }.joinToString("; "))
        if (json != null) describe(json, "glb", f, null)
        return result("glb", json, f)
    }

    private fun result(id: String, json: JSONObject?, f: CadFacts): InspectionResult {
        val gen = json?.optJSONObject("asset")?.optString("generator", "")?.takeIf { it.isNotBlank() }
        val meshes = json?.optJSONArray("meshes")?.length()
        val label = if (id == "glb") "GLB" else "glTF"
        return InspectionResult(id, "$label model", label + (json?.optJSONObject("asset")?.optString("version", "")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
            (meshes?.let { ", $it mesh(es)" } ?: "") + (gen?.let { ", $it" } ?: ""), f.list, f.warnings)
    }

    private fun nestingDepth(b: ByteArray): Int {
        var depth = 0; var max = 0; var inStr = false; var esc = false
        for (x in b) {
            val c = x.toInt()
            if (inStr) { if (esc) esc = false else if (c == '\\'.code) esc = true else if (c == '"'.code) inStr = false; continue }
            when (c) { '"'.code -> inStr = true; '{'.code, '['.code -> { depth++; if (depth > max) max = depth }; '}'.code, ']'.code -> depth-- }
        }
        return max
    }

    private fun len(a: JSONArray?): Int = a?.length() ?: 0

    private fun describe(j: JSONObject, id: String, f: CadFacts, unused: Any?) {
        val asset = j.optJSONObject("asset")
        f.add("Asset", "glTF version", asset?.optString("version", ""))
        f.add("Asset", "Minimum version", asset?.optString("minVersion", ""))
        f.add("Asset", "Generator", asset?.optString("generator", ""))
        f.add("Asset", "Copyright", asset?.optString("copyright", ""))
        for ((label, key) in listOf("Scenes" to "scenes", "Nodes" to "nodes", "Meshes" to "meshes", "Materials" to "materials",
            "Textures" to "textures", "Images" to "images", "Samplers" to "samplers", "Animations" to "animations", "Skins" to "skins",
            "Cameras" to "cameras", "Accessors" to "accessors", "Buffer views" to "bufferViews", "Buffers" to "buffers")) {
            val n = len(j.optJSONArray(key))
            if (n > 0) f.add("Contents", label, CadIo.count(n.toLong()))
        }
        val meshes = j.optJSONArray("meshes")
        if (meshes != null) {
            var prims = 0
            for (k in 0 until minOf(meshes.length(), 100000)) prims += len(meshes.optJSONObject(k)?.optJSONArray("primitives"))
            if (prims > 0) f.add("Contents", "Mesh primitives", CadIo.count(prims.toLong()))
        }
        val bufs = j.optJSONArray("buffers")
        if (bufs != null) {
            var total = 0L
            for (k in 0 until minOf(bufs.length(), 100000)) total += bufs.optJSONObject(k)?.optLong("byteLength", 0L) ?: 0L
            if (total > 0) f.add("Contents", "Buffer bytes (declared)", Bytes.humanSize(total))
        }
        if (j.has("scene")) f.add("Contents", "Default scene index", j.optInt("scene", -1).toString())
        f.add("Extensions", "Extensions used", names(j.optJSONArray("extensionsUsed")))
        f.add("Extensions", "Extensions required", names(j.optJSONArray("extensionsRequired")))
    }

    private fun names(a: JSONArray?): String =
        if (a == null) "" else (0 until minOf(a.length(), 64)).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotEmpty() } }.joinToString(", ")
}
