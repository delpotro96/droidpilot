package dev.droidpilot.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.droidpilot.R
import dev.droidpilot.observer.AgentAccessibilityService
import dev.droidpilot.serializer.ScreenSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Manual harness for the observation stage: dump another app as text
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = getString(R.string.hint_enable_service)
        }

        val settingsButton = Button(this).apply {
            text = getString(R.string.action_open_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val dumpButton = Button(this).apply {
            text = getString(R.string.action_dump)
            setOnClickListener { dumpAfterDelay() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsButton)
            addView(dumpButton)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        })
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
            val header = "hash=" + state.screenHash +
                    "  elements=" + state.elements.size +
                    "  text mode=" + (if (state.isTextUsable) "usable" else "insufficient, vision needed") +
                    "\n\n"
            val rendered = header + ScreenSerializer.toPrompt(state)

            output.text = rendered
            Log.i("DroidPilot", rendered)
        }
    }

    private companion object {
        const val COUNTDOWN = 5
    }
}
