package dev.droidpilot.planner

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.InstalledApp
import dev.droidpilot.core.model.GridPoint
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
        - Every press carries a risk you declare:
            "none"          ordinary navigation
            "spends"        pays, orders, subscribes, transfers money
            "irreversible"  sends, deletes, leaves, resets, or confirms one of
                            those. If you are about to press the OK button of a
                            dialog, the risk is whatever the dialog does.
          Declaring it does not stop you - it asks the user first. Guessing
          "none" to avoid the question is the one thing you must not do.
        - Some screens draw their whole interface and expose no elements to
          number, which a game always does. There you are given a screenshot
          instead, and you press with tapAt using a 0 to 1000 grid: x runs left
          to right, y runs top to bottom, so the centre is 500,500. Nothing on
          that screen can be read as text, which means the risk you declare is
          the only thing standing between the goal and a purchase. Read the
          button before you press it.
        - The phone may be showing anything at all when you start, including
          this app. Open what the goal needs with launch, naming a package
          from the list below. Do not invent one: a name that is not on the
          list opens nothing and says nothing.
        - If the screen is still loading, emit wait rather than pressing.
        - If the goal is already achieved, emit done.
        - If the screen does not let you make progress, emit back or swipe.
        - If you cannot tell which element is correct, emit ask. Never guess
          when the wrong choice would be destructive.
        - If the goal is impossible on this screen, emit fail.
    """.trimIndent()

    // How many apps are offered. A phone holds a few hundred, most of them
    // system components nobody names in a goal
    const val APP_LIMIT = 60

    fun build(
        goal: Goal,
        state: ScreenState,
        history: List<Step>,
        apps: List<InstalledApp> = emptyList()
    ): String = buildString {
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

        if (apps.isNotEmpty()) {
            append("Apps you can open:\n")
            apps.take(APP_LIMIT).forEach {
                append("- ").append(it.label).append("  ").append(it.packageName).append('\n')
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
        is AgentAction.TapAt -> "tapped " + at(action.point)
        is AgentAction.LongPressAt -> "long pressed " + at(action.point)
        is AgentAction.Input -> "typed into element " + action.elementId
        is AgentAction.Swipe -> "swiped " + action.direction.name.lowercase()
        is AgentAction.Launch -> "opened " + action.packageName
        AgentAction.Back -> "pressed back"
        AgentAction.Home -> "pressed home"
        is AgentAction.Wait -> "waited " + action.millis + "ms"
        is AgentAction.AskUser -> "asked: " + action.question
        is AgentAction.Done -> "finished: " + action.summary
        is AgentAction.Fail -> "gave up: " + action.reason
    }

    private fun at(point: GridPoint): String = point.x.toString() + "," + point.y
}
