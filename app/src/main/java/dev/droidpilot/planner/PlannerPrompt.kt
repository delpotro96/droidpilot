package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import dev.droidpilot.serializer.ScreenSerializer

// Builds the single-turn prompt the planner answers. One screen, one decision.
// A 4B model handles "pick one entry from a numbered list" far better than it
// handles open-ended planning, so the prompt never asks for more than that
object PlannerPrompt {

    // Older steps stop being useful and crowd out the current screen
    const val HISTORY_LIMIT = 6

    private val SYSTEM = """
        You operate an Android phone by choosing one action at a time.

        You are given a goal and the current screen as a numbered list of
        elements. Choose the single next action that makes progress toward the
        goal. Refer to elements by the number in brackets.

        Rules:
        - Emit exactly one action as JSON, nothing else.
        - If the goal is already achieved, emit done.
        - If the screen does not let you make progress, emit back or swipe.
        - If you cannot tell which element is correct, emit ask. Never guess
          when the wrong choice would be destructive.
        - If the goal is impossible on this screen, emit fail.
    """.trimIndent()

    fun build(goal: Goal, state: ScreenState, history: List<Step>): String = buildString {
        append(SYSTEM).append("\n\n")

        append("Goal: ").append(goal.raw).append('\n')
        goal.successHint?.let { append("Done when: ").append(it).append('\n') }
        append('\n')

        if (history.isNotEmpty()) {
            append("Recent actions:\n")
            history.takeLast(HISTORY_LIMIT).forEach { step ->
                append("- ").append(describe(step.action))
                if (!step.succeeded) append(" (failed)")
                append('\n')
            }
            append('\n')
        }

        append("Current screen:\n")
        append(ScreenSerializer.toPrompt(state))
        append('\n')

        append("Budget: ").append(goal.stepBudget - history.size).append(" actions left\n\n")
        append("Respond with one JSON action, for example ").append(ActionGrammar.EXAMPLE).append('\n')
    }

    private fun describe(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "tapped element " + action.elementId
        is AgentAction.LongPress -> "long pressed element " + action.elementId
        is AgentAction.Input -> "typed into element " + action.elementId
        is AgentAction.Swipe -> "swiped " + action.direction.name.lowercase()
        AgentAction.Back -> "pressed back"
        AgentAction.Home -> "pressed home"
        is AgentAction.Wait -> "waited " + action.millis + "ms"
        is AgentAction.AskUser -> "asked: " + action.question
        is AgentAction.Done -> "finished: " + action.summary
        is AgentAction.Fail -> "gave up: " + action.reason
    }
}
