package dev.droidpilot.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoopGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun guard(budget: Int = 20) = LoopGuard(context, budget)

    @Test
    fun `progress through different screens is not an abort`() {
        val guard = guard()

        listOf("a", "b", "c", "d").forEach { guard.record(screen(hash = it), AgentAction.Back) }

        assertNull(guard.abortReason())
    }

    @Test
    fun `the opening observation does not count towards being stuck`() {
        val guard = guard()

        // One observation before any action, then two actions that change nothing
        guard.record(screen(hash = "stuck"))
        guard.record(screen(hash = "stuck"), AgentAction.Tap(0))
        guard.record(screen(hash = "stuck"), AgentAction.Tap(0))

        assertNull(guard.abortReason())
    }

    @Test
    fun `three actions that change nothing abort the run`() {
        val guard = guard()

        guard.record(screen(hash = "stuck"))
        repeat(3) { guard.record(screen(hash = "stuck"), AgentAction.Tap(0)) }

        val reason = guard.abortReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("changed nothing"))
    }

    @Test
    fun `waiting repeatedly is not being stuck`() {
        val guard = guard()

        guard.record(screen(hash = "loading"))
        repeat(5) { guard.record(screen(hash = "loading"), AgentAction.Wait(500)) }

        assertNull(guard.abortReason())
    }

    @Test
    fun `body text changing does not hide a stuck screen`() {
        val guard = guard()

        // A clock ticking changes screenHash but leaves the controls alone
        guard.record(screen(hash = "t0", structureHash = "same"), null)
        repeat(3) { i ->
            guard.record(
                screen(hash = "t" + (i + 1), structureHash = "same", elements = listOf(element(0, "Retry"))),
                AgentAction.Tap(0)
            )
        }

        assertNotNull(guard.abortReason())
    }

    @Test
    fun `exhausting the step budget aborts the run`() {
        val guard = guard(budget = 3)

        listOf("a", "b", "c").forEach { guard.record(screen(hash = it), AgentAction.Back) }

        val reason = guard.abortReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("step budget"))
    }

    @Test
    fun `an old repeat outside the window does not trip the detector`() {
        val guard = guard()

        listOf("stuck", "stuck", "moved", "stuck").forEach {
            guard.record(screen(hash = it), AgentAction.Tap(0))
        }

        assertNull(guard.abortReason())
    }
}
