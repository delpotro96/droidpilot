package dev.droidpilot.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.droidpilot.R
import dev.droidpilot.agent.AgentRunner
import dev.droidpilot.data.AgentSettings
import dev.droidpilot.observer.AgentAccessibilityService
import dev.droidpilot.serializer.ScreenSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AgentSettings
    private lateinit var serverField: EditText
    private lateinit var goalField: EditText
    private lateinit var runButton: Button
    private lateinit var output: TextView

    private var confirmDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AgentSettings(this)
        setContentView(buildLayout())
        observeRunner()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    private fun buildLayout(): ViewGroup {
        serverField = EditText(this).apply {
            hint = getString(R.string.hint_server)
            setText(settings.plannerUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }

        goalField = EditText(this).apply {
            hint = getString(R.string.hint_goal)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }

        runButton = Button(this).apply {
            text = getString(R.string.action_run)
            setOnClickListener { onRunClicked() }
        }

        val settingsButton = Button(this).apply {
            text = getString(R.string.action_open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val dumpButton = Button(this).apply {
            text = getString(R.string.action_dump)
            setOnClickListener { dumpAfterDelay() }
        }

        output = TextView(this).apply {
            textSize = 12f
            setPadding(PADDING, PADDING, PADDING, PADDING)
            text = getString(R.string.hint_enable_service)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(serverField)
            addView(goalField)
            addView(runButton)
            addView(settingsButton)
            addView(dumpButton)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        }
    }

    private fun onRunClicked() {
        if (AgentRunner.isRunning) {
            AgentRunner.cancel()
            return
        }

        if (!AgentAccessibilityService.isConnected) {
            output.text = getString(R.string.error_service_off)
            return
        }

        val goal = goalField.text.toString().trim()
        if (goal.isEmpty()) {
            output.text = getString(R.string.error_no_goal)
            return
        }

        settings.plannerUrl = serverField.text.toString()
        AgentRunner.start(this, goal)
    }

    private fun observeRunner() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentRunner.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: AgentRunner.State) {
        runButton.text = getString(
            if (AgentRunner.isRunning) R.string.action_cancel else R.string.action_run
        )

        when (state) {
            AgentRunner.State.Idle -> Unit

            is AgentRunner.State.Running -> {
                dismissConfirm()
                output.text = state.log.joinToString("\n")
            }

            is AgentRunner.State.AwaitingConfirm -> {
                output.text = state.log.joinToString("\n")
                showConfirm(state.reason)
            }

            is AgentRunner.State.Finished -> {
                dismissConfirm()
                output.text = (state.log + "" + state.message).joinToString("\n")
            }
        }
    }

    // The guardrail asked a question, so nothing proceeds until the user answers
    private fun showConfirm(reason: String) {
        if (confirmDialog?.isShowing == true) return

        confirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm_allow) { _, _ -> AgentRunner.answerConfirm(true) }
            .setNegativeButton(R.string.confirm_deny) { _, _ -> AgentRunner.answerConfirm(false) }
            .show()
    }

    private fun dismissConfirm() {
        confirmDialog?.dismiss()
        confirmDialog = null
    }

    private fun dumpAfterDelay() {
        lifecycleScope.launch {
            for (n in COUNTDOWN downTo 1) {
                output.text = "dumping in " + n + "s, switch to the target app now"
                delay(1000)
            }

            val service = AgentAccessibilityService.instance
            if (service == null) {
                output.text = getString(R.string.error_service_off)
                return@launch
            }

            val state = service.observe()
            output.text = "hash=" + state.screenHash +
                    "  elements=" + state.elements.size +
                    "  text mode=" + (if (state.isTextUsable) "usable" else "insufficient, vision needed") +
                    "\n\n" + ScreenSerializer.toPrompt(state)
        }
    }

    private companion object {
        const val COUNTDOWN = 5
        const val PADDING = 24
    }
}
