package dev.droidpilot.data

import android.content.Context

// The planner endpoint is the one thing that differs between running the model
// on this phone, on a machine at home, or anywhere else
class AgentSettings(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var plannerUrl: String
        get() = prefs.getString(KEY_PLANNER_URL, DEFAULT_PLANNER_URL) ?: DEFAULT_PLANNER_URL
        set(value) = prefs.edit().putString(KEY_PLANNER_URL, value.trim()).apply()

    var stepBudget: Int
        get() = prefs.getInt(KEY_STEP_BUDGET, DEFAULT_STEP_BUDGET)
        set(value) = prefs.edit().putInt(KEY_STEP_BUDGET, value).apply()

    companion object {
        private const val NAME = "agent_settings"
        private const val KEY_PLANNER_URL = "planner_url"
        private const val KEY_STEP_BUDGET = "step_budget"

        // A llama.cpp server started with --host 0.0.0.0 on the default port
        const val DEFAULT_PLANNER_URL = "http://192.168.0.10:8080"
        const val DEFAULT_STEP_BUDGET = 40
    }
}
