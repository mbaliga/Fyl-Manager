package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.FormatInspector

/** Every text / e-mail / PIM / certificate / key / shortcut / Windows-metadata inspector in this group. */
object TextCryptoInspectors {
    val all: List<FormatInspector> = listOf(
        TextFileInspector, EmlInspector, MboxInspector, VcfInspector, IcsInspector,
        PemInspector, DerInspector, PgpInspector, SshPublicKeyInspector,
        LnkInspector, ShortcutInspector, RegistryHiveInspector, EvtxInspector, TorrentInspector,
    )
}
