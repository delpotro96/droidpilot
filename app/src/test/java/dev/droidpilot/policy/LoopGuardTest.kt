package dev.droidpilot.policy

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.droidpilot.screen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoopGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `progress through different screens is not an abort`() {
        val guard = LoopGuard(context, stepBudget = 10)

        listOf("a", "b", "c").forEach { guard.record(screen(hash = it)) }

        assertNull(guard.abortReason())
    }

    @Test
    fun `three identical screens abort the run`() {
        val guard = LoopGuard(context, stepBudget = 10)

        repeat(3) { guard.record(screen(hash = "stuck")) }

        val reason = guard.abortReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("no progress"))
    }

    @Test
    fun `two identical screens are still tolerated`() {
        val guard = LoopGuard(context, stepBudget = 10)

        repeat(2) { guard.record(screen(hash = "stuck")) }

        assertNull(guard.abortReason())
    }

    @Test
    fun `exhausting the step budget aborts the run`() {
        val guard = LoopGuard(context, stepBudget = 3)

        listOf("a", "b", "c").forEach { guard.record(screen(hash = it)) }

        val reason = guard.abortReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("step budget"))
    }

    @Test
    fun `an old repeat outside the window does not trip the detector`() {
        val guard = LoopGuard(context, stepBudget = 10)

        listOf("stuck", "stuck", "moved", "stuck").forEach { guard.record(screen(hash = it)) }

        assertNull(guard.abortReason())
    }
}
