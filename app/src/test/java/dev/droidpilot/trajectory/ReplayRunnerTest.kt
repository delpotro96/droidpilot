package dev.droidpilot.trajectory

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.element
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.screen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplayRunnerTest {

    private val performed = mutableListOf<AgentAction>()
    private val asked = mutableListOf<String>()

    private val executor = object : Executor {
        override suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit> {
            performed += action
            return Result.success(Unit)
        }
    }

    private fun runner(
        screens: List<ScreenState>,
        approve: Boolean = true
    ): ReplayRunner {
        var index = 0
        return ReplayRunner(
            executor = executor,
            policy = SafetyPolicy(),
            observe = { screens[minOf(index++, screens.lastIndex)] },
            confirm = { reason -> asked += reason; approve },
            settleMillis = 0L
        )
    }

    private fun path(
        label: String,
        anchors: List<String>,
        packageName: String = "com.example.app"
    ) = Trajectory(
        id = "t",
        goal = "do it",
        steps = listOf(
            RecordedStep(
                RecordedAction.Tap(ElementRef(label, Role.BUTTON, 0, 0, 100, 50)),
                ScreenSignature(packageName, "MainActivity", anchors)
            )
        ),
        recordedAt = 0L
    )

    private val before = screen(hash = "before", elements = listOf(element(0, "Open")))
    private val after = screen(hash = "after", elements = listOf(element(0, "Back")))

    @Test
    fun `a matching path runs and completes`() = runTest {
        val outcome = runner(listOf(before, after)).run(path("Open", listOf("Open")))

        assertEquals(ReplayRunner.Outcome.Completed(verified = true), outcome)
        assertEquals(listOf(AgentAction.Tap(0)), performed)
    }

    @Test
    fun `a screen that did not move completes unverified rather than replanning`() = runTest {
        // Every step has already run on the device. Handing the goal back to
        // the planner from here would risk repeating the final action
        val outcome = runner(listOf(before)).run(path("Open", listOf("Open")))

        assertEquals(ReplayRunner.Outcome.Completed(verified = false), outcome)
    }

    @Test
    fun `a path whose anchors are gone diverges before acting`() = runTest {
        val outcome = runner(listOf(before)).run(path("Open", listOf("Vanished", "Gone")))

        assertTrue(outcome is ReplayRunner.Outcome.Diverged)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a confirmation is asked during replay rather than blocking it`() = runTest {
        val deletable = screen(hash = "before", elements = listOf(element(0, "삭제")))
        // Observed once for the step, once after the answer, once to verify
        val outcome = runner(listOf(deletable, deletable, after)).run(path("삭제", listOf("삭제")))

        assertTrue(outcome is ReplayRunner.Outcome.Completed)
        assertEquals(1, asked.size)
        assertEquals(listOf(AgentAction.Tap(0)), performed)
    }

    @Test
    fun `a screen that moved while the user was deciding is not pressed`() = runTest {
        val deletable = screen(hash = "before", elements = listOf(element(0, "삭제")))
        // The user answered from another app and the screen changed underneath
        val outcome = runner(listOf(deletable, after)).run(path("삭제", listOf("삭제")))

        assertTrue(outcome is ReplayRunner.Outcome.Diverged)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `declining the confirmation stops the replay`() = runTest {
        val deletable = screen(hash = "before", elements = listOf(element(0, "삭제")))
        val outcome = runner(listOf(deletable, after), approve = false).run(path("삭제", listOf("삭제")))

        assertTrue(outcome is ReplayRunner.Outcome.Blocked)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a denied screen blocks the replay without asking`() = runTest {
        // A blocked package is the only thing that denies outright now. Label
        // matching was tried and could not be aimed - it locked chat screens
        // and still missed a real shopping page
        val bank = screen(
            packageName = "viva.republica.toss",
            hash = "before",
            elements = listOf(element(0, "송금하기"))
        )
        val outcome = runner(listOf(bank, after))
            .run(path("송금하기", listOf("송금하기"), packageName = "viva.republica.toss"))

        assertTrue(outcome is ReplayRunner.Outcome.Blocked)
        assertTrue(asked.isEmpty())
        assertTrue(performed.isEmpty())
    }
}
