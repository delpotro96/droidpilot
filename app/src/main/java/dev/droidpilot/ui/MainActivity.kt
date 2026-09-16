package dev.droidpilot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.droidpilot.R
import dev.droidpilot.agent.AgentRunner
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.data.AgentSettings
import dev.droidpilot.diag.DumpUploader
import dev.droidpilot.observer.AgentAccessibilityService
import dev.droidpilot.observer.ScreenshotProbe
import dev.droidpilot.serializer.ScreenSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AgentSettings

    private lateinit var statusChip: Chip
    private lateinit var serverLayout: TextInputLayout
    private lateinit var serverField: TextInputEditText
    private lateinit var goalField: TextInputEditText
    private lateinit var runButton: MaterialButton
    private lateinit var dumpButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AgentSettings(this)

        bindViews()
        wireActions()
        observeRunner()
        watchServiceState()
        requestNotificationPermission()
    }

    // Without this the confirmation notification is dropped silently, which
    // would park a run with no way for the user to learn why
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun bindViews() {
        statusChip = findViewById(R.id.statusChip)
        serverLayout = findViewById(R.id.serverLayout)
        serverField = findViewById(R.id.serverField)
        goalField = findViewById(R.id.goalField)
        runButton = findViewById(R.id.runButton)
        dumpButton = findViewById(R.id.dumpButton)
        clearButton = findViewById(R.id.clearButton)
        progress = findViewById(R.id.progress)
        logScroll = findViewById(R.id.logScroll)
        logView = findViewById(R.id.logView)

        serverField.setText(settings.plannerUrl)
    }

    private fun wireActions() {
        statusChip.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        runButton.setOnClickListener { onRunClicked() }
        dumpButton.setOnClickListener { dumpAfterDelay() }

        clearButton.setOnClickListener {
            AgentRunner.reset()
            logView.text = ""
        }

        // Clear a stale error as soon as the user starts correcting it
        serverField.doAfterTextChanged { serverLayout.error = null }
    }

    private fun onRunClicked() {
        if (AgentRunner.isRunning) {
            AgentRunner.cancel(this)
            return
        }

        if (!AgentAccessibilityService.isConnected) {
            append(getString(R.string.error_service_off))
            return
        }

        val url = serverField.text?.toString()?.trim().orEmpty()
        if (!isPlausibleUrl(url)) {
            serverLayout.error = getString(R.string.error_bad_url)
            return
        }

        val goal = goalField.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) {
            append(getString(R.string.error_no_goal))
            return
        }

        settings.plannerUrl = url
        AgentRunner.start(this, goal)
    }

    // Enough to catch a typo before a request hangs, not a validator
    private fun isPlausibleUrl(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) &&
                url.substringAfter("://").isNotBlank()

    private fun observeRunner() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentRunner.state.collectLatest { render(it) }
            }
        }
    }

    // The service can be toggled from system settings while this screen is open
    private fun watchServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    renderStatus(AgentRunner.state.value)
                    delay(SERVICE_POLL_MILLIS)
                }
            }
        }
    }

    private fun render(state: AgentRunner.State) {
        renderStatus(state)

        val running = state is AgentRunner.State.Running
        progress.visibility = if (running) View.VISIBLE else View.GONE
        runButton.setText(if (AgentRunner.isRunning) R.string.action_cancel else R.string.action_run)
        dumpButton.isEnabled = !AgentRunner.isRunning

        when (state) {
            AgentRunner.State.Idle -> Unit
            is AgentRunner.State.Running -> showLog(state.log)
            is AgentRunner.State.AwaitingConfirm -> {
                showLog(state.log)
                showConfirm(state.reason)
            }
            is AgentRunner.State.Finished -> showLog(state.log + state.message)
        }
    }

    private fun renderStatus(state: AgentRunner.State) {
        val (label, enabled) = when {
            !AgentAccessibilityService.isConnected -> R.string.status_service_off to false
            state is AgentRunner.State.AwaitingConfirm -> R.string.status_waiting to true
            AgentRunner.isRunning -> R.string.status_running to true
            else -> R.string.status_ready to true
        }
        statusChip.setText(label)
        statusChip.isChipIconVisible = true
        statusChip.setChipIconResource(
            if (enabled) android.R.drawable.presence_online else android.R.drawable.presence_invisible
        )
    }

    private fun showLog(lines: List<String>) {
        logView.text = lines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun append(line: String) {
        logView.text = listOf(logView.text.toString(), line).filter { it.isNotBlank() }.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // A guardrail asked a question, so nothing proceeds until it is answered.
    // Recreated rather than reused so a configuration change cannot leave a
    // dialog bound to a destroyed activity
    private fun showConfirm(reason: String) {
        if (isFinishing || supportFragmentManager.isDestroyed) return
        if (confirmDialog?.isShowing == true) return

        confirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm_allow) { _, _ -> AgentRunner.answerConfirm(true) }
            .setNegativeButton(R.string.confirm_deny) { _, _ -> AgentRunner.answerConfirm(false) }
            .create()
            .also { it.show() }
    }

    override fun onStop() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onStop()
    }

    private fun dumpAfterDelay() {
        lifecycleScope.launch {
            for (n in COUNTDOWN downTo 1) {
                logView.text = getString(R.string.dump_countdown, n)
                delay(1000)
            }

            val service = AgentAccessibilityService.instance
            if (service == null) {
                logView.text = getString(R.string.error_service_off)
                return@launch
            }

            val state = withContext(Dispatchers.Default) { service.observe() }
            val mode = getString(
                if (state.isTextUsable) R.string.dump_text_usable else R.string.dump_needs_vision
            )
            logView.text = getString(
                R.string.dump_header, state.elements.size, mode, state.screenHash
            ) + describeCapture(state) + "\n\n" + ScreenSerializer.toPrompt(state)
            logScroll.post { logScroll.fullScroll(View.FOCUS_UP) }

            // The listing above drops bounds, resource ids and the screenshot,
            // which are the fields every policy decision is actually made on.
            // Reading a screen by copying that listing out by hand meant
            // guessing at the rest
            sendDump(state)
        }
    }

    private suspend fun sendDump(state: ScreenState) {
        val endpoint = AgentSettings(this).dumpUrl
        if (endpoint.isBlank()) return

        when (val result = DumpUploader(endpoint).send(state)) {
            is DumpUploader.Result.Sent ->
                append(getString(R.string.dump_sent, result.bytes / 1024))
            is DumpUploader.Result.Failed ->
                append(getString(R.string.dump_not_sent, result.reason))
        }
    }

    // The one thing a view tree dump can never answer: whether this screen can
    // be photographed at all. A game behind a security solution returns a
    // capture that succeeded and shows one flat colour, and without this line
    // the dump looks identical either way
    private fun describeCapture(state: ScreenState): String {
        if (state.isTextUsable) return ""

        val bitmap = ScreenshotProbe.decode(state.screenshot)
            ?: return "\n" + getString(R.string.dump_shot_none)

        return try {
            val size = state.screenshot?.size ?: 0
            "\n" + if (ScreenshotProbe.isBlank(bitmap)) {
                getString(R.string.dump_shot_blank, bitmap.width, bitmap.height)
            } else {
                getString(R.string.dump_shot_ok, bitmap.width, bitmap.height, size / 1024)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private var confirmDialog: AlertDialog? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) append(getString(R.string.warn_no_notifications))
        }

    private companion object {
        const val COUNTDOWN = 5
        const val SERVICE_POLL_MILLIS = 1000L
    }
}
