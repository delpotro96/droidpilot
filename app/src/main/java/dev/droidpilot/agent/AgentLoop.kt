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
        var partial = false
        replay(goal) { partial = it }?.let { return it }

        // A replan that starts halfway through a path only ever records the
        // tail. Saving that would replace a working five step route with a two
        // step one whose first screen is mid flow, which then never matches
        // from the start again
        return plan(goal, mayStore = !partial)
    }

    // Returns null when there is nothing to replay or the path no longer fits,
    // which sends the caller on to the planner
    private suspend fun replay(goal: Goal, onPartial: (Boolean) -> Unit): Result? {
        val trajectory = store.findFor(goal.raw) ?: return null

        // The battery floor applies to replay too, and was only being consulted
        // on the planning path. Checked without an observation, since a fresh
        // guard has no history for the other rules to act on anyway
        guardFactory(goal.stepBudget).abortReason()?.let { return Result.Failed(it) }

        onProgress("replaying a stored path of " + trajectory.steps.size + " steps")

        val outcome = ReplayRunner(executor, policy, observe, confirm, settleMillis).run(trajectory)
        return when (outcome) {
            is ReplayRunner.Outcome.Completed -> {
                // Only a run that visibly moved the screen counts for or
                // against the path. Scoring an unverified run either way would
                // retire a working path or promote a failing one
                if (outcome.verified) store.recordOutcome(trajectory.id, success = true)
                else onProgress("replayed, but the screen did not visibly move")

                Result.Done("replayed a stored path", replayed = true)
            }

            is ReplayRunner.Outcome.Diverged -> {
                store.recordOutcome(trajectory.id, success = false)
                onProgress("replay diverged at step " + outcome.atStep + ", replanning")
                onPartial(outcome.atStep > 0)
                null
            }

            // A guardrail firing is not the path being wrong, so it is not
            // counted as a failure against it
            is ReplayRunner.Outcome.Blocked -> Result.Blocked(outcome.reason)
        }
    }

    private suspend fun plan(goal: Goal, mayStore: Boolean = true): Result {
        val guard = guardFactory(goal.stepBudget)
        val history = mutableListOf<Step>()
        val recorder = TrajectoryRecorder(goal.raw)
        var previousAction: AgentAction? = null
        var restarts = 0

        while (true) {
            val state = observe()
            guard.record(state, previousAction)
            guard.abortReason()?.let { return Result.Failed(it) }

            onProgress("thinking on " + state.packageName + " (" + state.elements.size + " elements)")
            val action = planner.next(goal, state, history)
            onProgress("-> " + action)

            when (action) {
                is AgentAction.Done -> {
                    if (mayStore) recorder.build()?.let { store.save(it) }
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
            // Acting on the screen it saw would press coordinates the policy
            // never examined, so a moved screen sends us round again.
            //
            // Compared on structure, not on every label: a clock or an unread
            // badge ticking over is not the screen moving, and treating it as
            // such meant the agent never acted at all on a chat list
            val current = observe()
            if (current.structureHash != state.structureHash) {
                restarts++
                if (restarts > MAX_RESTARTS) {
                    return Result.Failed("the screen kept changing faster than it could be acted on")
                }
                onProgress("screen moved while planning, re-observing")
                continue
            }
            restarts = 0

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

        // A screen that will not hold still for one round trip is not one the
        // agent can operate, and spinning on it burns the whole budget silently
        const val MAX_RESTARTS = 3
    }
}
