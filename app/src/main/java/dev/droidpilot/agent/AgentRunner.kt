package dev.droidpilot.agent

import android.content.Context
import dev.droidpilot.core.model.Goal
import dev.droidpilot.data.AgentSettings
import dev.droidpilot.executor.AccessibilityExecutor
import dev.droidpilot.observer.AgentAccessibilityService
import dev.droidpilot.planner.LlamaServerPlanner
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.trajectory.FileTrajectoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

// Owns a run and outlives the activity, because the user has to leave the app
// for the agent to have anything to operate.
//
// Every field the UI can observe lives in the state flow. The activity reads it
// on the main thread while the run writes from a background dispatcher, so
// nothing mutable is exposed directly
object AgentRunner {

    sealed interface State {
        val log: List<String>

        data object Idle : State {
            override val log: List<String> = emptyList()
        }

        data class Running(val goal: String, override val log: List<String>) : State
        data class AwaitingConfirm(
            val goal: String,
            override val log: List<String>,
            val reason: String
        ) : State

        data class Finished(val message: String, override val log: List<String>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val job = AtomicReference<Job?>(null)
    private val pendingConfirm = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isRunning: Boolean get() = job.get()?.isActive == true

    fun start(context: Context, goalText: String) {
        val app = context.applicationContext
        val settings = AgentSettings(app)

        val started = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                finish("accessibility service is not running")
                return@launch
            }

            val loop = AgentLoop(
                planner = LlamaServerPlanner(settings.plannerUrl),
                executor = AccessibilityExecutor(service),
                policy = SafetyPolicy(),
                store = FileTrajectoryStore(File(app.filesDir, TRAJECTORY_FILE)),
                guardFactory = { budget -> LoopGuard(app, budget) },
                observe = { service.observe() },
                confirm = { reason -> askUser(goalText, reason) },
                onProgress = { note(goalText, it) }
            )

            val result = runCatching {
                loop.run(Goal(raw = goalText, stepBudget = settings.stepBudget))
            }

            result
                .onSuccess { finish(describe(it)) }
                .onFailure {
                    if (it is CancellationException) throw it
                    finish("failed: " + (it.message ?: it.javaClass.simpleName))
                }
        }

        // Only one run at a time, and the job is published before it can finish
        if (!job.compareAndSet(null, started)) {
            started.cancel()
            return
        }
        _state.value = State.Running(goalText, listOf(stamp("started: " + goalText)))
        started.invokeOnCompletion { job.compareAndSet(started, null) }
        started.start()
    }

    fun answerConfirm(approved: Boolean) {
        pendingConfirm.getAndSet(null)?.complete(approved)
    }

    fun cancel() {
        // A parked question would otherwise keep the loop waiting forever
        pendingConfirm.getAndSet(null)?.complete(false)
        job.getAndSet(null)?.cancel()
        finish("cancelled")
    }

    fun reset() {
        if (!isRunning) _state.value = State.Idle
    }

    private suspend fun askUser(goal: String, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm.set(deferred)
        _state.update { State.AwaitingConfirm(goal, it.log, reason) }

        val approved = try {
            deferred.await()
        } finally {
            pendingConfirm.compareAndSet(deferred, null)
        }

        note(goal, if (approved) "approved: " + reason else "declined: " + reason)
        return approved
    }

    private fun note(goal: String, line: String) {
        _state.update { current ->
            val log = current.log + stamp(line)
            when (current) {
                is State.AwaitingConfirm -> current.copy(log = log)
                else -> State.Running(goal, log)
            }
        }
    }

    private fun finish(message: String) {
        _state.update { State.Finished(message, it.log + stamp(message)) }
    }

    private fun stamp(line: String): String = clock.format(Date()) + "  " + line

    private fun describe(result: AgentLoop.Result): String = when (result) {
        is AgentLoop.Result.Done ->
            if (result.replayed) "done, replayed without the model" else "done: " + result.summary
        is AgentLoop.Result.Failed -> "failed: " + result.reason
        is AgentLoop.Result.NeedsUser -> "needs you: " + result.question
        is AgentLoop.Result.Blocked -> "blocked: " + result.reason
    }

    private const val TRAJECTORY_FILE = "trajectories.json"
}
