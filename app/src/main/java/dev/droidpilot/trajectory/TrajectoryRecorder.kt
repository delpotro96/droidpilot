package dev.droidpilot.trajectory

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.ScreenState
import java.util.UUID

// Captures a run so it can be replayed without a planner next time
class TrajectoryRecorder(private val goal: String) {

    private val steps = mutableListOf<RecordedStep>()

    // A step that could not be recorded makes every later step unreachable,
    // because replay would arrive at its screen without having done what came
    // before. Storing the remainder produced a path that was wrong from its
    // second step onwards, so one unrecordable step discards the whole run
    private var broken = false

    // A step that ran and failed is the same problem as one that could not be
    // written down: replay would reach the next screen without it having
    // happened. An input that was rejected after the field had already taken
    // focus left a path that taps the box and presses send on an empty one
    fun discard() {
        broken = true
    }

    // Returns false when the action carries no replayable meaning
    fun record(action: AgentAction, state: ScreenState): Boolean {
        val recorded = convert(action, state)
        if (recorded == null) {
            broken = true
            return false
        }
        steps += RecordedStep(recorded, ScreenSignature.of(state))
        return true
    }

    fun build(): Trajectory? {
        if (broken || steps.isEmpty()) return null
        return Trajectory(
            id = UUID.randomUUID().toString(),
            goal = goal,
            steps = steps.toList(),
            recordedAt = System.currentTimeMillis()
        )
    }

    private fun convert(action: AgentAction, state: ScreenState): RecordedAction? {
        fun ref(id: Int) = state.elements.getOrNull(id)?.let { ElementRef.of(it) }

        return when (action) {
            is AgentAction.Tap -> ref(action.elementId)?.let { RecordedAction.Tap(it) }
            is AgentAction.LongPress -> ref(action.elementId)?.let { RecordedAction.LongPress(it) }
            is AgentAction.Input -> ref(action.elementId)?.let { RecordedAction.Input(it, action.text) }
            is AgentAction.Swipe ->
                RecordedAction.Swipe(action.direction.name, action.elementId?.let { ref(it) })
            is AgentAction.Launch -> RecordedAction.Launch(action.packageName)
            AgentAction.Back -> RecordedAction.Back
            AgentAction.Home -> RecordedAction.Home

            // A screen with nothing to address gives the signature no anchors,
            // and the fallback then matches on the activity name alone - which
            // for a game is one name covering every screen it has. A recorded
            // point would replay onto whatever the game happens to be showing,
            // so these runs are not stored at all and always replan
            is AgentAction.TapAt, is AgentAction.LongPressAt -> null

            // Terminal actions describe the run, not a step to repeat
            is AgentAction.AskUser, is AgentAction.Done, is AgentAction.Fail -> null
        }
    }
}
