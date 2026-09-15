package dev.droidpilot.trajectory

import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Verdict
import kotlinx.coroutines.delay

// Replays a recorded path. No planner and no model calls on the happy path,
// which is where almost all of the runtime cost would otherwise go
class ReplayRunner(
    private val executor: Executor,
    private val policy: Policy,
    private val observe: suspend () -> ScreenState,
    private val confirm: suspend (String) -> Boolean,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS
) {

    sealed interface Outcome {
        // Every step ran. Verified says whether the screen actually moved
        // afterwards. An unverified run is not a failure - a switch toggling
        // leaves no trace in the view tree - but it is not evidence either
        data class Completed(val verified: Boolean) : Outcome

        data class Diverged(val atStep: Int, val reason: String) : Outcome
        data class Blocked(val atStep: Int, val reason: String) : Outcome
    }

    suspend fun run(trajectory: Trajectory): Outcome {
        var lastHash: String? = null

        trajectory.steps.forEachIndexed { index, step ->
            val state = observe()
            lastHash = state.screenHash

            if (!TrajectoryMatcher.matches(step.signature, state)) {
                return Outcome.Diverged(index, "screen no longer matches the recorded step")
            }

            val action = TrajectoryMatcher.toAction(step.action, state)
                ?: return Outcome.Diverged(index, "recorded element is not on screen")

            // The recorded path is not a licence to skip the guardrails
            when (val verdict = policy.check(action, state)) {
                is Verdict.Deny -> return Outcome.Blocked(index, verdict.reason)

                // Asking is the point of this verdict. Treating it as a block
                // made any path containing a send or delete button permanently
                // unrunnable, which is most of what this agent is for
                is Verdict.RequireConfirm -> {
                    if (!confirm(verdict.reason)) {
                        return Outcome.Blocked(index, "declined: " + verdict.reason)
                    }
                    // Answering can take minutes, and the user has to leave this
                    // app to see the question at all. Acting on the screen from
                    // before they were asked would press whatever is in front of
                    // them now
                    if (observe().screenHash != state.screenHash) {
                        return Outcome.Diverged(index, "screen moved while waiting for approval")
                    }
                }

                Verdict.Allow -> Unit
            }

            executor.perform(action, state).onFailure {
                return Outcome.Diverged(index, it.message ?: "action failed")
            }

            delay(settleMillis)
        }

        // Replaying every step is not proof the goal was met, and a screen that
        // has not moved is weak evidence the last action missed. It cannot be
        // treated as a divergence: every step has already run on the device, so
        // handing the goal back to the planner from here risks sending twice.
        //
        // Waiting does not move the screen by design, and a toggle or a toast
        // does not show up in the tree at all, so an unmoved screen is reported
        // rather than acted on
        val moved = moved(lastHash)
        return Outcome.Completed(verified = moved)
    }

    // A transition routinely outlasts one settle interval, so an unmoved
    // screen is given a second look before it is reported as unmoved
    private suspend fun moved(lastHash: String?): Boolean {
        if (lastHash == null) return true
        if (observe().screenHash != lastHash) return true

        delay(settleMillis * SETTLE_RETRIES)
        return observe().screenHash != lastHash
    }

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 400L
        const val SETTLE_RETRIES = 3
    }
}
