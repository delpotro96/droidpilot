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

    // Kept short on purpose. The same instructions written out at three times
    // this length made a 3B model answer "wait" to every goal it was given -
    // the rules were all still there, and it had stopped reading them
    private val SYSTEM = """
        You operate an Android phone. Choose one action.

        FIRST: the screen below names the app in front. If it is not the app
        the goal needs, your action is launch with a package from the app
        list, copied exactly. Nothing else.

        Otherwise pick an element by its number in brackets.

        - One JSON action, nothing else.
        - Declare risk on every press: "none", "spends" (pays money),
          "irreversible" (sends, deletes, resets). Declaring asks the user; it
          does not stop you. Guessing "none" to avoid the question is the one
          thing you must not do.
        - Goal already done: done. Cannot tell which element: ask.
          Impossible here: fail. Nothing to press: back.
    """.trimIndent()

    // Added only where it applies. Carried on every screen it made the rules
    // above longer than the model would read
    private val VISION = """
        This screen draws its own interface and has no elements to number. Aim
        at the screenshot with tapAt on a 0 to 1000 grid, x left to right, y
        top to bottom, centre 500,500. Nothing here can be read as text, so
        the risk you declare is the only thing between the goal and a purchase.
    """.trimIndent()

    // How many apps are offered. A phone holds a few hundred, most of them
    // system components nobody names in a goal
    const val APP_LIMIT = 60

    fun build(
        goal: Goal,
        state: ScreenState,
        history: List<Step>,
        apps: List<InstalledApp> = emptyList(),
        ownPackage: String? = null
    ): String = buildString {
        append(SYSTEM).append('\n')
        // Attached where a point is the only way in, which is the same test
        // the policy applies. Keyed on isTextUsable it appeared on any screen
        // merely holding a surface, so a video with a buy button beside it was
        // told to aim at the screenshot and then refused for doing it
        if (!state.hasNameableTarget) append('\n').append(VISION).append('\n')
        append('\n')

        append("Goal: ").append(goal.raw).append('\n')
        goal.successHint?.let { append("Done when: ").append(it).append('\n') }
        append('\n')

        if (history.isNotEmpty()) {
            append("Recent actions:\n")
            history.takeLast(HISTORY_LIMIT).forEach { step ->
                append("- ").append(describe(step.action))
                when {
                    // "tapped element 0 (failed)" tells the model the press
                    // happened and did nothing, which is the opposite of what
                    // a refusal means: nothing reached the screen at all
                    step.refused -> append(" (not allowed, choose something else)")
                    !step.succeeded -> append(" (failed)")
                }
                append('\n')
            }
            append('\n')
        }

        // Before the screen, because the first decision of a run is almost
        // always which app to be in, and a list read after the screen was
        // being treated as an afterthought
        if (apps.isNotEmpty()) {
            append("Apps you can open:\n")
            apps.take(APP_LIMIT).forEach {
                append("- ").append(it.label).append("  ").append(it.packageName).append('\n')
            }
            append('\n')
        }

        append("Current screen:\n")
        if (state.packageName == ownPackage) {
            // Shown its own interface, the planner read the goal box as
            // somewhere to type the goal, pressed it, and found the screen
            // unchanged. It did that until the budget ran out
            append("app: ").append(state.packageName).append('\n')
            append("(this is the agent's own screen and none of it belongs")
            append(" to the goal - open the app you need)\n")
        } else {
            append(ScreenSerializer.toPrompt(state))
        }
        append('\n')

        // Counts what was actually done. Refusals were in here, and a screen
        // the planner misread four times reported a negative budget; so was
        // every entry of a history that outlives the budget, which reported
        // minus a hundred and sixty
        val spent = history.count { !it.refused }
        append("Budget: ").append((goal.stepBudget - spent).coerceAtLeast(0))
        append(" actions left\n\n")
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
