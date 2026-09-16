package io.github.mbaliga.fylz.util

/**
 * Human-readable byte sizes, in binary units.
 *
 * Lifted out of `FylzV1App` so the browser row, the details room and anything else that has to
 * print a size all print it the same way. Providers report sizes in bytes and users read them in
 * units, and two screens disagreeing about which units — or about how many decimals — is the kind
 * of inconsistency nobody files a bug for and everybody notices.
 *
 * Binary units (KiB, MiB) rather than decimal ones, because that is what the values actually are:
 * a `DocumentsProvider` reports `COLUMN_SIZE` in bytes and every threshold in this app (archive
 * limits, history quotas, backup manifests) is a power of 1024.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
