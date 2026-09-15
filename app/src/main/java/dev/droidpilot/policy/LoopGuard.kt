package dev.droidpilot.policy

import android.content.Context
import android.os.BatteryManager
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.ScreenState

// Stops the agent from circling the same screen or draining the battery
class LoopGuard(
    private val context: Context,
    private val stepBudget: Int
) {
    private val recent = ArrayDeque<String>()
    private var steps = 0

    // Waiting is deliberate non-progress, so it must not count towards being
    // stuck. Two consecutive waits used to kill the run outright, even though
    // the prompt tells the planner to wait for a screen to settle
    fun record(state: ScreenState, previousAction: AgentAction? = null) {
        steps++
        if (previousAction is AgentAction.Wait) return

        recent.addLast(state.structureHash)
        if (recent.size > REPEAT_LIMIT) recent.removeFirst()
    }

    fun abortReason(): String? = when {
        steps >= stepBudget -> "step budget of " + stepBudget + " exceeded"
        isStuck() -> "the last " + REPEAT_LIMIT + " actions changed nothing"
        batteryPercent() in 0 until MIN_BATTERY -> "battery at " + batteryPercent() + " percent"
        else -> null
    }

    // Only counts once actions have actually been taken. Recording starts
    // before the first action, so the window has to fill past that opening
    // observation before a repeat means anything
    private fun isStuck(): Boolean =
        recent.size >= REPEAT_LIMIT && steps > REPEAT_LIMIT && recent.toSet().size == 1

    private fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private companion object {
        const val REPEAT_LIMIT = 3
        const val MIN_BATTERY = 20
    }
}
