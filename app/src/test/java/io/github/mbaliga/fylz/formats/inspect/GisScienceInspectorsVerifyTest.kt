package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GisScienceInspectorsVerifyTest {

    // ---------- helpers ----------
    private class W {
        val o = ByteArrayOutputStream()
        fun b(vararg v: Int) = apply { v.forEach { o.write(it) } }
        fun bytes(x: ByteArray) = apply { o.write(x) }
        fun s(x: String) = apply { o.write(x.toByteArray(Charsets.ISO_8859_1)) }
        fun u16le(v: Int) = b(v and 0xFF, (v shr 8) and 0xFF)
        fun u16be(v: Int) = b((v shr 8) and 0xFF, v and 0xFF)
        fun u32le(v: Long) = b((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())
        fun u32be(v: Long) = b(((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
        fun u64be(v: Long) = u32be(v ushr 32).u32be(v and 0xFFFFFFFFL)
        fun u64le(v: Long) = u32le(v and 0xFFFFFFFFL).u32le(v ushr 32)
        fun f64le(d: Double) = u64le(java.lang.Double.doubleToLongBits(d))
        fun pad(n: Int) = apply { for (i in 0 until n) o.write(0) }
        fun padTo(n: Int) = apply { while (o.size() < n) o.write(0) }
        fun size() = o.size()
        fun done(): ByteArray = o.toByteArray()
    }

    private fun src(b: ByteArray) = ByteArraySource(b)
    private fun facts(r: InspectionResult?) = r!!.facts.associate { (it.group + "/" + it.label) to it.value }
    private fun fact(r: InspectionResult?, label: String): String? = r!!.facts.firstOrNull { it.label == label }?.value
    private fun run(i: FormatInspector, b: ByteArray, name: String? = null) = i.inspect(src(b), name)

    private fun truncations(i: FormatInspector, b: ByteArray, name: String? = null) {
        for (n in 0..b.size) {
            try { i.inspect(src(b.copyOf(n)), name) } catch (t: Throwable) { fail("threw on prefix $n of ${i.formatIds}: $t") }
        }
    }

    /** The fixture followed by zeros up to a nominal 50 GB. */
    private class Huge(val base: ByteArray) : ByteSource {
        override val size: Long = 50L * 1024 * 1024 * 1024
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            val out = ByteArray(n)
            for (k in 0 until n) { val p = offset + k; if (p < base.size) out[k] = base[p.toInt()] }
            return out
        }
    }

    private fun hugeOk(i: FormatInspector, b: ByteArray, name: String? = null) {
        val t0 = System.currentTimeMillis()
        try { i.inspect(Huge(b), name) } catch (t: Throwable) { fail("threw on huge source: $t") }
        assertTrue("slow on huge source", System.currentTimeMillis() - t0 < 20000)
    }

    // ---------- GPX ----------
    private val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="TestApp &amp; Co" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><name>Morning ride</name><time>2024-05-01T06:00:00Z</time></metadata>
<wpt lat="48.85" lon="2.35"><name>Start</name></wpt>
<wpt lat="48.90" lon="2.40"/>
<trk><name>Loop</name><trkseg>
<trkpt lat="48.8" lon="2.3"><ele>35.5</ele><time>2024-05-01T06:01:00Z</time></trkpt>
<trkpt lat="48.9" lon="2.5"><ele>40</ele><time>2024-05-01T07:30:00Z</time></trkpt>
<trkpt lat="999" lon="2.5"/>
</trkseg></trk>
<rte><rtept lat="47" lon="1"/></rte>
</gpx>""".toByteArray()

    fun gpxFacts() {
        val r = run(GisScienceGpxInspector, gpx)
        assertEquals("gpx", r!!.formatId)
        assertEquals("2", fact(r, "Waypoints"))
        assertEquals("1", fact(r, "Tracks"))
        assertEquals("1", fact(r, "Track segments"))
        assertEquals("3", fact(r, "Track points"))
        assertEquals("1", fact(r, "Routes"))
        assertEquals("TestApp & Co", fact(r, "Creator"))
        assertEquals("1.1", fact(r, "GPX version"))
        assertEquals("Morning ride", fact(r, "Name"))
        assertEquals("47", fact(r, "Min latitude"))
        assertEquals("48.9", fact(r, "Max latitude"))
        assertEquals("1", fact(r, "Min longitude"))
        assertEquals("2.5", fact(r, "Max longitude"))
        assertEquals("2024-05-01T06:00:00Z", fact(r, "Earliest time"))
        assertEquals("2024-05-01T07:30:00Z", fact(r, "Latest time"))
        assertEquals("35.5", fact(r, "Min elevation (m)"))
        assertEquals("Loop", fact(r, "Track name"))
        assertTrue(r.warnings.any { it.contains("out-of-range") })
        assertNull(run(GisScienceGpxInspector, "hello".toByteArray()))
    }
    fun gpxRobust() { truncations(GisScienceGpxInspector, gpx); hugeOk(GisScienceGpxInspector, gpx) }

    // ---------- KML ----------
    private val kml = """<?xml version="1.0"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Sights</name>
<Style id="a"><IconStyle/></Style>
<Folder><Placemark><name>P1</name><Point><coordinates>2,48,0</coordinates></Point></Placemark>
<Placemark><name>P2</name><LineString><coordinates>0,0 1,1</coordinates></LineString></Placemark>
<Placemark><Polygon><outerBoundaryIs><LinearRing/></outerBoundaryIs></Polygon></Placemark></Folder>
</Document></kml>""".toByteArray()

    fun kmlFacts() {
        val r = run(GisScienceKmlInspector, kml)
        assertEquals("Sights", fact(r, "Document name"))
        assertEquals("3", fact(r, "Placemarks"))
        assertEquals("1", fact(r, "Folders"))
        assertEquals("1", fact(r, "Points"))
        assertEquals("1", fact(r, "LineStrings"))
        assertEquals("1", fact(r, "Polygons"))
        assertEquals("1", fact(r, "Styles"))
        assertEquals("http://www.opengis.net/kml/2.2", fact(r, "Namespace"))
        assertNull(run(GisScienceKmlInspector, "<html></html>".toByteArray()))
    }
    fun kmlRobust() { truncations(GisScienceKmlInspector, kml); hugeOk(GisScienceKmlInspector, kml) }

    // ---------- GeoJSON ----------
    private val geojson = """{"type":"FeatureCollection","bbox":[-10,40,5,50.5],"crs":{"type":"name","properties":{"name":"EPSG:4326"}},"features":[
{"type":"Feature","properties":{"name":"a","pop":3},"geometry":{"type":"Point","coordinates":[2,48]}},
{"type":"Feature","properties":{"name":"b"},"geometry":{"type":"LineString","coordinates":[[0,41],[4,49]]}},
{"type":"Feature","properties":null,"geometry":null}]}""".toByteArray()

    fun geojsonFacts() {
        val r = run(GisScienceGeoJsonInspector, geojson)
        assertEquals("FeatureCollection", fact(r, "Type"))
        assertEquals("3", fact(r, "Feature count"))
        assertEquals("LineString x1, Point x1", fact(r, "Geometry types"))
        assertEquals("-10, 40, 5, 50.5", fact(r, "Declared bbox"))
        assertEquals("EPSG:4326", fact(r, "CRS"))
        assertEquals("3", fact(r, "Coordinate positions"))
        assertEquals("0", fact(r, "Computed min x (lon)"))
        assertEquals("49", fact(r, "Computed max y (lat)"))
        assertEquals("name, pop", fact(r, "Property keys (sample)"))
        assertEquals("1", fact(r, "Features without geometry"))
        assertNull(run(GisScienceGeoJsonInspector, """{"hello":1}""".toByteArray()))
    }
    fun geojsonRobust() {
        truncations(GisScienceGeoJsonInspector, geojson); hugeOk(GisScienceGeoJsonInspector, geojson)
        val deep = ByteArray(3_000_000) { '['.code.toByte() }
        val r = run(GisScienceGeoJsonInspector, "{\"type\":\"Point\",\"coordinates\":".toByteArray() + deep)
        assertTrue(r == null || r.warnings.isNotEmpty())
    }

    // ---------- SHP ----------
    private fun shp(recLen: Long = 10): ByteArray {
        val w = W()
        w.u32be(9994).pad(20).u32be(64).u32le(1000).u32le(1)
        w.f64le(1.0).f64le(2.0).f64le(3.0).f64le(4.0).f64le(0.0).f64le(0.0).f64le(0.0).f64le(0.0)
        w.u32be(1).u32be(recLen).u32le(1).f64le(1.0).f64le(2.0)
        return w.done()
    }
    fun shpFacts() {
        val r = run(GisScienceShpInspector, shp())
        assertEquals("Point (1)", fact(r, "Shape type"))
        assertEquals("1000", fact(r, "Version"))
        assertEquals("9994", fact(r, "File code"))
        assertEquals("128 bytes", fact(r, "Declared file length"))
        assertEquals("1", fact(r, "X min")); assertEquals("2", fact(r, "Y min"))
        assertEquals("3", fact(r, "X max")); assertEquals("4", fact(r, "Y max"))
        assertEquals("1", fact(r, "Record count"))
        assertTrue(r!!.warnings.isEmpty())
        assertNull(run(GisScienceShpInspector, ByteArray(200)))
    }
    fun shpRobust() {
        truncations(GisScienceShpInspector, shp()); hugeOk(GisScienceShpInspector, shp())
        val r = run(GisScienceShpInspector, shp(0xFFFFFFFFL))
        assertTrue(r!!.warnings.any { it.contains("extends past") })
    }

    // ---------- FITS ----------
    private fun card(k: String, v: String, str: Boolean = false): ByteArray {
        val s = (k.padEnd(8) + "= " + (if (str) "'" + v.padEnd(8) + "'" else v.padStart(20))).padEnd(80)
        return s.toByteArray(Charsets.ISO_8859_1)
    }
    private fun header(vararg cards: ByteArray): ByteArray {
        val w = W(); cards.forEach { w.bytes(it) }
        w.s("END".padEnd(80))
        while (w.size() % 2880 != 0) w.s(" ")
        return w.done()
    }
    private fun fits(naxis1: String = "2", naxis2: String = "2", bitpix: String = "16"): ByteArray {
        val w = W()
        w.bytes(header(card("SIMPLE", "T"), card("BITPIX", bitpix), card("NAXIS", "2"), card("NAXIS1", naxis1), card("NAXIS2", naxis2),
            card("OBJECT", "M31", true), card("TELESCOP", "Hubble", true), card("DATE-OBS", "2020-01-02T03:04:05", true), card("EXPTIME", "120.5")))
        w.pad(2880)
        w.bytes(header(card("XTENSION", "IMAGE", true), card("BITPIX", "8"), card("NAXIS", "1"), card("NAXIS1", "4"), card("PCOUNT", "0"),
            card("GCOUNT", "1"), card("EXTNAME", "SCI", true)))
        w.pad(2880)
        return w.done()
    }
    fun fitsFacts() {
        val r = run(GisScienceFitsInspector, fits())
        assertEquals("T", fact(r, "SIMPLE"))
        assertEquals("16 (16-bit signed integer)", fact(r, "BITPIX"))
        assertEquals("2", fact(r, "NAXIS"))
        assertEquals("2 x 2", fact(r, "Dimensions"))
        assertEquals("M31", fact(r, "Object"))
        assertEquals("Hubble", fact(r, "Telescope"))
        assertEquals("2020-01-02T03:04:05", fact(r, "Observation date"))
        assertEquals("120.5", fact(r, "Exposure time"))
        assertEquals("1", fact(r, "Extension count"))
        assertEquals("#1 IMAGE 'SCI' [4]", fact(r, "Extension"))
        assertTrue(r!!.warnings.isEmpty())
        assertNull(run(GisScienceFitsInspector, ByteArray(3000)))
    }
    fun fitsRobust() {
        truncations(GisScienceFitsInspector, fits()); hugeOk(GisScienceFitsInspector, fits())
        val r = run(GisScienceFitsInspector, fits("4294967295", "4294967295", "64"))
        assertNotNull(r)
        assertTrue(r!!.warnings.isNotEmpty())
    }

    // ---------- HDF5 ----------
    private fun h5v0(): ByteArray {
        val w = W()
        w.b(0x89, 'H'.code, 'D'.code, 'F'.code, 0x0D, 0x0A, 0x1A, 0x0A)
        w.b(0, 0, 0, 0, 0, 8, 8, 0).u16le(4).u16le(16).u32le(0)
        w.u64le(0).u64le(-1).u64le(200).u64le(-1)
        w.u64le(0).u64le(0x60).u32le(1).u32le(0).pad(16)
        w.padTo(200)
        return w.done()
    }
    private fun h5v2(): ByteArray {
        val w = W()
        w.b(0x89, 'H'.code, 'D'.code, 'F'.code, 0x0D, 0x0A, 0x1A, 0x0A)
        w.b(2, 8, 8, 0).u64le(0).u64le(-1).u64le(100).u64le(0x30).u32le(0)
        w.padTo(100)
        return w.done()
    }
    fun hdf5Facts() {
        val r = run(GisScienceHdf5Inspector, h5v0())
        assertEquals("0", fact(r, "Superblock version"))
        assertEquals("8 bytes", fact(r, "Size of offsets"))
        assertEquals("8 bytes", fact(r, "Size of lengths"))
        assertEquals("4", fact(r, "Group leaf node K"))
        assertEquals("16", fact(r, "Group internal node K"))
        assertEquals("0x60", fact(r, "Root group address"))
        assertEquals("0xc8", fact(r, "End-of-file address"))
        assertTrue(r!!.warnings.isEmpty())
        val r2 = run(GisScienceHdf5Inspector, h5v2())
        assertEquals("2", fact(r2, "Superblock version"))
        assertEquals("0x30", fact(r2, "Root group address"))
        assertEquals("0x64", fact(r2, "End-of-file address"))
        assertNull(run(GisScienceHdf5Inspector, ByteArray(100)))
    }
    fun hdf5Robust() {
        truncations(GisScienceHdf5Inspector, h5v0()); truncations(GisScienceHdf5Inspector, h5v2()); hugeOk(GisScienceHdf5Inspector, h5v0())
        val bad = h5v0(); for (k in 13..14) bad[k] = 0xFF.toByte()
        assertNotNull(run(GisScienceHdf5Inspector, bad))
    }

    // ---------- NetCDF ----------
    private fun nc(ver: Int, dimCount: Long = 2): ByteArray {
        val wide = ver == 5
        val w = W()
        fun nn(v: Long) { if (wide) w.u64be(v) else w.u32be(v) }
        fun name(s: String) { nn(s.length.toLong()); w.s(s); w.pad((4 - s.length % 4) % 4) }
        w.s("CDF").b(ver)
        nn(0)
        w.u32be(10); nn(dimCount)
        if (dimCount == 2L) { name("time"); nn(0); name("x"); nn(3) }
        w.u32be(12); nn(1); name("title"); w.u32be(2); nn(5); w.s("hello").pad(3)
        w.u32be(11); nn(1); name("temp"); nn(2); nn(0); nn(1)
        w.u32be(0); nn(0)
        w.u32be(5); nn(12); if (ver == 1) w.u32be(200) else w.u64be(200)
        return w.done()
    }
    fun netcdfFacts() {
        for (ver in listOf(1, 2, 5)) {
            val r = run(GisScienceNetCdfInspector, nc(ver))
            assertNotNull("v$ver", r)
            assertEquals("unlimited (record)", fact(r, "time"))
            assertEquals("3", fact(r, "x"))
            assertEquals("hello", fact(r, "title"))
            assertEquals("float [time, x]", fact(r, "temp"))
            assertEquals("0", fact(r, "Record count"))
            assertTrue("v$ver ${r!!.warnings}", r.warnings.isEmpty())
        }
        assertEquals("CDF-2 (64-bit offset)", fact(run(GisScienceNetCdfInspector, nc(2)), "Format"))
        assertEquals("CDF-5 (64-bit data)", fact(run(GisScienceNetCdfInspector, nc(5)), "Format"))
        assertNull(run(GisScienceNetCdfInspector, "CDF\u0009xxxxxxxx".toByteArray()))
    }
    fun netcdfRobust() {
        for (v in listOf(1, 5)) truncations(GisScienceNetCdfInspector, nc(v))
        hugeOk(GisScienceNetCdfInspector, nc(1))
        val r = run(GisScienceNetCdfInspector, nc(1, 0xFFFFFFFFL))
        assertNotNull(r); assertTrue(r!!.warnings.isNotEmpty())
    }

    // ---------- NIfTI ----------
    private fun nifti1(dim0: Int = 3): ByteArray {
        val b = ByteArray(352)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 348)
        bb.putShort(40, dim0.toShort()); bb.putShort(42, 64); bb.putShort(44, 64); bb.putShort(46, 30)
        bb.putShort(70, 16); bb.putShort(72, 32)
        bb.putFloat(76, 1f); bb.putFloat(80, 1.5f); bb.putFloat(84, 1.5f); bb.putFloat(88, 3f)
        bb.putFloat(108, 352f); bb.putFloat(112, 1f)
        b[123] = 10
        "test volume".toByteArray().copyInto(b, 148)
        bb.putShort(252, 1); bb.putShort(254, 4)
        "n+1".toByteArray().copyInto(b, 344)
        return b
    }
    private fun nifti2(): ByteArray {
        val b = ByteArray(544)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 540)
        "n+2".toByteArray().copyInto(b, 4); b[7] = 0; b[8] = 0x0D; b[9] = 0x0A; b[10] = 0x1A; b[11] = 0x0A
        bb.putShort(12, 4); bb.putShort(14, 16)
        bb.putLong(16, 3); bb.putLong(24, 10); bb.putLong(32, 20); bb.putLong(40, 5)
        bb.putDouble(112, 2.0); bb.putDouble(120, 2.0); bb.putDouble(128, 4.0)
        bb.putLong(168, 544)
        "nifti two".toByteArray().copyInto(b, 240)
        bb.putInt(344, 0); bb.putInt(348, 2)
        bb.putInt(500, 2)
        return b
    }
    fun niftiFacts() {
        val r = run(GisScienceNiftiInspector, nifti1())
        assertEquals("NIfTI-1 (single file)", fact(r, "NIfTI version"))
        assertEquals("64 x 64 x 30", fact(r, "Dimensions"))
        assertEquals("float32 (16)", fact(r, "Datatype"))
        assertEquals("32", fact(r, "Bits per voxel"))
        assertEquals("1.5 x 1.5 x 3", fact(r, "Voxel size (pixdim)"))
        assertEquals("millimetre", fact(r, "Spatial unit"))
        assertEquals("second", fact(r, "Temporal unit"))
        assertEquals("1 (scanner anatomical)", fact(r, "qform code"))
        assertEquals("4 (MNI 152)", fact(r, "sform code"))
        assertEquals("test volume", fact(r, "Description"))
        val r2 = run(GisScienceNiftiInspector, nifti2())
        assertEquals("NIfTI-2", fact(r2, "NIfTI version"))
        assertEquals("10 x 20 x 5", fact(r2, "Dimensions"))
        assertEquals("int16 (4)", fact(r2, "Datatype"))
        assertEquals("2 x 2 x 4", fact(r2, "Voxel size (pixdim)"))
        assertEquals("2 (aligned anatomical)", fact(r2, "sform code"))
        assertEquals("millimetre", fact(r2, "Spatial unit"))
        assertEquals("nifti two", fact(r2, "Description"))
        assertNull(run(GisScienceNiftiInspector, ByteArray(400)))
    }
    fun niftiRobust() {
        truncations(GisScienceNiftiInspector, nifti1()); truncations(GisScienceNiftiInspector, nifti2())
        hugeOk(GisScienceNiftiInspector, nifti1())
        val r = run(GisScienceNiftiInspector, nifti1(32000))
        assertNotNull(r); assertTrue(r!!.warnings.isNotEmpty())
    }

    // ---------- DICOM ----------
    private fun el(w: W, g: Int, e: Int, vr: String, v: ByteArray, long: Boolean = false, lenOverride: Long? = null) {
        w.u16le(g).u16le(e).s(vr)
        if (long) { w.u16le(0).u32le(lenOverride ?: v.size.toLong()) } else w.u16le(v.size)
        w.bytes(v)
    }
    private fun even(s: String, pad: Char = ' '): ByteArray = (if (s.length % 2 == 1) s + pad else s).toByteArray()
    private fun dicom(ts: String = "1.2.840.10008.1.2.1", hostile: Boolean = false): ByteArray {
        val w = W()
        w.pad(128).s("DICM")
        el(w, 2, 0x10, "UI", even(ts, '\u0000'))
        if (ts == "1.2.840.10008.1.2") {
            fun imp(g: Int, e: Int, v: ByteArray) { w.u16le(g).u16le(e).u32le(v.size.toLong()).bytes(v) }
            imp(8, 0x16, even("1.2.840.10008.5.1.4.1.1.2", '\u0000'))
            imp(8, 0x60, even("MR")); imp(8, 0x70, even("ACME"))
            imp(0x10, 0x10, even("DOE^JOHN"))
            imp(0x28, 0x08, even("12"))
            imp(0x28, 0x10, byteArrayOf(0, 2)); imp(0x28, 0x11, byteArrayOf(0, 1))
            imp(0x7FE0, 0x10, ByteArray(4))
            return w.done()
        }
        el(w, 8, 0x16, "UI", even("1.2.840.10008.5.1.4.1.1.2", '\u0000'))
        el(w, 8, 0x60, "CS", even("CT"))
        el(w, 8, 0x70, "LO", even("ACME"))
        if (hostile) el(w, 9, 0x10, "UN", ByteArray(0), long = true, lenOverride = 0xFFFFFFFEL)
        el(w, 0x10, 0x10, "PN", even("DOE^JOHN"))
        // a sequence with undefined length containing a nested Modality that must be ignored
        w.u16le(8).u16le(0x1140).s("SQ").u16le(0).u32le(0xFFFFFFFFL)
        w.u16le(0xFFFE).u16le(0xE000).u32le(0xFFFFFFFFL)
        el(w, 8, 0x60, "CS", even("XX"))
        w.u16le(0xFFFE).u16le(0xE00D).u32le(0)
        w.u16le(0xFFFE).u16le(0xE0DD).u32le(0)
        el(w, 0x28, 0x08, "IS", even("12"))
        el(w, 0x28, 0x10, "US", byteArrayOf(0, 2))
        el(w, 0x28, 0x11, "US", byteArrayOf(0, 1))
        el(w, 0x7FE0, 0x10, "OW", ByteArray(4), long = true)
        return w.done()
    }
    fun dicomFacts() {
        val r = run(GisScienceDicomInspector, dicom())
        assertEquals("CT", fact(r, "Modality"))
        assertEquals("ACME", fact(r, "Manufacturer"))
        assertEquals("512", fact(r, "Rows"))
        assertEquals("256", fact(r, "Columns"))
        assertEquals("12", fact(r, "Number of frames"))
        assertEquals("1.2.840.10008.1.2.1", fact(r, "Transfer syntax UID"))
        assertEquals("Explicit VR Little Endian", fact(r, "Transfer syntax name"))
        assertEquals("1.2.840.10008.5.1.4.1.1.2 (CT Image Storage)", fact(r, "SOP class"))
        assertEquals("DOE JOHN", fact(r, "Patient name (SENSITIVE)"))
        assertEquals("Patient (sensitive)", r!!.facts.first { it.label == "Patient name (SENSITIVE)" }.group)
        assertEquals("present", fact(r, "Pixel data"))
        assertEquals("CT, 256 x 512", r.summary)
        val ri = run(GisScienceDicomInspector, dicom("1.2.840.10008.1.2"))
        assertEquals("Implicit VR Little Endian", fact(ri, "Transfer syntax name"))
        assertEquals("MR", fact(ri, "Modality"))
        assertEquals("512", fact(ri, "Rows"))
        assertEquals("12", fact(ri, "Number of frames"))
        assertNull(run(GisScienceDicomInspector, ByteArray(200)))
    }
    fun dicomRobust() {
        truncations(GisScienceDicomInspector, dicom()); truncations(GisScienceDicomInspector, dicom("1.2.840.10008.1.2"))
        hugeOk(GisScienceDicomInspector, dicom())
        val r = run(GisScienceDicomInspector, dicom(hostile = true))
        assertNotNull(r)
    }

    // ---------- Parquet ----------
    private class Th {
        val o = ByteArrayOutputStream()
        var last = 0
        fun varint(v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
        fun zz(v: Long) = varint((v shl 1) xor (v shr 63))
        fun field(id: Int, type: Int) { o.write(((id - last) shl 4) or type); last = id }
        fun i32(id: Int, v: Int) { field(id, 5); zz(v.toLong()) }
        fun i64(id: Int, v: Long) { field(id, 6); zz(v) }
        fun str(id: Int, s: String) { field(id, 8); varint(s.length.toLong()); o.write(s.toByteArray()) }
        fun stop() { o.write(0); }
    }
    private fun parquet(hostileList: Boolean = false, hostileFooter: Boolean = false): ByteArray {
        val schemaEl = { f: (Th) -> Unit -> val t = Th(); f(t); t.stop(); t.o.toByteArray() }
        val root = schemaEl { it.str(4, "schema"); it.i32(5, 2) }
        val c1 = schemaEl { it.i32(1, 1); it.i32(3, 1); it.str(4, "id") }
        val c2 = schemaEl { it.i32(1, 6); it.i32(3, 1); it.str(4, "name"); it.i32(6, 0) }
        val t = Th()
        t.i32(1, 1)
        t.field(2, 9)
        if (hostileList) { t.o.write(0xF0 or 12); t.varint(0xFFFFFFFFL) } else {
            t.o.write((3 shl 4) or 12); t.o.write(root); t.o.write(c1); t.o.write(c2)
        }
        t.last = 2
        t.i64(3, 100)
        t.field(4, 9); t.o.write((1 shl 4) or 12)
        val rg = Th(); rg.i64(2, 10); rg.i64(3, 100); rg.stop(); t.o.write(rg.o.toByteArray())
        t.last = 4
        t.str(6, "fylz-test")
        t.stop()
        val footer = t.o.toByteArray()
        val w = W()
        w.s("PAR1").pad(20).bytes(footer).u32le(if (hostileFooter) 0xFFFFFFFFL else footer.size.toLong()).s("PAR1")
        return w.done()
    }
    fun parquetFacts() {
        val r = run(GisScienceParquetInspector, parquet())
        assertEquals("1", fact(r, "Format version"))
        assertEquals("100", fact(r, "Row count"))
        assertEquals("1", fact(r, "Row groups"))
        assertEquals("fylz-test", fact(r, "Created by"))
        assertEquals("2", fact(r, "Top-level columns"))
        assertEquals("INT32 OPTIONAL", fact(r, "id"))
        assertEquals("BYTE_ARRAY [UTF8] OPTIONAL", fact(r, "name"))
        assertEquals("schema", fact(r, "Schema root"))
        assertTrue(r!!.warnings.isEmpty())
        assertNull(run(GisScienceParquetInspector, ByteArray(100)))
    }
    fun parquetRobust() {
        truncations(GisScienceParquetInspector, parquet()); hugeOk(GisScienceParquetInspector, parquet())
        val a = run(GisScienceParquetInspector, parquet(hostileList = true))
        assertNotNull(a); assertTrue(a!!.warnings.isNotEmpty())
        val b = run(GisScienceParquetInspector, parquet(hostileFooter = true))
        assertNotNull(b); assertTrue(b!!.warnings.isNotEmpty())
    }

    // ---------- CSV / TSV ----------
    private val csv = "name,age,city\r\nAlice,30,Paris\r\nBob,25,\"New York, NY\"\r\n".toByteArray()
    fun csvFacts() {
        val r = run(GisScienceCsvInspector, csv, "people.csv")
        assertEquals("csv", r!!.formatId)
        assertEquals("comma", fact(r, "Delimiter"))
        assertEquals("3", fact(r, "Columns"))
        assertEquals("3", fact(r, "Record count (including header)"))
        assertEquals("double quotes used", fact(r, "Quoting"))
        assertEquals("CRLF", fact(r, "Line endings"))
        assertEquals("likely", fact(r, "Header row (heuristic)"))
        assertEquals("name, age, city", fact(r, "Column names"))
        val t = run(GisScienceCsvInspector, "a\tb\n1\t2\n3\t4\n".toByteArray(), "x.tsv")
        assertEquals("tsv", t!!.formatId); assertEquals("tab", fact(t, "Delimiter")); assertEquals("2", fact(t, "Columns"))
        val s = run(GisScienceCsvInspector, "x;y;z\n1;2;3\n4;5;6\n7;8;9\n".toByteArray(), "d.csv")
        assertEquals("semicolon", fact(s, "Delimiter")); assertEquals("3", fact(s, "Columns"))
        val ragged = run(GisScienceCsvInspector, "a,b,c\n1,2\n3,4,5\n6,7,8\n".toByteArray(), "r.csv")
        assertTrue(ragged!!.warnings.any { it.contains("differing") })
        assertNull(run(GisScienceCsvInspector, ByteArray(100)))
    }
    fun csvRobust() {
        truncations(GisScienceCsvInspector, csv, "a.csv"); hugeOk(GisScienceCsvInspector, "a,b\n1,2\n".toByteArray().let { it + ByteArray(0) }, "a.csv")
        val big = ByteArray(5_000_000) { if (it % 10 == 9) '\n'.code.toByte() else if (it % 2 == 0) 'a'.code.toByte() else ','.code.toByte() }
        val r = run(GisScienceCsvInspector, big, "big.csv")
        assertNotNull(r); assertTrue(r!!.warnings.any { it.contains("Only the first") })
        val quote = "\"".repeat(3_000_000).toByteArray()
        run(GisScienceCsvInspector, quote, "q.csv")
    }

    // ---------- JSON / NDJSON ----------
    private val json = "{\n \"name\": \"fylz\",\n \"tags\": [\"a\", \"b\"],\n \"n\": 3,\n \"nested\": {\"x\": {\"y\": null}}\n}\n".toByteArray()
    fun jsonFacts() {
        val r = run(GisScienceJsonInspector, json, "a.json")
        assertEquals("json", r!!.formatId)
        assertEquals("object", fact(r, "Top-level value"))
        assertEquals("4", fact(r, "Top-level members"))
        assertEquals("name, tags, n, nested", fact(r, "Top-level keys (sample)"))
        assertEquals("string", fact(r, "name")); assertEquals("array", fact(r, "tags")); assertEquals("number", fact(r, "n")); assertEquals("object", fact(r, "nested"))
        assertEquals("3", fact(r, "Maximum nesting depth"))
        assertEquals("yes", fact(r, "Brackets balanced"))
        val a = run(GisScienceJsonInspector, "[1, [2,3], {\"a\":1}]".toByteArray(), "b.json")
        assertEquals("array", fact(a, "Top-level value")); assertEquals("3", fact(a, "Array elements"))
        val e = run(GisScienceJsonInspector, "[]".toByteArray(), "e.json")
        assertEquals("0", fact(e, "Array elements"))
        val n = run(GisScienceJsonInspector, "{\"a\":1}\n{\"a\":2,\"b\":3}\n{\"a\":4}\n".toByteArray(), "log.ndjson")
        assertEquals("ndjson", n!!.formatId)
        assertEquals("3", fact(n, "Non-empty records"))
        assertEquals("a", fact(n, "Keys of first record"))
        val u = run(GisScienceJsonInspector, "{\"a\":[1,2".toByteArray(), "t.json")
        assertEquals("no", fact(u, "Brackets balanced"))
        assertNull(run(GisScienceJsonInspector, "hello".toByteArray(), "x.json"))
    }
    fun jsonRobust() {
        truncations(GisScienceJsonInspector, json, "a.json"); hugeOk(GisScienceJsonInspector, json, "a.json")
        val deep = ByteArray(3_000_000) { '['.code.toByte() }
        assertNotNull(run(GisScienceJsonInspector, deep, "d.json"))
        val sp = ByteArray(3_000_000) { '{'.code.toByte() }
        assertNotNull(run(GisScienceJsonInspector, sp, "d.json"))
    }

    // ---------- XML ----------
    private val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE note [<!ENTITY a "b">]>
<!-- c --><root xmlns="urn:a" xmlns:x='urn:x' id="1>2"><x:item/><item><sub>t</sub></item><item/><![CDATA[ <fake> ]]></root>""".toByteArray()
    fun xmlFacts() {
        val r = run(GisScienceXmlInspector, xml, "a.xml")
        assertEquals("1.0", fact(r, "Version")); assertEquals("UTF-8", fact(r, "Encoding")); assertEquals("yes", fact(r, "Standalone"))
        assertEquals("root", fact(r, "Root element"))
        assertEquals("note", fact(r, "DOCTYPE"))
        assertEquals(listOf("(default) = urn:a", "x = urn:x"), r!!.facts.filter { it.label == "xmlns" }.map { it.value })
        assertEquals("5", fact(r, "Elements"))
        assertEquals("3", fact(r, "Maximum depth"))
        assertEquals("item x2, x:item x1", fact(r, "Children of root"))
        assertTrue(r.warnings.isEmpty())
        val open = run(GisScienceXmlInspector, "<a><b>".toByteArray(), "u.xml")
        assertTrue(open!!.warnings.any { it.contains("never closed") })
        assertNull(run(GisScienceXmlInspector, "plain".toByteArray()))
    }
    fun xmlRobust() {
        truncations(GisScienceXmlInspector, xml, "a.xml"); hugeOk(GisScienceXmlInspector, xml, "a.xml")
        val deep = ByteArray(3_000_000) { if (it % 3 == 0) '<'.code.toByte() else if (it % 3 == 1) 'a'.code.toByte() else '>'.code.toByte() }
        val t0 = System.currentTimeMillis()
        assertNotNull(run(GisScienceXmlInspector, deep, "deep.xml"))
        assertTrue(System.currentTimeMillis() - t0 < 20000)
    }

    // ---------- YAML / TOML ----------
    private val yaml = "# cfg\n---\nname: fylz\nversion: 1.2\nitems:\n  - a\n  - b\n\"quoted key\": 1\ntext: |\n  foo: not a key\nlast: end\n".toByteArray()
    fun yamlFacts() {
        val r = run(GisScienceYamlInspector, yaml, "a.yaml")
        assertEquals("mapping", fact(r, "Top-level shape"))
        assertEquals("name, version, items, quoted key, text, last", fact(r, "Top-level keys (sample)"))
        assertEquals("6", fact(r, "Top-level key count"))
        val seq = run(GisScienceYamlInspector, "- a\n- b\n".toByteArray(), "s.yaml")
        assertEquals("sequence", fact(seq, "Top-level shape"))
        assertNull(run(GisScienceYamlInspector, "just some words".toByteArray(), "x.yaml"))
    }
    fun yamlRobust() { truncations(GisScienceYamlInspector, yaml, "a.yaml"); hugeOk(GisScienceYamlInspector, yaml, "a.yaml") }

    private val toml = "# c\ntitle = \"T\"\nport = 80\nlist = [\n  1,\n  2,\n]\nmulti = \"\"\"\nfake = 1\n\"\"\"\n\n[server]\nhost = \"h\"\n[[bin]]\nname = \"a\"\n[[bin]]\nname = \"b\"\n[db.pool]\nsize = 2\n".toByteArray()
    fun tomlFacts() {
        val r = run(GisScienceTomlInspector, toml, "Cargo.toml")
        assertEquals("title, port, list, multi", fact(r, "Top-level keys (sample)"))
        assertEquals("4", fact(r, "Top-level keys"))
        assertEquals("server, db.pool", fact(r, "Table names (sample)"))
        assertEquals("bin x2", fact(r, "Arrays of tables"))
        assertNull(run(GisScienceTomlInspector, "nothing here".toByteArray(), "x.toml"))
    }
    fun tomlRobust() { truncations(GisScienceTomlInspector, toml, "a.toml"); hugeOk(GisScienceTomlInspector, toml, "a.toml") }

    // ---------- registry ----------
    fun registry() {
        val ids = GisScienceInspectors.all.flatMap { it.formatIds }
        assertEquals(ids.toSet().size, ids.size)
        for (id in listOf("gpx", "kml", "geojson", "shp", "fits", "h5", "nc", "nii", "dcm", "parquet", "csv", "tsv", "json", "ndjson", "xml", "yaml", "toml"))
            assertTrue(id, id in ids)
    }

    // ================= VERIFY: fuzz =================
    private class Counting(val d: ByteArray, val nominal: Long? = null) : ByteSource {
        var requested = 0L; var calls = 0
        override val size: Long = nominal ?: d.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            calls++
            if (offset < 0 || length <= 0 || offset >= size) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            requested += n
            val out = ByteArray(n)
            for (k in 0 until n) { val p = offset + k; if (p < d.size) out[k] = d[p.toInt()] }
            return out
        }
    }

    private val failures = ArrayList<String>()
    private fun check(i: FormatInspector, label: String, b: ByteArray, name: String?, nominal: Long? = null) {
        val c = Counting(b, nominal)
        val t0 = System.nanoTime()
        try { i.inspect(c, name) } catch (t: Throwable) { failures.add("$label: threw $t"); return }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (c.requested > FormatInspector.MAX_READ_BYTES) failures.add("$label: requested ${c.requested} bytes")
        if (c.calls > 20000) failures.add("$label: ${c.calls} read calls")
        if (ms > 1500) failures.add("$label: took $ms ms")
    }

    private fun fixtures(): List<Triple<FormatInspector, ByteArray, String?>> = listOf(
        Triple(GisScienceGpxInspector, gpx, "a.gpx"), Triple(GisScienceKmlInspector, kml, "a.kml"),
        Triple(GisScienceGeoJsonInspector, geojson, "a.geojson"), Triple(GisScienceShpInspector, shp(), "a.shp"),
        Triple(GisScienceFitsInspector, fits(), "a.fits"), Triple(GisScienceHdf5Inspector, h5v0(), "a.h5"),
        Triple(GisScienceHdf5Inspector, h5v2(), "b.h5"),
        Triple(GisScienceNetCdfInspector, nc(1), "a.nc"), Triple(GisScienceNetCdfInspector, nc(2), "b.nc"), Triple(GisScienceNetCdfInspector, nc(5), "c.nc"),
        Triple(GisScienceNiftiInspector, nifti1(), "a.nii"), Triple(GisScienceNiftiInspector, nifti2(), "b.nii"),
        Triple(GisScienceDicomInspector, dicom(), "a.dcm"), Triple(GisScienceDicomInspector, dicom("1.2.840.10008.1.2"), "b.dcm"),
        Triple(GisScienceParquetInspector, parquet(), "a.parquet"),
        Triple(GisScienceCsvInspector, csv, "a.csv"), Triple(GisScienceJsonInspector, json, "a.json"),
        Triple(GisScienceXmlInspector, xml, "a.xml"), Triple(GisScienceYamlInspector, yaml, "a.yaml"), Triple(GisScienceTomlInspector, toml, "a.toml"))

    private fun report() { if (failures.isNotEmpty()) fail(failures.size.toString() + " failures:\n" + failures.distinct().take(25).joinToString("\n")) }

    @Test fun fuzzPrefixesAndFlips() {
        for ((i, b, n) in fixtures()) {
            val id = i.formatIds.first() + "/" + n
            for (k in 0..b.size) check(i, "$id prefix $k", b.copyOf(k), n)
            val stride = maxOf(1, b.size / 700)
            var p = 0
            while (p < b.size) {
                for (v in intArrayOf(0xFF, 0x00, 0x80, 0x7F)) {
                    val m = b.copyOf(); m[p] = if (v == 0xFF) (m[p].toInt() xor 0xFF).toByte() else v.toByte()
                    check(i, "$id flip@$p=$v", m, n)
                }
                p += stride
            }
        }
        report()
    }

    @Test fun fuzzFieldOverwrite() {
        val pats = listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0x7F, -1, -1, -1), byteArrayOf(-1, -1, -1, 0x7F), byteArrayOf(0, 0, 0, 0),
            ByteArray(8) { -1 }, byteArrayOf(0x7F, -1, -1, -1, -1, -1, -1, -1), byteArrayOf(-1, -1, -1, -1, -1, -1, -1, 0x7F), ByteArray(8))
        for ((i, b, n) in fixtures()) {
            val id = i.formatIds.first() + "/" + n
            val lim = minOf(b.size, if (b.size > 6000) 6000 else b.size)
            for (p in 0 until lim) for ((pi, pt) in pats.withIndex()) {
                if (p + pt.size > b.size) continue
                val m = b.copyOf(); System.arraycopy(pt, 0, m, p, pt.size)
                check(i, "$id ovw@$p pat$pi", m, n)
            }
        }
        report()
    }

    @Test fun fuzzZerosFfRandom() {
        val rnd = java.util.Random(12345)
        val sizes = intArrayOf(0, 1, 3, 4, 7, 8, 16, 64, 127, 128, 132, 200, 348, 540, 544, 1000, 2880, 5760, 70000)
        for (i in GisScienceInspectors.all) for (n in listOf("a.x", null, "a.csv", "a.tsv", "a.json", "a.ndjson")) {
            for (sz in sizes) {
                check(i, "${i.formatIds}/$n zeros $sz", ByteArray(sz), n)
                check(i, "${i.formatIds}/$n ff $sz", ByteArray(sz) { -1 }, n)
            }
        }
        val magics = listOf("<gpx", "<kml", "{\"type\":\"FeatureCollection\",", "SIMPLE  = ", "CDF\u0001", "CDF\u0002", "CDF\u0005", "PAR1", "\u0089HDF\r\n\u001a\n", "<?xml", "{", "[", "---\n", "[a]\n")
        for (i in GisScienceInspectors.all) for (mg in magics) for (r in 0 until 40) {
            val body = ByteArray(rnd.nextInt(6000)); rnd.nextBytes(body)
            val mb = mg.toByteArray(Charsets.ISO_8859_1)
            check(i, "${i.formatIds} magic '$mg' rnd$r", mb + body, "a.dat")
            val tail = byteArrayOf(80, 65, 82, 49); // trailing PAR1
            check(i, "${i.formatIds} PAR1 both rnd$r", "PAR1".toByteArray() + body + byteArrayOf(rnd.nextInt(2000).toByte(), 0, 0, 0) + tail, null)
        }
        // binary magics
        val bin = listOf(byteArrayOf(0, 0, 0x27, 0x0A), ByteArray(128) + "DICM".toByteArray(), byteArrayOf(0x5C, 1, 0, 0), byteArrayOf(0x1C, 2, 0, 0))
        for (i in GisScienceInspectors.all) for (mg in bin) for (r in 0 until 60) {
            val body = ByteArray(rnd.nextInt(3000)); rnd.nextBytes(body)
            check(i, "${i.formatIds} binmagic ${mg.size} rnd$r", mg + body, null)
        }
        report()
    }

    @Test fun fuzzHuge() {
        for ((i, b, n) in fixtures()) check(i, "${i.formatIds}/$n huge", b, n, 50L * 1024 * 1024 * 1024)
        report()
    }

    // hostile text that targets regex backtracking
    private fun timed(label: String, i: FormatInspector, b: ByteArray, n: String?) {
        val t = Thread { check(i, label, b, n) }
        t.isDaemon = true; t.start(); t.join(6000)
        if (t.isAlive) failures.add("$label: did not finish in 6s")
    }
    @Test fun hostileText() {
        val big = 3_000_000
        fun rep(s: String, total: Int) = StringBuilder().also { while (it.length < total) it.append(s) }.toString().toByteArray()
        timed("gpx <wpt rep", GisScienceGpxInspector, "<gpx>".toByteArray() + rep("<wpt ", big), "a.gpx")
        timed("gpx <metadata rep", GisScienceGpxInspector, "<gpx>".toByteArray() + rep("<metadata>", big), "a.gpx")
        timed("gpx time ws", GisScienceGpxInspector, "<gpx><time>".toByteArray() + rep(" ", big), "a.gpx")
        timed("gpx time rep", GisScienceGpxInspector, "<gpx>".toByteArray() + rep("<time> ", big), "a.gpx")
        timed("gpx ele rep", GisScienceGpxInspector, "<gpx>".toByteArray() + rep("<ele>1", big), "a.gpx")
        timed("kml <Placemark rep", GisScienceKmlInspector, "<kml>".toByteArray() + rep("<Placemark ", big), "a.kml")
        timed("kml Document", GisScienceKmlInspector, "<kml><Document>".toByteArray() + rep("<Placemark><name>", big), "a.kml")
        timed("yaml spaces", GisScienceYamlInspector, "a".toByteArray() + rep(" ", 1_000_000) + "b".toByteArray(), "a.yaml")
        timed("yaml key long", GisScienceYamlInspector, rep("a ", 1_000_000), "a.yaml")
        timed("toml [ spaces", GisScienceTomlInspector, "[".toByteArray() + rep(" ", 1_000_000) + "x".toByteArray(), "a.toml")
        timed("toml kv spaces", GisScienceTomlInspector, "a".toByteArray() + rep(" . a", 300_000), "a.toml")
        timed("toml [[ ", GisScienceTomlInspector, rep("[ ", 500_000), "a.toml")
        timed("xml unclosed", GisScienceXmlInspector, rep("<a b='", big), "a.xml")
        timed("xml many", GisScienceXmlInspector, rep("<a><b/>", big), "a.xml")
        timed("xml comments", GisScienceXmlInspector, "<a>".toByteArray() + rep("<!--x-->", big), "a.xml")
        timed("json deep", GisScienceJsonInspector, rep("[", big), "a.json")
        timed("json many keys", GisScienceJsonInspector, "{".toByteArray() + rep("\"a\":1,", big), "a.json")
        timed("json ndjson", GisScienceJsonInspector, rep("{\"a\":[1,2]}\n", big), "a.ndjson")
        timed("csv quotes", GisScienceCsvInspector, rep("\"a\",\"b\"\n", big), "a.csv")
        timed("csv wide", GisScienceCsvInspector, rep(",", big), "a.csv")
        timed("csv one line", GisScienceCsvInspector, rep("a,b;c|d\t", big), "a.csv")
        timed("csv newlines", GisScienceCsvInspector, rep("\n", big), "a.csv")
        timed("csv 1col", GisScienceCsvInspector, rep("x\n", big), "a.csv")
        timed("geojson big points", GisScienceGeoJsonInspector, "{\"type\":\"MultiPoint\",\"coordinates\":[".toByteArray() + rep("[1,2],", 3_900_000) + "[1,2]]}".toByteArray(), "a.geojson")
        timed("geojson many features", GisScienceGeoJsonInspector, "{\"type\":\"FeatureCollection\",\"features\":[".toByteArray() + rep("{\"type\":\"Feature\",\"properties\":{\"a\":1},\"geometry\":null},", 3_900_000) + "{}]}".toByteArray(), "a.geojson")
        report()
    }
}
