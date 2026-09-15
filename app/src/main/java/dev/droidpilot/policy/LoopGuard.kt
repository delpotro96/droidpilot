package dev.droidpilot.policy

import android.content.Context
import android.os.BatteryManager
import dev.droidpilot.core.model.ScreenState

// Stops the agent from circling the same screen or draining the battery
class LoopGuard(
    private val context: Context,
    private val stepBudget: Int
) {
    private val recentHashes = ArrayDeque<String>()
    private var steps = 0

    fun record(state: ScreenState) {
        recentHashes.addLast(state.screenHash)
        if (recentHashes.size > WINDOW) recentHashes.removeFirst()
        steps++
    }

    fun abortReason(): String? = when {
        steps >= stepBudget -> "step budget of " + stepBudget + " exceeded"
        isStuck() -> "same screen " + REPEAT_LIMIT + " times, no progress"
        batteryPercent() in 0 until MIN_BATTERY -> "battery at " + batteryPercent() + " percent"
        else -> null
    }

    // Every recent observation landing on the same screen means the agent is looping
    private fun isStuck(): Boolean =
        recentHashes.size >= REPEAT_LIMIT && recentHashes.toSet().size == 1

    private fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private companion object {
        const val WINDOW = 3
        const val REPEAT_LIMIT = 3
        const val MIN_BATTERY = 20
    }
}
