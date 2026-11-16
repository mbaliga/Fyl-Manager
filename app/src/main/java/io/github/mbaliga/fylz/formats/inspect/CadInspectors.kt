package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.FormatInspector

/** Every CAD / 3D / manufacturing inspector in this package. */
object CadInspectors {
    val all: List<FormatInspector> = listOf(
        CadStepInspector, CadIgesInspector, CadStlInspector, CadObjInspector, CadPlyInspector,
        CadDxfInspector, CadDwgInspector, CadGcodeInspector, CadGltfInspector, Cad3mfInspector, CadFbxInspector,
    )
}
