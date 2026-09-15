package dev.droidpilot.core.model

// The planner may only emit one of these. Free-form text is not accepted
sealed interface AgentAction {
    data class Tap(val elementId: Int, val risk: Risk = Risk.NONE) : AgentAction
    data class LongPress(val elementId: Int, val risk: Risk = Risk.NONE) : AgentAction
    data class Input(val elementId: Int, val text: String) : AgentAction
    data class Swipe(
        val direction: Direction,
        val elementId: Int? = null,
        val risk: Risk = Risk.NONE
    ) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data class Wait(val millis: Long) : AgentAction

    // A first-class action so an unsure planner asks instead of tapping at random
    data class AskUser(val question: String) : AgentAction

    data class Done(val summary: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

// What the planner says pressing this will do. Inferring it from the label
// failed repeatedly in both directions; the model already knows, so it says
enum class Risk { NONE, SPENDS, IRREVERSIBLE }

// The declared risk of an action, or NONE for anything that presses nothing
val AgentAction.declaredRisk: Risk
    get() = when (this) {
        is AgentAction.Tap -> risk
        is AgentAction.LongPress -> risk
        is AgentAction.Swipe -> risk
        else -> Risk.NONE
    }

sealed interface Verdict {
    data object Allow : Verdict
    data class RequireConfirm(val reason: String) : Verdict
    data class Deny(val reason: String) : Verdict
}

data class Step(
    val action: AgentAction,
    val beforeHash: String,
    val succeeded: Boolean,
    val at: Long = System.currentTimeMillis()
)
