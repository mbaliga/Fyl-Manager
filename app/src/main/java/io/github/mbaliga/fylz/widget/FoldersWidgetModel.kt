package io.github.mbaliga.fylz.widget

/**
 * One standard shared folder as a pad on the Quick folders widget.
 *
 * Only the platform's standard top-level folders can appear (the same six the storage home lists:
 * `SafStorageProvider.STANDARD_DIRECTORIES`). Their names are public knowledge, so naming them on
 * a home screen discloses nothing; nothing inside them is ever named or counted. A count would go
 * stale unseen whenever another app (the camera, a browser) writes there.
 *
 * @param directory the `Environment.DIRECTORY_*` value, e.g. "Download".
 */
data class FolderPad(val directory: String)

data class FoldersWidgetModel(
    val pads: List<FolderPad>,
    /** True when All files access is granted, so a pad opens its folder directly. */
    val opensDirectly: Boolean,
) {
    /**
     * Without All files access no pad could open its folder (every tap would land on the storage
     * home), so the widget shows that state at every size instead of ordinary-looking pads.
     */
    val needsAccess: Boolean get() = !opensDirectly

    fun padsFor(size: WidgetSize): List<FolderPad> = pads.take(capacity(size))

    companion object {
        /**
         * Pads per size. Below the `pads` template's 2x2 minimum the widget collapses to a single
         * target, which here is the first folder rather than a bare "open app".
         */
        fun capacity(size: WidgetSize): Int = when (size) {
            WidgetSize.COMPACT -> 1
            WidgetSize.SMALL -> 2
            WidgetSize.MEDIUM -> 4
            WidgetSize.LARGE -> 6
        }

        /**
         * @param standard the app's standard folders, in its own order.
         * @param existing which of them exist; only read with access.
         */
        fun from(standard: List<String>, existing: Set<String>, opensDirectly: Boolean): FoldersWidgetModel =
            FoldersWidgetModel(
                pads = if (opensDirectly) standard.filter { it in existing }.map(::FolderPad) else emptyList(),
                opensDirectly = opensDirectly,
            )
    }
}
