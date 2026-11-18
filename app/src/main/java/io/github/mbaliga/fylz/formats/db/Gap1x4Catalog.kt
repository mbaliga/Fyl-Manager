package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Instrument data, PLC and automation projects, FPGA files, statistics, trading and embroidery formats. */
object Gap1x4Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val G = FormatCategory.GIS
    private val S = FormatCategory.SCIENTIFIC
    private val E = FormatCategory.ENGINEERING
    private val ST = FormatCategory.STATISTICAL
    private val EM = FormatCategory.MANUFACTURING // no EMBROIDERY category exists in the core enum

    val specs: List<FormatSpec> = listOf(
        // ---- GIS ----
        f("laz", "Compressed LAS point cloud", G, d = "LASzip-compressed LAS LiDAR point cloud (shares the LASF header with plain LAS, so it is identified by extension only)."),
        f("aprx", "ArcGIS Pro project", G, container = "zip", d = "ArcGIS Pro project file referencing maps, layouts and data connections."),
        // ---- Scientific instrument data ----
        f("wiff", "SCIEX mass spectrometry data", S, d = "SCIEX Analyst/PeakView mass spectrometry acquisition file."),
        f("fid", "NMR free induction decay", S, d = "Raw NMR time-domain signal (Bruker/Varian directories)."),
        f("xyz", "XYZ coordinates", S, listOf("xyz"), listOf("chemical/x-xyz"), text = true,
            d = "Plain-text molecular geometry: atom count, comment line, then element and x y z per atom."),
        f("ibw", "Igor Pro binary wave", S, d = "WaveMetrics Igor Pro binary wave file."),
        // ---- PLC and industrial automation ----
        f("s7p", "Siemens Step 7 project", E, d = "Siemens Step 7 project file."),
        f("ap15", "Siemens TIA Portal V15 project", E, d = "Siemens TIA Portal V15 project."),
        f("ap16", "Siemens TIA Portal V16 project", E, d = "Siemens TIA Portal V16 project."),
        f("ap17", "Siemens TIA Portal V17 project", E, d = "Siemens TIA Portal V17 project."),
        f("ap18", "Siemens TIA Portal V18 project", E, d = "Siemens TIA Portal V18 project."),
        f("zap15", "Siemens TIA Portal V15 archive", E, d = "Siemens TIA Portal V15 project archive."),
        f("acd", "Rockwell Studio 5000 project", E, d = "Allen-Bradley Studio 5000 / RSLogix 5000 controller project."),
        f("gxw", "Mitsubishi GX Works project", E, d = "Mitsubishi GX Works PLC project."),
        f("gx2", "Mitsubishi GX Works2 project", E, d = "Mitsubishi GX Works2 PLC project."),
        f("cxp", "Omron CX-Programmer project", E, d = "Omron PLC CX-Programmer project."),
        f("smc2", "Omron Sysmac Studio project", E, d = "Omron Sysmac Studio project."),
        f("kpr", "Keyence KV Studio project", E, d = "Keyence KV Studio PLC project."),
        f("tsproj", "Beckhoff TwinCAT project", E, text = true, d = "Beckhoff TwinCAT system project (XML)."),
        f("stu", "Schneider Unity Pro project", E, d = "Schneider Electric Unity Pro PLC project."),
        f("xef", "Schneider Unity Pro export", E, text = true, d = "Schneider Electric Unity Pro XML export."),
        // ---- Modeling ----
        f("slx", "Simulink model", E, container = "zip", d = "MATLAB Simulink model (OPC package)."),
        // ---- Trading ----
        f("mq4", "MetaTrader 4 source", FormatCategory.FINANCIAL, text = true, d = "MetaTrader 4 expert advisor/indicator source."),
        f("mq5", "MetaTrader 5 source", FormatCategory.FINANCIAL, text = true, d = "MetaTrader 5 expert advisor/indicator source."),
        f("mqh", "MetaTrader include file", FormatCategory.FINANCIAL, text = true, d = "MetaQuotes language header/include file."),
        f("ex4", "MetaTrader 4 compiled program", FormatCategory.FINANCIAL, d = "Compiled MetaTrader 4 program."),
        f("ex5", "MetaTrader 5 compiled program", FormatCategory.FINANCIAL, d = "Compiled MetaTrader 5 program."),
        f("hst", "MetaTrader history", FormatCategory.FINANCIAL, d = "MetaTrader historical quote data."),
        // ---- FPGA ----
        f("sof", "Intel FPGA SRAM object file", FormatCategory.FIRMWARE, d = "Intel/Altera FPGA SRAM object file."),
        f("pof", "Intel FPGA programmer object file", FormatCategory.FIRMWARE, d = "Intel/Altera FPGA programmer object file."),
        f("rbf", "Intel FPGA raw binary file", FormatCategory.FIRMWARE, d = "Intel/Altera FPGA raw binary configuration."),
        f("bit", "Xilinx FPGA bitstream", FormatCategory.FIRMWARE, d = "Xilinx FPGA configuration bitstream."),
        f("xdc", "Xilinx design constraints", FormatCategory.FIRMWARE, text = true, d = "Xilinx Vivado design constraints file."),
        // ---- Statistics ----
        f("jrn", "JMP journal", ST, d = "JMP journal file."),
        f("jsl", "JMP script", ST, text = true, d = "JMP scripting language file."),
        f("gph", "Stata graph", ST, d = "Stata graph file."),
        f("ado", "Stata ado program", ST, text = true, d = "Stata automatic-do program file."),
        // ---- Embroidery ----
        f("10o", "Toyota embroidery", EM, listOf("10o"), d = "Toyota embroidery machine design."),
        f("xxx", "Singer embroidery", EM, listOf("xxx"), d = "Singer embroidery machine design."),
    )
}
