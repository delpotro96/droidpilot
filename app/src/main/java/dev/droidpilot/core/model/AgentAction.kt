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

    // A game draws its whole interface into one surface, so there are no
    // elements to number and nothing for the element actions to address. These
    // two carry a point on a fixed grid instead, which the executor scales to
    // the display.
    //
    // The grid rather than pixels because the model is looking at a downscaled
    // screenshot: asking it to convert back to device pixels puts the harder
    // half of the work on the side that is worst at arithmetic, and a path
    // recorded on one screen size would not survive another
    data class TapAt(val point: GridPoint, val risk: Risk = Risk.NONE) : AgentAction
    data class LongPressAt(val point: GridPoint, val risk: Risk = Risk.NONE) : AgentAction

    data object Back : AgentAction
    data object Home : AgentAction
    data class Wait(val millis: Long) : AgentAction

    // A first-class action so an unsure planner asks instead of tapping at random
    data class AskUser(val question: String) : AgentAction

    data class Done(val summary: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

// A point on a square grid laid over the display, independent of resolution
// and orientation. Out of range values are a model mistake rather than an
// intent to press the edge, so they are clamped at construction and never
// reach a gesture as a negative coordinate
data class GridPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..SIDE && y in 0..SIDE) { "point outside the grid: " + x + "," + y }
    }

    // Scaled onto the last addressable pixel rather than onto the width, or
    // the far edge of the grid lands one pixel outside every window on screen.
    // The gesture is dispatched, the callback reports it completed, and the
    // step is recorded a success having pressed nothing at all
    fun toPixels(width: Int, height: Int): Pair<Float, Float> =
        x.toFloat() / SIDE * (width - 1).coerceAtLeast(0) to
            y.toFloat() / SIDE * (height - 1).coerceAtLeast(0)

    companion object {
        // 1000 steps over a 1440px display is finer than a fingertip, and it
        // is what the vision models that emit coordinates are trained on
        const val SIDE = 1000

        fun clamped(x: Int, y: Int) = GridPoint(x.coerceIn(0, SIDE), y.coerceIn(0, SIDE))

        fun of(pixelX: Int, pixelY: Int, width: Int, height: Int): GridPoint {
            if (width <= 0 || height <= 0) return GridPoint(0, 0)
            return clamped(pixelX * SIDE / width, pixelY * SIDE / height)
        }
    }
}

// What the planner says pressing this will do. Inferring it from the label
// failed repeatedly in both directions; the model already knows, so it says
enum class Risk { NONE, SPENDS, IRREVERSIBLE }

// The declared risk of an action, or NONE for anything that presses nothing
val AgentAction.declaredRisk: Risk
    get() = when (this) {
        is AgentAction.Tap -> risk
        is AgentAction.LongPress -> risk
        is AgentAction.Swipe -> risk
        is AgentAction.TapAt -> risk
        is AgentAction.LongPressAt -> risk
        else -> Risk.NONE
    }

// A press aimed at a point rather than an element. Nothing on screen describes
// what it will do, so the keyword net has nothing to read and the declared risk
// is the only guardrail left
val AgentAction.isBlind: Boolean
    get() = this is AgentAction.TapAt || this is AgentAction.LongPressAt

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
