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
import dev.droidpilot.trajectory.RunOutcome
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
                // An unverified run is not a failure - toggling a switch leaves
                // no trace in the view tree - so it is neither scored as one nor
                // reported as an error. It is counted separately, and a path
                // that is never once seen to do anything retires on that count
                store.recordOutcome(
                    trajectory.id,
                    if (outcome.verified) RunOutcome.WORKED else RunOutcome.UNVERIFIED
                )
                if (!outcome.verified) onProgress("replayed, though the screen did not visibly change")

                Result.Done("replayed a stored path", replayed = true)
            }

            is ReplayRunner.Outcome.Diverged -> {
                store.recordOutcome(trajectory.id, RunOutcome.FAILED)
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
            undescribed(state)?.let { return it }

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
            if (!stillAddresses(action, state, current)) {
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
            if (outcome.isSuccess) recorder.record(action, current) else recorder.discard()
            previousAction = action

            delay(settleMillis)
        }
    }

    // A screen with nothing to name and nothing to look at cannot be planned
    // against. The grammar still obliges the planner to answer, and what it
    // answers with is a coordinate it made up, so the run stops here instead.
    //
    // The usual cause is a game running a security solution: FLAG_SECURE makes
    // every capture come back flat, and the view tree was never going to
    // describe it
    private fun undescribed(state: ScreenState): Result? {
        if (state.isTextUsable || state.screenshot != null) return null

        return Result.Failed("this screen cannot be listed or captured, nothing to plan against")
    }

    // An element id means nothing across two observations. A list that gained
    // three rows renumbers everything below them, and the executor would
    // faithfully resolve the wrong element by its live bounds and label. What
    // has to hold is not that the screen is identical, but that the element the
    // policy vetted is still the one at that index
    private fun stillAddresses(action: AgentAction, before: ScreenState, after: ScreenState): Boolean {
        // Opening an app is about somewhere else entirely, so nothing about
        // the screen it was decided on has to still hold. Holding it to one
        // would spend the restart budget on a screen it is about to leave
        if (action is AgentAction.Launch) return true

        // Every verdict was reached about one app. Another one in front of us
        // means the package rules were applied to a screen that has gone, and a
        // store or a bank opening mid-thought is exactly what that list exists
        // to refuse
        if (before.packageName != after.packageName) return false

        // The grid a point is aimed on is laid over the display, so a display
        // of another shape is another grid. Neither hash carries bounds, and a
        // game turning landscape after its portrait splash is the ordinary case
        if (before.displayWidth != after.displayWidth ||
            before.displayHeight != after.displayHeight
        ) {
            return false
        }

        val id = when (action) {
            is AgentAction.Tap -> action.elementId
            is AgentAction.LongPress -> action.elementId
            is AgentAction.Input -> action.elementId
            // Waiting is what the planner emits precisely because the screen
            // is still moving. Testing it against a settled screen meant wait
            // could never run in the one situation that calls for it
            is AgentAction.Swipe -> action.elementId ?: return true

            // A point is not an index, so there is nothing to renumber. What
            // still has to hold is that nothing has appeared to be pressed: a
            // game exposes no interactive nodes at all, so its structure hash
            // is constant however hard it animates, and a purchase sheet
            // opening over it moves that hash and sends us round again
            is AgentAction.TapAt, is AgentAction.LongPressAt ->
                return before.structureHash == after.structureHash

            else -> return true
        }

        if (before.structureHash != after.structureHash) return false

        val was = before.elements.getOrNull(id) ?: return false
        val now = after.elements.getOrNull(id) ?: return false
        return now.label == was.label && now.role == was.role && now.bounds == was.bounds
    }

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 400L

        // A screen that will not hold still for one round trip is not one the
        // agent can operate, and spinning on it burns the whole budget silently
        const val MAX_RESTARTS = 3
    }
}
