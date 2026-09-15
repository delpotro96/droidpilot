package dev.droidpilot.trajectory

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.ScreenState
import java.util.UUID

// Captures a run so it can be replayed without a planner next time
class TrajectoryRecorder(private val goal: String) {

    private val steps = mutableListOf<RecordedStep>()

    // Returns false when the action carries no replayable meaning
    fun record(action: AgentAction, state: ScreenState): Boolean {
        val recorded = convert(action, state) ?: return false
        steps += RecordedStep(recorded, ScreenSignature.of(state))
        return true
    }

    fun build(): Trajectory? {
        if (steps.isEmpty()) return null
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
            AgentAction.Back -> RecordedAction.Back
            AgentAction.Home -> RecordedAction.Home
            is AgentAction.Wait -> RecordedAction.Wait(action.millis)

            // Terminal actions describe the run, not a step to repeat
            is AgentAction.AskUser, is AgentAction.Done, is AgentAction.Fail -> null
        }
    }
}
