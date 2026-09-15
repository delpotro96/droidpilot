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
        data object Completed : Outcome
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
                is Verdict.RequireConfirm ->
                    if (!confirm(verdict.reason)) {
                        return Outcome.Blocked(index, "declined: " + verdict.reason)
                    }

                Verdict.Allow -> Unit
            }

            executor.perform(action, state).onFailure {
                return Outcome.Diverged(index, it.message ?: "action failed")
            }

            delay(settleMillis)
        }

        // Replaying every step is not proof the goal was met, but a screen that
        // is byte for byte what it was before the last action is proof that the
        // action did nothing. Without this, a blind coordinate tap that misses
        // is recorded as a success and the failing path gains priority
        val ending = observe()
        if (lastHash != null && ending.screenHash == lastHash) {
            return Outcome.Diverged(
                trajectory.steps.lastIndex,
                "screen unchanged after the final step"
            )
        }

        return Outcome.Completed
    }

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 400L
    }
}
