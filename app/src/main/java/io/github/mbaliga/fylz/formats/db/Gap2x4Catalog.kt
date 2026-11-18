package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatCategory.MEDICAL
import io.github.mbaliga.fylz.formats.FormatSpec
import io.github.mbaliga.fylz.formats.sig
import io.github.mbaliga.fylz.formats.sigHex

/** Electrophysiology, neuroimaging and clinical interchange formats. */
object Gap2x4Catalog {
    val specs: List<FormatSpec> = listOf(
        FormatSpec("hea", "PhysioNet WFDB header", MEDICAL, listOf("hea"), text = true,
            description = "Text header describing a WFDB record (signals, sampling rate) for PhysioNet data."),
        FormatSpec("scp", "SCP-ECG", MEDICAL, listOf("scp"),
            description = "Standard Communications Protocol for computer-assisted electrocardiography (EN 1064 / ISO 11073-91064)."),
        FormatSpec("gdf", "General Data Format for biosignals", MEDICAL, listOf("gdf"),
            signatures = listOf(sig(0, "GDF ")),
            description = "Biosignal container used by BioSig for EEG, ECG and similar recordings."),
        FormatSpec("xdf", "Extensible Data Format", MEDICAL, listOf("xdf"),
            signatures = listOf(sig(0, "XDF:")),
            description = "Multi-stream time series recordings from Lab Streaming Layer."),
        FormatSpec("snirf", "Shared Near Infrared Spectroscopy Format", MEDICAL, listOf("snirf", "nirs"),
            description = "NIRS data stored in an HDF5 container (the legacy .nirs files are MATLAB MAT files)."),
        FormatSpec("mff", "EGI MFF", MEDICAL, listOf("mff"),
            description = "Magstim EGI Meta File Format for dense-array EEG; usually a directory bundle of XML and binary files."),
        FormatSpec("nwb", "Neurodata Without Borders", MEDICAL, listOf("nwb"),
            description = "Neurophysiology data standard stored in an HDF5 container."),
        FormatSpec("abf", "Axon Binary Format", MEDICAL, listOf("abf"),
            signatures = listOf(sig(0, "ABF2"), sig(0, "ABF ")),
            description = "Axon pCLAMP electrophysiology recordings (ABF1 and ABF2)."),
        FormatSpec("smr", "Spike2 data", MEDICAL, listOf("smr", "smrx"),
            description = "CED Spike2 recording file (.smr 32-bit, .smrx 64-bit)."),
        FormatSpec("plx", "Plexon data", MEDICAL, listOf("plx", "pl2"),
            signatures = listOf(sig(0, "PLEX")),
            description = "Plexon neural recording files (PLX, and the newer PL2)."),
        FormatSpec("nev", "Blackrock neural data", MEDICAL,
            listOf("nev", "ns1", "ns2", "ns3", "ns4", "ns5", "ns6"),
            signatures = listOf(sig(0, "NEURALEV"), sig(0, "NEURALCD"), sig(0, "NEURALSG")),
            description = "Blackrock Microsystems event (NEV) and continuous sample (NSx) recordings."),
        FormatSpec("rhd", "Intan data", MEDICAL, listOf("rhd", "rhs"),
            signatures = listOf(sigHex(0, "02 27 91 C6"), sigHex(0, "AC 27 91 D6")),
            description = "Intan RHD (recording) and RHS (stimulation/recording) headstage data."),
        FormatSpec("vff", "VFF volume", MEDICAL, listOf("vff"),
            signatures = listOf(sig(0, "ncaa")),
            description = "Sun VFF volume with an ASCII header."),
        FormatSpec("dicomdir", "DICOMDIR", MEDICAL, listOf("dicomdir"),
            mimeTypes = listOf("application/dicom"),
            description = "DICOM media directory listing the files on a DICOM disc or folder."),
        FormatSpec("hv", "Interfile header", MEDICAL, listOf("hv", "ifh"),
            signatures = listOf(sig(0, "!INTERFILE")), text = true,
            description = "Interfile nuclear-medicine image header (the data is in a separate file)."),
        FormatSpec("gipl", "GIPL volume", MEDICAL, listOf("gipl"),
            description = "Guys Image Processing Lab volume format."),
        FormatSpec("vmrk", "BrainVision markers", MEDICAL, listOf("vmrk"), text = true,
            description = "BrainVision Core Data Format event marker file."),
        FormatSpec("hl7", "HL7 v2 message", MEDICAL, listOf("hl7"),
            signatures = listOf(sig(0, "MSH|^~\\&")), text = true,
            description = "HL7 version 2 pipe-delimited clinical message starting with an MSH segment."),
        FormatSpec("oebin", "Open Ephys structure file", MEDICAL, listOf("oebin"), text = true,
            description = "Open Ephys binary-format JSON metadata describing continuous and event streams."),
    )
}
