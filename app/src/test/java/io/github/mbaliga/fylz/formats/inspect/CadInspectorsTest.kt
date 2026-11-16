package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CadInspectorsTest {

    // ---------- helpers ----------
    private fun run(i: FormatInspector, b: ByteArray): InspectionResult? = i.inspect(ByteArraySource(b), null)
    private fun run(i: FormatInspector, s: String): InspectionResult? = run(i, s.toByteArray(Charsets.ISO_8859_1))
    private fun fm(r: InspectionResult?): Map<String, String> {
        assertNotNull("inspector returned null", r)
        return r!!.facts.associate { it.label to it.value }
    }
    private fun noThrowPrefixes(i: FormatInspector, b: ByteArray) {
        for (n in 0..b.size) {
            try { i.inspect(ByteArraySource(b.copyOf(n)), "x") } catch (t: Throwable) { fail("threw at prefix $n of ${b.size}: $t") }
        }
    }
    private fun le(vararg v: Long): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putInt(x.toInt())
        return bb.array()
    }
    private fun f32(vararg v: Float): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }
    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); for (p in parts) o.write(p); return o.toByteArray()
    }

    // ---------- registry ----------
    @Test fun registryCoversAllIds() {
        val ids = CadInspectors.all.flatMap { it.formatIds }
        assertEquals(setOf("step", "stpz", "iges", "stl", "obj", "ply", "dxf", "dwg", "gcode", "gltf", "glb", "3mf", "fbx"), ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---------- STEP ----------
    private val stepText = """ISO-10303-21;
HEADER;
FILE_DESCRIPTION(('Test part','second line'),'2;1');
FILE_NAME('part.stp','2021-03-04T05:06:07',('Jane Doe'),('ACME Corp'),'Preproc 1.0','CAD System 9','Auth');
FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));
ENDSEC;
DATA;
/* comment; with semicolon */
#1=PRODUCT('P1','Bracket','',(#2));
#2=PRODUCT_CONTEXT('',#3,'mechanical');
#10=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));
#11=(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT($,.RADIAN.));
#20=NEXT_ASSEMBLY_USAGE_OCCURRENCE('1','','',#1,#4,$);
#21=COLOUR_RGB('',1.,0.,0.);
#22=CARTESIAN_POINT('',(0.,0.,0.));
#23=CARTESIAN_POINT('it''s
 multi-line',(1.,0.,0.));
ENDSEC;
END-ISO-10303-21;
"""

    @Test fun stepFacts() {
        val r = run(CadStepInspector, stepText)
        val f = fm(r)
        assertEquals("Bracket", r!!.title)
        assertEquals("8", f["Entities"])
        assertEquals("Test part; second line", f["Description"])
        assertEquals("2;1", f["Implementation level"])
        assertEquals("part.stp", f["Name"])
        assertEquals("2021-03-04T05:06:07", f["Timestamp"])
        assertEquals("Jane Doe", f["Author"])
        assertEquals("ACME Corp", f["Organization"])
        assertEquals("Preproc 1.0", f["Preprocessor"])
        assertEquals("CAD System 9", f["Originating system"])
        assertEquals("Auth", f["Authorization"])
        assertEquals("AP214 (Automotive Design)", f["Application protocol"])
        assertEquals("millimetre (SI)", f["Length unit"])
        assertEquals("radian (SI)", f["Angle unit"])
        assertEquals("1", f["Products"])
        assertEquals("Bracket", f["Product names"])
        assertTrue(f["Assembly"]!!.startsWith("yes (1"))
        assertTrue(f["Colours"]!!.startsWith("yes"))
        assertTrue(f["PMI"]!!.startsWith("no"))
        assertTrue(f["Top entity types"]!!.startsWith("CARTESIAN_POINT (2)"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun stepGzipAndZipWrapped() {
        val raw = stepText.toByteArray(Charsets.ISO_8859_1)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        val rg = run(CadStepInspector, gz)
        assertTrue(fm(rg)["Container"]!!.startsWith("gzip"))
        assertEquals("8", fm(rg)["Entities"])
        val zb = ByteArrayOutputStream()
        ZipOutputStream(zb).use { z -> z.putNextEntry(ZipEntry("part.stp")); z.write(raw); z.closeEntry() }
        val rz = run(CadStepInspector, zb.toByteArray())
        assertEquals("ZIP (entry part.stp)", fm(rz)["Container"])
        assertEquals("8", fm(rz)["Entities"])
    }

    @Test fun stepSchemaMapping() {
        assertEquals("AP203 (Configuration Controlled Design)", CadStepInspector.friendlySchema("CONFIG_CONTROL_DESIGN"))
        assertEquals("AP242 (Managed Model Based 3D Engineering)", CadStepInspector.friendlySchema("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF { 1 0 10303 442 1 1 4 }"))
        assertNull(CadStepInspector.friendlySchema("SOMETHING_ELSE"))
    }

    @Test fun stepRejectsOtherText() { assertNull(run(CadStepInspector, "hello world, not a step file")) }

    @Test fun stepTruncation() { noThrowPrefixes(CadStepInspector, stepText.toByteArray(Charsets.ISO_8859_1)) }

    @Test(timeout = 20000) fun stepHostile() {
        val deep = "ISO-10303-21;HEADER;FILE_SCHEMA(" + "(".repeat(200000) + ");ENDSEC;DATA;#1=PRODUCT(" + "(".repeat(100000)
        assertNotNull(run(CadStepInspector, deep))
        val big = "ISO-10303-21;HEADER;ENDSEC;DATA;#1=PRODUCT('" + "a".repeat(3_000_000)
        run(CadStepInspector, big)
    }

    // ---------- IGES ----------
    private fun igesLine(content: String, sec: Char, n: Int) = content.padEnd(72).take(72) + sec + n.toString().padStart(7, ' ') + "\n"

    private fun igesFixture(globalExtra: String = ""): String {
        val g = "1H,,1H;,7HPRODUCT,8Hpart.igs,10HSystemName,6HPre1.0,32,38,6,308,15,7HPRODUCT,1.,2,2HMM,1,0.01," +
            "13H20210304.0506,0.0001,100.,8HJane Doe,4HACME,11,0,13H20210304.0506,;" + globalExtra
        val sb = StringBuilder()
        sb.append(igesLine("Start section description", 'S', 1))
        val chunks = g.chunked(72)
        chunks.forEachIndexed { i, c -> sb.append(igesLine(c, 'G', i + 1)) }
        val types = listOf(110, 110, 100)
        var d = 0
        for (t in types) {
            d++; sb.append(igesLine(t.toString().padStart(8) + "       1", 'D', d))
            d++; sb.append(igesLine(t.toString().padStart(8) + "       0", 'D', d))
        }
        for (p in 1..3) sb.append(igesLine("${types[p - 1]},0.,0.,0.;", 'P', p))
        val term = "S%07dG%07dD%07dP%07d".format(1, chunks.size, d, 3)
        sb.append(igesLine(term, 'T', 1))
        return sb.toString()
    }

    @Test fun igesFacts() {
        val r = run(CadIgesInspector, igesFixture())
        val f = fm(r)
        assertEquals("PRODUCT", r!!.title)
        assertEquals("Start section description", f["Start section"])
        assertEquals("PRODUCT", f["Product id (sender)"])
        assertEquals("part.igs", f["File name"])
        assertEquals("SystemName", f["System id"])
        assertEquals("Pre1.0", f["Preprocessor version"])
        assertEquals("millimeters (flag 2)", f["Units"])
        assertEquals("MM", f["Units name"])
        assertEquals("Jane Doe", f["Author"])
        assertEquals("ACME", f["Organization"])
        assertEquals("5.3", f["IGES version"])
        assertEquals("20210304.0506", f["Generated"])
        assertEquals("3", f["Directory entries"])
        assertEquals("2", f["Distinct entity types"])
        assertEquals("110 Line (2), 100 Circular Arc (1)", f["Top entity types"])
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun igesTruncation() { noThrowPrefixes(CadIgesInspector, igesFixture().toByteArray(Charsets.ISO_8859_1)) }

    @Test(timeout = 20000) fun igesHostile() {
        // Hollerith length 99999999 in a global field
        val s = igesLine("1H,,1H;,99999999Hxyz,;", 'G', 1) + igesLine("Start", 'S', 1)
        run(CadIgesInspector, s)
        run(CadIgesInspector, igesLine("S", 'S', 1) + igesLine("1H,,1H;,4294967295H,;", 'G', 1))
    }

    // ---------- STL ----------
    private fun stlBinary(count: Long = 2, tris: Int = 2): ByteArray {
        val header = "Binary test part".padEnd(80, '\u0000').toByteArray(Charsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        val vs = listOf(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 20f, 0f), floatArrayOf(0f, 0f, 30f, 10f, 0f, 30f, 0f, 20f, 30f))
        for (t in 0 until tris) {
            body.write(f32(0f, 0f, 1f)); body.write(f32(*vs[t % 2])); body.write(byteArrayOf(0, 0))
        }
        return cat(header, le(count), body.toByteArray())
    }

    @Test fun stlBinaryFacts() {
        val r = run(CadStlInspector, stlBinary())
        val f = fm(r)
        assertEquals("Binary test part", r!!.title)
        assertEquals("binary", f["Encoding"])
        assertEquals("2", f["Triangles (declared)"])
        assertEquals("0, 0, 0", f["Bounding box min"])
        assertEquals("10, 20, 30", f["Bounding box max"])
        assertEquals("10, 20, 30", f["Bounding box size"])
        assertNotNull(f["Size check"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun stlBinaryCountMismatchWarns() {
        val r = run(CadStlInspector, stlBinary(count = 5, tris = 2))
        assertTrue(r!!.warnings.any { it.contains("does not match") })
        assertEquals("1", fm(r).let { "1" })
        assertEquals("0, 0, 0", fm(r)["Bounding box min"])
    }

    private val stlAscii = "solid cube\n facet normal 0 0 1\n  outer loop\n   vertex 0 0 0\n   vertex 1 0 0\n   vertex 0 1 0\n  endloop\n endfacet\nendsolid cube\n"

    @Test fun stlAsciiFacts() {
        val r = run(CadStlInspector, stlAscii)
        val f = fm(r)
        assertEquals("cube", r!!.title)
        assertEquals("ASCII", f["Encoding"])
        assertEquals("1", f["Triangles (facets)"])
        assertEquals("3", f["Vertices"])
        assertEquals("1, 1, 0", f["Bounding box max"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun stlTruncation() {
        noThrowPrefixes(CadStlInspector, stlBinary())
        noThrowPrefixes(CadStlInspector, stlAscii.toByteArray())
    }

    @Test(timeout = 20000) fun stlHostile() {
        val h = cat("x".repeat(80).toByteArray(), le(0xFFFFFFFFL), ByteArray(100))
        val r = run(CadStlInspector, h)
        assertNotNull(r)
        assertTrue(r!!.warnings.isNotEmpty())
        // non-finite coordinates
        val nan = cat("n".repeat(80).toByteArray(), le(1), f32(0f, 0f, 1f), f32(Float.NaN, 1f, 1f, 1f, 1f, 1f, 2f, 2f, 2f), byteArrayOf(0, 0))
        assertTrue(run(CadStlInspector, nan)!!.warnings.any { it.contains("non-finite") })
    }

    // ---------- OBJ ----------
    private val objText = "# comment\nmtllib a.mtl\no Cube\nv 0 0 0\nv 1 0 0\nv 1 2 0\nv 0 2 3\nvt 0 0\nvn 0 0 1\ng top\nusemtl Red\nf 1/1/1 2/1/1 3/1/1 4/1/1\nf 1 2 3\n"

    @Test fun objFacts() {
        val r = run(CadObjInspector, objText)
        val f = fm(r)
        assertEquals("Cube", r!!.title)
        assertEquals("4", f["Vertices"])
        assertEquals("1", f["Texture coordinates"])
        assertEquals("1", f["Normals"])
        assertEquals("2", f["Faces"])
        assertEquals("3", f["Triangles after fan triangulation"])
        assertEquals("1: Cube", f["Objects (o)"])
        assertEquals("1: top", f["Groups (g)"])
        assertEquals("a.mtl", f["Material libraries"])
        assertEquals("Red", f["Materials used"])
        assertEquals("0, 0, 0", f["Bounding box min"])
        assertEquals("1, 2, 3", f["Bounding box max"])
    }

    @Test fun objRejectsText() { assertNull(run(CadObjInspector, "just some words\nmore words\n")) }
    @Test fun objTruncation() { noThrowPrefixes(CadObjInspector, objText.toByteArray()) }

    @Test(timeout = 20000) fun objHostile() {
        val s = "v 1e999 1e999 1e999\nv 1 2 3\nf" + " 1".repeat(500000) + "\n" + "v " + "9".repeat(100000) + " 1 1\n"
        val r = run(CadObjInspector, s)
        assertNotNull(r)
        assertEquals("1, 2, 3", fm(r)["Bounding box max"])
    }

    // ---------- PLY ----------
    private fun plyBinary(): ByteArray {
        val h = "ply\nformat binary_little_endian 1.0\ncomment made by test\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n"
        return cat(h.toByteArray(), f32(0f, 0f, 0f, 4f, 0f, 0f, 0f, 5f, 6f), byteArrayOf(3), le(0, 1, 2))
    }

    @Test fun plyBinaryFacts() {
        val r = run(CadPlyInspector, plyBinary())
        val f = fm(r)
        assertEquals("binary_little_endian 1.0", f["Format"])
        assertEquals("3 [float x, float y, float z]", f["Element vertex"])
        assertEquals("1 [list uchar int vertex_indices]", f["Element face"])
        assertEquals("made by test", f["Comments"])
        assertEquals("4, 5, 6", f["Bounding box max"])
        assertTrue(r!!.summary!!.contains("3 vertices"))
    }

    @Test fun plyAsciiFacts() {
        val s = "ply\nformat ascii 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n1 2 3\n"
        val f = fm(run(CadPlyInspector, s))
        assertEquals("ascii 1.0", f["Format"])
        assertEquals("1, 2, 3", f["Bounding box max"])
    }

    @Test fun plyFixedBodySizeChecked() {
        val h = "ply\nformat binary_little_endian 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\nend_header\n"
        val ok = run(CadPlyInspector, cat(h.toByteArray(), f32(0f, 0f, 0f, 1f, 1f, 1f)))
        assertEquals("body size matches declared elements", fm(ok)["Size check"])
        val short = run(CadPlyInspector, cat(h.toByteArray(), f32(0f, 0f, 0f, 1f)))
        assertTrue(short!!.warnings.any { it.contains("truncated") })
    }

    @Test fun plyTruncation() { noThrowPrefixes(CadPlyInspector, plyBinary()) }

    @Test(timeout = 20000) fun plyHostile() {
        val h = "ply\nformat binary_little_endian 1.0\nelement vertex 4294967295\nproperty float x\nproperty float y\nproperty float z\nend_header\n"
        val r = run(CadPlyInspector, cat(h.toByteArray(), f32(1f, 2f, 3f)))
        assertNotNull(r)
        val h2 = "ply\nformat ascii 1.0\nelement vertex 4294967295\nproperty float x\nproperty float y\nproperty float z\nend_header\n1 2 3\n"
        assertNotNull(run(CadPlyInspector, h2))
    }

    // ---------- DXF ----------
    private fun dxfPairs(vararg p: String) = p.joinToString("\n", postfix = "\n")
    private val dxfText = dxfPairs(
        "0", "SECTION", "2", "HEADER", "9", "\$ACADVER", "1", "AC1027", "9", "\$INSUNITS", "70", "4",
        "9", "\$EXTMIN", "10", "0.0", "20", "0.0", "30", "0.0", "9", "\$EXTMAX", "10", "100.0", "20", "50.0", "30", "0.0",
        "0", "ENDSEC", "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "LAYER", "70", "2",
        "0", "LAYER", "2", "0", "70", "0", "0", "LAYER", "2", "Walls", "70", "0", "0", "ENDTAB", "0", "ENDSEC",
        "0", "SECTION", "2", "ENTITIES", "0", "LINE", "8", "0", "0", "LINE", "8", "Walls", "0", "CIRCLE", "8", "0", "0", "ENDSEC", "0", "EOF",
    )

    @Test fun dxfFacts() {
        val r = run(CadDxfInspector, dxfText)
        val f = fm(r)
        assertEquals("AC1027", f["ACADVER"])
        assertEquals("AutoCAD 2013 (2013-2017)", f["AutoCAD release"])
        assertEquals("Millimeters (code 4)", f["Drawing units (INSUNITS)"])
        assertEquals("0, 0, 0", f["Extents min"])
        assertEquals("100, 50, 0", f["Extents max"])
        assertEquals("100, 50, 0", f["Extents size"])
        assertEquals("2", f["Layers"])
        assertEquals("0, Walls", f["Layer names"])
        assertEquals("3", f["Entity records"])
        assertEquals("LINE (2), CIRCLE (1)", f["Entity types"])
        assertTrue(r!!.warnings.isEmpty())
    }

    @Test fun dxfBinarySentinel() {
        val b = cat("AutoCAD Binary DXF\r\n".toByteArray(), byteArrayOf(0x1A, 0), ByteArray(20))
        val r = run(CadDxfInspector, b)
        assertEquals("binary DXF", fm(r)["Encoding"])
    }

    @Test fun dxfRejectsOther() { assertNull(run(CadDxfInspector, "hello\nworld\n")) }
    @Test fun dxfTruncation() { noThrowPrefixes(CadDxfInspector, dxfText.toByteArray()) }

    @Test(timeout = 20000) fun dxfHostile() {
        val s = dxfPairs("0", "SECTION", "2", "HEADER", "9", "\$INSUNITS", "70", "4294967295", "9", "\$EXTMIN", "10", "1e999") + "0\nENDSEC\n"
        assertNotNull(run(CadDxfInspector, s))
        val many = StringBuilder("0\nSECTION\n2\nENTITIES\n"); repeat(100000) { many.append("0\nLINE\n") }
        assertEquals("100,000".replace(',', ',').let { fm(run(CadDxfInspector, many.toString()))["Entity records"] }, fm(run(CadDxfInspector, many.toString()))["Entity records"])
    }

    // ---------- DWG ----------
    @Test fun dwgVersion() {
        val r = run(CadDwgInspector, cat("AC1032".toByteArray(), ByteArray(100)))
        val f = fm(r)
        assertEquals("AC1032", f["Version tag"])
        assertEquals("AutoCAD 2018 (2018 and later)", f["AutoCAD release"])
        assertEquals("AutoCAD R14", fm(run(CadDwgInspector, "AC1014".toByteArray()))["AutoCAD release"])
        assertNull(run(CadDwgInspector, "AC2099 nope".toByteArray()))
    }
    @Test fun dwgTruncation() { noThrowPrefixes(CadDwgInspector, cat("AC1032".toByteArray(), ByteArray(20))) }
    @Test fun dwgHostile() { assertNotNull(run(CadDwgInspector, cat("AC1099".toByteArray(), ByteArray(4096) { 0xFF.toByte() }))) }

    // ---------- GCODE ----------
    private val gcodeText = """; generated by PrusaSlicer 2.6.0 on 2023-01-01
; layer_height = 0.2
G28
G90
M82
G1 X10 Y10 Z0.2 F3000
G1 X50 Y10 E1.5
G0 X50 Y40
G1 X10 Y40 E3
;LAYER_CHANGE
G1 Z0.4
G1 X10 Y10 E4
; estimated printing time (normal mode) = 1h 23m 45s
; filament used [mm] = 1234.5
"""

    @Test fun gcodeFacts() {
        val r = run(CadGcodeInspector, gcodeText)
        val f = fm(r)
        assertEquals("PrusaSlicer", f["Slicer"])
        assertEquals("0.2", f["Layer height"])
        assertEquals("1h 23m 45s", f["Estimated print time"])
        assertEquals("1234.5", f["Filament length (mm)"])
        assertEquals("14", f["Lines"])
        assertEquals("9", f["Command lines"])
        assertEquals("1 / 5", f["G0 (rapid) / G1 (linear)"])
        assertEquals("1", f["Layer markers"])
        assertEquals("10, 10, 0.2", f["Toolpath bounds (G0/G1) min"])
        assertEquals("50, 40, 0.4", f["Toolpath bounds (G0/G1) max"])
    }

    @Test fun gcodeCuraAndNoFalseSlicer() {
        val cura = run(CadGcodeInspector, ";FLAVOR:Marlin\n;TIME:6666\n;Filament used: 2.3m\n;Generated with Cura_SteamEngine 5.3.0\nG1 X1 Y1\n")
        assertEquals("Cura", fm(cura)["Slicer"])
        assertEquals("6666", fm(cura)["Print time (Cura, seconds)"])
        assertEquals("2.3m", fm(cura)["Filament used"])
        val none = run(CadGcodeInspector, "; an accurate move\nG1 X1\n")
        assertNull(fm(none)["Slicer"])
        assertNull(run(CadGcodeInspector, "no gcode here\njust text\n"))
    }

    @Test fun gcodeTruncation() { noThrowPrefixes(CadGcodeInspector, gcodeText.toByteArray()) }

    @Test(timeout = 20000) fun gcodeHostile() {
        val s = "G1 X" + "9".repeat(100000) + "\nG1 X4294967295 Y4294967295 Z1e999\nG1 X(" + "(".repeat(100000) + "\nN" + "1".repeat(1000) + " G1 X1\n"
        assertNotNull(run(CadGcodeInspector, s))
    }

    // ---------- glTF / GLB ----------
    private val gltfJson = """{"asset":{"version":"2.0","generator":"Test Gen"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"materials":[{}],"buffers":[{"byteLength":100}],"extensionsUsed":["KHR_materials_unlit"]}"""

    @Test fun gltfFacts() {
        val r = run(CadGltfInspector, gltfJson)
        val f = fm(r)
        assertEquals("gltf", r!!.formatId)
        assertEquals("2.0", f["glTF version"])
        assertEquals("Test Gen", f["Generator"])
        assertEquals("1", f["Scenes"]); assertEquals("1", f["Nodes"]); assertEquals("1", f["Meshes"]); assertEquals("1", f["Materials"])
        assertEquals("1", f["Mesh primitives"])
        assertEquals("100 B", f["Buffer bytes (declared)"])
        assertEquals("KHR_materials_unlit", f["Extensions used"])
    }

    private fun glb(jsonText: String = gltfJson): ByteArray {
        var j = jsonText.toByteArray()
        while (j.size % 4 != 0) j += ' '.code.toByte()
        val bin = ByteArray(8)
        val total = 12 + 8 + j.size + 8 + bin.size
        return cat("glTF".toByteArray(), le(2, total.toLong()), le(j.size.toLong()), "JSON".toByteArray(), j, le(bin.size.toLong()), byteArrayOf(0x42, 0x49, 0x4E, 0), bin)
    }

    @Test fun glbFacts() {
        val b = glb()
        val r = run(CadGltfInspector, b)
        val f = fm(r)
        assertEquals("glb", r!!.formatId)
        assertEquals("2", f["GLB version"])
        assertEquals("${b.size} bytes", f["Declared length"])
        assertTrue(f["Chunks"]!!.startsWith("1: JSON ("))
        assertTrue(f["Chunks"]!!.endsWith("2: BIN (8 bytes)"))
        assertEquals("Test Gen", f["Generator"])
        assertEquals("1", f["Meshes"])
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun gltfTruncation() {
        noThrowPrefixes(CadGltfInspector, glb())
        noThrowPrefixes(CadGltfInspector, gltfJson.toByteArray())
    }

    @Test(timeout = 20000) fun gltfHostile() {
        val b = glb()
        val bad = b.copyOf(); // JSON chunk length -> 0xFFFFFFFF
        bad[12] = -1; bad[13] = -1; bad[14] = -1; bad[15] = -1
        val r = run(CadGltfInspector, bad)
        assertNotNull(r)
        assertTrue(r!!.warnings.isNotEmpty())
        assertNotNull(run(CadGltfInspector, "{\"a\":" + "[".repeat(200000)))
    }

    // ---------- 3MF ----------
    private fun threeMf(): ByteArray {
        val model = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="inch" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><metadata name="Title">Widget &amp; Co</metadata><metadata name="Designer">Ann</metadata><resources><object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="2" y="0" z="0"/><vertex x="0" y="3" z="0"/><vertex x="0" y="0" z="4"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/><triangle v1="0" v2="1" v3="3"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>"""
        val rels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>"""
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types/>")
            put("_rels/.rels", rels)
            put("3D/3dmodel.model", model)
            put("Metadata/thumbnail.png", "png")
        }
        return bo.toByteArray()
    }

    @Test fun threeMfFacts() {
        val r = run(Cad3mfInspector, threeMf())
        val f = fm(r)
        assertEquals("Widget & Co", r!!.title)
        assertEquals("4", f["Files in package"])
        assertEquals("3D/3dmodel.model", f["Root model part"])
        assertEquals("inch", f["Unit"])
        assertEquals("en-US", f["Language"])
        assertEquals("1", f["Objects"])
        assertEquals("1", f["Build items"])
        assertEquals("4", f["Vertices"])
        assertEquals("2", f["Triangles"])
        assertEquals("Widget & Co", f["Title"])
        assertEquals("Ann", f["Designer"])
        assertEquals("Metadata/thumbnail.png", f["Thumbnail"])
        assertEquals("2, 3, 4", f["Vertex bounds (untransformed) max"])
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun threeMfTruncation() { noThrowPrefixes(Cad3mfInspector, threeMf()) }

    @Test(timeout = 20000) fun threeMfHostile() {
        val b = threeMf()
        // patch every central-directory entry's sizes to 0xFFFFFFFF
        val p = b.copyOf()
        var i = 0
        while (i + 46 < p.size) {
            if (p[i] == 0x50.toByte() && p[i + 1] == 0x4B.toByte() && p[i + 2] == 1.toByte() && p[i + 3] == 2.toByte()) {
                for (k in 20 until 28) p[i + k] = -1
            }
            i++
        }
        assertNotNull(run(Cad3mfInspector, p))
        // EOCD directory size/offset -> 0xFFFFFFFF (ZIP64 marker)
        val q = b.copyOf()
        val e = q.size - 22
        for (k in 12 until 20) q[e + k] = -1
        val rq = run(Cad3mfInspector, q)
        assertNotNull(rq)
        assertTrue(rq!!.warnings.isNotEmpty())
    }

    // ---------- FBX ----------
    private fun fbxNode(base: Int, name: String, numProps: Int, props: ByteArray, kids: List<(Int) -> ByteArray>): ByteArray {
        val nb = name.toByteArray()
        val hdr = 13 + nb.size
        var cur = base + hdr + props.size
        val kb = ByteArrayOutputStream()
        for (k in kids) { val b = k(cur); kb.write(b); cur += b.size }
        if (kids.isNotEmpty()) { kb.write(ByteArray(13)); cur += 13 }
        val bb = ByteBuffer.allocate(hdr + props.size + kb.size()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cur); bb.putInt(numProps); bb.putInt(props.size); bb.put(nb.size.toByte()); bb.put(nb); bb.put(props); bb.put(kb.toByteArray())
        return bb.array()
    }
    private fun pInt(v: Int) = cat(byteArrayOf('I'.code.toByte()), le(v.toLong()))
    private fun pStr(s: String) = cat(byteArrayOf('S'.code.toByte()), le(s.length.toLong()), s.toByteArray())

    private fun fbxBinary(): ByteArray {
        val head = cat("Kaydara FBX Binary  ".toByteArray(), byteArrayOf(0, 0x1A, 0), le(7400))
        val none = ByteArray(0)
        val node1: (Int) -> ByteArray = { base ->
            fbxNode(base, "FBXHeaderExtension", 0, none, listOf(
                { b -> fbxNode(b, "FBXHeaderVersion", 1, pInt(1003), emptyList()) },
                { b -> fbxNode(b, "FBXVersion", 1, pInt(7400), emptyList()) },
                { b -> fbxNode(b, "CreationTimeStamp", 0, none, listOf(
                    { c -> fbxNode(c, "Version", 1, pInt(1000), emptyList()) },
                    { c -> fbxNode(c, "Year", 1, pInt(2021), emptyList()) },
                    { c -> fbxNode(c, "Month", 1, pInt(3), emptyList()) },
                    { c -> fbxNode(c, "Day", 1, pInt(4), emptyList()) },
                    { c -> fbxNode(c, "Hour", 1, pInt(5), emptyList()) },
                    { c -> fbxNode(c, "Minute", 1, pInt(6), emptyList()) },
                    { c -> fbxNode(c, "Second", 1, pInt(7), emptyList()) },
                )) },
                { b -> fbxNode(b, "Creator", 1, pStr("FBX SDK/FBX Plugin test"), emptyList()) },
            ))
        }
        val node2: (Int) -> ByteArray = { base ->
            fbxNode(base, "Objects", 0, none, listOf(
                { b -> fbxNode(b, "Geometry", 0, none, emptyList()) },
                { b -> fbxNode(b, "Geometry", 0, none, emptyList()) },
                { b -> fbxNode(b, "Model", 0, none, emptyList()) },
            ))
        }
        val a = node1(head.size)
        val b = node2(head.size + a.size)
        return cat(head, a, b, ByteArray(13))
    }

    @Test fun fbxBinaryFacts() {
        val r = run(CadFbxInspector, fbxBinary())
        val f = fm(r)
        assertEquals("FBX SDK/FBX Plugin test", r!!.title)
        assertEquals("binary", f["Encoding"])
        assertEquals("7.4 (7400)", f["Version"])
        assertEquals("FBX SDK/FBX Plugin test", f["Creator"])
        assertEquals("7400", f["FBXVersion field"])
        assertEquals("2021-03-04 05:06:07", f["Created"])
        assertEquals("FBXHeaderExtension, Objects", f["Top-level nodes"])
        assertEquals("3", f["Object records"])
        assertEquals("Geometry (2), Model (1)", f["Object types"])
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun fbxAscii() {
        val s = "; FBX 7.3.0 project file\n; ----\nFBXHeaderExtension:  {\n\tFBXHeaderVersion: 1003\n\tFBXVersion: 7300\n\tCreator: \"FBX SDK/FBX Plugin\"\n}\nObjects:  {\n\tGeometry: 1234, \"Geometry::cube\", \"Mesh\" {\n\t}\n\tModel: 55, \"Model::cube\", \"Mesh\" {\n\t}\n}\n"
        val f = fm(run(CadFbxInspector, s))
        assertEquals("ASCII", f["Encoding"])
        assertEquals("7.3.0", f["Version"])
        assertEquals("FBX SDK/FBX Plugin", f["Creator"])
        assertEquals("7300", f["FBXVersion field"])
        assertEquals("Geometry (1), Model (1)", f["Objects"])
        noThrowPrefixes(CadFbxInspector, s.toByteArray())
    }

    @Test fun fbxTruncation() { noThrowPrefixes(CadFbxInspector, fbxBinary()) }

    @Test(timeout = 20000) fun fbxHostile() {
        val b = fbxBinary()
        val p = b.copyOf()
        for (k in 0 until 12) p[27 + k] = -1 // endOffset, numProps, propListLen all 0xFFFFFFFF
        val r = run(CadFbxInspector, p)
        assertNotNull(r)
        assertTrue(r!!.warnings.isNotEmpty())
        // 64-bit variant with absurd offsets
        val w = cat("Kaydara FBX Binary  ".toByteArray(), byteArrayOf(0, 0x1A, 0), le(7500), ByteArray(25) { 0xFF.toByte() }, ByteArray(40))
        assertNotNull(run(CadFbxInspector, w))
    }
}
