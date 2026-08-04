package io.github.mbaliga.fylz.data

/** CharArray passwords are intentionally not converted to immutable Strings. */
internal fun CharArray?.isNullOrEmpty(): Boolean = this == null || isEmpty()
