package dev.droidpilot.agent

import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.Planner
import dev.droidpilot.core.model.Policy
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.trajectory.ReplayRunner
import dev.droidpilot.trajectory.TrajectoryRecorder
import dev.droidpilot.trajectory.TrajectoryStore
import kotlinx.coroutines.delay

// Ties observation, planning, policy and replay into one run.
//
// Replay is tried first because a path that already worked costs no model
// calls. The planner only runs when there is no stored path or the screen has
// moved away from it
class AgentLoop(
    private val planner: Planner,
    private val executor: Executor,
    private val policy: Policy,
    private val store: TrajectoryStore,
    private val guardFactory: (Int) -> LoopGuard,
    private val observe: suspend () -> ScreenState,
    private val confirm: suspend (String) -> Boolean,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    private val onProgress: (String) -> Unit = {}
) {

    sealed interface Result {
        data class Done(val summary: String, val replayed: Boolean) : Result
        data class Failed(val reason: String) : Result
        data class NeedsUser(val question: String) : Result
        data class Blocked(val reason: String) : Result
    }

    suspend fun run(goal: Goal): Result {
        replay(goal)?.let { return it }
        return plan(goal)
    }

    // Returns null when there is nothing to replay or the path no longer fits,
    // which sends the caller on to the planner
    private suspend fun replay(goal: Goal): Result? {
        val trajectory = store.findFor(goal.raw) ?: return null
        onProgress("replaying a stored path of " + trajectory.steps.size + " steps")

        val outcome = ReplayRunner(executor, policy, observe, confirm, settleMillis).run(trajectory)
        return when (outcome) {
            is ReplayRunner.Outcome.Completed -> {
                store.recordOutcome(trajectory.id, success = true)
                Result.Done("replayed a stored path", replayed = true)
            }

            is ReplayRunner.Outcome.Diverged -> {
                store.recordOutcome(trajectory.id, success = false)
                onProgress("replay diverged at step " + outcome.atStep + ", replanning")
                null
            }

            // A guardrail firing is not the path being wrong, so it is not
            // counted as a failure against it
            is ReplayRunner.Outcome.Blocked -> Result.Blocked(outcome.reason)
        }
    }

    private suspend fun plan(goal: Goal): Result {
        val guard = guardFactory(goal.stepBudget)
        val history = mutableListOf<Step>()
        val recorder = TrajectoryRecorder(goal.raw)
        var previousAction: AgentAction? = null

        while (true) {
            val state = observe()
            guard.record(state, previousAction)
            guard.abortReason()?.let { return Result.Failed(it) }

            onProgress("thinking on " + state.packageName + " (" + state.elements.size + " elements)")
            val action = planner.next(goal, state, history)
            onProgress("-> " + action)

            when (action) {
                is AgentAction.Done -> {
                    recorder.build()?.let { store.save(it) }
                    return Result.Done(action.summary, replayed = false)
                }

                is AgentAction.Fail -> return Result.Failed(action.reason)
                is AgentAction.AskUser -> return Result.NeedsUser(action.question)
                else -> Unit
            }

            when (val verdict = policy.check(action, state)) {
                is Verdict.Deny -> return Result.Blocked(verdict.reason)
                is Verdict.RequireConfirm ->
                    if (!confirm(verdict.reason)) return Result.Blocked("declined: " + verdict.reason)
                Verdict.Allow -> Unit
            }

            // The planner round trip can take minutes on a CPU bound model.
            // Acting on the screen it saw would tap coordinates the policy
            // never examined, so a moved screen sends us round again
            val current = observe()
            if (current.screenHash != state.screenHash) {
                onProgress("screen moved while planning, re-observing")
                continue
            }

            val outcome = executor.perform(action, current)
            // A step that failed still belongs in the history, otherwise the
            // planner repeats it forever
            history += Step(
                action = action,
                beforeHash = state.screenHash,
                succeeded = outcome.isSuccess
            )
            if (outcome.isSuccess) recorder.record(action, current)
            previousAction = action

            delay(settleMillis)
        }
    }

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 400L
    }
}
