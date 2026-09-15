package dev.droidpilot.trajectory

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import kotlinx.serialization.Serializable

// A path that already worked once, stored so the next run needs no planner
@Serializable
data class Trajectory(
    val id: String,
    val goal: String,
    val steps: List<RecordedStep>,
    val recordedAt: Long,
    val successCount: Int = 0,
    val failureCount: Int = 0
) {
    // A path that keeps failing is worse than replanning from scratch
    val isTrustworthy: Boolean
        get() = failureCount < MAX_FAILURES && successCount >= failureCount

    companion object {
        const val MAX_FAILURES = 3
    }
}

@Serializable
data class RecordedStep(
    val action: RecordedAction,
    val signature: ScreenSignature
)

// AgentAction refers to elements by list index, which is only meaningful
// within one observation. A replayed step has to describe the element itself
@Serializable
sealed interface RecordedAction {
    @Serializable
    data class Tap(val target: ElementRef) : RecordedAction

    @Serializable
    data class LongPress(val target: ElementRef) : RecordedAction

    @Serializable
    data class Input(val target: ElementRef, val text: String) : RecordedAction

    @Serializable
    data class Swipe(val direction: String, val target: ElementRef?) : RecordedAction

    @Serializable
    data object Back : RecordedAction

    @Serializable
    data object Home : RecordedAction

    @Serializable
    data class Wait(val millis: Long) : RecordedAction
}

// How an element was identified when the path was recorded
@Serializable
data class ElementRef(
    val label: String?,
    val role: Role,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    companion object {
        fun of(element: UiElement) = ElementRef(
            label = element.label,
            role = element.role,
            left = element.bounds.left,
            top = element.bounds.top,
            right = element.bounds.right,
            bottom = element.bounds.bottom
        )
    }
}

// Screen identity for replay. The exact screen hash is too strict because
// list contents change between runs, so only stable anchors are kept
@Serializable
data class ScreenSignature(
    val packageName: String,
    val activity: String?,
    val anchors: List<String>
) {
    companion object {
        const val MAX_ANCHORS = 12

        fun of(state: ScreenState) = ScreenSignature(
            packageName = state.packageName,
            activity = state.activity,
            // Interactive labels survive across runs, body text usually does not
            anchors = state.elements
                .filter { it.clickable || it.editable }
                .mapNotNull { it.label }
                .distinct()
                .sorted()
                .take(MAX_ANCHORS)
        )
    }
}
