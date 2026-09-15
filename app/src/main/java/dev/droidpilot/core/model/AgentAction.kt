package dev.droidpilot.core.model

// The planner may only emit one of these. Free-form text is not accepted
sealed interface AgentAction {
    data class Tap(val elementId: Int) : AgentAction
    data class LongPress(val elementId: Int) : AgentAction
    data class Input(val elementId: Int, val text: String) : AgentAction
    data class Swipe(val direction: Direction, val elementId: Int? = null) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data class Wait(val millis: Long) : AgentAction

    // A first-class action so an unsure planner asks instead of tapping at random
    data class AskUser(val question: String) : AgentAction

    data class Done(val summary: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

sealed interface Verdict {
    data object Allow : Verdict
    data class RequireConfirm(val reason: String) : Verdict
    data class Deny(val reason: String) : Verdict
}

data class Step(
    val action: AgentAction,
    val beforeHash: String,
    val afterHash: String?,
    val succeeded: Boolean,
    val at: Long = System.currentTimeMillis()
)
