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

    // Where a screen dump is posted for inspection. Separate from the planner
    // because the two move independently - the listener runs wherever the
    // development machine happens to be, the model wherever it fits
    var dumpUrl: String
        get() = prefs.getString(KEY_DUMP_URL, DEFAULT_DUMP_URL) ?: DEFAULT_DUMP_URL
        set(value) = prefs.edit().putString(KEY_DUMP_URL, value.trim()).apply()

    companion object {
        private const val NAME = "agent_settings"
        private const val KEY_PLANNER_URL = "planner_url"
        private const val KEY_STEP_BUDGET = "step_budget"
        private const val KEY_DUMP_URL = "dump_url"

        // A llama.cpp server on the development machine, reached over the
        // tailnet so mobile data works. Not llama.cpp's own default port:
        // 8080 is a gateway on this machine and taking it broke other work
        const val DEFAULT_PLANNER_URL = "http://100.123.217.82:18080"
        const val DEFAULT_STEP_BUDGET = 40

        // The tailnet address of the development machine. A tailnet address
        // works on mobile data, which matters because wireless debugging does
        // not - it needs the phone on a wifi network, and there is not always
        // one to join
        const val DEFAULT_DUMP_URL = "http://100.123.217.82:8099"
    }
}
