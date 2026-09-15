package dev.droidpilot.core.model

data class Goal(
    val raw: String,
    val targetPackage: String? = null,
    val successHint: String? = null,
    val stepBudget: Int = DEFAULT_STEP_BUDGET
) {
    companion object {
        const val DEFAULT_STEP_BUDGET = 40
    }
}

// Decides one action at a time. Swap the implementation for on-device,
// home server or cloud inference
interface Planner {
    suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction
}

// Applies a decided action to the live screen
interface Executor {
    suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit>
}

// Guardrail evaluated before anything reaches the screen
interface Policy {
    fun check(action: AgentAction, state: ScreenState): Verdict
}
