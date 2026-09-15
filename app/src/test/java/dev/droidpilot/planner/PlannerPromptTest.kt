package dev.droidpilot.planner

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.Step
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerPromptTest {

    private val goal = Goal(raw = "open settings", stepBudget = 40)
    private val state = screen(elements = listOf(element(0, "Settings"), element(1, "Profile")))

    @Test
    fun `the prompt states the goal and lists the screen`() {
        val prompt = PlannerPrompt.build(goal, state, emptyList())

        assertTrue(prompt.contains("Goal: open settings"))
        assertTrue(prompt.contains("[0] button \"Settings\""))
        assertTrue(prompt.contains("[1] button \"Profile\""))
    }

    @Test
    fun `the remaining budget shrinks as history grows`() {
        val history = List(5) { step(AgentAction.Back) }

        assertTrue(PlannerPrompt.build(goal, state, emptyList()).contains("Budget: 40 actions left"))
        assertTrue(PlannerPrompt.build(goal, state, history).contains("Budget: 35 actions left"))
    }

    @Test
    fun `only recent history is included so the screen is not crowded out`() {
        val history = (0 until 20).map { step(AgentAction.Tap(it)) }

        val prompt = PlannerPrompt.build(goal, state, history)

        assertTrue(prompt.contains("tapped element 19"))
        assertFalse(prompt.contains("tapped element 5"))
    }

    @Test
    fun `a failed step is marked so the planner does not repeat it`() {
        val prompt = PlannerPrompt.build(goal, state, listOf(step(AgentAction.Tap(3), succeeded = false)))

        assertTrue(prompt.contains("tapped element 3 (failed)"))
    }

    @Test
    fun `no history section appears on the first turn`() {
        assertFalse(PlannerPrompt.build(goal, state, emptyList()).contains("Recent actions"))
    }

    @Test
    fun `a success hint is passed through when given`() {
        val hinted = goal.copy(successHint = "the settings screen is open")

        assertTrue(PlannerPrompt.build(hinted, state, emptyList()).contains("Done when: the settings screen is open"))
    }

    private fun step(action: AgentAction, succeeded: Boolean = true) =
        Step(action = action, beforeHash = "h", succeeded = succeeded)
}
