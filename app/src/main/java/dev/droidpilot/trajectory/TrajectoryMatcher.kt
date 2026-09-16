package dev.droidpilot.trajectory

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Direction
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.UiElement
import kotlin.math.abs

// Decides whether a recorded step still applies to the screen in front of us,
// and which live element it now refers to
object TrajectoryMatcher {

    // Below this the screen has changed enough that replaying is a guess
    const val MIN_ANCHOR_OVERLAP = 0.6

    // A label match this far from the recorded position is probably a different element
    const val MAX_DRIFT_PX = 400

    fun signatureScore(recorded: ScreenSignature, live: ScreenState): Double {
        if (recorded.packageName != live.packageName) return 0.0

        val liveSignature = ScreenSignature.of(live)
        if (recorded.anchors.isEmpty()) {
            // Nothing to compare, fall back to the activity name alone
            return if (recorded.activity != null && recorded.activity == live.activity) 1.0 else 0.0
        }

        val shared = recorded.anchors.count { it in liveSignature.anchors }
        return shared.toDouble() / recorded.anchors.size
    }

    fun matches(recorded: ScreenSignature, live: ScreenState): Boolean =
        signatureScore(recorded, live) >= MIN_ANCHOR_OVERLAP

    // Labels survive layout shifts better than coordinates, so they are tried first
    fun resolve(ref: ElementRef, live: ScreenState): UiElement? {
        if (ref.label != null) {
            // A recorded label only ever resolves to the same label. Falling
            // back to position here would press whatever moved into the old
            // spot, which is how a remembered Cancel becomes an unnamed icon
            return live.elements
                .filter { it.label == ref.label && it.role == ref.role }
                .minByOrNull { drift(ref, it) }
                ?.takeIf { drift(ref, it) <= MAX_DRIFT_PX }
        }

        // Unlabelled icons only ever had their position. Requiring the
        // replacement to be unlabelled too keeps a named button out of the slot
        return live.elements
            .filter { it.role == ref.role && it.clickable && it.label == null }
            .minByOrNull { drift(ref, it) }
            ?.takeIf { drift(ref, it) <= MAX_DRIFT_PX }
    }

    fun toAction(recorded: RecordedAction, live: ScreenState): AgentAction? = when (recorded) {
        is RecordedAction.Tap ->
            resolve(recorded.target, live)?.let { AgentAction.Tap(it.id) }

        is RecordedAction.LongPress ->
            resolve(recorded.target, live)?.let { AgentAction.LongPress(it.id) }

        is RecordedAction.Input ->
            resolve(recorded.target, live)?.let { AgentAction.Input(it.id, recorded.text) }

        is RecordedAction.Swipe -> {
            val direction = runCatching { Direction.valueOf(recorded.direction) }.getOrNull()
            direction?.let { dir ->
                // A swipe without a recorded target still works on the default scrollable
                val id = recorded.target?.let { resolve(it, live)?.id }
                AgentAction.Swipe(dir, id)
            }
        }

        is RecordedAction.Launch -> AgentAction.Launch(recorded.packageName)
        RecordedAction.Back -> AgentAction.Back
        RecordedAction.Home -> AgentAction.Home
        is RecordedAction.Wait -> AgentAction.Wait(recorded.millis)
    }

    private fun drift(ref: ElementRef, element: UiElement): Int =
        abs(ref.centerX - element.bounds.centerX()) + abs(ref.centerY - element.bounds.centerY())
}
