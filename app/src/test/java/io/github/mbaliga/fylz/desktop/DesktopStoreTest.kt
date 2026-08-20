package io.github.mbaliga.fylz.desktop

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.canvas.TilePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [DesktopStore] is [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled; these tests pin
 * the same contract that store's own test pins -- round-trip, idempotent upsert, the
 * corrupted-current-falls-back-to-backup recovery, a malformed record not sinking the whole list
 * -- plus the two things unique to the desktop: [DesktopPolicy.MAX_ITEMS] eviction on one flat
 * list rather than per-location, and [DesktopStore.seedIfEmpty]'s once-ever contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DesktopStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = DesktopStore(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")
    private fun placement(z: Int = 0) = TilePlacement(0.3f, 0.4f, z)

    private fun folderShortcut(id: String, treeUri: Uri = uri("tree-a"), folderUri: Uri = uri("folder-a")) =
        DesktopItem.FolderShortcut(
            id = id,
            treeUri = treeUri,
            folderUri = folderUri,
            displayName = "Docs",
            placement = placement(),
        )

    private fun fileShortcut(id: String, fileUri: Uri = uri("file-a"), treeUri: Uri? = uri("tree-a")) =
        DesktopItem.FileShortcut(
            id = id,
            uri = fileUri,
            treeUri = treeUri,
            displayName = "notes.txt",
            placement = placement(),
        )

    private fun quickAccessWidget(id: String, target: String = "downloads") =
        DesktopItem.Widget(
            id = id,
            type = DesktopWidgetType.QUICK_ACCESS,
            size = DesktopItemSize.MEDIUM,
            config = mapOf("target" to target, "label" to "My Downloads"),
            placement = placement(),
        )

    // ── round trip ────────────────────────────────────────────────────────────────────

    @Test
    fun `items survive a fresh store instance over the same preferences`() {
        store().upsert(folderShortcut("a"))
        assertEquals(listOf(folderShortcut("a")), store().items())
    }

    @Test
    fun `a fresh preferences file reports an empty list, not a crash`() {
        assertTrue(store().items().isEmpty())
    }

    @Test
    fun `a folder shortcut round-trips every field`() {
        val item = folderShortcut("f1", treeUri = uri("tree-x"), folderUri = uri("folder-x"))
        store().upsert(item)
        assertEquals(item, store().items().single())
    }

    @Test
    fun `a file shortcut round-trips a null treeUri`() {
        val item = fileShortcut("s1", treeUri = null)
        store().upsert(item)
        assertEquals(item, store().items().single())
    }

    @Test
    fun `a file shortcut round-trips a present treeUri`() {
        val item = fileShortcut("s1", treeUri = uri("tree-y"))
        store().upsert(item)
        assertEquals(item, store().items().single())
    }

    @Test
    fun `a widget round-trips its config map including multiple keys`() {
        val item = quickAccessWidget("w1")
        store().upsert(item)
        val restored = store().items().single() as DesktopItem.Widget
        assertEquals(item.config, restored.config)
        assertEquals(DesktopItemSize.MEDIUM, restored.size)
        assertEquals(DesktopWidgetType.QUICK_ACCESS, restored.type)
    }

    @Test
    fun `a widget with an empty config map round-trips as empty, not null`() {
        val item = DesktopItem.Widget(
            id = "w2",
            type = DesktopWidgetType.SHELF,
            size = DesktopItemSize.SMALL,
            config = emptyMap(),
            placement = placement(),
        )
        store().upsert(item)
        assertEquals(emptyMap<String, String>(), (store().items().single() as DesktopItem.Widget).config)
    }

    @Test
    fun `mixed item types all round-trip together in one list`() {
        val store = store()
        store.upsert(folderShortcut("f"))
        store.upsert(fileShortcut("s"))
        store.upsert(quickAccessWidget("w"))

        val ids = store().items().map { it.id }.toSet()
        assertEquals(setOf("f", "s", "w"), ids)
    }

    // ── upsert ────────────────────────────────────────────────────────────────────────

    @Test
    fun `upserting the same id again updates it in place rather than duplicating`() {
        val store = store()
        store.upsert(folderShortcut("f", folderUri = uri("first")))

        store.upsert(folderShortcut("f", folderUri = uri("second")))

        val items = store.items()
        assertEquals(1, items.size)
        assertEquals(uri("second"), (items.single() as DesktopItem.FolderShortcut).folderUri)
    }

    // ── remove ────────────────────────────────────────────────────────────────────────

    @Test
    fun `remove drops exactly the matching id`() {
        val store = store()
        store.upsert(folderShortcut("f"))
        store.upsert(fileShortcut("s"))

        store.remove("f")

        assertEquals(setOf("s"), store.items().map { it.id }.toSet())
    }

    @Test
    fun `removing an id that is not present is a no-op`() {
        val store = store()
        store.upsert(folderShortcut("f"))
        store.remove("nonexistent")
        assertEquals(1, store.items().size)
    }

    // ── place ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `place moves an existing item without touching its other fields`() {
        val store = store()
        store.upsert(folderShortcut("f"))

        store.place("f", TilePlacement(0.9f, 0.1f, 5))

        val moved = store.items().single() as DesktopItem.FolderShortcut
        assertEquals(TilePlacement(0.9f, 0.1f, 5), moved.placement)
        assertEquals("Docs", moved.displayName)
    }

    @Test
    fun `place on an id with nothing on the desktop is a silent no-op`() {
        val store = store()
        store.place("ghost", TilePlacement(0.5f, 0.5f, 1))
        assertTrue(store.items().isEmpty())
    }

    // ── replaceAll ────────────────────────────────────────────────────────────────────

    @Test
    fun `replaceAll swaps the whole list`() {
        val store = store()
        store.upsert(folderShortcut("f"))

        store.replaceAll(listOf(fileShortcut("s")))

        assertEquals(listOf("s"), store.items().map { it.id })
    }

    // ── MAX_ITEMS bound ───────────────────────────────────────────────────────────────

    @Test
    fun `upserting past MAX_ITEMS evicts the oldest item first`() {
        val store = store()
        (1..DesktopPolicy.MAX_ITEMS + 1).forEach { n -> store.upsert(folderShortcut("f$n")) }

        val ids = store.items().map { it.id }
        assertEquals(DesktopPolicy.MAX_ITEMS, ids.size)
        assertFalse("f1" in ids)
        assertTrue("f${DesktopPolicy.MAX_ITEMS + 1}" in ids)
    }

    @Test
    fun `re-upserting an existing item near the cap does not itself evict anything extra`() {
        val store = store()
        (1..DesktopPolicy.MAX_ITEMS).forEach { n -> store.upsert(folderShortcut("f$n")) }

        store.upsert(folderShortcut("f1", folderUri = uri("moved")))

        assertEquals(DesktopPolicy.MAX_ITEMS, store.items().size)
        assertTrue("f1" in store.items().map { it.id })
    }

    // ── migrateUri ────────────────────────────────────────────────────────────────────

    @Test
    fun `migrateUri rewrites a folder shortcut's treeUri and folderUri`() {
        val store = store()
        store.upsert(folderShortcut("f", treeUri = uri("tree-old"), folderUri = uri("folder-old")))

        val changed = store.migrateUri(uri("tree-old"), uri("tree-new"))

        assertTrue(changed)
        val migrated = store.items().single() as DesktopItem.FolderShortcut
        assertEquals(uri("tree-new"), migrated.treeUri)
        assertEquals(uri("folder-old"), migrated.folderUri)
    }

    @Test
    fun `migrateUri rewrites a file shortcut's own uri`() {
        val store = store()
        store.upsert(fileShortcut("s", fileUri = uri("before"), treeUri = null))

        val changed = store.migrateUri(uri("before"), uri("after"))

        assertTrue(changed)
        assertEquals(uri("after"), (store.items().single() as DesktopItem.FileShortcut).uri)
    }

    @Test
    fun `migrateUri rewrites the tree half of a quick-access widget's target config`() {
        val store = store()
        val widget = DesktopItem.Widget(
            id = "w",
            type = DesktopWidgetType.QUICK_ACCESS,
            size = DesktopItemSize.MEDIUM,
            config = mapOf("target" to "tree:${uri("tree-old")}|folder:${uri("folder-old")}"),
            placement = placement(),
        )
        store.upsert(widget)

        val changed = store.migrateUri(uri("tree-old"), uri("tree-new"))

        assertTrue(changed)
        val migrated = store.items().single() as DesktopItem.Widget
        assertEquals("tree:${uri("tree-new")}|folder:${uri("folder-old")}", migrated.config["target"])
    }

    @Test
    fun `migrateUri rewrites the folder half of a quick-access widget's target config`() {
        val store = store()
        val widget = DesktopItem.Widget(
            id = "w",
            type = DesktopWidgetType.QUICK_ACCESS,
            size = DesktopItemSize.MEDIUM,
            config = mapOf("target" to "tree:${uri("tree-a")}|folder:${uri("folder-old")}"),
            placement = placement(),
        )
        store.upsert(widget)

        val changed = store.migrateUri(uri("folder-old"), uri("folder-new"))

        assertTrue(changed)
        val migrated = store.items().single() as DesktopItem.Widget
        assertEquals("tree:${uri("tree-a")}|folder:${uri("folder-new")}", migrated.config["target"])
    }

    @Test
    fun `migrateUri leaves a downloads quick-access target alone`() {
        val store = store()
        store.upsert(quickAccessWidget("w", target = "downloads"))

        val changed = store.migrateUri(uri("tree-old"), uri("tree-new"))

        assertFalse(changed)
        assertEquals("downloads", (store.items().single() as DesktopItem.Widget).config["target"])
    }

    @Test
    fun `migrateUri leaves a non quick-access widget's config alone`() {
        val store = store()
        val widget = DesktopItem.Widget(
            id = "w",
            type = DesktopWidgetType.SHELF,
            size = DesktopItemSize.SMALL,
            config = mapOf("target" to "tree:${uri("tree-old")}|folder:${uri("folder-old")}"),
            placement = placement(),
        )
        store.upsert(widget)

        val changed = store.migrateUri(uri("tree-old"), uri("tree-new"))

        assertFalse(changed)
    }

    @Test
    fun `migrateUri on an untracked uri is a no-op`() {
        val store = store()
        store.upsert(folderShortcut("f"))
        assertFalse(store.migrateUri(uri("untracked"), uri("also-untracked")))
    }

    @Test
    fun `migrateUri onto the same uri is a no-op`() {
        assertFalse(store().migrateUri(uri("a"), uri("a")))
    }

    // ── seedIfEmpty ───────────────────────────────────────────────────────────────────

    @Test
    fun `seedIfEmpty writes the seed on an empty desktop and returns true`() {
        val store = store()
        val seed = listOf(folderShortcut("seed-1"))

        val wrote = store.seedIfEmpty(seed)

        assertTrue(wrote)
        assertEquals(seed, store.items())
    }

    @Test
    fun `seedIfEmpty does nothing when items already exist and returns false`() {
        val store = store()
        store.upsert(fileShortcut("existing"))

        val wrote = store.seedIfEmpty(listOf(folderShortcut("seed-1")))

        assertFalse(wrote)
        assertEquals(listOf("existing"), store.items().map { it.id })
    }

    @Test
    fun `seedIfEmpty never resurrects a desktop the user intentionally emptied`() {
        val store = store()
        val seed = listOf(folderShortcut("seed-1"))
        assertTrue(store.seedIfEmpty(seed))

        store.remove("seed-1")
        assertTrue(store.items().isEmpty())

        val wroteAgain = store.seedIfEmpty(seed)

        assertFalse(wroteAgain)
        assertTrue(store.items().isEmpty())
    }

    @Test
    fun `seedIfEmpty is a one-time decision even across fresh store instances`() {
        val seed = listOf(folderShortcut("seed-1"))
        assertTrue(store().seedIfEmpty(seed))

        store().remove("seed-1")

        assertFalse(store().seedIfEmpty(seed))
    }

    // ── corruption recovery ───────────────────────────────────────────────────────────

    @Test
    fun `a corrupted current list falls back to the last known good backup`() {
        val store = store()
        store.upsert(folderShortcut("a"))
        // This second write is what promotes the first write's payload into the backup slot.
        store.upsert(fileShortcut("b"))

        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", "{not json[")
            .commit()

        assertEquals(setOf("a"), store.items().map { it.id }.toSet())
    }

    @Test
    fun `an unrecognized extra field in a stored record does not break decoding`() {
        val raw = """
            [{"schemaVersion":1,"kind":"folder","id":"a","treeUri":"${uri("tree-a")}",
              "folderUri":"${uri("folder-a")}","displayName":"Docs","x":0.3,"y":0.4,"z":0,
              "futureField":"wat"}]
        """.trimIndent()
        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", raw)
            .commit()

        val items = store().items()

        assertEquals(1, items.size)
        assertEquals("a", items.single().id)
    }

    @Test
    fun `a malformed record is skipped without sinking the rest of the list`() {
        val raw = """
            [
              {"schemaVersion":1,"kind":"folder","id":"good","treeUri":"${uri("tree-a")}",
               "folderUri":"${uri("folder-a")}","displayName":"Docs","x":0.3,"y":0.4,"z":0},
              {"schemaVersion":1,"kind":"folder","id":"bad-missing-fields"},
              {"totally":"unrelated shape"}
            ]
        """.trimIndent()
        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", raw)
            .commit()

        val items = store().items()

        assertEquals(1, items.size)
        assertEquals("good", items.single().id)
    }

    @Test
    fun `an unknown widget type is skipped rather than crashing decode`() {
        val raw = """
            [{"schemaVersion":1,"kind":"widget","id":"w","widgetType":"not_a_real_type",
              "size":"MEDIUM","config":{},"x":0.3,"y":0.4,"z":0}]
        """.trimIndent()
        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", raw)
            .commit()

        assertTrue(store().items().isEmpty())
    }

    @Test
    fun `an unrecognized widget size falls back to MEDIUM rather than failing the record`() {
        val raw = """
            [{"schemaVersion":1,"kind":"widget","id":"w","widgetType":"shelf",
              "size":"GIGANTIC","config":{},"x":0.3,"y":0.4,"z":0}]
        """.trimIndent()
        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", raw)
            .commit()

        val widget = store().items().single() as DesktopItem.Widget
        assertEquals(DesktopItemSize.MEDIUM, widget.size)
    }

    @Test
    fun `an empty preferences value decodes as an empty list, not a crash`() {
        context.getSharedPreferences("fylz_desktop", Context.MODE_PRIVATE)
            .edit()
            .putString("items", "")
            .commit()

        assertTrue(store().items().isEmpty())
    }
}
