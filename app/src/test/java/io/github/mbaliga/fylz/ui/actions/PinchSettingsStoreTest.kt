package io.github.mbaliga.fylz.ui.actions

import io.github.mbaliga.fylz.model.PinchInBehavior
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [PinchSettingsStore] follows [io.github.mbaliga.fylz.operations.VerifySettings]'s own shape --
 * this pins the same contract that store's own tests would: it defaults to [PinchInBehavior.GO_UP]
 * with nothing saved, a write is readable back through the SAME instance's [StateFlow][
 * kotlinx.coroutines.flow.StateFlow], and -- the part that matters for "survives a process
 * restart" -- a SECOND store instance built later against the same [android.content.Context]
 * (Robolectric's simulation of that, the same way [io.github.mbaliga.fylz.browse.SessionStoreTest]
 * simulates it for `SessionStore`) reads the value the first one wrote, not the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinchSettingsStoreTest {

    @Test
    fun `a fresh store defaults to GO_UP`() {
        val store = PinchSettingsStore(RuntimeEnvironment.getApplication())
        assertEquals(PinchInBehavior.GO_UP, store.behavior.value)
    }

    @Test
    fun `setBehavior updates the same instance's StateFlow`() {
        val store = PinchSettingsStore(RuntimeEnvironment.getApplication())
        store.setBehavior(PinchInBehavior.DETAIL_LEVEL)
        assertEquals(PinchInBehavior.DETAIL_LEVEL, store.behavior.value)
    }

    @Test
    fun `a later store instance -- simulating a process restart -- reads back what an earlier one saved`() {
        val context = RuntimeEnvironment.getApplication()
        PinchSettingsStore(context).setBehavior(PinchInBehavior.DETAIL_LEVEL)

        val restarted = PinchSettingsStore(context)
        assertEquals(PinchInBehavior.DETAIL_LEVEL, restarted.behavior.value)
    }

    @Test
    fun `setting GO_UP explicitly after DETAIL_LEVEL is also persisted, not just the non-default value`() {
        val context = RuntimeEnvironment.getApplication()
        PinchSettingsStore(context).setBehavior(PinchInBehavior.DETAIL_LEVEL)
        PinchSettingsStore(context).setBehavior(PinchInBehavior.GO_UP)

        val restarted = PinchSettingsStore(context)
        assertEquals(PinchInBehavior.GO_UP, restarted.behavior.value)
    }
}
