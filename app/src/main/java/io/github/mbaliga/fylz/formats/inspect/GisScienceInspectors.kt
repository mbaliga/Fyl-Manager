package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.FormatInspector

/** Every GIS / scientific / data-text inspector in this group. */
object GisScienceInspectors {
    val all: List<FormatInspector> = listOf(
        GisScienceGpxInspector, GisScienceKmlInspector, GisScienceGeoJsonInspector, GisScienceShpInspector,
        GisScienceFitsInspector, GisScienceHdf5Inspector, GisScienceNetCdfInspector, GisScienceNiftiInspector,
        GisScienceDicomInspector, GisScienceParquetInspector,
        GisScienceCsvInspector, GisScienceJsonInspector, GisScienceXmlInspector,
        GisScienceYamlInspector, GisScienceTomlInspector,
    )
}
