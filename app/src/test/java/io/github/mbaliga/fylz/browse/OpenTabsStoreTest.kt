package io.github.mbaliga.fylz.browse

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P0.10: only a tab the user actually opened should reappear on the next launch -- see
 * [OpenTabsStore]'s own KDoc for the defect this replaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenTabsStoreTest {

    private lateinit var store: OpenTabsStore

    @Before
    fun setUp() {
        store = OpenTabsStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `a fresh store has nothing to restore`() {
        assertEquals(emptyList<Uri>(), store.list())
    }

    @Test
    fun `recorded trees come back in the order they were opened`() {
        val a = Uri.parse("content://fylz/tree/a")
        val b = Uri.parse("content://fylz/tree/b")

        store.record(a)
        store.record(b)

        assertEquals(listOf(a, b), store.list())
    }

    @Test
    fun `recording the same tree twice keeps its original position`() {
        val a = Uri.parse("content://fylz/tree/a")
        val b = Uri.parse("content://fylz/tree/b")

        store.record(a)
        store.record(b)
        store.record(a)

        assertEquals(listOf(a, b), store.list())
    }
}
