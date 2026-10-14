package io.github.mbaliga.fylz.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [SessionStore] is a thin SharedPreferences passthrough -- [SessionCodecTest] already pins the
 * JSON shape it stores, so this only needs to pin the store's own contract: nothing to restore
 * until something is saved, and a later save replaces rather than merges with an earlier one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStoreTest {

    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        store = SessionStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `a fresh store has nothing to restore`() {
        assertNull(store.restore())
    }

    @Test
    fun `a saved session comes back exactly as it was saved`() {
        store.save("""{"tabs":[]}""")

        assertEquals("""{"tabs":[]}""", store.restore())
    }

    @Test
    fun `a later save replaces the earlier one, not appends to it`() {
        store.save("""{"tabs":[],"query":"first"}""")
        store.save("""{"tabs":[],"query":"second"}""")

        assertEquals("""{"tabs":[],"query":"second"}""", store.restore())
    }
}
