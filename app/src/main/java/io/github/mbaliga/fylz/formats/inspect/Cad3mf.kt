package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** 3D Manufacturing Format: an OPC/ZIP package containing a 3D model part. */
object Cad3mfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("3mf")

    private const val MODEL_CAP = 6 * 1024 * 1024
    private val modelTag = Regex("<model\\b([^<>]*)>")
    private val attr = Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*\"([^\"]*)\"")
    private val vertexRe = Regex("<vertex\\b([^<>]*?)/?>")
    private val relRe = Regex("<Relationship\\b([^<>]*?)/?>")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = try {
        run(source)
    } catch (t: Throwable) { null }

    private fun attrs(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (a in attr.findAll(s)) { if (m.size < 32) m[a.groupValues[1]] = a.groupValues[2] }
        return m
    }

    private fun run(source: ByteSource): InspectionResult? {
        val h = source.read(0, 4)
        if (!Bytes.startsWith(h, "PK")) return null
        val f = CadFacts()
        val zip = CadZip.open(source)
        if (zip == null) {
            f.warn("ZIP central directory could not be read (corrupt, truncated or ZIP64)")
            return InspectionResult("3mf", "3MF package", "3MF (unreadable ZIP)", f.list, f.warnings)
        }
        val names = zip.entries.map { it.name }
        if (zip.find("[Content_Types].xml") == null) f.warn("[Content_Types].xml missing; not a conforming OPC package")
        f.add("Package", "Files in package", zip.entries.size.toString())
        if (zip.declaredEntries > zip.entries.size) f.warn("Central directory lists ${zip.declaredEntries} entries but only ${zip.entries.size} were readable")
        val modelFiles = names.filter { it.lowercase().endsWith(".model") }
        f.add("Package", "Model parts", modelFiles.take(10).joinToString(", ") + if (modelFiles.size > 10) " (+${modelFiles.size - 10} more)" else "")
        val thumb = names.firstOrNull { val l = it.lowercase(); l.contains("thumbnail") && (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")) }
        f.add("Package", "Thumbnail", thumb)
        val texCount = names.count { it.lowercase().startsWith("3d/texture") || it.lowercase().startsWith("/3d/texture") }
        if (texCount > 0) f.add("Package", "Texture files", texCount.toString())

        // locate the root model part via _rels/.rels
        var rootName: String? = null
        zip.find("_rels/.rels")?.let { e ->
            val o = zip.extract(e, 256 * 1024)
            if (o != null) {
                val xml = CadIo.utf8(o.data)
                for (m in relRe.findAll(xml)) {
                    val a = attrs(m.groupValues[1])
                    val type = a["Type"] ?: continue
                    if (type.endsWith("/3dmodel") || type.endsWith("3dmodel")) { rootName = a["Target"]?.trimStart('/'); break }
                }
            }
        }
        val entry = (rootName?.let { zip.find(it) }) ?: zip.find("3D/3dmodel.model") ?: zip.entries.firstOrNull { it.name.lowercase().endsWith(".model") }
        if (entry == null) {
            f.warn("No 3D model part found in the package")
            return InspectionResult("3mf", "3MF package", "3MF, no model part", f.list, f.warnings)
        }
        f.add("Model", "Root model part", entry.name)
        val out = zip.extract(entry, MODEL_CAP)
        if (out == null) {
            f.warn("Model part could not be extracted (unsupported compression or damaged)")
            return InspectionResult("3mf", "3MF package", "3MF", f.list, f.warnings)
        }
        val xml = CadIo.utf8(out.data)
        val mt = modelTag.find(xml)
        val ma = mt?.let { attrs(it.groupValues[1]) } ?: emptyMap()
        f.add("Model", "Unit", (ma["unit"] ?: "millimeter") + if (ma["unit"] == null) " (default)" else "")
        f.add("Model", "Language", ma["xml:lang"])
        f.add("Model", "Required extensions", ma["requiredextensions"])
        val objects = Regex("<object\\b").findAll(xml).count()
        val items = Regex("<item\\b").findAll(xml).count()
        val comps = Regex("<component\\b").findAll(xml).count()
        val tris = Regex("<triangle\\b").findAll(xml).count()
        f.add("Model", "Objects", objects.toString())
        f.add("Model", "Build items", items.toString())
        if (comps > 0) f.add("Model", "Components", comps.toString())
        var verts = 0L
        val b = CadBounds()
        for (m in vertexRe.findAll(xml)) {
            verts++
            if (verts <= 2_000_000) {
                val a = attrs(m.groupValues[1])
                val x = a["x"]?.toDoubleOrNull(); val y = a["y"]?.toDoubleOrNull(); val z = a["z"]?.toDoubleOrNull()
                if (x != null && y != null && z != null) b.add(x, y, z)
            }
        }
        f.add("Model", "Vertices", CadIo.count(verts))
        f.add("Model", "Triangles", CadIo.count(tris.toLong()))
        b.addFacts(f, "Geometry", "Vertex bounds (untransformed)")
        var mc = 0
        var pos = 0
        var tries = 0
        while (mc < 30 && tries++ < 5000) {
            val st = xml.indexOf("<metadata", pos)
            if (st < 0) break
            val gt = xml.indexOf('>', st)
            if (gt < 0) break
            val tagEnd = st + 9
            val sep = if (tagEnd < xml.length) xml[tagEnd] else ' '
            pos = gt + 1
            if (sep != ' ' && sep != '\t' && sep != '\n' && sep != '\r' && sep != '>' && sep != '/') continue
            if (xml[gt - 1] == '/') continue
            val close = xml.indexOf("</metadata>", gt + 1)
            if (close < 0) break
            val a = attrs(xml.substring(st + 9, gt))
            pos = close + 11
            val name = a["name"] ?: continue
            val v = CadIo.unxml(xml.substring(gt + 1, close).trim())
            if (v.isNotEmpty()) { f.add("Metadata", name, CadIo.clip(v, 300)); mc++ }
        }
        if (out.truncated) f.warn("Model part is larger than ${Bytes.humanSize(MODEL_CAP.toLong())} (or its data is cut); counts are lower bounds")
        if (modelFiles.size > 1 && objects <= items) f.warn("Geometry may be split across several model parts; only the root part was counted")
        val title = f.list.firstOrNull { it.label == "Title" }?.value ?: "3MF model"
        return InspectionResult("3mf", title, "3MF, ${ma["unit"] ?: "millimeter"}, $objects objects, ${CadIo.count(tris.toLong())} triangles", f.list, f.warnings)
    }
}
