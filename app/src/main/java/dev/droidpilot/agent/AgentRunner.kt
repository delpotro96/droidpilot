package dev.droidpilot.agent

import android.content.Context
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.data.AgentSettings
import dev.droidpilot.executor.AccessibilityExecutor
import dev.droidpilot.observer.AgentAccessibilityService
import dev.droidpilot.planner.LlamaServerPlanner
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.trajectory.FileTrajectoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// Owns a run and survives the activity, because the user has to leave the app
// for the agent to have anything to operate
object AgentRunner {

    sealed interface State {
        data object Idle : State
        data class Running(val goal: String, val log: List<String>) : State
        data class AwaitingConfirm(val goal: String, val log: List<String>, val reason: String) : State
        data class Finished(val message: String, val log: List<String>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val log = mutableListOf<String>()
    private var job: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, goalText: String) {
        if (isRunning) return

        val app = context.applicationContext
        val settings = AgentSettings(app)

        log.clear()
        _state.value = State.Running(goalText, emptyList())

        job = scope.launch {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                finish("Accessibility service is not running")
                return@launch
            }

            val loop = AgentLoop(
                planner = LlamaServerPlanner(settings.plannerUrl),
                executor = AccessibilityExecutor(service),
                policy = SafetyPolicy(),
                store = FileTrajectoryStore(File(app.filesDir, "trajectories.json")),
                guardFactory = { budget -> LoopGuard(app, budget) },
                observe = { observe(service) },
                confirm = { reason -> askUser(goalText, reason) },
                onProgress = { note(goalText, it) }
            )

            val result = runCatching {
                loop.run(Goal(raw = goalText, stepBudget = settings.stepBudget))
            }.getOrElse { AgentLoop.Result.Failed(it.message ?: it.javaClass.simpleName) }

            finish(describe(result))
        }
    }

    fun answerConfirm(approved: Boolean) {
        pendingConfirm?.complete(approved)
        pendingConfirm = null
    }

    fun cancel() {
        // A pending question would otherwise keep the loop parked forever
        pendingConfirm?.complete(false)
        pendingConfirm = null
        job?.cancel()
        job = null
        finish("cancelled")
    }

    fun reset() {
        if (!isRunning) _state.value = State.Idle
    }

    private suspend fun observe(service: AgentAccessibilityService): ScreenState = service.observe()

    private suspend fun askUser(goal: String, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm = deferred
        _state.value = State.AwaitingConfirm(goal, log.toList(), reason)

        val approved = deferred.await()
        note(goal, if (approved) "approved: " + reason else "declined: " + reason)
        return approved
    }

    private fun note(goal: String, line: String) {
        log += line
        if (_state.value !is State.AwaitingConfirm) {
            _state.value = State.Running(goal, log.toList())
        }
    }

    private fun finish(message: String) {
        _state.value = State.Finished(message, log.toList())
        job = null
    }

    private fun describe(result: AgentLoop.Result): String = when (result) {
        is AgentLoop.Result.Done ->
            if (result.replayed) "done, replayed without the model" else "done: " + result.summary
        is AgentLoop.Result.Failed -> "failed: " + result.reason
        is AgentLoop.Result.NeedsUser -> "needs you: " + result.question
        is AgentLoop.Result.Blocked -> "blocked: " + result.reason
    }
}
